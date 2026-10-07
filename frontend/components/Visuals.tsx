"use client";
import { useEffect, useState, useMemo, useRef } from "react";
import { motion } from "framer-motion";
import { WebRtcVideo } from "./WebRtcVideo";
import { videoEndpoints } from "../lib/video-playback";
import {
  Camera,
  Crosshair,
  Pause,
  Play,
  SkipBack,
  SkipForward,
} from "lucide-react";
import {
  AnalysisMode,
  api,
  color,
  Event,
  number,
  Replay,
  ReplayFrame,
  Status,
} from "../lib/types";

import { mappedEvents, eventSpeed, densityCells } from "../lib/bounce-view";
import { PoseOverlay } from "./PoseOverlay";
import { CaptureArea } from "./CaptureArea";

const sectorNames: Record<string,string> = {
  far_deep_left: "Fundo A · esquerda",
  far_deep_right: "Fundo A · direita",
  far_service_left: "Saque A · esquerda",
  far_service_right: "Saque A · direita",
  near_service_left: "Saque B · esquerda",
  near_service_right: "Saque B · direita",
  near_deep_left: "Fundo B · esquerda",
  near_deep_right: "Fundo B · direita",
  left_alley: "Corredor esquerdo",
  right_alley: "Corredor direito",
  outside_court: "Fora da quadra",
};
const strokeName = (event: Event) => event.strokeType === "forehand"
  ? "FOREHAND"
  : event.strokeType === "backhand" ? "BACKHAND" : "GOLPE NÃO CLASSIFICADO";

