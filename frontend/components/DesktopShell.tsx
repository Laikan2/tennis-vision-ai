"use client";
import { useEffect, useState } from "react";
import { CheckCircle2, Download, FolderOpen, Github, HardDrive, Maximize2, Monitor, RefreshCw, ScrollText, Wifi } from "lucide-react";

type UpdateInfo = {
  status: "idle" | "unconfigured" | "development" | "checking" | "current" | "available" | "downloading" | "downloaded" | "installing" | "error";
  currentVersion: string; availableVersion: string | null; percent: number;
  transferred: number; total: number; checkedAt: string | null; message: string; repository: string | null;
};

type StudioInfo = {
  version: string; ready: boolean; stage: string; issue: string;
  backend: string; receiver: string; dataPath: string; logsPath: string;
  projectPath: string | null; videoAcceleration: string;
  updates?: UpdateInfo;
};
type StudioBridge = {
  getInfo: () => Promise<StudioInfo>;
  checkUpdates: () => Promise<UpdateInfo>;
  installUpdate: () => Promise<UpdateInfo>;
  openData: () => Promise<string>;
  openLogs: () => Promise<string>;
  selectProject: () => Promise<StudioInfo | { cancelled: true }>;
  prepareNetwork: () => Promise<{ ok: boolean; detail: string }>;
  fullscreen: () => Promise<boolean>;
  onStatus: (callback: (info: StudioInfo) => void) => () => void;
};
declare global { interface Window { tennisStudio?: StudioBridge; } }

export function DesktopTitlebar() {
  const [native, setNative] = useState(false);
  useEffect(() => {
    if (!window.tennisStudio) return;
    setNative(true);
    document.body.classList.add("studio-desktop");
    return () => document.body.classList.remove("studio-desktop");
  }, []);
  if (!native) return null;
  return <div className="desktop-titlebar">
    <span className="titlebar-dot" /> Tennis Vision Studio
    <span className="titlebar-mode">ESTÚDIO LOCAL</span>
    <button aria-label="Tela cheia" onClick={() => void window.tennisStudio?.fullscreen()}><Maximize2 size={13} /></button>
  </div>;
}

export function DesktopSettings() {
  const [info, setInfo] = useState<StudioInfo | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    const bridge = window.tennisStudio;
    if (!bridge) return;
    let mounted = true;
    const refresh = () => bridge.getInfo().then(value => { if (mounted) setInfo(value); }).catch(() => {});
    void refresh();
    const unsubscribe = bridge.onStatus(value => { if (mounted) setInfo(value); });
    const interval = window.setInterval(refresh, 5000);
    return () => { mounted = false; unsubscribe(); window.clearInterval(interval); };
  }, []);
  if (!info) return null;
  const run = async (action: () => Promise<unknown>) => {
    setError("");
    try {
      const result = await action();
      if (typeof result === "string" && result) throw new Error(result);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Não foi possível abrir."); }
  };
  return <section className="panel padded desktop-settings">
    <div className="panel-heading"><h2><Monitor size={19} /> Programa para Windows</h2><span className="tag">v{info.version}</span></div>
    {info.updates && <DesktopUpdates update={info.updates} />}
    <div className="data-row"><span>Motor de análise</span><b>{info.ready ? "Conectado" : "Iniciando"}</b></div>
    <div className="data-row"><span>Receptor das câmeras</span><b>{info.receiver === "integrado" ? "Integrado" : info.receiver === "compartilhado" ? "Já disponível no PC" : info.receiver}</b></div>
    <div className="data-row"><span>Decodificação de vídeo</span><b>{info.videoAcceleration === "enabled" ? "Acelerada pelo hardware" : "Pelo sistema"}</b></div>
    <div className="desktop-data-path"><HardDrive size={16} /><div><strong>Seus dados ficam aqui</strong><code>{info.dataPath}</code></div></div>
    <div className="desktop-action-row">
      <button onClick={() => void run(() => window.tennisStudio!.openData())}><FolderOpen size={16} /> Abrir dados</button>
      <button onClick={() => void run(() => window.tennisStudio!.openLogs())}><ScrollText size={16} /> Registros</button>
    </div>
    <button className="desktop-import" onClick={() => void run(() => window.tennisStudio!.selectProject())}><FolderOpen size={16} /> Usar dados de um projeto existente</button>
    <p className="inline-note">Selecione a pasta tennis-vision-ai para usar suas câmeras, áreas e sessões anteriores. Encerre a sessão antes de trocar a pasta.</p>
    <DesktopNetworkSetup />
    {error && <p className="notice" role="alert">{error}</p>}
  </section>;
}

