import { test } from "node:test";
import assert from "node:assert/strict";
import { readyOutCalls } from "../frontend/lib/out-calls.ts";

const event = (id: string, extra = {}) => ({ id, sessionId: "s", timestamp: 10,
  createdAt: 10.2, inOrOut: "OUT", status: "candidate", courtPosition: null,
  speedBefore: 30, confidence: null, ...extra });

test("OUT seen only by camera two is called once from the session", () => {
  const e=event("one",{cameraId:"b"});
  assert.equal(readyOutCalls([e],"s",9,11,new Set(),2).length,1);
  assert.equal(readyOutCalls([e],"s",9,11,new Set(["one"]),2).length,0);
});
test("all new OUT events survive a later IN in the same update", () => {
  const events=[event("1"),event("2",{inOrOut:"IN",timestamp:10.4}),event("3")];
  assert.deepEqual(readyOutCalls(events,"s",9,11,new Set()).map(e=>e.id),["1","3"]);
});
test("wait for second camera and never call a conflicting review", () => {
  assert.equal(readyOutCalls([event("1")],"s",9,10.4,new Set(),2).length,0);
  assert.equal(readyOutCalls([event("1",{inOrOut:"REVISAR",updatedAt:10.5})],"s",9,11,new Set(),2).length,0);
});
test("historical sessions and old decisions remain silent", () => {
  assert.equal(readyOutCalls([event("1")],"s",11,12,new Set()).length,0);
  assert.equal(readyOutCalls([event("1")],"another",9,11,new Set()).length,0);
  assert.equal(readyOutCalls([event("1")],"s",9,30,new Set()).length,0);
});

test("human reviews, rejected impacts and muted decisions never speak OUT", () => {
  const events = [
    event("reviewed", { manualReview: { decision: "OUT" }, updatedAt: 10.8 }),
    event("rejected", { status: "rejected", updatedAt: 10.8 }),
    event("muted", { varAlert: false }),
    event("allowed", { varAlert: true }),
  ];
  assert.deepEqual(readyOutCalls(events, "s", 9, 11, new Set()).map(e => e.id), ["allowed"]);
  assert.equal(readyOutCalls(events, "s", 9, 11, new Set(["allowed"])).length, 0);
});