export function Court({
  events = [],
  heat = false,
  mode = "court",
}: {
  events?: Event[];
  heat?: boolean;
  mode?: AnalysisMode;
}) {
  // Show candidates by default, but keep their dashed outline and “incerta” label.
  // This makes a detected ping-pong bounce visible without claiming confirmation.
  const [includeUncertain, setIncludeUncertain] = useState(true);
  const [selected, setSelected] = useState<string | null>(null);
  const roomMode = mode === "room";
  const labelMinX = roomMode ? -2.9 : 0;
  const labelMaxX = roomMode ? 2.1 : 8.2;
  const labelMinY = roomMode ? -1.8 : 0.4;
  const labelMaxY = roomMode ? 1.8 : 23.5;
  const visible = useMemo(
    () => mappedEvents(events, includeUncertain, mode).sort((a,b)=>a.timestamp-b.timestamp),
    [events, includeUncertain, mode],
  );
  const cells = useMemo(
    () => (heat ? densityCells(visible, mode) : []),
    [heat, mode, visible],
  );
  const active = visible.find((e) => e.id === selected);
  const eventNumbers = useMemo(()=>new Map([...events].sort((a,b)=>a.timestamp-b.timestamp).map((event,i)=>[event.id,i+1])),[events]);
  const markers = visible.slice(-80);
  // An OUT can be well beyond the marked rectangle. Keep its real position
  // visible instead of clipping it at the fixed court SVG border.
  const bounds=markers.reduce((box,event) => {
    const [x,y]=event.courtPosition!;
    return [Math.min(box[0],x-.7),Math.min(box[1],y-.7),
      Math.max(box[2],x+.7),Math.max(box[3],y+.7)];
  },roomMode ? [-3.4,-2.4,3.4,2.4] : [-1.2,-1.2,12.17,24.97]);
  const viewBox=`${bounds[0]} ${bounds[1]} ${bounds[2]-bounds[0]} ${bounds[3]-bounds[1]}`;
  return (
    <div className="bounce-map">
      <label className="toggle">
        <input
          type="checkbox"
          checked={includeUncertain}
          onChange={(e) => setIncludeUncertain(e.target.checked)}
        />
        <span />
        {roomMode
          ? "Incluir possíveis impactos (incertos)"
          : "Incluir possíveis quicadas (incertas)"}
      </label>
      <div className="court-wrap">
        <svg
          viewBox={viewBox}
          role="img"
          aria-label={
            heat
              ? roomMode
                ? "Mapa de concentração no ambiente"
                : "Mapa de concentração de quicadas"
              : roomMode
                ? "Marcas de movimento no ambiente 2D"
                : "Marcas de quicadas na quadra 2D"
          }
        >
          <rect
            x={roomMode ? -3.4 : -1.2}
            y={roomMode ? -2.4 : -1.2}
            width={roomMode ? 6.8 : 13.37}
            height={roomMode ? 4.8 : 26.17}
            rx=".4"
            fill="#172c2b"
          />
          {roomMode ? (
            <rect x="-3" y="-2" width="6" height="4" fill="#213b36" />
          ) : (
            <rect width="10.97" height="23.77" fill="#213b36" />
          )}
          {heat &&
            cells.map((c) => (
              <rect
                key={`${c.x}/${c.y}`}
                x={c.x}
                y={c.y}
                width=".51"
                height=".51"
                fill={`hsl(${(1 - c.value) * 220} 90% 55%)`}
                opacity={Math.min(0.82, c.value)}
              />
            ))}
          <g fill="none" stroke="#b6ceba" strokeWidth=".065">
            {roomMode ? (
              <>
                <rect x="-3" y="-2" width="6" height="4" />
                <path d="M0 -2V2M-3 0H3" strokeDasharray=".18 .09" strokeWidth=".1" />
                <path d="M-1.5 -2V2M1.5 -2V2M-3 -1H3M-3 1H3" opacity=".45" />
              </>
            ) : (
              <>
                <rect width="10.97" height="23.77" />
                <path d="M1.37 0V23.77M9.6 0V23.77M1.37 5.485H9.6M1.37 18.285H9.6M5.485 5.485V18.285" />
                <path d="M-.4 11.885H11.37" strokeDasharray=".18 .09" strokeWidth=".1" />
              </>
            )}
          </g>
          {!heat &&
            markers.map((e, i) => (
              <g
                key={e.id}
                role="button"
                tabIndex={0}
                aria-label={`Marca ${eventNumbers.get(e.id)}, ${e.status === "candidate" ? roomMode ? "impacto incerto" : "quicada incerta" : e.inOrOut}`}
                onClick={() => setSelected(e.id)}
                onKeyDown={(k) => {
                  if (k.key === "Enter" || k.key === " ") {
                    k.preventDefault();
                    setSelected(e.id);
                  }
                }}
              >
                <circle
                  cx={e.courtPosition![0]}
                  cy={e.courtPosition![1]}
                  r=".28"
                  fill={e.strokeType === "forehand" ? "#40c4da" : e.strokeType === "backhand" ? "#e6d54a" : "#f5f7f0"}
                  fillOpacity={e.status === "candidate" ? .22 : undefined}
                  stroke={e.inOrOut === "OUT" ? "#ff4854" : eventSpeed(e) == null ? "#b2c0c5" : color(eventSpeed(e))}
                  strokeWidth={e.inOrOut === "OUT" ? ".16" : ".1"}
                  strokeDasharray={
                    e.status === "candidate" ? ".12 .09" : undefined
                  }
                />
                {(e.id === selected || i === markers.length - 1) && <text
                  x={Math.max(labelMinX, Math.min(labelMaxX, e.courtPosition![0] + 0.4))}
                  y={Math.max(labelMinY, Math.min(labelMaxY, e.courtPosition![1] - 0.25))}
                  fontSize=".42"
                  fill="#eef5ed"
                  paintOrder="stroke"
                  stroke="#10211b"
                  strokeWidth=".12"
                >
                  {eventSpeed(e) == null
                    ? `#${eventNumbers.get(e.id)} · ${e.strokeType === "forehand" ? "FH" : e.strokeType === "backhand" ? "BH" : "—"}`
                    : `#${eventNumbers.get(e.id)} · ${e.strokeType === "forehand" ? "FH" : e.strokeType === "backhand" ? "BH" : "—"} · ${number(eventSpeed(e))} km/h`}
                </text>}
                <title>
                  {e.status === "candidate"
                    ? roomMode ? "Possível impacto · incerto" : "Possível quique · incerta"
                    : e.inOrOut}{" "}
                  ·{" "}
                  {eventSpeed(e) == null
                    ? "Velocidade indisponível"
                    : number(eventSpeed(e)) + " km/h"}{" "}
                  · {new Date(e.timestamp * 1000).toLocaleTimeString("pt-BR")}
                </title>
              </g>
            ))}
        </svg>
        {!visible.length && (
          <span className="court-empty">
            {includeUncertain
              ? "Sem eventos localizados"
              : roomMode ? "Sem impactos confirmados" : "Sem quicadas confirmadas"}
          </span>
        )}
      </div>
      <small>
        {heat
          ? "Azul → vermelho: menor → maior concentração relativa."
          : roomMode
            ? "Posição relativa na superfície ou imagem. Cor = velocidade estimada quando disponível."
            : "Cor = velocidade. Cinza = velocidade indisponível."}
      </small>
      {!heat && <small>Até 80 marcas recentes. Toque em uma marca para ver a velocidade; o mapa de calor reúne toda a sessão.</small>}
      {includeUncertain && (
        <p className="inline-note">
          Inclui candidatos incertos; não representa decisões confirmadas.
        </p>
      )}
      {active && (
        <div className="mark-details">
          <strong>
            {active.status === "candidate"
              ? roomMode ? "Possível impacto · incerto" : "Possível quique · incerta"
              : active.inOrOut}
          </strong>
          <span>
            {eventSpeed(active) == null
              ? "Velocidade indisponível"
              : number(eventSpeed(active)) + " km/h"}
          </span>
          <span>{strokeName(active)}{active.courtSector ? ` · ${sectorNames[active.courtSector] || active.courtSector}` : ""}</span>
          <small>
            {active.targetPosition && (!active.courtPosition || active.positionApproximate || active.speedMethod !== "ground_plane_projection")
              ? `${Math.round(active.targetPosition[0]*100)}% / ${Math.round(active.targetPosition[1]*100)}% · `
              : `${number(active.courtPosition![0],2)} / ${number(active.courtPosition![1],2)} m · `}
            {new Date(active.timestamp * 1000).toLocaleTimeString("pt-BR")}
          </small>
        </div>
      )}
    </div>
  );
}

