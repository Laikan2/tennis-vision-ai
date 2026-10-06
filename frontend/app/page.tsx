"use client";
import { useEffect, useMemo, useRef, useState, FormEvent } from "react";
import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import {
  Activity,
  ArrowDownToLine,
  ArrowUpRight,
  Camera as CameraIcon,
  Check,
  ChevronRight,
  CircleDot,
  Clock3,
  Copy,
  Crosshair,
  Database,
  Gauge,
  History,
  Layers,
  Play,
  Plus,
  Radio,
  ScanLine,
  Settings2,
  ShieldCheck,
  Smartphone,
  Square,
  Target,
  Video,
  Volume2,
  Wifi,
  X,
} from "lucide-react";
import { QRCodeSVG } from "qrcode.react";
import {
  AnalysisMode,
  api,
  color,
  Camera,
  Event,
  number,
  Replay,
  Session,
  Status,
} from "../lib/types";
import { Court, LiveVideo, ReplayPlayer } from "../components/Visuals";
import { LearningPanel } from "../components/LearningPanel";
import { StreamDiagnostics } from "../components/StreamDiagnostics";
import { useOutAudio } from "../components/useOutAudio";
import { DesktopTitlebar, DesktopSettings, DesktopNetworkSetup } from "../components/DesktopShell";

type View = "live" | "sessions" | "replays" | "accuracy" | "settings" | "learning";
type PhoneReceiverStatus = {
  receiverAvailable: boolean;
  streamReady: boolean;
  announcedFps: number | null;
  message: string;
};
type PhoneCameraInfo = {
  available: boolean;
  localIp: string | null;
  path: string;
  androidUrl: string;
  webRtcUrl: string;
  appPairingUri: string;
  srtUrl: string;
  androidApp: {
    available: boolean;
    version: string;
    downloadUrl: string;
    sizeBytes: number | null;
  };
  receiverUrl: string;
  cameraPreset: {
    name: string;
    host: string;
    port: number;
    path: string;
    requestedFps: number;
    codec: "H.264";
    ballDiameterMm: number;
    dominantHand: "right";
    courtOrientation: "normal";
  };
};
const initial: Status = {
  running: false,
  session: null,
  cameraStatus: "offline",
  cameraFps: null,
  resolution: null,
  persons: [],
  framesReceived: 0,
  droppedFrames: 0,
  detector: "não iniciado",
  device: null,
  calibration: null,
  stale: true,
};
const tabs = ["Quadra 2D", "Mapa de calor", "Timeline", "Estatísticas"];

function eventsForCamera(events: Event[], cameraId?: string) {
  if (!cameraId) return events;
  return events.flatMap((event) => {
    const observation = event.observations?.find((item) => item.cameraId === cameraId);
    if (observation) {
      return [{
        ...event,
        ...observation,
        id: event.id,
        sessionId: event.sessionId,
        cameraIds: event.cameraIds,
        observations: event.observations,
        throwNumber: event.throwNumber,
        inOrOut: event.inOrOut,
      } as Event];
    }
    return event.cameraId === cameraId ? [event] : [];
  });
}

