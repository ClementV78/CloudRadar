import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchBboxBoostStatus,
  fetchFlightDetail,
  fetchFlights,
  fetchIngesterScale,
  fetchIngesterScalePublic,
  fetchMetrics,
  scaleIngester,
  subscribeFlightUpdates,
  triggerBboxBoost
} from './api';
import { ADMIN_SCALE_PATH, API_FLIGHTS_BASE } from './constants';

type Listener = (event: MessageEvent) => void;

class MockEventSource {
  public onerror: (() => void) | null = null;
  public closed = false;
  private readonly listeners = new Map<string, Set<Listener>>();

  constructor(public readonly url: string) {}

  addEventListener(type: string, listener: Listener): void {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set());
    }
    this.listeners.get(type)?.add(listener);
  }

  removeEventListener(type: string, listener: Listener): void {
    this.listeners.get(type)?.delete(listener);
  }

  emit(type: string, payload: unknown): void {
    const event = { data: JSON.stringify(payload) } as MessageEvent;
    for (const listener of this.listeners.get(type) ?? []) {
      listener(event);
    }
  }

  emitRaw(type: string, raw: string): void {
    const event = { data: raw } as MessageEvent;
    for (const listener of this.listeners.get(type) ?? []) {
      listener(event);
    }
  }

  close(): void {
    this.closed = true;
  }
}

describe('api helpers', () => {
  const fetchMock = vi.fn();
  let lastEventSource: MockEventSource | null = null;

  beforeEach(() => {
    vi.clearAllMocks();
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);

    vi.stubGlobal(
      'EventSource',
      vi.fn((url: string) => {
        lastEventSource = new MockEventSource(url);
        return lastEventSource;
      })
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('builds expected query for flights and calls fetch with JSON headers', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({ items: [], count: 0 })
    });

    await fetchFlights({ minLon: 1, minLat: 2, maxLon: 3, maxLat: 4 }, 123);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain(`${API_FLIGHTS_BASE}?`);
    expect(url).toContain('bbox=1%2C2%2C3%2C4');
    expect(url).toContain('limit=123');
    expect(url).toContain('sort=lastSeen');
    expect(url).toContain('order=desc');
    expect(options.credentials).toBe('same-origin');
    expect((options.headers as Record<string, string>).Accept).toBe('application/json');
  });

  it('calls expected endpoints for detail/metrics/boost/admin status', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({ ok: true })
    });

    await fetchFlightDetail('abc123');
    await fetchMetrics({ minLon: 1, minLat: 2, maxLon: 3, maxLat: 4 });
    await fetchBboxBoostStatus();
    await triggerBboxBoost();
    await fetchIngesterScalePublic();

    const urls = fetchMock.mock.calls.map((call) => call[0] as string);
    expect(urls).toContain(`${API_FLIGHTS_BASE}/abc123?include=track,enrichment`);
    expect(urls.some((u) => u.startsWith(`${API_FLIGHTS_BASE}/metrics?`))).toBe(true);
    expect(urls).toContain(`${API_FLIGHTS_BASE}/bbox/boost`);
    expect(urls).toContain(ADMIN_SCALE_PATH);
  });

  it('uses basic auth for admin read/write calls', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({ status: 'ok' })
    });

    await fetchIngesterScale('admin', 'secret');
    await scaleIngester(1, 'admin', 'secret');

    const authRead = (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    const authWrite = (fetchMock.mock.calls[1][1] as RequestInit).headers as Record<string, string>;
    expect(authRead.Authorization).toMatch(/^Basic /);
    expect(authWrite.Authorization).toMatch(/^Basic /);
    expect((fetchMock.mock.calls[1][1] as RequestInit).method).toBe('POST');
    expect((fetchMock.mock.calls[1][1] as RequestInit).body).toBe(JSON.stringify({ replicas: 1 }));
  });

  it('surfaces payload message on apiGet error and falls back to HTTP status text', async () => {
    fetchMock
      .mockResolvedValueOnce({
        ok: false,
        status: 429,
        statusText: 'Too Many Requests',
        json: async () => ({ message: 'cooldown active' })
      })
      .mockResolvedValueOnce({
        ok: false,
        status: 500,
        statusText: 'Server Error',
        json: async () => {
          throw new Error('non-json');
        }
      });

    await expect(fetchBboxBoostStatus()).rejects.toThrow('cooldown active');
    await expect(fetchFlights({ minLon: 1, minLat: 1, maxLon: 2, maxLat: 2 })).rejects.toThrow(
      '500 Server Error'
    );
  });

  it('prefers payload.error for authenticated calls and falls back to status text', async () => {
    fetchMock
      .mockResolvedValueOnce({
        ok: false,
        status: 401,
        statusText: 'Unauthorized',
        json: async () => ({ error: 'invalid credentials', message: 'ignored' })
      })
      .mockResolvedValueOnce({
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        json: async () => {
          throw new Error('non-json');
        }
      });

    await expect(fetchIngesterScale('admin', 'bad')).rejects.toThrow('invalid credentials');
    await expect(scaleIngester(0, 'admin', 'bad')).rejects.toThrow('403 Forbidden');
  });

  it('subscribes to flight stream, forwards valid events, ignores invalid JSON, and cleans up', () => {
    const updates: unknown[] = [];
    const errors = vi.fn();

    const unsubscribe = subscribeFlightUpdates((evt) => updates.push(evt), errors);

    expect(lastEventSource).not.toBeNull();
    expect(lastEventSource?.url).toBe(`${API_FLIGHTS_BASE}/stream`);

    lastEventSource?.emit('connected', {
      latestOpenSkyBatchEpoch: 123,
      timestamp: '2026-03-10T10:00:00Z'
    });
    lastEventSource?.emitRaw('batch-update', '{not-valid-json');
    lastEventSource?.emit('batch-update', {
      latestOpenSkyBatchEpoch: 'bad-type',
      timestamp: '2026-03-10T10:01:00Z'
    });
    lastEventSource?.onerror?.();

    expect(updates).toHaveLength(2);
    expect((updates[0] as { latestOpenSkyBatchEpoch: number }).latestOpenSkyBatchEpoch).toBe(123);
    expect((updates[1] as { latestOpenSkyBatchEpoch: null }).latestOpenSkyBatchEpoch).toBeNull();
    expect(errors).toHaveBeenCalledTimes(1);

    unsubscribe();
    expect(lastEventSource?.closed).toBe(true);
  });
});
