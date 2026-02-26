export type SnapshotUpdateAction = 'snap' | 'animate' | 'noop';

interface SnapshotUpdateInput {
  hasRenderedFlights: boolean;
  batchChanged: boolean;
  animationRunning: boolean;
}

export function resolveSnapshotUpdateAction({
  hasRenderedFlights,
  batchChanged,
  animationRunning
}: SnapshotUpdateInput): SnapshotUpdateAction {
  if (!hasRenderedFlights) {
    return 'snap';
  }
  if (batchChanged) {
    return 'animate';
  }
  if (animationRunning) {
    return 'noop';
  }
  return 'snap';
}

export function shouldRefreshFromStreamEvent(
  latestOpenSkyBatchEpoch: number | null,
  lastBatchEpoch: number | null
): boolean {
  return latestOpenSkyBatchEpoch === null || latestOpenSkyBatchEpoch !== lastBatchEpoch;
}

interface RefreshWatchdogTimers {
  setTimeoutFn: (callback: () => void, ms: number) => number;
  clearTimeoutFn: (timeoutId: number) => void;
}

export interface RefreshWatchdog {
  reschedule: () => void;
  stop: () => void;
}

function defaultTimers(): RefreshWatchdogTimers {
  return {
    setTimeoutFn: (callback, ms) => window.setTimeout(callback, ms),
    clearTimeoutFn: (timeoutId) => window.clearTimeout(timeoutId)
  };
}

export function createRefreshWatchdog(
  onTick: () => void,
  intervalMs: number,
  timers: RefreshWatchdogTimers = defaultTimers()
): RefreshWatchdog {
  let timeoutId: number | null = null;

  const schedule = (): void => {
    if (timeoutId !== null) {
      timers.clearTimeoutFn(timeoutId);
    }
    timeoutId = timers.setTimeoutFn(() => {
      onTick();
      schedule();
    }, intervalMs);
  };

  const stop = (): void => {
    if (timeoutId !== null) {
      timers.clearTimeoutFn(timeoutId);
      timeoutId = null;
    }
  };

  schedule();

  return {
    reschedule: schedule,
    stop
  };
}