function DesktopUpdates({ update }: { update: UpdateInfo }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const active = busy || ["checking", "downloading", "installing"].includes(update.status);
  const enabled = !["development", "unconfigured"].includes(update.status);
  const titles: Record<UpdateInfo["status"], string> = {
    idle: "Atualizações automáticas ativas", checking: "Buscando uma nova versão…",
    current: "Seu programa está atualizado", available: `Versão ${update.availableVersion} disponível`,
    downloading: `Baixando versão ${update.availableVersion} · ${Math.round(update.percent)}%`,
    downloaded: `Versão ${update.availableVersion} pronta para instalar`, installing: "Preparando a atualização…",
    error: "A atualização pode ser tentada novamente", development: "Atualizações disponíveis no programa instalado",
    unconfigured: "Repositório de atualizações ainda não configurado",
  };
  const run = async (install: boolean) => {
    setBusy(true); setError("");
    try {
      const bridge = window.tennisStudio!;
      await (install ? bridge.installUpdate() : bridge.checkUpdates());
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Não foi possível atualizar."); }
    finally { setBusy(false); }
  };
  return <div className="desktop-updates" aria-busy={active}>
    <div className="desktop-updates-icon"><Github size={23} /></div>
    <div className="desktop-updates-copy">
      <span className="desktop-updates-label">ATUALIZAÇÕES PELO GITHUB</span>
      <strong role="status">{update.status === "current" && <CheckCircle2 size={15} />} {titles[update.status]}</strong>
      <p>{update.message || "Novas versões são baixadas quando a sessão está parada e instaladas ao fechar o programa. Suas câmeras e sessões ficam salvas."}</p>
      {update.status === "downloading" && <progress aria-label="Download da atualização" max={100} value={update.percent} />}
      {update.repository && <small>{update.repository.replace("https://github.com/", "")}{update.checkedAt && ` · Verificado às ${new Date(update.checkedAt).toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" })}`}</small>}
      {error && <p className="notice" role="alert">{error}</p>}
    </div>
    <button disabled={!enabled || active} onClick={() => void run(update.status === "downloaded")}>
      {update.status === "downloaded" ? <Download size={16} /> : <RefreshCw size={16} />}
      {update.status === "downloaded" ? "Reiniciar para atualizar" : "Buscar atualização"}
    </button>
  </div>;
}

export function DesktopNetworkSetup({ browserInstructions = false }: { browserInstructions?: boolean }) {
  const [native, setNative] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  useEffect(() => setNative(Boolean(window.tennisStudio)), []);
  if (!native) return browserInstructions ? <span> Execute <code>LIBERAR-CAMERA-CELULAR.bat</code> no PC.</span> : null;
  const prepare = async () => {
    setBusy(true); setMessage("");
    try { const result = await window.tennisStudio!.prepareNetwork(); setMessage(result.detail); }
    catch (reason) { setMessage(reason instanceof Error ? reason.message : "Não foi possível preparar a rede."); }
    finally { setBusy(false); }
  };
  return <div className="desktop-network">
    <button disabled={busy} onClick={() => void prepare()}><Wifi size={16} /> {busy ? "Aguardando o Windows…" : "Preparar rede do PC"}</button>
    <small>O Windows pede autorização. Libera o receptor somente na rede privada local.</small>
    {message && <p role="status">{message}</p>}
  </div>;
}
