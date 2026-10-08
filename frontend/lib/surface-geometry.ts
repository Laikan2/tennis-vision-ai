export type XY = [number, number];

/** A perspective surface needs four distinct corners in perimeter order. */
export function validSurface(points: XY[]): boolean {
  if (points.length !== 4 || points.some(p => p.some(v => !Number.isFinite(v) || v < 0 || v > 1))) return false;
  const crosses = points.map((a, i) => {
    const b = points[(i + 1) % 4], c = points[(i + 2) % 4];
    return (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
  });
  const area = Math.abs(points.reduce((sum, p, i) => {
    const next = points[(i + 1) % 4]; return sum + p[0] * next[1] - next[0] * p[1];
  }, 0));
  return area > .004 && (crosses.every(v => v > .000001) || crosses.every(v => v < -.000001));
}
