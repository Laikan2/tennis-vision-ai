import { test } from "node:test";
import assert from "node:assert/strict";
import { loadOutBuffer, type OutAudioState } from "../lib/audio-buffer.ts";

test("OUT can load again after a network failure", async () => {
  const originalFetch = globalThis.fetch;
  let requests = 0;
  const decoded = {} as AudioBuffer;
  const state: OutAudioState = { context: { decodeAudioData: async () => decoded }, buffer: null };
  globalThis.fetch = async () => {
    if (++requests === 1) throw new Error("network interrupted");
    return new Response(new Uint8Array([1, 2]));
  };
  try {
    await assert.rejects(loadOutBuffer(state));
    assert.equal(await loadOutBuffer(state), decoded);
    assert.equal(await loadOutBuffer(state), decoded);
    assert.equal(requests, 2);
  } finally { globalThis.fetch = originalFetch; }
});

test("clicking Test OUT while loading waits for the decoded sound", async () => {
  const originalFetch = globalThis.fetch;
  let requests = 0;
  let finish!: (value: AudioBuffer) => void;
  const decoded = {} as AudioBuffer;
  const state: OutAudioState = { context: {
    decodeAudioData: () => new Promise<AudioBuffer>(resolve => { finish = resolve; }),
  }, buffer: null };
  globalThis.fetch = async () => { requests++; return new Response(new Uint8Array([1])); };
  try {
    const first = loadOutBuffer(state);
    const click = loadOutBuffer(state);
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.equal(state.buffer, null);
    finish(decoded);
    assert.deepEqual(await Promise.all([first, click]), [decoded, decoded]);
    assert.equal(requests, 1);
  } finally { globalThis.fetch = originalFetch; }
});

test("an invalid audio response is also retryable", async () => {
  const originalFetch = globalThis.fetch;
  let attempts = 0;
  const state: OutAudioState = { context: { decodeAudioData: async () => {
    if (++attempts === 1) throw new Error("bad audio");
    return {} as AudioBuffer;
  } }, buffer: null };
  globalThis.fetch = async () => new Response(new Uint8Array([1]));
  try {
    await assert.rejects(loadOutBuffer(state));
    assert.ok(await loadOutBuffer(state));
    assert.equal(attempts, 2);
  } finally { globalThis.fetch = originalFetch; }
});