export function LiveVideo({
  status,
  events = [],
  trajectory = true,
  length = 50,
  labels = true,
  debug = false,
  webrtcPath,
  cameraId,
  synchronized = false,
  skeleton = true,
}: {
  status: Status;
  events?: Event[];
  trajectory?: boolean;
  length?: number;
  labels?: boolean;
  debug?: boolean;
  webrtcPath?: string;
  cameraId?: string;
  synchronized?: boolean;
  skeleton?: boolean;
}) {
  const [src, setSrc] = useState<string>();
  const [clearedAt, setClearedAt] = useState(0);
  const [overlay, setOverlay] = useState<Partial<Status>>({});
  const lastFrameSequence = useRef<number | undefined>(undefined);
  const editingSurface = useRef(false);
  const [surfaceEditing, setSurfaceEditing] = useState(false);
  const [controlsMount, setControlsMount] = useState<HTMLDivElement | null>(null);
  const [previewOrigin, setPreviewOrigin] = useState("");
  const [directSrc, setDirectSrc] = useState<string>();
  const [pageVisible, setPageVisible] = useState(true);
  const [directFailed, setDirectFailed] = useState(false);
  const [directReady, setDirectReady] = useState(false);
  const [previewFailed, setPreviewFailed] = useState(false);
  useEffect(() => {
    setDirectFailed(false); setDirectReady(false); setPreviewFailed(false);
  }, [cameraId, webrtcPath, status.session?.id, status.stale]);
  useEffect(() => {
    // The Next.js proxy is useful for normal JSON calls, but relaying every
    // 1080p JPEG through it drops several live frames. The browser can read an
    // image stream cross-origin, so connect it straight to the local backend.
    const endpoints = videoEndpoints(location.href, Boolean(window.tennisStudio), webrtcPath);
    setPreviewOrigin(endpoints.previewOrigin);
    setDirectSrc(endpoints.directUrl);
  }, [webrtcPath]);
  useEffect(() => {
    const update = () => setPageVisible(document.visibilityState === "visible");
    update();
    document.addEventListener("visibilitychange", update);
    return () => document.removeEventListener("visibilitychange", update);
  }, []);
  const directConnecting = Boolean(
    pageVisible && !surfaceEditing && !synchronized && !directFailed && directSrc && !status.stale &&
    status.cameraStatus === "online",
  );
  const directActive = directConnecting && directReady;
  useEffect(() => {
    if (!directConnecting) setDirectReady(false);
  }, [directConnecting]);
  useEffect(() => {
    if (!pageVisible || surfaceEditing || synchronized || status.stale || status.cameraStatus !== "online" ||
        (!directFailed && !previewFailed)) return;
    const retry = window.setTimeout(() => {
      setDirectFailed(false); setPreviewFailed(false);
    }, 15000);
    return () => window.clearTimeout(retry);
  }, [pageVisible, surfaceEditing, synchronized, status.stale, status.cameraStatus, directFailed, previewFailed]);
  const cameraQuery = cameraId ? `&cameraId=${encodeURIComponent(cameraId)}` : "";
  const previewQuery = cameraId ? `?cameraId=${encodeURIComponent(cameraId)}` : "";
  const fluidPreview = Boolean(
    pageVisible && !surfaceEditing && !synchronized && !directActive && !previewFailed && !status.stale &&
    status.cameraStatus === "online",
  );
  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    let socket: WebSocket | undefined;
    let fallback = false;
    let current: string | undefined;
    lastFrameSequence.current = undefined;
    // Hiding a WebRTC tab disables directActive. It must not activate the
    // JPEG fallback: every hidden tab then encoded and fetched full frames.
    if (!pageVisible || status.stale || directActive || fluidPreview) {
      setSrc(undefined);
      setOverlay({});
      return;
    }
    const pull = async () => {
      if (editingSurface.current && current) {
        if (active) timer = setTimeout(pull, 60);
        return;
      }
      try {
        const after = lastFrameSequence.current;
        const response = await fetch(
          `/api/frame?synchronized=${synchronized || editingSurface.current ? "true" : "false"}${cameraQuery}${after == null ? "" : `&after=${after}`}`,
          { cache: "no-store" },
        );
        if (response.status === 204) {
          // The analyser has not committed a newer frame yet. Keep the current
          // image instead of decoding and repainting the same JPEG.
        } else if (response.ok) {
          const metadata = JSON.parse(
            response.headers.get("X-Frame-Metadata") || "{}",
          );
          const blob = await response.blob();
          if (active) {
            const url = URL.createObjectURL(blob);
            const decoded = new Image();
            decoded.src = url;
            try { await decoded.decode(); } catch (error) { URL.revokeObjectURL(url); throw error; }
            if (!active || (editingSurface.current && current)) { URL.revokeObjectURL(url); if(active)timer=setTimeout(pull,60); return; }
            setSrc(url);
            setOverlay(metadata);
            if (typeof metadata.sequence === "number") {
              lastFrameSequence.current = metadata.sequence;
            }
            if (current) URL.revokeObjectURL(current);
            current = url;
          }
        } else if (active) {
          setSrc(undefined);
          setOverlay({});
        }
      } catch {
        if (active) {
          setSrc(undefined);
          setOverlay({});
        }
      }
      // Pull sequentially so requests never accumulate. The server returns 204
      // for duplicate frames, keeping rendering tied to real AI output.
      if (active) timer = setTimeout(pull, 8);
    };
    if (synchronized) {
      socket = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/video${cameraId ? `?cameraId=${encodeURIComponent(cameraId)}` : ""}`);
      socket.binaryType = "arraybuffer";
      socket.onmessage = async (event) => {
        try {
          const data = event.data as ArrayBuffer;
          const size = new DataView(data).getUint32(0);
          if (size > data.byteLength - 4) throw new Error("Quadro incompleto");
          const metadata = JSON.parse(new TextDecoder().decode(data.slice(4, 4 + size)));
          if (editingSurface.current && current) {
            // Hold this single packet while annotating; after editing, discard
            // it and request the newest frame instead of playing a backlog.
            while (active && editingSurface.current) await new Promise(resolve => setTimeout(resolve, 60));
            if (active && socket?.readyState === WebSocket.OPEN) socket.send(String(metadata.sequence));
            return;
          }
          const url = URL.createObjectURL(new Blob([data.slice(4 + size)], { type: "image/jpeg" }));
          const decoded = new Image();
          decoded.src = url;
          try { await decoded.decode(); } catch (error) { URL.revokeObjectURL(url); throw error; }
          await new Promise<void>(resolve => requestAnimationFrame(() => {
            if (active && !(editingSurface.current && current)) {
              setSrc(url); setOverlay(metadata);
              lastFrameSequence.current = metadata.sequence;
              if (current) URL.revokeObjectURL(current);
              current = url;
            } else URL.revokeObjectURL(url);
            resolve();
          }));
          if (active && socket?.readyState === WebSocket.OPEN) socket.send(String(metadata.sequence));
        } catch { socket?.close(); }
      };
      socket.onclose = () => { if (active && !fallback) { fallback = true; pull(); } };
      socket.onerror = () => socket?.close();
    } else pull();
    return () => {
      active = false;
      clearTimeout(timer);
      socket?.close();
      if (current) URL.revokeObjectURL(current);
    };
  }, [pageVisible, status.stale, directActive, fluidPreview, synchronized, cameraId, cameraQuery]);
  const displayed: Partial<Status> = directActive || fluidPreview ? status : overlay;
  const [playback, setPlayback] = useState<{fps: number; dropped: number} | null>(null);
  useEffect(() => { setPlayback(null); }, [cameraId, directSrc]);
  const w = displayed.resolution?.[0] || 1920,
    h = displayed.resolution?.[1] || 1080;
  const sourceTrack = displayed.track;
  // WebRTC and inference have independent clocks. Processing latency alone
  // cannot align them, and advancing the old velocity overshoots a bounce.
  // Draw the measured position; exact frame matching is available in sync mode.
  const rawTrack = sourceTrack;
  const t = rawTrack?.predicted && (rawTrack.predictionAge || 0) > .24
    ? undefined
    : rawTrack;
  const rawPoints = (displayed.trajectory || []).slice(-length);
  // A stale Kalman prediction may briefly leave the image at the end of a
  // throw. Keep only the newest in-frame segment so the SVG never draws a
  // diagonal from an invalid old point to a newly acquired ball.
  let segmentStart = 0;
  for (let i = 0; i < rawPoints.length; i += 1) {
    const point = rawPoints[i];
    if (point.x < 0 || point.y < 0 || point.x > w || point.y > h) {
      segmentStart = i + 1;
    }
    if (i > 0 && point.timestamp && rawPoints[i-1].timestamp &&
        point.timestamp - rawPoints[i-1].timestamp! > .18) segmentStart = i;
  }
  const points = rawPoints.slice(segmentStart),
    speedValue = displayed.speed?.projectedKmh,
    // Keep the ball visible even before calibration supplies a speed. Gray is
    // reserved for missing measurements in the 2D map; the live detector uses
    // a strong magenta/amber cue so the operator can find the ball in motion.
    ballStroke =
      t?.tennisColor
          ? "#dfff6b"
          : t?.autoAppearanceVerified
          ? "#62f5d1"
          : t?.source === "model"
          ? "#ffe16b"
          : "#ff42d0";
  const persons = displayed.persons || [];
  const hasVideo = directActive || fluidPreview || Boolean(src);
  const orderedEvents = useMemo(() => [...events].sort((a,b)=>a.timestamp-b.timestamp), [events]);
  const latestDecision = orderedEvents[orderedEvents.length - 1];
  const [reviewEvent,setReviewEvent] = useState<Event | null>(null);
  const lastReviewKey = useRef("");
  useEffect(() => {
    if (!latestDecision || !["IN","OUT","REVISAR"].includes(latestDecision.inOrOut)) {
      setReviewEvent(null);
      lastReviewKey.current = "";
      return;
    }
    const key=`${latestDecision.id}:${latestDecision.inOrOut}:${latestDecision.updatedAt || ""}`;
    if (key === lastReviewKey.current) return;
    lastReviewKey.current=key;
    const age=Date.now()/1000-(latestDecision.updatedAt ?? latestDecision.createdAt ?? latestDecision.timestamp);
    if (age > 5 || age < -2) return;
    setReviewEvent(latestDecision);
    const timer=window.setTimeout(
      ()=>setReviewEvent(current=>current?.id===latestDecision.id ? null : current),4800);
    return ()=>window.clearTimeout(timer);
  },[latestDecision?.id,latestDecision?.inOrOut,latestDecision?.updatedAt]);
  const recentEvents = orderedEvents.filter(e => e.position && e.resolution?.[0] === w && e.resolution?.[1] === h
    && e.timestamp > Math.max(clearedAt, status.regionChangedAt || 0)).slice(-30);
  return (
    <div className="video-wrapper">
    <div className="video-stage">
      {hasVideo ? (
        <>
          {fluidPreview ? (
            <img
              className="video-frame fluid-video"
              src={`${previewOrigin}/api/preview${previewQuery}`}
              alt="Vídeo fluido da câmera"
              draggable={false}
              onError={() => setPreviewFailed(true)}
            />
          ) : !directActive ? (
            <img className="video-frame" src={src} alt="Frames reais da câmera" />
          ) : null}
          {directConnecting && <WebRtcVideo url={directSrc!} visible={directActive}
            onMetrics={setPlayback}
            onReady={() => setDirectReady(true)} onFailure={() => { setDirectReady(false); setDirectFailed(true); }} />}
          <svg
            className="video-overlay"
            style={{ visibility: surfaceEditing ? "hidden" : "visible" }}
            viewBox={`0 0 ${w} ${h}`}
            preserveAspectRatio="xMidYMid meet"
          >
            {trajectory && points.length > 1 && (
              <polyline
                points={points.map((p) => `${p.x},${p.y}`).join(" ")}
                fill="none"
                stroke={ballStroke}
                strokeWidth={Math.max(5,w/300)}
                strokeLinecap="round"
                strokeLinejoin="round"
                opacity={.14}
              />
            )}
            {trajectory &&
              points
                .slice(1)
                .map((p, i) => (
                  <line
                    key={i}
                    x1={points[i].x}
                    y1={points[i].y}
                    x2={p.x}
                    y2={p.y}
                    stroke={ballStroke}
                    strokeWidth={Math.max(2.5,w/650)}
                    opacity={.25 + .75 * (i + 1) / points.length}
                    strokeDasharray={p.predicted ? "6 6" : undefined}
                    strokeLinecap="round"
                  />
                ))}
            {trajectory && (t?.tennisColor || t?.autoAppearanceVerified) && points.filter((p) => !p.predicted).map((p, i) => (
              <circle key={`trail-${i}`} cx={p.x} cy={p.y} r={Math.max(2,w/500)} fill="#baff26" opacity={.18 + .55*(i+1)/points.length} />
            ))}
            {recentEvents
              .map((e) => {
                const speed = eventSpeed(e);
                const markerColor = e.inOrOut === "OUT" ? "#ff5964" : e.inOrOut === "REVISAR" ? "#ffbd59" : speed == null ? "#bfc6ff" : color(speed);
                const impactNumber = e.throwNumber ?? orderedEvents.indexOf(e) + 1;
                const impactLabel = e.inOrOut === "OUT"
                  ? `#${impactNumber} FORA${speed == null ? "" : ` · ≈ ${number(speed)} km/h`}`
                  : e.inOrOut === "REVISAR" ? `#${impactNumber} REVISAR${speed == null ? "" : ` · ≈ ${number(speed)} km/h`}`
                  : speed == null
                    ? `#${impactNumber} · velocidade indisponível`
                    : `#${impactNumber} · ≈ ${number(speed)} km/h`;
                const fontSize=Math.max(16,w/82);
                const labelWidth=Math.max(150,Math.min(330,impactLabel.length*fontSize*.56+18));
                const labelX=Math.max(8,Math.min(w-labelWidth-8,e.position![0]+18));
                const labelY=Math.max(fontSize+12,Math.min(h-12,e.position![1]-18));
                return (
                <g key={e.id} opacity={e.status === "candidate" ? .86 : 1}>
                  <title>{e.decisionReason || e.inOrOut}</title>
                  {e.positionUncertaintyPx != null && e.positionUncertaintyPx > 3 && <circle
                    cx={e.position![0]} cy={e.position![1]}
                    r={Math.min(100,e.positionUncertaintyPx + (e.ballDiameterPx ?? 0)/2)}
                    fill={markerColor} fillOpacity=".07" stroke={markerColor} strokeOpacity=".3" strokeDasharray="3 5" />}
                  <circle
                    cx={e.position![0]}
                    cy={e.position![1]}
                    r={Math.max(12,w/105)}
                    fill={markerColor}
                    fillOpacity=".18"
                    stroke={markerColor}
                    strokeWidth="3"
                    strokeDasharray={e.status === "candidate" ? "7 5" : undefined}
                  />
                  <text
                    x={e.position![0]}
                    y={e.position![1] + 7}
                    textAnchor="middle"
                    fill="#101819"
                    fontWeight="700"
                    fontSize={Math.max(16, w / 78)}
                  >
                    {impactNumber}
                  </text>
                  <rect x={labelX} y={labelY-fontSize-7} width={labelWidth} height={fontSize+13}
                    rx="5" fill="#0b1112" fillOpacity=".88" stroke={markerColor} strokeWidth="1.5" />
                  <text x={labelX+9} y={labelY} fill={markerColor} fontWeight="700" fontSize={fontSize}>
                    {impactLabel}
                  </text>
                </g>
                );
              })}
            {skeleton && <PoseOverlay persons={persons} width={w} />}
            {debug && persons.map((person, i) => (
              <g key={`person-${person.classID ?? i}-${person.x}-${person.y}`}>
                <rect
                  x={person.x}
                  y={person.y}
                  width={person.width}
                  height={person.height}
                  fill="none"
                  stroke="#72d6ff"
                  strokeWidth={Math.max(2, w / 700)}
                  rx={4}
                />
                <rect
                  x={person.x}
                  y={Math.max(0, person.y - Math.max(24, h / 45))}
                  width={Math.max(110, person.width * 0.75)}
                  height={Math.max(24, h / 45)}
                  fill="#72d6ff"
                  opacity=".92"
                  rx={4}
                />
                <text
                  x={person.x + 8}
                  y={Math.max(18, person.y - 7)}
                  fill="#071317"
                  fontSize={Math.max(16, w / 75)}
                  fontWeight="600"
                >
                  PESSOA {Math.round(person.confidence * 100)}%
                </text>
              </g>
            ))}
            {t && (
              <g key={`ball-${t.trackID}`} stroke={ballStroke}
                transform={`translate(${t.centerX},${t.centerY})`}>
                <circle
                  cx={0}
                  cy={0}
                  r={Math.max(9, Math.max(t.width,t.height) / 2 + 4)}
                  fill="none"
                  strokeWidth={Math.max(2,w/650)}
                  strokeDasharray={t.predicted ? "6 5" : undefined}
                  opacity={t.predicted ? .65 : 1}
                />
                {debug && (
                  <rect
                    x={-Math.max(20, t.width + 8) / 2}
                    y={-Math.max(20, t.height + 8) / 2}
                    width={Math.max(20, t.width + 8)}
                    height={Math.max(20, t.height + 8)}
                    fill="none"
                    strokeWidth="3"
                    strokeDasharray={t.predicted ? "6 4" : undefined}
                  />
                )}
                {labels && (
                  <text
                    x={Math.min(t.centerX + 20, w - 380)-t.centerX}
                    y={Math.max(35, t.centerY - 20)-t.centerY}
                    fill={ballStroke}
                    stroke="#101819"
                    strokeWidth=".5"
                    fontSize={Math.max(20, w / 55)}
                  >
                    {t.predicted ? "PREVISÃO · " : ""}
                    {displayed.speed
                      ? `≈ ${number(displayed.speed.projectedKmh)} km/h`
                      : t.source === "model"
                        ? "BOLA · IA"
                        : t.autoAppearanceVerified ? "BOLA · APRENDIZADO AUTO" : t.appearanceVerified ? "BOLA · IA LOCAL" : t.tennisColor ? "BOLA" : "BOLA · INCERTA"}
                  </text>
                )}
              </g>
            )}
          </svg>
          {t && !surfaceEditing && (
            <div
              className="ball-readout"
              style={{ borderColor: ballStroke, color: ballStroke }}
              aria-live="polite"
            >
              <strong>
                {speedValue != null
                  ? `${number(speedValue)} km/h`
                  : t.source === "model"
                    ? "BOLA DETECTADA"
                    : t.autoAppearanceVerified ? "BOLA RECONHECIDA" : t.tennisColor ? "BOLA DE TÊNIS" : "BOLA POSSÍVEL"}
              </strong>
              <small>
                {t.predicted ? "posição prevista · " : ""}
                {speedValue != null
                  ? displayed.speed?.approximate
                    ? `aprox. pelo diâmetro de ${number(status.ballDiameterMm, 0)} mm`
                    : "velocidade projetada no plano"
                  : t.source === "model"
                    ? "calculando velocidade"
                    : t.autoAppearanceVerified ? "assinatura automática · calculando velocidade" : t.appearanceVerified ? "modelo treinado · calculando velocidade" : "movimento/cor · validar no replay"}
              </small>
            </div>
          )}
          {displayed.cameraMoving && (
            <div className="camera-motion-warning" role="status">
              Mantenha o iPhone parado para rastrear a bola
            </div>
          )}
          {!surfaceEditing && events.length > 0 && reviewEvent && (
            <div className={`official-review decision-${reviewEvent.inOrOut.toLowerCase()}`}
              role={reviewEvent.inOrOut === "OUT" ? "alert" : "status"} aria-live="assertive">
              <span className="official-review-kicker">{reviewEvent.status === "confirmed" ? "REVISÃO CONFIRMADA" : "ESTIMATIVA · REVISAR NO REPLAY"}</span>
              <strong>{reviewEvent.inOrOut === "REVISAR" ? "REVIEW" : reviewEvent.inOrOut}</strong>
              <div>
                <b>{reviewEvent.status === "candidate" ? "POSSÍVEL CONTATO" : "IMPACTO"} {reviewEvent.throwNumber ? `#${reviewEvent.throwNumber}` : ""}</b>
                <span>{eventSpeed(reviewEvent) != null ? `≈ ${number(eventSpeed(reviewEvent))} km/h` : "velocidade indisponível"}</span>
                {reviewEvent.strokeType && <span>{strokeName(reviewEvent)}</span>}
                {reviewEvent.courtSector && <span>{sectorNames[reviewEvent.courtSector] || reviewEvent.courtSector}</span>}
              </div>
            </div>
          )}
        </>
      ) : (
        <div className="video-empty">
          <svg className="empty-court" viewBox="0 0 200 120" aria-hidden="true">
            <rect x="22" y="15" width="156" height="90" rx="2" />
            <path d="M38 15V105M162 15V105M22 60H178M38 37H162M38 83H162M100 37V83" />
            <path className="empty-ball-trail" d="M50 80Q100 16 145 48" />
            <circle className="empty-ball" cx="145" cy="48" r="5" />
          </svg>
          <h2>Seu próximo treino começa aqui.</h2>
          <p>
            Conecte seu celular ou uma câmera e inicie uma sessão.
            <br />O vídeo e o acompanhamento da bola aparecem aqui.
          </p>
          <span className="technical">ANDROID · IPHONE · CÂMERA IP</span>
        </div>
      )}
      <div className="video-corner">
        <span
          className={`dot ${status.cameraStatus === "online" ? "green" : ""}`}
        />
        {status.cameraStatus === "online"
          ? directActive
            ? "H.264 DIRETO"
            : synchronized ? "IA SINCRONIZADA" : "CÂMERA FLUIDA"
          : "SEM SINAL"}
        <span className="divider" />
        {status.resolution
          ? status.resolution.join(" × ")
          : "Resolução indisponível"}
      </div>
      <div className="video-bottom">
        <span>
          <Crosshair size={14} />{" "}
          {t
            ? `TRACK ${t.trackID} · ${t.predicted ? "PREVISTO" : t.source === "model" || t.appearanceVerified || t.autoAppearanceVerified ? "DETECTADO" : "INCERTO"}`
            : "AGUARDANDO BOLA"}
        </span>
        <span title={directActive && playback ? `${playback.dropped} quadros descartados na última amostra` : undefined}>
          {persons.length ? `${persons.length} PESSOA${persons.length > 1 ? "S" : ""} · ` : ""}
          {number(directActive ? playback?.fps : fluidPreview ? status.previewFps : status.processingFps)} FPS · {directActive ? "REPRODUÇÃO" : fluidPreview ? "PRÉVIA" : "IA"}
        </span>
      </div>
      <CaptureArea status={status} cameraId={cameraId} width={w} height={h} controlsMount={controlsMount} onEditing={value=>{editingSurface.current=value;setSurfaceEditing(value);}} />
      {hasVideo && !surfaceEditing && <button className="clear-video-marks" onClick={() => setClearedAt(Date.now()/1000)}>Limpar marcas</button>}
    </div>
    <div ref={setControlsMount} className="surface-mount" />
    </div>
  );
}

