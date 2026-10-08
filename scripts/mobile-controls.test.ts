import { test } from "node:test";
import assert from "node:assert/strict";
import { api } from "../frontend/lib/api-client.ts";
import { validSurface } from "../frontend/lib/surface-geometry.ts";

test("a save with a stalled response body times out and can be tried again", async () => {
  const original=globalThis.fetch;
  globalThis.fetch = async (_url, init) => ({ok:true, json:()=>new Promise((_resolve,reject)=>{
    init!.signal!.addEventListener("abort",()=>reject(new DOMException("aborted","AbortError")));
  })}) as Response;
  try {
    await assert.rejects(api("/capture-region",{}, {timeoutMs:20}), /PC demorou/);
    globalThis.fetch=async()=>Response.json({capturePolygon:[[.1,.2],[.8,.2],[.8,.9],[.1,.9]]});
    assert.ok(await api("/capture-region",{}));
  } finally { globalThis.fetch=original; }
});

test("leaving a camera cancels its unfinished save", async () => {
  const original=globalThis.fetch;
  globalThis.fetch = async (_url, init) => new Promise((_resolve,reject)=>{
    init!.signal!.addEventListener("abort",()=>reject(new DOMException("aborted","AbortError")));
  });
  try {
    const controller=new AbortController();
    const request=api("/capture-region",{}, {signal:controller.signal,timeoutMs:1000});
    controller.abort();
    await assert.rejects(request,{name:"AbortError"});
  } finally { globalThis.fetch=original; }
});

test("crossed, folded and collapsed perspective areas cannot be saved",()=>{
  for (const area of [
    [[.1,.1],[.9,.9],[.9,.1],[.1,.9]],
    [[.1,.1],[.9,.1],[.2,.2],[.1,.9]],
    [[.1,.1],[.9,.1],[.9,.1],[.1,.9]],
    [[.1,.1],[.101,.1],[.101,.101],[.1,.101]],
  ] as [number,number][][]) assert.equal(validSurface(area),false);
  const good: [number,number][]=[[.4,.1],[.6,.1],[.95,.9],[.05,.9]];
  assert.ok(validSurface(good)); assert.ok(validSurface(good.toReversed()));
});
