import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  createRefreshWatchdog,
  resolveSnapshotUpdateAction,
  shouldRefreshFromStreamEvent
} from './mapRefresh';

describe('mapRefresh helpers', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('resolves snapshot action to snap on first paint', () => {
    expect(resolveSnapshotUpdateAction({
      hasRenderedFlights: false,
      batchChanged: true,
      animationRunning: false
    })).toBe('snap');
  });

  it('resolves snapshot action to animate when a new batch arrives', () => {
    expect(resolveSnapshotUpdateAction({
      hasRenderedFlights: true,
      batchChanged: true,
      animationRunning: false
    })).toBe('animate');
  });

  it('resolves snapshot action to noop when same batch refresh arrives during animation', () => {
    expect(resolveSnapshotUpdateAction({
      hasRenderedFlights: true,
      batchChanged: false,
      animationRunning: true
    })).toBe('noop');
  });

  it('resolves snapshot action to snap when same batch refresh arrives without animation', () => {
    expect(resolveSnapshotUpdateAction({
      hasRenderedFlights: true,
      batchChanged: false,
      animationRunning: false
    })).toBe('snap');
  });

  it('requests refresh when stream event has null batch epoch', () => {
    expect(shouldRefreshFromStreamEvent(null, 10)).toBe(true);
  });

  it('requests refresh when stream event batch differs from latest known batch', () => {
    expect(shouldRefreshFromStreamEvent(11, 10)).toBe(true);
  });

  it('skips refresh when stream event batch equals latest known batch', () => {
    expect(shouldRefreshFromStreamEvent(10, 10)).toBe(false);
  });

  it('runs watchdog tick periodically and supports reschedule + stop', () => {
    vi.useFakeTimers();

    const onTick = vi.fn();
    const watchdog = createRefreshWatchdog(onTick, 1000);

    vi.advanceTimersByTime(900);
    expect(onTick).toHaveBeenCalledTimes(0);

    watchdog.reschedule();
    vi.advanceTimersByTime(900);
    expect(onTick).toHaveBeenCalledTimes(0);

    vi.advanceTimersByTime(100);
    expect(onTick).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(1000);
    expect(onTick).toHaveBeenCalledTimes(2);

    watchdog.stop();
    vi.advanceTimersByTime(3000);
    expect(onTick).toHaveBeenCalledTimes(2);
  });
});