export default function Page() {
  const [view, setView] = useState<View>("live"),
    [status, setStatus] = useState<Status>(initial),
    [connected, setConnected] = useState(false),
    [cameras, setCameras] = useState<Camera[]>([]),
    [cameraId, setCameraId] = useState(""),
    [events, setEvents] = useState<Event[]>([]),
    [sessions, setSessions] = useState<Session[]>([]),
    [replays, setReplays] = useState<Replay[]>([]),
    [tab, setTab] = useState(tabs[0]);
  const [modal, setModal] = useState<
      "session" | "camera" | "phone" | "calibration" | null
    >(null),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false),
    [trajectory, setTrajectory] = useState(true),
    [length, setLength] = useState(24),
    // The camera is already available as low-latency WebRTC.  Showing the
    // analysed JPEG stream here tied visible motion to the CPU inference rate
    // (about 2-8 fps on the test machine), even though the iPhone delivered
    // 60 fps.  Keep alignment as an opt-in diagnostic mode.
    [synchronizedPreview, setSynchronizedPreview] = useState(false),
    [skeleton, setSkeleton] = useState(true),
    [labels, setLabels] = useState(true),
    [showImpacts, setShowImpacts] = useState(true),
    [outAudio, setOutAudio] = useState(true),
    [debug, setDebug] = useState(false),
    [activeReplay, setActiveReplay] = useState<Replay | null>(null),
    [health, setHealth] = useState(false);
  const [phoneInfo, setPhoneInfo] = useState<PhoneCameraInfo | null>(null);
  const [phoneReceiver, setPhoneReceiver] = useState<PhoneReceiverStatus | null>(null);
  useEffect(() => {
    if (modal !== "phone") return;
    let alive = true;
    let timer: ReturnType<typeof setTimeout>;
    setPhoneReceiver(null);
    const check = async () => {
      try {
        const result = await api<PhoneReceiverStatus>("/mobile-camera/status");
        if (alive) setPhoneReceiver(result);
      } catch {
        if (alive) setPhoneReceiver({ receiverAvailable: false, streamReady: false, announcedFps: null,
          message: "Não foi possível conferir o receptor. Verifique se o motor do Windows está online." });
      } finally {
        if (alive) timer = setTimeout(check, 2000);
      }
    };
    void check();
    return () => { alive = false; clearTimeout(timer); };
  }, [modal]);
  const [preferencesReady, setPreferencesReady] = useState(false);
  const refreshing = useRef(false);
  const [selectedCameraIds, setSelectedCameraIds] = useState<string[]>([]);
  const [accuracy, setAccuracy] = useState<{
    summary: {
      count: number;
      mae: number | null;
      mape: number | null;
      std: number | null;
      ci95: number[] | null;
    };
  }>({ summary: { count: 0, mae: null, mape: null, std: null, ci95: null } });
  const reduce = useReducedMotion();
  const outSound = useOutAudio(events, outAudio, status.session?.id,
    status.cameraCount ?? 1, status.fusionWindowMs ?? 550);
  useEffect(() => {
    try {
      const saved = window.localStorage.getItem("tennis-out-audio");
      if (saved != null) setOutAudio(saved !== "false");
      const savedImpacts = window.localStorage.getItem("tennis-show-impacts");
      if (savedImpacts != null) setShowImpacts(savedImpacts !== "false");
    } catch { /* Private browsing can make storage unavailable. */ }
    setPreferencesReady(true);
  }, []);
  useEffect(() => {
    if (preferencesReady) {
      try { window.localStorage.setItem("tennis-out-audio", String(outAudio)); } catch {}
    }
  }, [outAudio, preferencesReady]);
  useEffect(() => {
    if (preferencesReady) {
      try { window.localStorage.setItem("tennis-show-impacts", String(showImpacts)); } catch {}
    }
  }, [showImpacts, preferencesReady]);
  async function refresh() {
    if (refreshing.current) return;
    refreshing.current = true;
    try {
    const [c, s, r, a] = await Promise.all([
      api<Camera[]>("/cameras"),
      api<Session[]>("/sessions"),
      api<Replay[]>("/replays"),
      api<typeof accuracy>("/accuracy"),
    ]);
    setCameras(c);
    setSessions(s);
    setReplays(r);
    setAccuracy(a);
    setCameraId((id) => id || c[0]?.id || "");
    } finally { refreshing.current = false; }
  }
  useEffect(() => {
    refresh().catch(() => {});
    api<{ credentialsStorageReady: boolean }>("/health")
      .then((v) => setHealth(v.credentialsStorageReady))
      .catch(() => {});
    let alive = true;
    let ws: WebSocket;
    let retry: ReturnType<typeof setTimeout>;
    const connect = () => {
      ws = new WebSocket(
        `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/live`,
      );
      ws.onmessage = (e) => {
        if (alive && document.visibilityState === "visible") {
          setStatus(JSON.parse(e.data));
          setConnected(true);
        }
      };
      ws.onclose = () => {
        if (alive) {
          setConnected(false);
          setStatus((s) => ({ ...s, stale: true, track: null, speed: null }));
          retry = setTimeout(connect, 2500);
        }
      };
    };
    connect();
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") refresh().catch(() => {});
    }, 10000);
    return () => {
      alive = false;
      clearTimeout(retry);
      clearInterval(timer);
      ws?.close();
    };
  }, []);
  useEffect(() => {
    if (!status.session) {
      setEvents([]);
      return;
    }
    let active = true;
    api<Event[]>(
      `/events?sessionId=${encodeURIComponent(status.session.id)}`,
    )
      .then(value => { if (active) setEvents(value); })
      .catch(() => {});
    return () => { active = false; };
  }, [status.session?.id, status.lastEvent?.id, status.lastEvent?.updatedAt, status.lastEvent?.observationCount]);
  useEffect(() => {
    if (!message) return;
    const timer = setTimeout(() => setMessage(""), 7000);
    return () => clearTimeout(timer);
  }, [message]);
  async function act(action: () => Promise<unknown>, success: string) {
    setBusy(true);
    try {
      await action();
      setMessage(success);
      // A successful write must not be reported as a failure just because a
      // background refresh races a reconnecting websocket. The write already
      // reached the local API; refresh is best effort.
      try {
        await refresh();
      } catch {
        // Keep the success toast and let the normal polling retry the refresh.
      }
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Não foi possível concluir.");
    } finally {
      setBusy(false);
    }
  }
  async function openPhoneSetup() {
    setBusy(true);
    try {
      const info = await api<PhoneCameraInfo>("/mobile-camera");
      setPhoneInfo(info);
      setModal("phone");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Não foi possível preparar o celular.");
    } finally {
      setBusy(false);
    }
  }
  async function copyPhoneUrl(value: string) {
    try {
      await navigator.clipboard.writeText(value);
      setMessage("Endereço copiado.");
    } catch {
      setMessage("Selecione o endereço e copie manualmente.");
    }
  }
  async function savePhoneCamera() {
    if (!phoneInfo) return;
    const existing = cameras.find(
      (camera) =>
        camera.host === phoneInfo.cameraPreset.host &&
        camera.port === phoneInfo.cameraPreset.port &&
        camera.path === phoneInfo.cameraPreset.path,
    );
    if (existing) {
      setCameraId(existing.id);
      setModal(null);
      setMessage("Câmera do celular selecionada. Abra a transmissão e inicie a sessão.");
      return;
    }
    await act(async () => {
      const saved = await api<Camera>("/cameras", phoneInfo.cameraPreset);
      setCameraId(saved.id);
      setModal(null);
    }, "Câmera do celular adicionada. Abra a transmissão e inicie a sessão.");
  }
  async function connectPhoneCamera() {
    await act(async () => {
      const result = await api<{ camera: Camera; session: Session }>("/mobile-camera/connect", {});
      setCameraId(result.camera.id);
      setSelectedCameraIds(result.session.cameraIds ?? [result.camera.id]);
      setModal(null);
      setView("live");
    }, "Celular conectado ao painel. Vídeo e análise iniciados.");
  }
  const stat = status.statistics;
  const liveSpeed =
    status.speed?.projectedKmh ?? status.lastEvent?.speedBefore ?? null;
  const analysisMode = status.analysisMode ?? status.calibration?.mode ?? "room";
  const roomMode = analysisMode === "room";
  const trackingOnly = analysisMode === "tracking";
  const activeCamera =
    cameras.find((camera) => camera.id === status.session?.cameraId) ??
    cameras.find((camera) => camera.id === cameraId);
  const cameraFeeds = status.cameraFeeds?.length
    ? status.cameraFeeds
    : [status];
  const sessionEvents = useMemo(() => status.session
    ? events.filter(event => event.sessionId === status.session!.id) : [],
    [events, status.session?.id]);
  const decisions = useMemo(() => ({
    inside: sessionEvents.filter(event => event.inOrOut === "IN").length,
    outside: sessionEvents.filter(event => event.inOrOut === "OUT").length,
  }), [sessionEvents]);
  const activeBallDiameterMm = status.session
    ? status.ballDiameterMm ?? activeCamera?.ballDiameterMm ?? 40
    : activeCamera?.ballDiameterMm ?? status.ballDiameterMm ?? 40;
  const nav = [
    { id: "live" as View, label: "Ao vivo", icon: Radio },
    { id: "sessions" as View, label: "Sessões", icon: History },
    { id: "replays" as View, label: "Replays", icon: Video },
    { id: "learning" as View, label: "Treinar IA", icon: Crosshair },
    { id: "accuracy" as View, label: "Precisão do sistema", icon: ShieldCheck },
  ];
  return (
    <div className="app-shell">
      <DesktopTitlebar />
      <aside className="sidebar">
        <a className="brand" href="/" aria-label="Tennis Vision AI, início">
          <span className="brand-mark">
            <CircleDot size={27} />
          </span>
          <span>
            TENNIS
            <span className="brand-sub">
              VISION <b>AI</b>
            </span>
          </span>
        </a>
        <div className="workspace-label">
          SEU ESTÚDIO <span>LOCAL</span>
        </div>
        <nav>
          {nav.map((n) => (
            <button
              key={n.id}
              title={n.label}
              className={view === n.id ? "nav-item active" : "nav-item"}
              onClick={() => setView(n.id)}
            >
              <n.icon size={19} />
              {n.label}
              {view === n.id && <span className="nav-tick" />}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="engine-status">
            <span className={`dot ${connected ? "green" : ""}`} />
            <span>
              {connected ? "Motor conectado" : "Motor desconectado"}
              <small>Processamento local</small>
            </span>
          </div>
          <button
            className={view === "settings" ? "nav-item active" : "nav-item"}
            title="Configurações"
            onClick={() => setView("settings")}
          >
            <Settings2 size={19} />
            Configurações
          </button>
          <div className="local-profile">
            <span>TV</span>
            <div>
              Tennis Vision<small>Seu centro de treino</small>
            </div>
          </div>
        </div>
      </aside>
      <main>
        <header className="topbar">
          <div className="breadcrumb">
            Estúdio <ChevronRight size={14} />
            <strong>
              {view === "live"
                ? "Análise ao vivo"
                : view === "sessions"
                  ? "Sessões"
                  : view === "replays"
                    ? "Replays"
                    : view === "accuracy"
                      ? "Precisão do sistema"
                      : view === "learning" ? "Treinar IA" : "Configurações"}
            </strong>
          </div>
          <div className="topbar-right">
            <span className="local-badge">
              <ShieldCheck size={14} /> Dados no seu computador
            </span>
            <span className="avatar">TV</span>
          </div>
        </header>
        <div className="page-content">
          <div className="page-heading">
            <div>
              <div className="eyebrow">
                TENNIS VISION /{" "}
                {view === "live" ? "SEU TREINO AO VIVO" : view === "settings" ? "CONFIGURAÇÕES" : "ESTÚDIO"}
              </div>
              <h1>
                {view === "live"
                  ? "Cada movimento conta."
                  : view === "sessions"
                    ? "Histórico de sessões"
                    : view === "replays"
                      ? "Reveja cada detalhe."
                      : view === "accuracy"
                        ? "Precisão se mede."
                        : view === "learning" ? "A IA aprende com seu treino." : "Prepare sua quadra."}
              </h1>
              <p>
                {view === "live"
                  ? "Bola, trajetória e impactos. Tudo em um só lugar."
                  : view === "sessions"
                    ? "Suas análises e eventos, preservados localmente."
                    : view === "replays"
                      ? "Frames reais, com os tempos e trajetórias da captura."
                      : view === "accuracy"
                        ? "Compare estimativas com uma referência externa."
                        : view === "learning" ? "Aparência aprendida no movimento; correções manuais ficam opcionais." : "Configure a câmera e calibre o plano da quadra."}
              </p>
            </div>
            <div className="heading-actions">
              <button className="phone-shortcut" disabled={busy} onClick={() => void openPhoneSetup()}>
                <Smartphone size={17} /> Conectar celular
              </button>
            {status.running ? (
              <button
                className="primary stop"
                disabled={busy}
                onClick={() =>
                  act(
                    () => api("/sessions/stop", {}),
                    "Sessão encerrada e salva.",
                  )
                }
              >
                <Square size={15} />
                Encerrar sessão
              </button>
            ) : (
              <button
                className="primary"
                onClick={() => {
                  if (cameras.length) {
                    setSelectedCameraIds([cameraId || cameras[0].id]);
                    setModal("session");
                  } else setModal("camera");
                }}
              >
                <Plus size={18} />
                {cameras.length ? "Nova sessão" : "Conectar câmera"}
              </button>
            )}
            </div>
          </div>
          {status.error && (
            <div className="notice" role="alert">
              {status.error}
            </div>
          )}
          {!connected && (
            <div className="notice">
              <Activity size={16} /> Aguardando o backend local. Nenhuma medição
              está sendo gerada.
            </div>
          )}
          {view === "live" && (
            <>
              <div className="live-overview" aria-label="Visão geral da sessão">
                <div><span><Target size={15} /> Impactos registrados</span><strong>{sessionEvents.length}<small>na sessão</small></strong></div>
                <div><span><Check size={15} /> Dentro da área</span><strong className="decision-in">{decisions.inside}<small>IN</small></strong></div>
                <div><span><ArrowUpRight size={15} /> Fora da área</span><strong className="decision-out">{decisions.outside}<small>OUT</small></strong></div>
                <div><span><CameraIcon size={15} /> Captura</span><strong>{number(status.captureFpsTotal ?? status.cameraFps, 0)}<small>{(status.cameraCount ?? 0) > 1 ? `fps · ${status.cameraCount} câmeras` : "fps recebidos"}</small></strong></div>
              </div>
              <div className="session-strip">
                <div>
                  <span className={`dot ${status.running ? "green" : ""}`} />
                  <strong>
                    {status.session?.name || "Nenhuma sessão ativa"}
                  </strong>
                  <span className="muted">
                    {status.session?.kind || "Pronto para começar"}
                  </span>
                </div>
                <button
                  className="text-button"
                  onClick={() => setModal("camera")}
                >
                  <CameraIcon size={15} />
                  {status.running && (status.cameraCount || 0) > 1
                    ? `${status.cameraCount} câmeras trabalhando juntas`
                    : cameras.find((c) => c.id === (status.session?.cameraId || cameraId))?.name ||
                      "Adicionar câmera"}
                  <ChevronRight size={14} />
                </button>
              </div>
              <div className="live-grid">
                <section className="panel video-panel">
                  <div className="panel-heading">
                    <h2>
                      <Radio size={16} />
                      {trackingOnly ? "Somente rastreamento" : roomMode ? "Treino na parede" : "Treino no chão"}
                    </h2>
                  <span className="tag">
                      {status.cameraCount && status.cameraCount > 1
                        ? `${status.onlineCameras || 0}/${status.cameraCount} CÂMERAS`
                        : status.cameraStatus === "online" ? "ONLINE" : "OFFLINE"}
                    </span>
                  </div>
                  <div className={`camera-wall ${cameraFeeds.length > 1 ? "is-dual" : ""}`}>
                    {cameraFeeds.map((feed, index) => {
                      const feedCameraId = feed.cameraId || status.session?.cameraId || cameraId;
                      const camera = cameras.find((item) => item.id === feedCameraId);
                      const feedAnalysisMode: AnalysisMode = status.session
                        ? feed.analysisMode ?? camera?.analysisMode ?? "room"
                        : camera?.analysisMode ?? feed.analysisMode ?? "room";
                      const feedEvents = status.session
                        ? eventsForCamera(sessionEvents, feedCameraId)
                        : [];
                      return (
                        <div className="camera-feed" key={feedCameraId || index}>
                          <div className="camera-feed-heading">
                            <span>CÂMERA {index + 1}</span>
                            <strong>{feed.cameraName || camera?.name || "Visual principal"}</strong>
                            <small className={feed.cameraStatus === "online" ? "" : "camera-offline-label"}>{camera?.courtOrientation === "opposite" ? "lado B · " : "lado A · "}{feed.cameraStatus === "online" ? `${number(feed.cameraFps)} fps` : "sem sinal · não participa do VAR"}</small>
                          </div>
                          <label className="ball-scale-control">
                            Bola para estimar velocidade
                            <select aria-label={`Bola da câmera ${index + 1}`}
                              value={status.session
                                ? feed.ballDiameterMm ?? camera?.ballDiameterMm ?? 67
                                : camera?.ballDiameterMm ?? feed.ballDiameterMm ?? 67}
                              disabled={busy || !feedCameraId}
                              onChange={e => void act(() => api(`/cameras/${encodeURIComponent(feedCameraId)}/ball-scale`,
                                { ballDiameterMm: Number(e.target.value) }), "Tamanho da bola salvo. A velocidade será recalculada.")}>
                              <option value="67">Tênis · 67 mm</option>
                              <option value="40">Tênis de mesa · 40 mm</option>
                            </select>
                          </label>
                          <label className="impact-mode-control">
                            <span>DETECTAR IMPACTO</span>
                            <select aria-label={`Detecção de impacto da câmera ${index + 1}`}
                              value={feedAnalysisMode}
                              disabled={busy || !feedCameraId}
                              onChange={e => void act(() => api(`/cameras/${encodeURIComponent(feedCameraId)}/analysis-mode`,
                                { analysisMode: e.target.value as AnalysisMode }),
                                e.target.value === "tracking"
                                  ? "Impactos desligados. A câmera continuará rastreando a bola."
                                  : e.target.value === "court"
                                    ? "Impactos no chão ativados. Somente quicadas serão contadas."
                                    : "Impactos na parede ativados.") }>
                              <option value="room">Parede · marcar impactos</option>
                              <option value="court">Chão · marcar quicadas</option>
                              <option value="tracking">Não contar impactos</option>
                            </select>
                          </label>
                          <LiveVideo
                            events={showImpacts ? feedEvents : []}
                            status={feed}
                            cameraId={feedCameraId}
                            trajectory={trajectory}
                            length={length}
                            labels={labels}
                            debug={debug}
                            webrtcPath={camera?.port === 8554 ? camera.path : undefined}
                            synchronized={synchronizedPreview}
                            skeleton={skeleton}
                          />
                        </div>
                      );
                    })}
                  </div>
                  <div className="video-controls">
                    <label className="toggle" title="Alinha exatamente imagem e IA, mas reduz a fluidez à taxa de análise">
                      <input
                        type="checkbox"
                        checked={synchronizedPreview}
                        onChange={(e) => setSynchronizedPreview(e.target.checked)}
                      />
                      <span />
                      Alinhar vídeo à IA
                    </label>
                    <label className="toggle" title={status.poseStatus || "Postura detectada pela IA"}>
                      <input type="checkbox" checked={skeleton} onChange={e=>setSkeleton(e.target.checked)} />
                      <span /> Esqueleto
                    </label>
                    <label className="toggle">
                      <input
                        type="checkbox"
                        checked={trajectory}
                        onChange={(e) => setTrajectory(e.target.checked)}
                      />
                      <span />
                      Trajetória
                    </label>
                    <select
                      aria-label="Comprimento da trajetória"
                      value={length}
                      onChange={(e) => setLength(+e.target.value)}
                    >
                      {[12, 24, 40, 70].map((n) => (
                        <option key={n} value={n}>
                          {n} frames
                        </option>
                      ))}
                    </select>
                    <label className="toggle">
                      <input
                        type="checkbox"
                        checked={labels}
                        onChange={(e) => setLabels(e.target.checked)}
                      />
                      <span />
                      Velocidade
                    </label>
                    <label className="toggle" title="Oculta ou mostra as marcas e o cartão IN/OUT sem apagar as estatísticas">
                      <input
                        type="checkbox"
                        checked={showImpacts}
                        onChange={(e) => setShowImpacts(e.target.checked)}
                      />
                      <span />
                      Mostrar impactos
                    </label>
                    <label className="toggle" title="Fala OUT quando a decisão for fora">
                      <input
                        type="checkbox"
                        checked={outAudio}
                        onChange={(e) => setOutAudio(e.target.checked)}
                      />
                      <span />
                      <Volume2 size={14} /> Som OUT
                    </label>
                    <button type="button" className="button button-secondary out-sound-test"
                      onClick={() => { setOutAudio(true); void outSound.test(); }}>
                      <Volume2 size={14} /> Testar som OUT
                    </button>
                    {outSound.notice && <span className="out-sound-notice" role="status">{outSound.notice}</span>}
                    <button
                      className="icon-button"
                      title="Marcar replay"
                      aria-label="Marcar replay"
                      disabled={!status.running || status.stale || busy}
                      onClick={() =>
                        act(
                          () => api("/replays", {}),
                          "Replay marcado. Salvando 3 segundos posteriores.",
                        )
                      }
                    >
                      <Video size={17} />
                    </button>
                    <button
                      className="icon-button"
                      title="Salvar quadro para revisar em Treinar IA"
                      aria-label="Salvar quadro para treinar IA"
                      disabled={status.stale || busy}
                      onClick={() =>
                        act(
                          () => api("/learning/capture", {}),
                          "Quadro salvo. Abra Treinar IA para revisar a bola.",
                        )
                      }
                    >
                      <Database size={17} />
                    </button>
                  </div>
                  <div className="performance">
                    <span>
                      CAPTURA{(status.cameraCount ?? 1) > 1 ? " · TOTAL " : " "}
                      <b>
                        {number(status.captureFpsTotal ?? status.cameraFps)} <i>fps</i>
                      </b>
                    </span>
                    <span>
                      PROCESSAMENTO{(status.cameraCount ?? 1) > 1 ? " · TOTAL " : " "}
                      <b>
                        {number(status.processingFpsTotal ?? status.processingFps)} <i>fps</i>
                      </b>
                    </span>
                    <span>
                      INFERÊNCIA{" "}
                      <b>
                        {number(status.inferenceMs)} <i>ms</i>
                      </b>
                    </span>
                    <span>
                      FILA + ANÁLISE{" "}
                      <b>
                        {number(status.frameLatencyMs)} <i>ms</i>
                      </b>
                    </span>
                  </div>
                  <StreamDiagnostics status={status} />
                </section>
                <details className="metrics-column" open>
                  <summary>Análise em tempo real <ChevronRight size={16} /></summary>
                  <div className="metrics-body">
                  <section className="panel speed-card">
                    <div className="panel-heading">
                      <h2>
                        <Gauge size={17} />
                        Velocidade estimada
                      </h2>
                      <span className="tiny-label">km/h</span>
                    </div>
                    <div className="speed-value">
                      <span className="speed-number" style={{ color: color(liveSpeed) }}>
                        {number(liveSpeed)}
                      </span>
                      <span>
                        {status.speed?.projectedKmh != null
                          ? status.speed.approximate
                            ? "ESTIMATIVA VISUAL"
                            : "PROJEÇÃO NO PLANO"
                          : status.lastEvent?.speedBefore != null
                            ? "ÚLTIMO IMPACTO"
                            : "SEM MEDIÇÃO"}
                      </span>
                    </div>
                    <p className="unavailable">
                      {status.speed?.projectedKmh != null
                        ? status.speed.approximate
                          ? "Aproximada pelo diâmetro conhecido da bola"
                          : "Estimativa com timestamps reais"
                        : status.lastEvent?.speedBefore != null
                          ? "Velocidade registrada antes do quique"
                          : "Velocidade indisponível"}
                    </p>
                    <div className="speed-legend" />
                    <div className="legend-labels">
                      <span>Lento</span>
                      <span>Rápido</span>
                    </div>
                    <div className="mini-metrics">
                      <div>
                        <span>Máxima</span>
                        <strong>
                          {number(stat?.estimatedMaxKmh)}
                          <small>km/h</small>
                        </strong>
                      </div>
                      <div>
                        <span>Média</span>
                        <strong>
                          {number(stat?.estimatedMeanKmh)}
                          <small>km/h</small>
                        </strong>
                      </div>
                    </div>
                  </section>
                  <section className="panel bounce-card">
                    <div className="panel-heading">
                      <h2>
                        <Target size={16} />
                        Último possível impacto
                      </h2>
                      <span className="muted">{status.lastEvent ? status.lastEvent.inOrOut : "—"}</span>
                    </div>
                    <div className="bounce-result">
                      <span className="target-ring">
                        <Crosshair size={25} />
                      </span>
                      <div>
                        {status.lastEvent
                          ? "Possível impacto"
                          : "Aguardando impacto"}
                        <small>
                          {status.lastEvent
                            ? "Contato com a superfície não confirmado"
                            : "Nenhuma decisão disponível"}
                        </small>
                      </div>
                    </div>
                    {status.lastEvent?.speedBefore != null && <div className="event-speed">{number(status.lastEvent.speedBefore)} km/h <small>{status.lastEvent.speedMethod === "monocular_depth_estimate" ? "estimativa visual em profundidade" : status.lastEvent.speedMethod === "ball_diameter_estimate" ? "estimativa visual no impacto" : "velocidade projetada no impacto"}</small></div>}
                    <div className="data-row">
                      <span>Confiança da decisão</span>
                      <b>—</b>
                    </div>
                    <div className="data-row">
                      <span>Coordenadas estimadas</span>
                      <b>{status.lastEvent?.courtPosition ? `${number(status.lastEvent.courtPosition[0],2)} / ${number(status.lastEvent.courtPosition[1],2)} m` : "— / —"}</b>
                    </div>
                  </section>
                  <div className="calibration-link">
                    <ShieldCheck size={21} />
                    <div>
                      <strong>
                        {status.calibration
                          ? status.calibration.mode === "room"
                            ? "Escala do ambiente " + status.calibration.quality
                            : "Calibração " + status.calibration.quality
                          : status.speed?.approximate
                            ? "Estimativa automática ativa"
                            : "Configure a escala para melhorar a medição"}
                      </strong>
                      <span>
                        {status.calibration
                          ? status.calibration.mode === "room"
                            ? "Distância conhecida usada para o quarto"
                            : "Transformação do plano da quadra"
                          : status.speed?.approximate
                            ? "Usando o diâmetro da bola; a escala manual melhora a precisão."
                            : `A bola de ${number(activeBallDiameterMm, 0)} mm permite estimar; a escala aumenta a precisão.`}
                      </span>
                    </div>
                    <button
                      aria-label="Abrir calibração"
                      onClick={() => setModal("calibration")}
                    >
                      <ArrowUpRight size={19} />
                    </button>
                  </div>
                  </div>
                </details>
              </div>
              <ShotCoachSummary events={sessionEvents} status={status} mode={analysisMode} />
              <section className="panel analysis-panel">
                {((!status.calibration && !roomMode) ||
                  status.detector.includes("heuristic")) && (
                  <div className="map-readiness">
                    <strong>O que falta para medir</strong>
                    <span>
                      {!status.calibration && !roomMode
                        ? "Calibre uma escala do ambiente ou os quatro cantos da quadra. "
                        : ""}
                      {status.detector.includes("heuristic")
                        ? "O detector especializado ainda não está carregado; movimento/cor não confirma a bola."
                        : ""}
                    </span>
                    <button onClick={() => setModal("calibration")}>
                      Calibrar escala
                    </button>
                  </div>
                )}

                <div
                  className="analysis-tabs"
                  role="tablist"
                  aria-label="Análise da sessão"
                >
                  {tabs.map((t) => (
                    <button
                      key={t}
                      role="tab"
                      aria-selected={tab === t}
                      onClick={() => setTab(t)}
                      className={tab === t ? "selected" : ""}
                    >
                      {t}
                    </button>
                  ))}
                  <span className="analysis-caption">
                    SESSÃO {status.session ? "ATUAL" : "NÃO INICIADA"}
                  </span>
                </div>
                <div className="analysis-content" role="tabpanel">
                  {tab === "Quadra 2D" || tab === "Mapa de calor" ? (
                    <>
                      <Court
                        events={status.session ? events : []}
                        heat={tab === "Mapa de calor"}
                        mode={analysisMode}
                      />
                      <div className="court-summary">
                        <div className="eyebrow">
                          {trackingOnly
                            ? "RASTREAMENTO SEM IMPACTOS"
                            : roomMode
                            ? "DISTRIBUIÇÃO NO AMBIENTE"
                            : "DISTRIBUIÇÃO NA QUADRA"}
                        </div>
                        <h3>
                          {trackingOnly
                            ? "Acompanhe a bola sem registrar jogadas."
                            : roomMode
                            ? "O ambiente deixa suas marcas."
                            : "O jogo deixa suas marcas."}
                        </h3>
                        <p>
                          {trackingOnly
                            ? "A caixa, o rastro e a velocidade continuam ativos; marcas, estatísticas e som OUT ficam desligados."
                            : roomMode
                            ? "Movimentos detectados aparecem no plano do ambiente com cor e velocidade disponível."
                            : "Quicadas confirmadas deixam marcas com cor e velocidade disponível."}
                          <br />
                          {trackingOnly
                            ? "Troque o modo da câmera para Chão ou Parede quando quiser contar impactos."
                            : roomMode
                            ? "Candidatos incertos continuam disponíveis para revisão."
                            : "Você também pode exibir possíveis quicadas, identificadas como incertas."}
                        </p>
                        <div className="summary-numbers">
                          <div>
                            <strong>{stat?.throws ?? events.length}</strong>
                            <span>Contatos detectados</span>
                          </div>
                          <div>
                            <strong>{stat?.candidates ?? 0}</strong>
                            <span>Eventos para revisar</span>
                          </div>
                          <div>
                            <strong>
                              {number(stat?.insideRate ?? stat?.accuracy)}
                              <small>%</small>
                            </strong>
                            <span>Dentro da área marcada</span>
                          </div>
                        </div>
                        <div className="inline-note">
                          <span className="dot" /> Forehand/backhand são estimados pela posição da bola, ombros e punho dominante; eventos incertos permanecem sem classificação.
                        </div>
                      </div>
                    </>
                  ) : tab === "Timeline" ? (
                    <div className="full-width">
                      <h3>Eventos da sessão</h3>
                      {!status.session || !events.length ? (
                        <Empty
                          title="Nenhum evento registrado"
                          text={trackingOnly ? "Esta câmera está configurada para rastrear sem registrar impactos." : roomMode ? "Os possíveis impactos aparecerão aqui para revisão." : "Somente quicadas medidas no chão aparecerão aqui."}
                        />
                      ) : (
                        events.map((e) => (
                          <div className="list-row" key={e.id}>
                            <Clock3 size={17} />
                            <strong>
                              {new Date(e.timestamp * 1000).toLocaleTimeString(
                                "pt-BR",
                              )}
                            </strong>
                            <span>{roomMode ? "Possível impacto" : "Possível quique"}{e.strokeType ? ` · ${e.strokeType === "forehand" ? "forehand" : "backhand"}` : " · golpe não classificado"}{e.courtSector ? ` · ${e.courtSector.replaceAll("_"," ")}` : ""}{e.cameraIds?.length ? ` · ${e.cameraIds.length} câmera${e.cameraIds.length > 1 ? "s" : ""}` : ""}</span>
                            <span className={`tag decision-${e.inOrOut.toLowerCase()}`}>{e.inOrOut === "IN" ? "DENTRO" : e.inOrOut === "OUT" ? "FORA" : "REVISAR"}</span>
                          </div>
                        ))
                      )}
                    </div>
                  ) : (
                    <div className="statistics-grid">
                      {[
                        ["Contatos detectados", stat?.impacts ?? events.length],
                        ["Maior sequência dentro", stat?.longestInsideStreak],
                        ["Mediana estimada (km/h)", stat?.estimatedMedianKmh],
                        ["P90 estimado (km/h)", stat?.estimatedP90Kmh],
                        ["Dentro da área", stat?.inside],
                        ["Fora da área", stat?.outside],
                        ["Decisão inconclusiva", stat?.inconclusive],
                        ["Taxa dentro (%)", stat?.insideRate],
                        ["Lances vistos por 2 câmeras", stat?.multiCameraEvents],
                        ["Câmeras usadas", stat?.camerasUsed ?? status.cameraCount],
                        ["Forehands estimados", stat?.strokeStats?.forehand?.total],
                        ["Backhands estimados", stat?.strokeStats?.backhand?.total],
                        ["Golpes sem classificação", stat?.strokeStats?.unclassified?.total],
                        ["Forehand dentro (%)", stat?.strokeStats?.forehand?.insideRate],
                        ["Backhand dentro (%)", stat?.strokeStats?.backhand?.insideRate],
                        ["Velocidade estimada máxima", stat?.estimatedMaxKmh],
                        ["Velocidade estimada média", stat?.estimatedMeanKmh],
                        ["Frames processados", status.framesProcessed],
                        [
                          "Frames com detecção do modelo",
                          status.detectedFrames,
                        ],
                        ["Frames descartados na fila", status.droppedFrames],
                        [roomMode ? "Impactos confirmados" : "Quicadas confirmadas", stat?.bounces],
                        ["Velocidade mediana", stat?.medianKmh],
                        ["Percentil 90 de velocidade", stat?.p90Kmh],
                      ].map(([label, value]) => (
                        <div key={String(label)}>
                          <span>{label}</span>
                          <strong>
                            {number(value as number | null | undefined, 0)}
                          </strong>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </section>
              {debug && (
                <section className="panel debug">
                  <h3>Diagnóstico</h3>
                  <p>{status.detector}</p>
                  <p>
                    Projeção no plano: {number(status.speed?.projectedKmh)} km/h
                    · não equivale à velocidade da bola em voo.
                  </p>
                  <pre>
                    {JSON.stringify(
                      {
                        track: status.track,
                        calibration: status.calibration,
                        device: status.device,
                        networkLatency: status.networkLatencyMs,
                      },
                      null,
                      2,
                    )}
                  </pre>
                </section>
              )}
            </>
          )}
          {view === "learning" && <LearningPanel />}
          {view === "settings" && (
            <div className="settings-grid">
              <DesktopSettings />
              <section className="panel padded">
                <div className="panel-heading">
                  <h2>
                    <CameraIcon size={19} />
                    Câmeras
                  </h2>
                  <button onClick={() => setModal("camera")}>
                    <Plus size={15} />
                    Adicionar
                  </button>
                </div>
                {cameras.length ? (
                  cameras.map((c) => (
                    <div className="camera-item" key={c.id}>
                      <CameraIcon size={25} />
                      <div>
                        <strong>{c.name}</strong>
                        <small>
                          {c.host}:{c.port}
                          {c.path} · {c.courtOrientation === "opposite" ? "lado B" : "lado A"} · {c.dominantHand === "left" ? "canhoto" : "destro"}
                        </small>
                      </div>
                      <span className="tag">RTSP</span>
                    </div>
                  ))
                ) : (
                  <Empty
                    title="Conecte sua primeira câmera"
                    text="A captura usa o stream RTSP na sua rede local."
                  />
                )}
                <p className="inline-note">
                  Credenciais:{" "}
                  {health
                    ? "armazenamento criptografado disponível"
                    : "configure TV_SECRET_KEY no arquivo .env"}
                  . Resolução, codec e FPS são configurados na câmera; valores
                  salvos aqui são o perfil desejado.
                </p>
                <button onClick={() => setModal("calibration")}>
                  <Crosshair size={16} />
                  Calibrar ambiente / quadra
                </button>
              </section>
              <section className="panel padded">
                <h2>Processamento</h2>
                <div className="data-row">
                  <span>Dispositivo</span>
                  <b>{status.device || "AUTO"}</b>
                </div>
                <div className="data-row">
                  <span>Detector</span>
                  <b>{status.detector}</b>
                </div>
                <label className="toggle">
                  <input
                    type="checkbox"
                    checked={debug}
                    onChange={(e) => setDebug(e.target.checked)}
                  />
                  <span />
                  Modo debug no painel ao vivo
                </label>
                <p>
                  Configure TV_MODEL_PATH e TV_DEVICE no backend. Sem pesos
                  especializados, as propostas por movimento são incertas.
                </p>
                <h3>Replay</h3>
                <p>
                  5 segundos anteriores + 3 posteriores. Limite de 64 MB por
                  buffer e até 3 gravações simultâneas. Frames com timestamps
                  preservados.
                </p>
              </section>
            </div>
          )}
          {view === "sessions" && (
            <section className="panel padded">
              <h2>
                Sessões salvas{" "}
                <span className="muted">/ {sessions.length}</span>
              </h2>
              {sessions.length ? (
                sessions.map((s) => (
                  <div className="list-row" key={s.id}>
                    <History size={20} />
                    <div className="grow">
                      <strong>{s.name}</strong>
                      <small>
                        {new Date(s.createdAt * 1000).toLocaleString("pt-BR")} ·{" "}
                        {s.kind} ·{" "}
                        {s.endedAt ? "Encerrada" : "Aberta ou interrompida"}
                      </small>
                    </div>
                    <a
                      className="button-link"
                      href={`/api/sessions/${s.id}/export?format=csv`}
                    >
                      <ArrowDownToLine size={15} />
                      CSV
                    </a>
                    <a
                      className="button-link"
                      href={`/api/sessions/${s.id}/export?format=json`}
                    >
                      JSON
                    </a>
                  </div>
                ))
              ) : (
                <Empty
                  title={roomMode ? "A primeira sessão começa no ambiente." : "A primeira sessão começa na quadra."}
                  text="Conecte a câmera e inicie uma análise para construir seu histórico."
                />
              )}
            </section>
          )}
          {view === "replays" && (
            <section className="panel padded">
              <h2>Biblioteca de replays</h2>
              {replays.length ? (
                <div className="replay-grid">
                  {replays.map((r) => (
                    <button
                      className="replay-card"
                      key={r.id}
                      onClick={() => setActiveReplay(r)}
                    >
                      <img
                        src={`/api/replays/${r.id}/frames/0`}
                        alt="Primeiro frame do replay"
                      />
                      <strong>
                        <Play size={16} />
                        {r.reason === "manual"
                          ? "Momento marcado"
                          : roomMode ? "Possível impacto" : "Possível quique"}
                      </strong>
                      <small>
                        {new Date(r.createdAt * 1000).toLocaleString("pt-BR")} ·{" "}
                        {r.frames} frames{r.truncated ? " · parcial" : ""}
                      </small>
                    </button>
                  ))}
                </div>
              ) : (
                <Empty
                  title="Seus melhores momentos, em detalhe."
                  text="Marque um replay durante a captura para salvar frames reais antes e depois do evento."
                />
              )}
            </section>
          )}
          {view === "accuracy" && (
            <div className="settings-grid">
              <section className="panel padded">
                <h2>Validação com referência externa</h2>
                <p>
                  Informe pares reais de medições sincronizadas, por exemplo de
                  um radar. Não misture velocidade física e projeção no plano.
                </p>
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const d = new FormData(e.currentTarget);
                    act(
                      () =>
                        api("/accuracy", {
                          estimated: +d.get("estimated")!,
                          reference: +d.get("reference")!,
                          source: d.get("source"),
                        }),
                      "Medição de validação salva.",
                    );
                  }}
                >
                  <div className="form-grid">
                    <Field
                      label="Estimativa (km/h)"
                      name="estimated"
                      type="number"
                      min="0"
                      max="500"
                      step=".01"
                      required
                    />
                    <Field
                      label="Referência (km/h)"
                      name="reference"
                      type="number"
                      min=".01"
                      max="500"
                      step=".01"
                      required
                    />
                  </div>
                  <Field
                    label="Fonte e identificação da medição"
                    name="source"
                    placeholder="Radar · evento / horário"
                    minLength={3}
                    required
                  />
                  <button className="primary" disabled={busy}>
                    Adicionar medição real
                  </button>
                </form>
              </section>
              <section className="panel padded">
                <h2>Resultados observados</h2>
                {[
                  ["Medições", accuracy.summary.count],
                  ["Erro absoluto médio (km/h)", accuracy.summary.mae],
                  ["Erro percentual médio (%)", accuracy.summary.mape],
                  ["Desvio padrão do erro", accuracy.summary.std],
                ].map(([l, v]) => (
                  <div className="data-row" key={String(l)}>
                    <span>{l}</span>
                    <strong>{number(v as number | null)}</strong>
                  </div>
                ))}
                <p>
                  IC 95% do viés:{" "}
                  {accuracy.summary.ci95?.map((x) => number(x)).join(" a ") ||
                    "Indisponível; mínimo de 30 pares independentes."}
                </p>
                <p>Precisão de quique e IN/OUT: ainda não validada.</p>
              </section>
            </div>
          )}
          <footer>
            <span>
              <CircleDot size={13} /> TENNIS VISION AI
            </span>
            <span>Medições reais. Incertezas visíveis.</span>
            <span>{connected ? "ESTÚDIO CONECTADO" : "AGUARDANDO CONEXÃO"}</span>
          </footer>
        </div>
      </main>
      <AnimatePresence>
        {message && (
          <motion.div
            className="toast"
            role="status"
            initial={{ opacity: 0, y: reduce ? 0 : 12 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
          >
            {message}
            <button onClick={() => setMessage("")} aria-label="Fechar mensagem">
              <X size={16} />
            </button>
          </motion.div>
        )}
      </AnimatePresence>
      {(modal || activeReplay) && (
        <div
          className="modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget) {
              setModal(null);
              setActiveReplay(null);
            }
          }}
        >
          <Dialog
            title={
              activeReplay
                ? "Replay da sessão"
                : modal === "camera"
                  ? "Conectar câmera"
                  : modal === "phone"
                    ? "Usar o celular sem Larix"
                  : modal === "session"
                    ? "Nova sessão"
                    : "Calibração da escala"
            }
            close={() => {
              setModal(null);
              setActiveReplay(null);
            }}
          >
            {modal === "camera" && (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const d = new FormData(e.currentTarget);
                  act(async () => {
                    await api("/cameras", {
                      name: d.get("name"),
                      host: d.get("host"),
                      port: +d.get("port")!,
                      path: d.get("path"),
                      username: d.get("username"),
                      password: d.get("password"),
                      requestedFps: +d.get("fps")!,
                      codec: d.get("codec"),
                      ballDiameterMm: +d.get("ballDiameterMm")!,
                      dominantHand: d.get("dominantHand"),
                      courtOrientation: d.get("courtOrientation"),
                    });
                    setModal(null);
                  }, "Câmera salva. Inicie uma sessão para testar o stream.");
                }}
              >
                <div className="phone-camera-offer">
                  <span className="phone-camera-icon"><Smartphone size={22} /></span>
                  <div>
                    <strong>Câmera Tennis Vision</strong>
                    <p>Use o aplicativo Android e leia o QR para transmitir ao PC.</p>
                  </div>
                  <button type="button" onClick={openPhoneSetup} disabled={busy}>
                    Preparar <ChevronRight size={15} />
                  </button>
                </div>
                <p>Câmera RTSP · Hikvision ou outro transmissor</p>
                <Field
                  name="name"
                  label="Nome da câmera"
                  defaultValue="Quadra principal · câmera 1"
                  required
                />
                <div className="form-grid">
                  <Field
                    name="host"
                    label="IP ou hostname"
                    placeholder="192.168.1.64"
                    required
                  />
                  <Field
                    name="port"
                    label="Porta RTSP"
                    type="number"
                    defaultValue="554"
                    min="1"
                    max="65535"
                    required
                  />
                </div>
                <Field
                  name="path"
                  label="Caminho RTSP"
                  defaultValue="/Streaming/Channels/101"
                  required
                />
                <div className="form-grid">
                  <Field
                    name="username"
                    label="Usuário da câmera"
                    autoComplete="off"
                  />
                  <Field
                    name="password"
                    label="Senha da câmera"
                    type="password"
                    autoComplete="new-password"
                  />
                </div>
                <div className="form-grid">
                  <Field
                    name="fps"
                    label="FPS desejado"
                    type="number"
                    defaultValue="25"
                    min="1"
                    max="240"
                  />
                  <label>
                    Codec desejado
                    <select name="codec">
                      <option>H.264</option>
                      <option>H.265</option>
                    </select>
                  </label>
                </div>
                <label>
                  Tipo de bola usado na estimativa automática
                  <select name="ballDiameterMm" defaultValue="67">
                    <option value="40">Pingue-pongue · 40 mm</option>
                    <option value="67">Tênis · 67 mm</option>
                  </select>
                </label>
                <div className="form-grid">
                  <label>
                    Mão dominante do jogador
                    <select name="dominantHand" defaultValue="right">
                      <option value="right">Destro</option>
                      <option value="left">Canhoto</option>
                    </select>
                  </label>
                  <label>
                    Orientação desta câmera
                    <select name="courtOrientation" defaultValue="normal">
                      <option value="normal">Lado A da quadra</option>
                      <option value="opposite">Lado B · girar mapa 180°</option>
                    </select>
                  </label>
                </div>
                <p className="inline-note">
                  Credenciais são enviadas apenas ao backend local e
                  criptografadas. Deixe em branco se a câmera não exigir
                  autenticação; elas não são retornadas na configuração. Para
                  o iPhone, use H.264, 1080p, 60 fps e 10–16 Mbps no Larix.
                </p>
                <button className="primary" disabled={busy}>
                  {busy ? "Salvando…" : "Salvar câmera"}
                </button>
              </form>
            )}
            {modal === "phone" && phoneInfo && (
              <section className="phone-camera-setup">
                <div className="phone-camera-lead phone-camera-lead--native">
                  <div>
                    <span className="eyebrow"><Smartphone size={13} /> APLICATIVO ANDROID</span>
                    <h3>Aponte, leia o QR e comece a transmitir.</h3>
                    <p>O app Tennis Vision Camera envia H.264 por SRT direto ao computador. Não precisa de conta, Larix ou nuvem.</p>
                  </div>
                  <div className="phone-camera-qr" aria-label="QR code de pareamento do aplicativo">
                    {phoneInfo.available ? (
                      <QRCodeSVG value={phoneInfo.appPairingUri} size={142} level="M" marginSize={2} />
                    ) : (
                      <Smartphone size={42} />
                    )}
                  </div>
                </div>

                {!phoneInfo.available && (
                  <p className="error">Não encontrei o IP da rede. Conecte o computador ao Wi-Fi ou configure TV_LAN_IP no arquivo .env.</p>
                )}

                <div className="phone-app-download">
                  <div>
                    <b>Tennis Vision Camera · Android {phoneInfo.androidApp.version}</b>
                    <p>Baixe no PC e envie o APK ao Galaxy por USB ou Quick Share. Abra o arquivo no celular para instalar.</p>
                  </div>
                  {phoneInfo.androidApp.available ? (
                    <a className="phone-download-button" href={phoneInfo.androidApp.downloadUrl} download>
                      <ArrowDownToLine size={16} /> Baixar aplicativo
                    </a>
                  ) : (
                    <span className="inline-note">APK indisponível nesta cópia do projeto.</span>
                  )}
                </div>

                <ol className="phone-steps">
                  <li><b>Uma vez:</b> instale o Tennis Vision Camera no Galaxy. Mantenha o PC e o celular na mesma rede. <DesktopNetworkSetup browserInstructions /></li>
                  <li>Abra o app, toque em <b>Ler QR do computador</b> e leia o código acima.</li>
                  <li>Em <b>Captura</b>, escolha a qualidade. Em <b>Rede</b>, comece com <b>Mínimo atraso</b>. Toque em <b>Transmitir para o PC</b>.</li>
                  <li>Quando o estado abaixo mostrar <b>Vídeo recebido</b>, clique em <b>Conectar e iniciar vídeo</b>. O painel abre a imagem e inicia a sessão.</li>
                </ol>

                <div className={phoneReceiver?.streamReady ? "phone-receiver-state is-ready" : "phone-receiver-state"} role="status" aria-live="polite">
                  <span className="status-dot" />
                  <div><b>{phoneReceiver?.streamReady ? "Vídeo recebido" : phoneReceiver?.receiverAvailable ? "Aguardando transmissão do celular" : phoneReceiver ? "Receptor indisponível" : "Conferindo conexão"}</b>
                    <p>{phoneReceiver?.message ?? "Verificando o receptor no PC…"}</p>
                    <button type="button" className="primary" disabled={busy || !phoneReceiver?.streamReady} onClick={connectPhoneCamera}>
                      <Check size={16} /> Conectar e iniciar vídeo
                    </button>
                  </div>
                </div>

                <label className="phone-url">
                  Pareamento do aplicativo
                  <span>
                    <input readOnly value={phoneInfo.appPairingUri} onFocus={(event) => event.currentTarget.select()} />
                    <button type="button" onClick={() => copyPhoneUrl(phoneInfo.appPairingUri)} aria-label="Copiar pareamento">
                      <Copy size={16} />
                    </button>
                  </span>
                </label>

                <div className="phone-settings-summary">
                  <span><b>1080p</b> solicitado</span>
                  <span><b>60 fps</b> solicitado</span>
                  <span><b>12 Mbps</b></span>
                  <span><b>H.264</b></span>
                </div>

                <div className="phone-app-tuning">
                  <div><Gauge size={18} /><span><b>Bolas rápidas</b><small>Em Captura, ative “Congelar bola” e teste 1/1000 s com boa luz. Há zoom, foco e grade para enquadrar.</small></span></div>
                  <div><Wifi size={18} /><span><b>Vídeo mais próximo do ao vivo</b><small>Em Rede, escolha Mínimo atraso. Se houver perdas no Wi-Fi, teste Equilibrado ou reduza a resolução.</small></span></div>
                </div>

                <p className="inline-note">120/240 fps aparecem somente com suporte da câmera e do H.264; nesses modos, foco e exposição são automáticos. Confira os FPS reais de captura, codificação e envio no app. O PC recebe a taxa enviada; a análise tem uma taxa própria. Mantenha o aplicativo aberto.</p>

                <div className="phone-pipeline" aria-label="Caminho do vídeo até a análise">
                  <span><Smartphone size={15} /><b>Galaxy</b><small>captura</small></span>
                  <i>→</i>
                  <span><Wifi size={15} /><b>PC local</b><small>recebe SRT</small></span>
                  <i>→</i>
                  <span><ScanLine size={15} /><b>IA</b><small>bola e impacto</small></span>
                  <i>→</i>
                  <span><Activity size={15} /><b>Painel</b><small>vídeo e dados</small></span>
                </div>

                <details className="phone-browser-fallback">
                  <summary>Usar pelo Chrome enquanto o aplicativo não estiver instalado</summary>
                  <div>
                    <div className="phone-camera-qr phone-camera-qr--small" aria-label="QR code do modo navegador">
                      {phoneInfo.available ? <QRCodeSVG value={phoneInfo.androidUrl} size={112} level="M" marginSize={2} /> : <Smartphone size={36} />}
                    </div>
                    <p>
                      Leia este QR no Galaxy, permita a câmera e toque em <b>publish</b>. O navegador pode entregar menos FPS e mais atraso que o aplicativo.
                    </p>
                  </div>
                </details>

                <div className="phone-actions">
                  <button type="button" className="secondary" onClick={() => setModal("camera")}>Voltar</button>
                  <button type="button" className="secondary" disabled={busy || !phoneInfo.available} onClick={savePhoneCamera}>
                    Cadastrar para usar depois
                  </button>
                </div>
              </section>
            )}
            {modal === "session" && (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const d = new FormData(e.currentTarget);
                  act(async () => {
                    await api("/sessions/start", {
                      name: d.get("name"),
                      player1: d.get("player1"),
                      player2: d.get("player2"),
                      kind: d.get("kind"),
                      cameraId: selectedCameraIds[0],
                      cameraIds: selectedCameraIds,
                    });
                    setCameraId(selectedCameraIds[0]);
                    setModal(null);
                    setView("live");
                  }, "Captura iniciada. Aguardando conexão RTSP.");
                }}
              >
                <Field
                  label="Nome da sessão"
                  name="name"
                  defaultValue="Treino na quadra principal"
                  required
                />
                <div className="form-grid">
                  <Field label="Jogador 1 (opcional)" name="player1" />
                  <Field label="Jogador 2 (opcional)" name="player2" />
                </div>
                <label>
                  Tipo
                  <select name="kind">
                    {["Treino", "Partida", "Saque", "Rally", "Exercício"].map(
                      (t) => (
                        <option key={t}>{t}</option>
                      ),
                    )}
                  </select>
                </label>
                <fieldset className="camera-picker">
                  <legend>Câmeras da sessão <small>selecione até 2</small></legend>
                  {cameras.map((camera, index) => {
                    const checked = selectedCameraIds.includes(camera.id);
                    return (
                      <label className={checked ? "selected" : ""} key={camera.id}>
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={(event) => {
                            if (event.target.checked) {
                              if (selectedCameraIds.length >= 2) {
                                setMessage("Use no máximo duas câmeras por sessão.");
                                return;
                              }
                              setSelectedCameraIds((ids) => [...ids, camera.id]);
                            } else {
                              setSelectedCameraIds((ids) => ids.filter((id) => id !== camera.id));
                            }
                          }}
                        />
                        <span><b>{camera.name}</b><small>{camera.courtOrientation === "opposite" ? "Lado B · mapa girado" : "Lado A"} · {camera.dominantHand === "left" ? "canhoto" : "destro"} · área independente</small></span>
                        <Check size={16} />
                      </label>
                    );
                  })}
                  <p>
                    Cada câmera salva seus próprios quatro cantos. Configure uma como lado A e a câmera oposta como lado B; impactos simultâneos são unidos no mesmo mapa.
                  </p>
                </fieldset>
                <button
                  className="primary"
                  disabled={busy || !selectedCameraIds.length || !connected}
                >
                  <Play size={16} />
                  Iniciar análise
                </button>
              </form>
            )}
            {modal === "calibration" && (
              <CalibrationForm
                status={status}
                cameras={cameras}
                cameraId={
                  status.session
                    ? (status.session as Session & { cameraId: string })
                        .cameraId
                    : cameraId
                }
                done={() => {
                  setMessage(
                    "Calibração salva. A precisão de campo ainda precisa ser validada.",
                  );
                  setModal(null);
                  void refresh().catch(() => {});
                }}
              />
            )}
            {activeReplay && <ReplayPlayer replay={activeReplay} />}
          </Dialog>
        </div>
      )}
    </div>
  );
}

