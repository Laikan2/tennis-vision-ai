import { test } from "node:test";
import assert from "node:assert/strict";
import { readyInCalls,readyOutCalls } from "../lib/out-calls.ts";
import { loadLineBuffer, type OutAudioState } from "../lib/audio-buffer.ts";
import type { Event } from "../lib/types.ts";
const hit=(overrides:Partial<Event>={}):Event=>({id:"hit",sessionId:"session",timestamp:100,createdAt:100,
  status:"confirmed",inOrOut:"IN",courtPosition:null,confidence:.95,speedBefore:40,...overrides});
test("IN waits for fusion and does not announce history or duplicate events",()=>{
  const seen=new Set<string>();const event=hit({varAlert:false});
  assert.deepEqual(readyInCalls([event],"session",99,100.2,seen,2),[]);
  assert.deepEqual(readyInCalls([event],"session",99,100.6,seen,2),[event]);
  seen.add(event.id);assert.deepEqual(readyInCalls([event],"session",99,101,seen),[]);
  assert.deepEqual(readyInCalls([event],"other",99,101,new Set()),[]);
  assert.deepEqual(readyInCalls([event],"session",101,102,new Set()),[]);
  assert.deepEqual(readyInCalls([event],"session",99,110,new Set()),[]);
});
test("candidates, approximate locations and human reviews never speak IN",()=>{
  for(const overrides of [{status:"candidate"},{status:"rejected"},{positionApproximate:true},{manualReview:{decision:"IN"}}]) {
    assert.equal(readyInCalls([hit(overrides as Partial<Event>)],"session",99,101,new Set()).length,0);
  }
});
test("a fused correction from IN to OUT keeps its independent announcement",()=>{
  const event=hit({inOrOut:"OUT",varAlert:true,updatedAt:101});
  assert.equal(readyOutCalls([event],"session",99,102,new Set()).length,1);
  assert.equal(readyInCalls([event],"session",99,102,new Set()).length,0);
});
test("IN and OUT use independent decoded buffers and versioned ElevenLabs assets",async()=>{
  const original=globalThis.fetch,paths:string[]=[];
  globalThis.fetch=async input=>{paths.push(String(input));return new Response(new Uint8Array([1]));};
  const make=():OutAudioState=>({context:{decodeAudioData:async()=>({} as AudioBuffer)},buffer:null});
  try{
    const inside=make(),outside=make();
    const a=await loadLineBuffer(inside,"IN"),b=await loadLineBuffer(outside,"OUT");
    assert.notEqual(a,b);await loadLineBuffer(inside,"IN");
    assert.deepEqual(paths,["/audio/in-eleven-v4-v1.mp3","/audio/out-eleven-v4-v1.mp3"]);
  }finally{globalThis.fetch=original;}
});
