export type OutAudioState = {
  context: Pick<AudioContext, "decodeAudioData">;
  buffer: AudioBuffer | null;
  loading?: Promise<AudioBuffer>;
};

// A click during initial loading waits for the same decoded sound. Failed
// requests are retryable instead of leaving an empty buffer forever.
export async function loadLineBuffer(state: OutAudioState, voice: "OUT" | "IN"): Promise<AudioBuffer> {
  if (state.buffer) return state.buffer;
  if (!state.loading) {
    state.loading = (async () => {
      const response = await fetch(`/audio/${voice.toLowerCase()}-eleven-v4-v3.mp3`);
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

export const loadOutBuffer = (state: OutAudioState) => loadLineBuffer(state,"OUT");
