/** Desktop uses a private backend port; its local HTTP relay owns that route. */
export function videoEndpoints(pageUrl: string, native: boolean, path?: string) {
  const page = new URL(pageUrl);
  const preview = new URL(page.origin);
  if (!native && page.protocol === "http:") preview.port = "8000";
  const encodedPath = path?.split("/").filter(Boolean).map(encodeURIComponent).join("/");
  const receiver = new URL(page.origin);
  receiver.port = "8889";
  // The local receiver serves HTTP. HTTPS pages use their own preview relay.
  return {
    previewOrigin: preview.origin,
    directUrl: encodedPath && page.protocol === "http:"
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
