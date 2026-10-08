type RequestOptions = { signal?: AbortSignal; timeoutMs?: number };

/** Bound network waits, including a stalled response body on a phone's Wi-Fi. */
export async function api<T>(path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
  const controller = new AbortController();
  let timedOut = false;
  const cancel = () => controller.abort();
  if (options.signal?.aborted) cancel();
  options.signal?.addEventListener("abort", cancel, { once: true });
  const timer = setTimeout(() => { timedOut = true; cancel(); }, options.timeoutMs ?? 30000);
  try {
    const response = await fetch("/api" + path, {
      signal: controller.signal,
      ...(body === undefined ? { cache: "no-store" as const } : {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
      }),
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ detail: "Backend indisponível." }));
      throw new Error(typeof error.detail === "string" ? error.detail : "Confira os campos informados.");
    }
    return await response.json();
  } catch (error) {
    if (timedOut) throw new Error("O PC demorou a responder. Confira a conexão e tente salvar novamente.");
    throw error;
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener("abort", cancel);
  }
}
