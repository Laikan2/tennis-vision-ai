/** Desktop uses a private backend port; its local HTTP relay owns that route. */
export function videoEndpoints(pageUrl: string, native: boolean, path?: string, phonePanel=false) {
  const page = new URL(pageUrl);
  const phone = phonePanel || page.searchParams.get("phone") === "1";
  const preview = new URL(page.origin);
  // Only the standalone Next server uses the separate development backend.
  // A browser opened on Studio's random port must use the same relay as the
  // Electron window; it has no native preload flag, but the route is valid.
  if (!native && !phone && page.protocol === "http:" && page.port === "3000") preview.port = "8000";
  const encodedPath = path?.split("/").filter(Boolean).map(encodeURIComponent).join("/");
  const receiver = new URL(page.origin);
  receiver.port = "8889";
  // The local receiver serves HTTP. HTTPS pages use their own preview relay.
  return {
    previewOrigin: preview.origin,
    directUrl: encodedPath && page.protocol === "http:" && !phone
      ? `${receiver.origin}/${encodedPath}/whep` : undefined,
  };
}

/** Decoded-frame progress distinguishes a slow connection from a frozen image. */
export class VideoProgress {
  private lastFrame = -1;
  private hasFrame = false;
  private lastProgress: number;
  constructor(now: number) { this.lastProgress = now; }

  observe(frame: number, now: number): "waiting" | "ready" | "playing" | "stalled" {
    if (frame > 0 && frame !== this.lastFrame) {
      const first = !this.hasFrame;
      this.lastFrame = frame;
      this.hasFrame = true;
      this.lastProgress = now;
      return first ? "ready" : "playing";
    }
    return now - this.lastProgress > (this.hasFrame ? 3500 : 10000) ? "stalled" : "waiting";
  }
}

/** Sample playback counters, never equate the requested camera rate with display FPS. */
export class PlaybackMeter {
  private previous?: { now: number; frames: number; dropped: number };
  sample(now: number, frames: number, dropped: number) {
    const before = this.previous;
    if (!before || frames < before.frames || dropped < before.dropped) {
      this.previous = { now, frames, dropped };
      return null;
    }
    const elapsed = now - before.now;
    if (elapsed < 1000) return null;
    this.previous = { now, frames, dropped };
    const losses = dropped - before.dropped;
    return { fps: Math.max(0, frames - before.frames - losses) * 1000 / elapsed, dropped: losses };
  }
}
