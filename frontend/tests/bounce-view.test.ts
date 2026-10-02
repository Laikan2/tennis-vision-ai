import assert from "node:assert/strict";
import { mappedEvents, eventSpeed, densityCells } from "../lib/bounce-view.ts";
const base = {
  timestamp: 1,
  inOrOut: "INCONCLUSIVO",
  confidence: null,
  speedBefore: null,
  sessionId: "test",
};
const confirmed = {
  ...base,
  id: "confirmed",
  status: "confirmed",
  courtPosition: [5, 12] as [number, number],
};
const candidate = {
  ...base,
  id: "candidate",
  status: "candidate",
  courtPosition: [4, 10] as [number, number],
};
assert.deepEqual(
  mappedEvents([confirmed, candidate]).map((e) => e.id),
  ["confirmed"],
);
assert.equal(mappedEvents([confirmed, candidate], true).length, 2);
const roomEvent = {
  ...candidate,
  position: [1440, 540] as [number, number],
  resolution: [1920, 1080] as [number, number],
  positionApproximate: true,
  courtPosition: [99, 99] as [number, number],
};
const mappedRoom = mappedEvents([roomEvent], true, "room")[0];
assert.ok(Math.abs(mappedRoom.courtPosition![0] - 1.45) < 0.01);
assert.ok(Math.abs(mappedRoom.courtPosition![1]) < 0.01);
assert.equal(
  mappedEvents([{ ...confirmed, courtPosition: [NaN, 1] }]).length,
  0,
);
assert.equal(eventSpeed(confirmed), null);
assert.equal(eventSpeed({ ...confirmed, speedBefore: 112 }), 112);
assert.equal(eventSpeed({ ...confirmed, speedBefore: NaN }), null);
assert.equal(densityCells([]).length, 0);
const peak = densityCells([confirmed]).sort((a, b) => b.value - a.value)[0];
assert.ok(Math.abs(peak.x - 5) < 0.6 && Math.abs(peak.y - 12) < 0.6);
console.log(
  "10 assertions passed: event filtering, room projection, unavailable speeds, measured speed and density position.",
);
