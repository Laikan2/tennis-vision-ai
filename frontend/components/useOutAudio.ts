"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import type { Event } from "../lib/types";
import { readyOutCalls } from "../lib/out-calls";
import { loadOutBuffer } from "../lib/audio-buffer";

export function useOutAudio(events: Event[], enabled: boolean, sessionId?: string,
  cameraCount = 1, fusionWindowMs = 550) {
  const audio = useRef<{ context: AudioContext; buffer: AudioBuffer | null; loading?: Promise<AudioBuffer> } | null>(null);
  const seen = useRef(new Set<string>());
  const since = useRef(Date.now()/1000);
  const lastPlayed = useRef(0);
  const current = useRef({ events, enabled, sessionId, cameraCount, fusionWindowMs });
  current.current = { events, enabled, sessionId, cameraCount, fusionWindowMs };
  const [notice, setNotice] = useState("");

  const prepare = useCallback(async () => {
    if (!audio.current) {
      const context = new AudioContext();
      const state = { context, buffer: null as AudioBuffer | null };
      audio.current = state;
    }
    const state = audio.current;
    await loadOutBuffer(state);
    return state;
  }, []);

  const play = useCallback(async () => {
    try {
      const state = await prepare();
      if (state.context.state !== "running") await state.context.resume();
      if (state.context.state !== "running" || !state.buffer) return false;
      const source = state.context.createBufferSource();
      source.buffer = state.buffer;
      source.connect(state.context.destination);
      source.start();
      lastPlayed.current = Date.now()/1000;
      setNotice("");
      return true;
    } catch {
      setNotice("Ative o áudio em Testar som OUT.");
      return false;
    }
  }, [prepare]);

  useEffect(() => {
    void prepare().catch(() => setNotice("Não foi possível carregar o som OUT."));
    const unlock = () => { void audio.current?.context.resume().catch(() => {}); };
    window.addEventListener("pointerdown", unlock);
    window.addEventListener("keydown", unlock);
    return () => {
      window.removeEventListener("pointerdown", unlock);
      window.removeEventListener("keydown", unlock);
      void audio.current?.context.close();
      audio.current = null;
    };
  }, [prepare]);

  useEffect(() => {
    seen.current.clear();
    since.current = Date.now()/1000 - 1;
  }, [sessionId]);

  useEffect(() => {
    let playing = false;
    const timer = window.setInterval(() => {
      const state = current.current;
      if (!state.sessionId) return;
      const calls = readyOutCalls(state.events,state.sessionId,since.current,
        Date.now()/1000,seen.current,state.cameraCount,state.fusionWindowMs/1000);
      if (!state.enabled) {
        calls.forEach(event => seen.current.add(event.id));
        return;
      }
      if (playing || Date.now()/1000-lastPlayed.current < 1.0 || !calls.length) return;
      if (audio.current?.context.state !== "running") {
        setNotice("Clique em Testar som OUT para ativar o áudio.");
        return;
      }
      playing = true;
      const id = calls[0].id;
      void play().then(ok => { if (ok) seen.current.add(id); }).finally(() => { playing=false; });
    }, 120);
    return () => window.clearInterval(timer);
  }, [play]);

  const test = useCallback(async () => {
    // Resume immediately in the click gesture, before awaiting any loading.
    void audio.current?.context.resume();
    if (await play()) setNotice("Som OUT testado. Mantenha o volume ligado.");
    else setNotice("O navegador bloqueou o áudio. Clique para testar novamente.");
  }, [play]);
  return { test, notice };
}
