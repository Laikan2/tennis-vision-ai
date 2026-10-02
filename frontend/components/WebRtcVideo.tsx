"use client";
import { useEffect, useRef } from "react";

type Reader = { close(): void };
type ReaderConstructor = new (options: {
  url: string;
  onTrack(event: RTCTrackEvent): void;
  onError(error: string): void;
}) => Reader;
declare global { interface Window { MediaMTXWebRTCReader?: ReaderConstructor } }

let readerLoading: Promise<ReaderConstructor> | undefined;
function loadReader(origin: string): Promise<ReaderConstructor> {
  if (window.MediaMTXWebRTCReader) return Promise.resolve(window.MediaMTXWebRTCReader);
  if (!readerLoading) {
    readerLoading = new Promise<ReaderConstructor>((resolve, reject) => {
      const script = document.createElement("script");
      const timeout = window.setTimeout(() => failed(), 5000);
      function failed() {
        window.clearTimeout(timeout);
        script.remove();
        reject(new Error("Receptor WebRTC indisponível"));
      }
      script.src = `${origin}/reader.js`;
      script.async = true;
      script.onerror = failed;
      script.onload = () => {
        if (!window.MediaMTXWebRTCReader) { failed(); return; }
        window.clearTimeout(timeout);
        resolve(window.MediaMTXWebRTCReader);
      };
      document.head.append(script);
    }).catch(error => { readerLoading = undefined; throw error; });
  }
  return readerLoading;
}

/** Observe decoded video so an unavailable/frozen peer can use the JPEG fallback. */
export function WebRtcVideo({ url, onFailure }: { url: string; onFailure(): void }) {
  const element = useRef<HTMLVideoElement>(null);
  const failure = useRef(onFailure);
  failure.current = onFailure;
  useEffect(() => {
    const video = element.current;
    if (!video) return;
    let active = true;
    let reader: Reader | undefined;
    let lastFrame = -1;
    let hasFrame = false;
    let lastProgress = performance.now();
    const fail = () => { if (active) failure.current(); };
    void loadReader(new URL(url).origin).then(ReaderClass => {
      if (!active) return;
      reader = new ReaderClass({ url, onError: fail, onTrack: event => {
        if (!active || event.track.kind !== "video") return;
        video.srcObject = event.streams[0] || new MediaStream([event.track]);
        void video.play().catch(fail);
      } });
    }).catch(fail);
    const monitor = window.setInterval(() => {
      const frame = video.getVideoPlaybackQuality?.().totalVideoFrames ?? video.currentTime;
      const now = performance.now();
      if (frame > 0 && frame !== lastFrame) {
        lastFrame = frame; hasFrame = true; lastProgress = now;
      } else if (now-lastProgress > (hasFrame ? 3500 : 10000)) fail();
    }, 1000);
    return () => {
      active = false;
      window.clearInterval(monitor);
      reader?.close();
      video.srcObject = null;
    };
  }, [url]);
  return <video ref={element} className="video-frame direct-video" autoPlay muted playsInline
    aria-label="Vídeo H.264 direto da câmera" />;
}
