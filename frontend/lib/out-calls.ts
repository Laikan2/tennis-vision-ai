import type { Event } from "./types";

// Read the fused session, not just the primary camera or the latest event.
export function readyOutCalls(events: Event[], sessionId: string, since: number,
  now: number, seen: ReadonlySet<string>, cameraCount = 1,
  fusionWindowSeconds = .55): Event[] {
  return events.filter(event => {
    if (event.manualReview || event.status === "rejected" || event.varAlert === false) return false;
    const received = event.createdAt ?? event.timestamp;
    const decisionAt = event.updatedAt ?? received;
    return event.sessionId === sessionId && event.inOrOut === "OUT" &&
      !seen.has(event.id) && received >= since && now - decisionAt <= 8 &&
      now >= received + (cameraCount > 1 ? fusionWindowSeconds : 0);
  }).sort((a,b) => a.timestamp-b.timestamp);
}

export function readyInCalls(events: Event[], sessionId: string, since: number,
  now: number, seen: ReadonlySet<string>, cameraCount = 1,
  fusionWindowSeconds = .55): Event[] {
  return events.filter(event => {
    if (event.manualReview || event.status !== "confirmed" || event.positionApproximate) return false;
    const received=event.createdAt ?? event.timestamp;
    const decisionAt=event.updatedAt ?? received;
    return event.sessionId===sessionId && event.inOrOut==="IN" && !seen.has(event.id) &&
      received>=since && now-decisionAt<=8 && now>=received+(cameraCount>1 ? fusionWindowSeconds:0);
  }).sort((a,b)=>a.timestamp-b.timestamp);
}