function ShotCoachSummary({
  events,
  status,
  mode,
}: {
  events: Event[];
  status: Status;
  mode: AnalysisMode;
}) {
  const ordered = [...events].sort((a, b) => a.timestamp - b.timestamp);
  const speeds = ordered
    .map((event) => event.speedBefore)
    .filter((speed): speed is number => speed != null && speed > 0);
  const liveSpeed =
    status.speed?.projectedKmh ?? status.lastEvent?.speedBefore ?? null;
  const peak = speeds.length
    ? Math.max(...speeds, liveSpeed ?? 0)
    : liveSpeed;
  const mean = speeds.length
    ? speeds.reduce((sum, speed) => sum + speed, 0) / speeds.length
    : null;
  const confirmed = ordered.filter((event) => event.status === "confirmed").length;
  const candidates = ordered.filter((event) => event.status === "candidate").length;
  const inside = ordered.filter((event) => event.inOrOut === "IN").length;
  const outside = ordered.filter((event) => event.inOrOut === "OUT").length;
  const review = ordered.filter((event) => !["IN", "OUT"].includes(event.inOrOut)).length;
  const forehands = ordered.filter((event) => event.strokeType === "forehand");
  const backhands = ordered.filter((event) => event.strokeType === "backhand");
  const unclassified = ordered.length - forehands.length - backhands.length;
  const strokeInfo = (rows: Event[]) => {
    const decidedRows=rows.filter(event=>["IN","OUT"].includes(event.inOrOut));
    const insideRows=rows.filter(event=>event.inOrOut==="IN");
    const rowSpeeds=rows.map(event=>event.speedBefore).filter((value):value is number=>value!=null);
    return {
      insideRate:decidedRows.length ? insideRows.length/decidedRows.length*100 : null,
      max:rowSpeeds.length ? Math.max(...rowSpeeds) : null,
    };
  };
  const forehandInfo=strokeInfo(forehands),backhandInfo=strokeInfo(backhands);
  const decided = inside + outside;
  const insideRate = decided ? inside / decided * 100 : null;
  const history = ordered.slice(-8);
  const mapEvents = ordered.slice(-30);
  const sectorOrder = [
    ["far_deep_left","Fundo A · esquerda"],["far_deep_right","Fundo A · direita"],
    ["far_service_left","Saque A · esquerda"],["far_service_right","Saque A · direita"],
    ["near_service_left","Saque B · esquerda"],["near_service_right","Saque B · direita"],
    ["near_deep_left","Fundo B · esquerda"],["near_deep_right","Fundo B · direita"],
  ] as const;
  const fallbackSector = (event:Event) => {
    if (event.courtSector) return event.courtSector;
    const point=event.targetPosition;if(!point)return "unlocated";
    const [x,y]=point,inset=1.37/10.97,service=.5-6.4/23.77;
    if(x<0||x>1||y<0||y>1)return "outside_court";
    if(x<inset)return "left_alley";if(x>1-inset)return "right_alley";
    const side=x<.5?"left":"right";
    const depth=y<service?"far_deep":y<.5?"far_service":y<=1-service?"near_service":"near_deep";
    return `${depth}_${side}`;
  };
  const sectorRows=Object.fromEntries([...sectorOrder.map(([key])=>key),"left_alley","right_alley"].map(key=>{
    const rows=ordered.filter(event=>fallbackSector(event)===key);
    return [key,{total:rows.length,forehand:rows.filter(event=>event.strokeType==="forehand").length,
      backhand:rows.filter(event=>event.strokeType==="backhand").length,
      outside:rows.filter(event=>event.inOrOut==="OUT").length}];
  })) as Record<string,{total:number;forehand:number;backhand:number;outside:number}>;
  const mapPoint = (event: Event): [number, number] | null => {
    if (event.targetPosition) return [event.targetPosition[0]*100,event.targetPosition[1]*60];
    if (event.courtPosition) {
      if (mode === "room") {
        return [
          ((event.courtPosition[0] + 3) / 6) * 100,
          ((event.courtPosition[1] + 2) / 4) * 60,
        ];
      }
      return [
        (event.courtPosition[0] / 10.97) * 100,
        (event.courtPosition[1] / 23.77) * 60,
      ];
    }
    if (event.position && event.resolution?.[0] && event.resolution?.[1]) {
      return [
        (event.position[0] / event.resolution[0]) * 100,
        (event.position[1] / event.resolution[1]) * 60,
      ];
    }
    return null;
  };
  return (
    <section className="coach-summary panel" aria-label="Resumo do treino">
      <div className="coach-summary-heading">
        <div>
          <div className="eyebrow">TENNIS VISION / COACH VIEW</div>
          <h2>Seu treino em números</h2>
          <p>
            Revisão da área marcada · observações compatíveis das câmeras são agrupadas. Casos ambíguos ficam para revisão.
          </p>
        </div>
        <span className="coach-live-tag">
          <span className={`dot ${status.running ? "green" : ""}`} />
          {status.running ? "AO VIVO" : "AGUARDANDO SESSÃO"}
        </span>
      </div>
      <div className="coach-kpis">
        <div className="coach-kpi">
          <span>CONTATOS DETECTADOS</span>
          <strong>{ordered.length}</strong>
          <small>
            {confirmed} confirmados · {candidates} candidatos
          </small>
        </div>
        <div className="coach-kpi">
          <span>DENTRO DA ÁREA</span>
          <strong className="decision-in">{inside}</strong>
          <small>{number(insideRate, 0)}% dos impactos decididos</small>
        </div>
        <div className="coach-kpi coach-kpi-out">
          <span>FORA · ALERTA VAR</span>
          <strong>{outside}</strong>
          <small>{review} ainda inconclusivos</small>
        </div>
        <div className="coach-kpi">
          <span>FRAMES COM BOLA</span>
          <strong>{status.framesProcessed ? Math.round(100*(status.detectedFrames || 0)/status.framesProcessed) : "—"}<em className="kpi-unit">%</em></strong>
          <small>Presença detectada · não mede precisão</small>
        </div>
        <div className="coach-kpi coach-kpi-speed">
          <span>VELOCIDADE ESTIMADA</span>
          <div>
            <strong style={{ color: color(liveSpeed) }}>
              {number(liveSpeed)}
            </strong>
            <em>km/h</em>
          </div>
          <small>
            pico {number(peak)} km/h · média {number(mean)} km/h
          </small>
        </div>
      </div>
      <div className="stroke-overview">
        <div className="stroke-card forehand">
          <span>FOREHAND · ESTIMADO PELA POSE</span>
          <strong>{forehands.length}</strong>
          <small>{number(forehandInfo.insideRate,0)}% dentro · pico {number(forehandInfo.max)} km/h</small>
        </div>
        <div className="stroke-card backhand">
          <span>BACKHAND · ESTIMADO PELA POSE</span>
          <strong>{backhands.length}</strong>
          <small>{number(backhandInfo.insideRate,0)}% dentro · pico {number(backhandInfo.max)} km/h</small>
        </div>
        <div className="stroke-card unclassified">
          <span>AINDA NÃO CLASSIFICADOS</span>
          <strong>{unclassified}</strong>
          <small>Exige corpo, punhos e bola visíveis no contato</small>
        </div>
      </div>
      <div className="coach-visuals">
        <div className="coach-history">
          <div className="coach-subheading">
            <strong>Velocidade por impacto</strong>
            <small>últimos {history.length || 0} eventos</small>
          </div>
          {history.length ? (
            <div className="coach-bars" role="img" aria-label="Histórico de velocidade dos golpes">
              {history.map((event, index) => {
                const speed = event.speedBefore;
                const height = speed == null ? 12 : Math.max(12, Math.min(100, (speed / Math.max(peak || 1, 1)) * 100));
                return (
                  <div className="coach-bar-wrap" key={event.id}>
                    <span
                      className="coach-bar"
                      style={{
                        height: `${height}%`,
                        backgroundColor:
                          speed == null ? "#9ba9a0" : color(speed),
                      }}
                      title={speed == null ? "Velocidade indisponível" : `${number(speed)} km/h`}
                    />
                    <small>{ordered.indexOf(event) + 1}</small>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="coach-empty">Cada impacto acrescenta uma barra.</div>
          )}
          <div className="coach-axis">
            <span>0 km/h</span>
            <span>pico {number(peak)} km/h</span>
          </div>
        </div>
        <div className="coach-placement">
          <div className="coach-subheading">
            <strong>{mode === "room" ? "Marcas na parede" : "Marcas na quadra"}</strong>
            <small>{status.capturePolygon || status.captureRegion ? "superfície selecionada" : "posição na imagem"}</small>
          </div>
          <svg viewBox="0 0 100 60" role="img" aria-label="Mapa compacto de posicionamento dos golpes">
            <rect x="0" y="0" width="100" height="60" rx="2" fill="#d6ddd2" />
            <g fill="none" stroke="#7b8f80" strokeWidth=".6">
              <rect x="4" y="5" width="92" height="50" />
              <path d="M50 5V55M4 30H96" strokeDasharray="2 2" />
              {mode === "court" && <path d="M18 5V55M82 5V55M18 15H82M18 45H82M50 15V45" />}
            </g>
            {mapEvents.map((event, index) => {
              const point = mapPoint(event);
              if (!point) return null;
              const speed = event.speedBefore;
              const fill = event.strokeType === "forehand" ? "#40c4da" : event.strokeType === "backhand" ? "#e6d54a" : "#f5f7f0";
              const outline = event.inOrOut === "OUT" ? "#dd3346" : speed == null ? "#5e7165" : color(speed);
              return (
                <g key={event.id}>
                  <circle
                    cx={Math.max(5, Math.min(95, point[0]))}
                    cy={Math.max(6, Math.min(54, point[1]))}
                    r="3.2"
                    fill={fill}
                    fillOpacity=".9"
                    stroke={outline}
                    strokeWidth={event.inOrOut === "OUT" ? "1.5" : ".8"}
                    strokeDasharray={event.status === "candidate" ? "1.5 1" : undefined}
                  />
                  <text
                    x={Math.max(6, Math.min(94, point[0] + 4))}
                    y={Math.max(8, Math.min(55, point[1] - 3))}
                    fontSize="4"
                    fill="#17211b"
                    fontWeight="700"
                  >
                    {ordered.indexOf(event) + 1}
                  </text>
                </g>
              );
            })}
          </svg>
          <div className="placement-legend">
            <span><i className="forehand" />Forehand</span>
            <span><i className="backhand" />Backhand</span>
            <span><i className="out" />Borda vermelha = OUT</span>
          </div>
          {mode === "court" && <div className="court-sector-stats" aria-label="Impactos por setor da quadra">
            {sectorOrder.map(([key,label])=>{
              const row=sectorRows[key];
              return <div className="court-sector-cell" key={key}>
                <span>{label}</span><strong>{row.total}</strong>
                <small>FH {row.forehand} · BH {row.backhand}{row.outside ? ` · OUT ${row.outside}` : ""}</small>
              </div>;
            })}
            <div className="court-sector-cell alley"><span>Corredor esquerdo</span><strong>{sectorRows.left_alley.total}</strong><small>FH {sectorRows.left_alley.forehand} · BH {sectorRows.left_alley.backhand}</small></div>
            <div className="court-sector-cell alley"><span>Corredor direito</span><strong>{sectorRows.right_alley.total}</strong><small>FH {sectorRows.right_alley.forehand} · BH {sectorRows.right_alley.backhand}</small></div>
          </div>}
          <small className="coach-map-note">
            Preenchimento = tipo de golpe · borda = velocidade/decisão · posição relativa
          </small>
        </div>
      </div>
    </section>
  );
}

function Field({
  label,
  ...props
}: React.InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  return (
    <label>
      {label}
      <input {...props} />
    </label>
  );
}
function Empty({ title, text }: { title: string; text: string }) {
  return (
    <div className="empty">
      <Layers size={27} />
      <h3>{title}</h3>
      <p>{text}</p>
    </div>
  );
}
function Dialog({
  title,
  close,
  children,
}: {
  title: string;
  close: () => void;
  children: React.ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement;
    const el = ref.current;
    // Put the caret in the first editable control. Focusing the dialog
    // container itself made the form look active while keystrokes went
    // nowhere until the user clicked a field a second time.
    const firstControl =
      el?.querySelector<HTMLElement>(
        'input:not([readonly]):not(:disabled),textarea:not([readonly]):not(:disabled)',
      ) ||
      el?.querySelector<HTMLElement>(
        'select:not(:disabled),button:not(:disabled):not([aria-label="Fechar diálogo"])',
      );
    (firstControl || el)?.focus({ preventScroll: true });
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
      if (e.key === "Tab") {
        const nodes = Array.from(
          el?.querySelectorAll<HTMLElement>(
            'button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled),a[href],[tabindex="0"]',
          ) || [],
        );
        const first = nodes[0],
          last = nodes[nodes.length - 1];
        if (
          e.shiftKey &&
          (document.activeElement === first || document.activeElement === el)
        ) {
          e.preventDefault();
          last?.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first?.focus();
        }
      }
    };
    document.addEventListener("keydown", handler);
    return () => {
      document.removeEventListener("keydown", handler);
      previous?.focus();
    };
  }, []);
  return (
    <div
      className="modal panel"
      ref={ref}
      role="dialog"
      aria-modal="true"
      aria-label={title}
      tabIndex={-1}
    >
      <div className="modal-heading">
        <h2>{title}</h2>
        <button onClick={close} aria-label="Fechar diálogo">
          <X size={20} />
        </button>
      </div>
      {children}
    </div>
  );
}
function CalibrationForm({
  status,
  cameraId: initialCameraId,
  cameras,
  done,
}: {
  status: Status;
  cameraId: string;
  cameras: Camera[];
  done: () => void;
}) {
  const [cameraId, setCameraId] = useState(initialCameraId);
  const [points, setPoints] = useState<[number, number][]>([]),
    [src, setSrc] = useState<string>(),
    [resolution, setResolution] = useState<[number, number] | null>(null),
    [refreshToken, setRefreshToken] = useState(0),
    [mode, setMode] = useState<"room" | "court">("room"),
    [distance, setDistance] = useState("1"),
    [name, setName] = useState("Teste no quarto"),
    [error, setError] = useState(""),
    [saving, setSaving] = useState(false);
  const requiredPoints = mode === "room" ? 2 : 4;
  useEffect(() => {
    let url: string;
    let active = true;
    setError("");
    setSrc(undefined);
    setPoints([]);
    setResolution(null);
    // The fluid preview is resized for display. Calibration requires the
    // original analysed frame, from the selected camera, in original pixels.
    fetch(`/api/frame?synchronized=true&cameraId=${encodeURIComponent(cameraId)}`, { cache: "no-store" })
      .then(async (r) => {
        if (!r.ok)
          throw Error(
            "Inicie uma sessão e aguarde a imagem real para calibrar.",
          );
        url = URL.createObjectURL(await r.blob());
        if (active) setSrc(url);
        else URL.revokeObjectURL(url);
      })
      .catch((e) => { if (active) setError(e.message); });
    return () => {
      active = false;
      if (url) URL.revokeObjectURL(url);
    };
  }, [refreshToken, cameraId]);
  return (
    <>
      {cameras.length > 1 && <label>
        Câmera para calibrar
        <select value={cameraId} disabled={saving} onChange={e => setCameraId(e.target.value)}>
          {cameras.map(camera => <option key={camera.id} value={camera.id}>{camera.name}</option>)}
        </select>
      </label>}
      <label>
        Tipo de teste
        <select
          value={mode}
          onChange={(e) => {
            const next = e.target.value as "room" | "court";
            setMode(next);
            setPoints([]);
            setError("");
          }}
        >
          <option value="room">Ambiente / quarto (escala manual)</option>
          <option value="court">Quadra de tênis (quatro cantos)</option>
        </select>
      </label>
      <p>
        {mode === "room"
          ? "Marque dois pontos visíveis cuja distância você conhece, como duas marcas separadas por 1 metro no chão ou na mesa."
          : "Marque os quatro cantos externos, nesta ordem: fundo esquerdo, fundo direito, próximo direito, próximo esquerdo. O perfil usa a quadra de duplas (10,97 × 23,77 m)."}
      </p>
      <p className="calibration-why">
        {mode === "room"
          ? "A câmera enxerga pixels. A distância conhecida cria uma escala aproximada para converter o movimento da bola em metros e estimar km/h no ambiente."
          : "A câmera enxerga pixels. Estes quatro pontos informam onde está o plano real da quadra, permitindo converter deslocamento em metros e estimar km/h."} A câmera continua rodando; esta imagem fica congelada só para a marcação.
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {src && (
        <>
          <div
            className="calibration-image"
            onClick={(e) => {
              if (points.length >= requiredPoints || !resolution) return;
              const box = e.currentTarget.getBoundingClientRect();
              setPoints([
                ...points,
                [
                  ((e.clientX - box.left) / box.width) * resolution[0],
                  ((e.clientY - box.top) / box.height) * resolution[1],
                ],
              ]);
            }}
          >
            <img
              src={src}
              alt={
                mode === "room"
                  ? "Clique em dois pontos com distância conhecida"
                  : "Clique nos quatro cantos externos da quadra"
              }
              onLoad={(e) =>
                setResolution([
                  e.currentTarget.naturalWidth,
                  e.currentTarget.naturalHeight,
                ])
              }
            />
            {resolution && (
              <svg viewBox={`0 0 ${resolution[0]} ${resolution[1]}`}>
                {points.length > 1 && (
                  <polyline
                    points={points.map((p) => p.join(",")).join(" ")}
                    fill="none"
                    stroke="#c9f56b"
                    strokeWidth="3"
                  />
                )}
                {points.map((p, i) => (
                  <g key={i}>
                    <circle cx={p[0]} cy={p[1]} r="10" fill="#c9f56b" />
                    <text x={p[0] + 14} y={p[1]} fill="white" fontSize="24">
                      {i + 1}
                    </text>
                  </g>
                ))}
              </svg>
            )}
          </div>
          <button
            type="button"
            className="frame-refresh"
            onClick={() => {
              setPoints([]);
              setResolution(null);
              setRefreshToken((v) => v + 1);
            }}
          >
            Atualizar imagem da câmera
          </button>
        </>
      )}
      <p>{points.length} / {requiredPoints} pontos marcados</p>
      <small className="point-order">
        {mode === "room"
          ? "Marque o ponto inicial e depois o ponto final da distância conhecida."
          : "Ordem: 1 fundo esquerdo · 2 fundo direito · 3 próximo direito · 4 próximo esquerdo"}
      </small>
      {mode === "room" && (
        <Field
          label="Distância entre os dois pontos (metros)"
          type="number"
          min="0.01"
          max="100"
          step="0.01"
          value={distance}
          onChange={(e) => setDistance(e.target.value)}
        />
      )}
      <Field
        label="Nome do perfil"
        value={name}
        onChange={(e) => setName(e.target.value)}
      />
      <p className="inline-note">
        {mode === "room"
          ? "A escala de ambiente é uma aproximação no plano dos pontos. Mantenha a bola perto desse plano para obter uma estimativa útil."
          : "Quatro pontos ajustam a homografia, mas não comprovam sua qualidade. Valide com pontos independentes pela API antes de confiar nas posições."}
      </p>
      <div className="controls">
        <button type="button" onClick={() => setPoints([])}>Recomeçar</button>
        <button
          className="primary"
          disabled={points.length !== requiredPoints || !cameraId || !resolution || saving}
          onClick={async () => {
            setSaving(true);
            try {
              await api("/calibrations", {
                cameraId,
                name,
                mode,
                imagePoints: points,
                resolution,
                ...(mode === "room" ? { realDistanceM: Number(distance) } : {}),
              });
              done();
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setSaving(false);
            }
          }}
        >
          <Check size={16} />
          {saving
            ? "Salvando…"
            : points.length === requiredPoints
              ? "Salvar calibração"
              : `Marque ${requiredPoints - points.length} ponto${requiredPoints - points.length === 1 ? "" : "s"}`}
        </button>
      </div>
    </>
  );
}


