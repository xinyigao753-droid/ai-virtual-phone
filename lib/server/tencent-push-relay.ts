type RelayMessage = {
  title: string;
  body: string;
  url?: string;
  type?: string;
  sessionId?: string;
  callTs?: number;
};

type QueuedMessage = RelayMessage & { queuedAt: number };
type Waiter = {
  resolve: (message: RelayMessage | null) => void;
  timer: ReturnType<typeof setTimeout>;
};

type RelayStore = {
  queues: Map<string, QueuedMessage[]>;
  waiters: Map<string, Waiter[]>;
  lastPollLogAt: Map<string, number>;
};

const TOKEN_PATTERN = /^[a-f0-9]{64}$/;
const MESSAGE_TTL_MS = 10 * 60 * 1000;
const MAX_QUEUED_PER_DEVICE = 20;

const globalRelay = globalThis as typeof globalThis & {
  __floatTencentPushRelay?: RelayStore;
};

const store = globalRelay.__floatTencentPushRelay ??= {
  queues: new Map(),
  waiters: new Map(),
  lastPollLogAt: new Map(),
};

export function isValidTencentPushToken(value: unknown): value is string {
  return typeof value === "string" && TOKEN_PATTERN.test(value);
}

export function normalizeTencentPushMessage(value: unknown): RelayMessage | null {
  let raw = value;
  if (typeof raw === "string") {
    try { raw = JSON.parse(raw); } catch { return null; }
  }
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const record = raw as Record<string, unknown>;
  const notification = record.notification && typeof record.notification === "object"
    ? record.notification as Record<string, unknown>
    : null;
  const data = notification?.data && typeof notification.data === "object"
    ? notification.data as Record<string, unknown>
    : null;
  const text = (candidate: unknown, max: number) => typeof candidate === "string"
    ? candidate.trim().slice(0, max)
    : "";
  const title = text(notification?.title ?? record.title, 120) || "小手机";
  const body = text(notification?.body ?? record.body, 500) || "有新消息";
  const url = text(notification?.navigate ?? data?.url ?? record.url, 1000);
  const type = text(data?.type ?? record.type, 60);
  const sessionId = text(record.sessionId, 200);
  const callTs = Number(record.callTs);
  return {
    title,
    body,
    ...(url ? { url } : {}),
    ...(type ? { type } : {}),
    ...(sessionId ? { sessionId } : {}),
    ...(Number.isFinite(callTs) && callTs > 0 ? { callTs } : {}),
  };
}

export function deliverTencentPush(token: string, message: RelayMessage): void {
  const waiters = store.waiters.get(token);
  const waiter = waiters?.shift();
  if (waiter) {
    clearTimeout(waiter.timer);
    if (waiters && waiters.length === 0) store.waiters.delete(token);
    waiter.resolve(message);
    return;
  }

  const now = Date.now();
  const queue = (store.queues.get(token) ?? [])
    .filter((item: QueuedMessage) => now - item.queuedAt < MESSAGE_TTL_MS);
  queue.push({ ...message, queuedAt: now });
  store.queues.set(token, queue.slice(-MAX_QUEUED_PER_DEVICE));
}

export function waitForTencentPush(
  token: string,
  signal?: AbortSignal,
  timeoutMs = 25_000,
): Promise<RelayMessage | null> {
  const now = Date.now();
  const lastPollLogAt = store.lastPollLogAt.get(token) ?? 0;
  if (now - lastPollLogAt >= 5 * 60 * 1000) {
    store.lastPollLogAt.set(token, now);
    console.info("[push-relay] device polling", { tokenSuffix: token.slice(-8) });
  }
  const queue = (store.queues.get(token) ?? [])
    .filter((item: QueuedMessage) => now - item.queuedAt < MESSAGE_TTL_MS);
  const queued = queue.shift();
  if (queue.length > 0) store.queues.set(token, queue);
  else store.queues.delete(token);
  if (queued) {
    const { queuedAt: _queuedAt, ...message } = queued;
    return Promise.resolve(message);
  }

  return new Promise(resolve => {
    let settled = false;
    const finish = (message: RelayMessage | null) => {
      if (settled) return;
      settled = true;
      const waiters = store.waiters.get(token);
      if (waiters) {
        const index = waiters.indexOf(waiter);
        if (index >= 0) waiters.splice(index, 1);
        if (waiters.length === 0) store.waiters.delete(token);
      }
      resolve(message);
    };
    const waiter: Waiter = {
      resolve: finish,
      timer: setTimeout(() => finish(null), timeoutMs),
    };
    const waiters = store.waiters.get(token) ?? [];
    waiters.push(waiter);
    store.waiters.set(token, waiters);
    signal?.addEventListener("abort", () => {
      clearTimeout(waiter.timer);
      finish(null);
    }, { once: true });
  });
}
