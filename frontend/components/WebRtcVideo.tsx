"use client";
import { useEffect, useRef } from "react";
import { VideoProgress } from "../lib/video-playback";

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
export function WebRtcVideo({ url, onFailure, onReady, visible = true }: {
  url: string; onFailure(): void; onReady(): void; visible?: boolean;
}) {
  const element = useRef<HTMLVideoElement>(null);
  const failure = useRef(onFailure);
  failure.current = onFailure;
  const ready = useRef(onReady);
  ready.current = onReady;
  useEffect(() => {
    const video = element.current;
    if (!video) return;
    let active = true;
    let failed = false;
    let reader: Reader | undefined;
    const progress = new VideoProgress(performance.now());
    const fail = () => {
      if (!active || failed) return;
      failed = true;
      window.clearInterval(monitor);
      reader?.close();
      video.srcObject = null;
      failure.current();
    };
    void loadReader(new URL(url).origin).then(ReaderClass => {
      if (!active) return;
      reader = new ReaderClass({ url, onError: fail, onTrack: event => {
        if (!active || failed || event.track.kind !== "video") return;
        event.track.addEventListener("ended", fail, { once: true });
        video.srcObject = event.streams[0] || new MediaStream([event.track]);
        void video.play().catch(fail);
      } });
      // A reader may report an error synchronously during construction.
      if (failed) reader.close();
    }).catch(fail);
    const monitor = window.setInterval(() => {
      const frame = video.srcObject && video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA
        ? video.getVideoPlaybackQuality?.().totalVideoFrames ?? video.currentTime : 0;
      const state = progress.observe(frame, performance.now());
      if (state === "ready") ready.current();
      else if (state === "stalled") fail();
    }, 250);
    return () => {
      active = false;
      window.clearInterval(monitor);
      reader?.close();
      video.srcObject = null;
    };
  }, [url]);
  return <video ref={element} className="video-frame direct-video" autoPlay muted playsInline
    style={{ visibility: visible ? "visible" : "hidden" }}
    aria-label="Vídeo H.264 direto da câmera" />;
}
