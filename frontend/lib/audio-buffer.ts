export type OutAudioState = {
  context: Pick<AudioContext, "decodeAudioData">;
  buffer: AudioBuffer | null;
  loading?: Promise<AudioBuffer>;
};

// A click during initial loading waits for the same decoded sound. Failed
// requests are retryable instead of leaving an empty buffer forever.
export async function loadOutBuffer(state: OutAudioState): Promise<AudioBuffer> {
  if (state.buffer) return state.buffer;
  if (!state.loading) {
    state.loading = (async () => {
      const response = await fetch("/audio/out-natural.mp3");
      if (!response.ok) throw new Error("audio unavailable");
      const buffer = await state.context.decodeAudioData(await response.arrayBuffer());
      state.buffer = buffer;
      return buffer;
    })();
  }
  const pending = state.loading;
  try { return await pending; }
  finally { if (state.loading === pending) state.loading = undefined; }
}