export function ReplayPlayer({ replay, onFrame, onPoint, onBox, annotation = "none", focusTimestamp, contact }: {
  replay: Replay; onFrame?: (frame: ReplayFrame) => void;
  onPoint?: (point: [number, number]) => void; onBox?: (box: [number, number, number, number]) => void;
  annotation?: "none" | "point" | "box"; focusTimestamp?: number;
  contact?: Partial<Event>;
}) {
  const [overlays, setOverlays] = useState(true);
  const [selection, setSelection] = useState<[number, number, number, number] | null>(null);
  const anchor = useRef<[number, number] | null>(null);
  const [frames, setFrames] = useState<ReplayFrame[]>([]),
    [index, setIndex] = useState(0),
    [rate, setRate] = useState(1),
    [playing, setPlaying] = useState(false),
    [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    setFrames([]);
    setError("");
    setIndex(0);
    setPlaying(false);
    api<ReplayFrame[]>(`/replays/${replay.id}`)
      .then(value => { if (active) { setFrames(value); const t = focusTimestamp ?? replay.triggerTimestamp;
        if (t && value.length) setIndex(value.reduce((best, f, i) => Math.abs(f.timestamp-t)<Math.abs(value[best].timestamp-t)?i:best, 0)); } })
      .catch((e) => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [replay.id]);
  useEffect(() => {
    if (!playing || !frames.length) return;
    if (index >= frames.length - 1) {
      setPlaying(false);
      return;
    }
    const ms =
      ((frames[index + 1].timestamp - frames[index].timestamp) * 1000) / rate;
    const timer = setTimeout(() => setIndex((i) => i + 1), Math.max(10, ms));
    return () => clearTimeout(timer);
  }, [playing, index, rate, frames]);
  const f = frames[index],
    res = f?.overlay.resolution;
  const contactFits = Boolean(res && contact?.resolution?.[0]===res[0] && contact?.resolution?.[1]===res[1]);
  useEffect(() => { setSelection(null); if (f) onFrame?.(f); }, [f, onFrame]);
  function point(e: React.PointerEvent<SVGSVGElement>): [number, number] {
    const r=e.currentTarget.getBoundingClientRect();
    return [Math.max(0,Math.min((res?.[0] ?? 1)-.01,(e.clientX-r.left)/r.width*(res?.[0] ?? 1))),
      Math.max(0,Math.min((res?.[1] ?? 1)-.01,(e.clientY-r.top)/r.height*(res?.[1] ?? 1)))];
  }
  return (
    <div className="replay-player">
      {error && <p role="alert">{error}</p>}
      <div className="replay-image">
        {f && (
          <>
            <img
              src={`/api/replays/${replay.id}/frames/${f.index}`}
              alt={`Replay, frame ${index + 1}`}
            />
            <svg className={annotation !== "none" ? "replay-annotate" : ""} viewBox={`0 0 ${res?.[0]} ${res?.[1]}`}
              onPointerDown={e => { if (annotation === "none") return; setPlaying(false); const p=point(e);
                if (annotation === "point") { onPoint?.(p); setSelection([p[0],p[1],0,0]); }
                else { anchor.current=p; e.currentTarget.setPointerCapture(e.pointerId); } }}
              onPointerMove={e => { if (!anchor.current) return; const p=point(e), a=anchor.current;
                setSelection([Math.min(p[0],a[0]),Math.min(p[1],a[1]),Math.abs(p[0]-a[0]),Math.abs(p[1]-a[1])]); }}
              onPointerUp={e => { if (!anchor.current) return; const p=point(e), a=anchor.current; anchor.current=null;
                const b: [number,number,number,number]=[Math.min(p[0],a[0]),Math.min(p[1],a[1]),Math.abs(p[0]-a[0]),Math.abs(p[1]-a[1])];
                setSelection(b); onBox?.(b); }} onPointerCancel={() => { anchor.current=null; }}>
              {overlays && <>
              {contactFits && <g pointerEvents="none">
                {contact?.surfaceSnapshot?.targetPolygonPx && <polygon points={contact.surfaceSnapshot.targetPolygonPx.map(p=>p.join(",")).join(" ")}
                  fill="#c9f56b" fillOpacity=".06" stroke="#c9f56b" strokeWidth="2" strokeDasharray="10 5" vectorEffect="non-scaling-stroke"/>}
                {contact?.surfaceSnapshot?.floorBoundary && <polyline points={contact.surfaceSnapshot.floorBoundary.map(p=>`${p[0]*res![0]},${p[1]*res![1]}`).join(" ")}
                  fill="none" stroke="#ffd28d" strokeWidth="2" strokeDasharray="10 5" vectorEffect="non-scaling-stroke"/>}
                {contact?.possiblePositions && <polyline points={contact.possiblePositions.map(p=>p.join(",")).join(" ")}
                  fill="none" stroke="#ffd28d" strokeWidth="3" vectorEffect="non-scaling-stroke"/>}
                {contact?.position && <g>
                  <circle cx={contact.position[0]} cy={contact.position[1]} r={contact.positionUncertaintyPx || 3} fill="#ffd28d" fillOpacity=".08" stroke="#ffd28d" strokeWidth="2" strokeDasharray="5 4" vectorEffect="non-scaling-stroke"/>
                  <circle cx={contact.position[0]} cy={contact.position[1]} r={Math.max(4,(res?.[0] || 640)/160)} fill={contact.inOrOut === "OUT" ? "#ff746d" : contact.inOrOut === "IN" ? "#c9f56b" : "#ffd28d"} stroke="#102020" strokeWidth="2"/>
                </g>}
              </g>}
              <PoseOverlay persons={f.overlay.persons || []} width={res?.[0]} />
              <polyline
                points={(f.overlay.trajectory || [])
                  .map((p) => `${p.x},${p.y}`)
                  .join(" ")}
                fill="none"
                stroke={color(f.overlay.speed?.projectedKmh)}
                strokeWidth="3"
              />
              </>}
              {selection && (annotation === "point" ? <circle cx={selection[0]} cy={selection[1]} r={Math.max(5,(res?.[0] ?? 640)/80)} fill="#c9f56b" stroke="#0a1515" strokeWidth={2}/>
                : <rect x={selection[0]} y={selection[1]} width={selection[2]} height={selection[3]} fill="#c9f56b33" stroke="#c9f56b" strokeWidth={2} />)}
            </svg>
          </>
        )}
      </div>
      <input
        aria-label="Frame do replay"
        type="range"
        disabled={!frames.length}
        min={0}
        max={Math.max(0, frames.length - 1)}
        value={index}
        onChange={(e) => {
          setPlaying(false);
          setIndex(+e.target.value);
        }}
      />
      <div className="controls">
        <label className="replay-overlay-toggle"><input type="checkbox" checked={overlays} onChange={e => setOverlays(e.target.checked)} /> Mostrar marcações</label>
        <button
          aria-label="Frame anterior"
          disabled={!frames.length || index === 0}
          onClick={() => {
            setPlaying(false);
            setIndex((i) => Math.max(0, i - 1));
          }}
        >
          <SkipBack size={16} />
        </button>
        <button
          disabled={!frames.length}
          onClick={() => {
            if (index === frames.length - 1) setIndex(0);
            setPlaying(!playing);
          }}
        >
          {playing ? <Pause size={16} /> : <Play size={16} />}{" "}
          {playing ? "Pausar" : "Reproduzir"}
        </button>
        <button
          aria-label="Próximo frame"
          disabled={!frames.length || index >= frames.length - 1}
          onClick={() => {
            setPlaying(false);
            setIndex((i) => Math.max(0, Math.min(frames.length - 1, i + 1)));
          }}
        >
          <SkipForward size={16} />
        </button>
        <select
          aria-label="Velocidade do replay"
          value={rate}
          onChange={(e) => setRate(+e.target.value)}
        >
          <option value={0.25}>0.25×</option>
          <option value={0.5}>0.5×</option>
          <option value={1}>1×</option>
        </select>
        <small>
          {frames.length ? index + 1 : 0} / {frames.length} frames
          {f && focusTimestamp != null ? ` · ${((f.timestamp-focusTimestamp)*1000).toFixed(0)} ms do contato` : ""}
        </small>
      </div>
    </div>
  );
}
