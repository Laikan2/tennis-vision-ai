"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import type { Event } from "../lib/types";
import { readyOutCalls, readyInCalls } from "../lib/out-calls";
import { loadLineBuffer, type OutAudioState } from "../lib/audio-buffer";

type Voice="OUT"|"IN";
type AudioState={context:AudioContext;OUT:OutAudioState;IN:OutAudioState};
export function useOutAudio(events: Event[], enabled: boolean, sessionId?: string,
  cameraCount=1, fusionWindowMs=550, inEnabled=false) {
  const audio=useRef<AudioState|null>(null);
  const seen=useRef({OUT:new Set<string>(),IN:new Set<string>()});
  const since=useRef(Date.now()/1000);
  const playing=useRef<{voice:Voice;source:AudioBufferSourceNode}|null>(null);
  const request=useRef(0);
  const pending=useRef<Voice|null>(null);
  const current=useRef({events,enabled,inEnabled,sessionId,cameraCount,fusionWindowMs});
  current.current={events,enabled,inEnabled,sessionId,cameraCount,fusionWindowMs};
  const [notice,setNotice]=useState("");
  const ensureAudio=useCallback(()=>{
    if(!audio.current){
      const context=new AudioContext();
      audio.current={context,OUT:{context,buffer:null},IN:{context,buffer:null}};
    }
    return audio.current;
  },[]);
  const stop=useCallback(()=>{
    request.current++;pending.current=null;
    if(playing.current){playing.current.source.onended=null;playing.current.source.stop();playing.current.source.disconnect();playing.current=null;}
  },[]);
  const play=useCallback(async(voice:Voice,test=false)=>{
    if(!test && playing.current && !(voice==="OUT" && playing.current.voice==="IN")) return false;
    stop();const token=request.current;pending.current=voice;
    try{
      const state=ensureAudio();
      // Resume inside the click gesture, before the first asynchronous load.
      if(state.context.state!=="running") await state.context.resume();
      const buffer=await loadLineBuffer(state[voice],voice);
      if(token!==request.current || audio.current!==state || state.context.state!=="running") return false;
      const source=state.context.createBufferSource();source.buffer=buffer;source.connect(state.context.destination);
      playing.current={voice,source};source.onended=()=>{source.disconnect();if(playing.current?.source===source) playing.current=null;};
      source.start();setNotice(`Reprodução ${voice} iniciada · confira o volume de mídia e a saída de áudio.`);return true;
    }catch{
      if(token===request.current) setNotice(`Não iniciou o som ${voice}. Clique em Testar para tentar novamente.`);
      return false;
    }finally{if(token===request.current) pending.current=null;}
  },[ensureAudio,stop]);
  useEffect(()=>{
    const state=ensureAudio();
    void Promise.all([loadLineBuffer(state.OUT,"OUT"),loadLineBuffer(state.IN,"IN")]).catch(()=>setNotice("Não carregou as vozes. Use Testar para tentar novamente."));
    const unlock=()=>{void audio.current?.context.resume().catch(()=>{});};
    const hidden=()=>{since.current=Date.now()/1000;if(document.hidden) stop();};
    window.addEventListener("pointerdown",unlock);window.addEventListener("keydown",unlock);document.addEventListener("visibilitychange",hidden);
    return()=>{
      window.removeEventListener("pointerdown",unlock);window.removeEventListener("keydown",unlock);document.removeEventListener("visibilitychange",hidden);
      stop();void audio.current?.context.close();audio.current=null;
    };
  },[ensureAudio,stop]);
  useEffect(()=>{seen.current.OUT.clear();seen.current.IN.clear();since.current=Date.now()/1000-1;stop();},[sessionId,stop]);
  useEffect(()=>{
    const timer=window.setInterval(()=>{
      const s=current.current;if(!s.sessionId || document.hidden) return;
      const now=Date.now()/1000;
      const out=readyOutCalls(s.events,s.sessionId,since.current,now,seen.current.OUT,s.cameraCount,s.fusionWindowMs/1000);
      const inside=readyInCalls(s.events,s.sessionId,since.current,now,seen.current.IN,s.cameraCount,s.fusionWindowMs/1000);
      if(!s.enabled) out.forEach(e=>seen.current.OUT.add(e.id));
      if(!s.inEnabled) inside.forEach(e=>seen.current.IN.add(e.id));
      const voice:Voice|null=s.enabled && out.length ? "OUT":s.inEnabled && inside.length ? "IN":null;
      if(!voice) return;
      if(audio.current?.context.state!=="running"){setNotice("Clique em Testar som OUT ou IN para ativar o áudio.");return;}
      // Consume a burst together: never replay old calls over the following rally.
      out.forEach(e=>seen.current.OUT.add(e.id));inside.forEach(e=>seen.current.IN.add(e.id));
      if(pending.current && !(voice==="OUT" && pending.current==="IN")) return;
      void play(voice);
    },120);
    return()=>window.clearInterval(timer);
  },[play]);
  return {test:useCallback(()=>play("OUT",true),[play]),testIn:useCallback(()=>play("IN",true),[play]),notice};
}
