import type { AnalysisMode, Event } from "./types";

export function mappedEvents(
  events: Event[],
  includeUncertain = false,
  mode: AnalysisMode = "court",
) {
  const surfaceMode = mode === "tracking" ? "room" : mode;
  return events
    .map((event) => {
      if (event.targetPosition && (surfaceMode === "court" || !event.courtPosition || event.positionApproximate)) {
        return {...event, courtPosition: (surfaceMode === "room"
          ? [(event.targetPosition[0]-.5)*5.8,(event.targetPosition[1]-.5)*3.8]
          : [event.targetPosition[0]*10.97,event.targetPosition[1]*23.77]) as [number,number]};
      }
      // Early room sessions stored an approximate metre conversion. Depending
      // on camera resolution, that could place a valid wall hit outside the
      // room diagram. Reproject those saved events from their image position
      // so old and new marks remain visible in the same normalized plan.
      if (
        surfaceMode === "room" &&
        event.positionApproximate &&
        event.position?.length === 2 &&
        event.resolution?.length === 2 &&
        event.resolution[0] > 0 &&
        event.resolution[1] > 0
      ) {
        return {
          ...event,
          courtPosition: [
            (event.position[0] / event.resolution[0] - 0.5) * 5.8,
            (event.position[1] / event.resolution[1] - 0.5) * 3.8,
          ] as [number, number],
        };
      }
      return event;
    })
    .filter(
      (e) =>
        (e.status === "confirmed" ||
          (includeUncertain && e.status === "candidate")) &&
        e.courtPosition?.length === 2 &&
        e.courtPosition.every(Number.isFinite),
    );
}

export function eventSpeed(e: Event) {
  return e.speedBefore != null &&
    Number.isFinite(e.speedBefore) &&
    e.speedBefore >= 0
    ? e.speedBefore
    : null;
}

// Gaussian density in metres. Colour represents frequency, never speed.
export function densityCells(events: Event[], mode: AnalysisMode = "court") {
  const cells: { x: number; y: number; value: number }[] = [];
  const bounds = mode !== "court"
    ? { minX: -3, maxX: 3, minY: -2, maxY: 2 }
    : { minX: -1, maxX: 12, minY: -1, maxY: 25 };
  for (let y = bounds.minY; y < bounds.maxY; y += 0.5)
    for (let x = bounds.minX; x < bounds.maxX; x += 0.5) {
      const value = events.reduce((sum, e) => {
        const p = e.courtPosition!;
        const distance = (x + 0.25 - p[0]) ** 2 + (y + 0.25 - p[1]) ** 2;
        return sum + Math.exp(-distance / (2 * 0.65 ** 2));
      }, 0);
      if (value > 0.02) cells.push({ x, y, value });
    }
  const maximum = Math.max(1, ...cells.map((c) => c.value));
  return cells.map((c) => ({ ...c, value: c.value / maximum }));
}
