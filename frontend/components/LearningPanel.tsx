"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { Check, Crosshair, Database, Play, RefreshCw, X } from "lucide-react";
import { api, AutoLearning } from "../lib/types";

type Box = [number, number, number, number];
type Report = { version: string; accepted: boolean; trainExamples: number; augmentedExamples: number; testExamples: number; testPositives: number; metrics: { tp: number; fp: number; fn: number; precision: number; recall: number }; scope: string };
type Learning = { settings: { collect: boolean; autoTrain: boolean }; busy: boolean; error: string | null; active: Report | null; lastTraining: Report | null; reviewed: number; newExamples: number; running: boolean; automatic: AutoLearning[]; pending: { id: string; box: Box | null; reason: string; split: string; timestamp: number }[] };

export function LearningPanel() {
  const [data, setData] = useState<Learning>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [box, setBox] = useState<Box | null>(null);
  const [size, setSize] = useState<[number, number]>([1920, 1080]);
  const [zoom, setZoom] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const start = useRef<[number, number] | null>(null);
  const item = data?.pending[0];
  const refresh = useCallback(async () => { setData(await api<Learning>("/learning")); }, []);
  useEffect(() => {
    refresh().catch(() => setError("Não foi possível carregar o treinamento."));
    const timer = setInterval(() => refresh().catch(() => {}), 2500);
    return () => clearInterval(timer);
  }, [refresh]);
  useEffect(() => { setBox(item?.box || null); setLoaded(false); setZoom(false); }, [item?.id]);
  async function action(path: string, body: unknown) {
    setBusy(true); setError("");
    try { await api(path, body); await refresh(); }
    catch (e) { setError(e instanceof Error ? e.message : "Não foi possível concluir."); }
    finally { setBusy(false); }
  }
  function point(event: React.PointerEvent<SVGSVGElement>): [number, number] {
    const rect = event.currentTarget.getBoundingClientRect();
    return [Math.max(0, Math.min(size[0], (event.clientX - rect.left) / rect.width * size[0])), Math.max(0, Math.min(size[1], (event.clientY - rect.top) / rect.height * size[1]))];
  }
  const report = data?.lastTraining;
  const automatic = data?.automatic || [];
  const automaticSamples = automatic.reduce((sum,item)=>sum+item.sampleCount,0);
  const automaticReady = automatic.filter(item=>item.ready).length;
  return <section className="learning-panel">
    <div className="learning-intro">
      <span className="learning-orb"><Crosshair size={30} /></span>
      <div><div className="eyebrow">APRENDIZADO AUTOMÁTICO / SEM RÓTULOS</div><h2>A bola ensina o sistema enquanto se move.</h2><p>Trajetórias fortes e coerentes criam uma assinatura visual própria para cada câmera. Você não precisa informar a cor; a revisão abaixo fica disponível apenas para correções avançadas.</p></div>
      <span className="learning-state">{automaticReady ? `${automaticReady}/${automatic.length} câmera${automatic.length === 1 ? "" : "s"} adaptada${automatic.length === 1 ? "" : "s"}` : data?.running ? "Observando a trajetória…" : "Começa durante a sessão"}</span>
    </div>
    {error && <p className="error" role="alert">{error}</p>}
    {data?.error && <p className="error" role="alert">{data.error}</p>}
    <div className="learning-stats">
      <div><span>AMOSTRAS AUTOMÁTICAS</span><strong>{automatic.length ? automaticSamples : "—"}</strong><small>{automaticReady ? `${automaticReady} câmera${automaticReady === 1 ? "" : "s"} com identidade visual ativa` : "5 observações contínuas por câmera ativam a assinatura"}</small></div>
      <div><span>EXEMPLOS REVISADOS</span><strong>{data?.reviewed ?? "—"}</strong><small>{data?.newExamples ?? 0} novos desde o último treino</small></div>
      <div><span>PARA REVISAR</span><strong>{data?.pending.length ?? "—"}</strong><small>Capturas locais, limitadas a 120</small></div>
      <div><span>ÚLTIMA VALIDAÇÃO</span><strong>{report ? `${report.metrics.tp} / ${report.testPositives}` : "—"}</strong><small>Bolas reconhecidas · {report?.metrics.fp ?? "—"} falsos positivos</small></div>
    </div>
    <div className="learning-workspace">
      <div className="learning-review">
        <div className="learning-card-heading"><div><span className="eyebrow">01 / CORREÇÃO OPCIONAL</span><h3>{item ? "Onde está a bola?" : "O aprendizado automático está pronto."}</h3></div>{item && <button className="secondary" onClick={() => setZoom(!zoom)}>{zoom ? "Visão completa" : "Ampliar 2×"}</button>}</div>
        {item ? <>
          <p>Arraste um retângulo justo sobre a bola. Se a marca pegou outro objeto, mantenha o retângulo e escolha “Não é bola”.</p>
          <div className="learning-image-scroll">
            <div className="learning-image" style={{ width: zoom ? "200%" : "100%" }}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img key={item.id} src={`/api/learning/frames/${item.id}`} alt="Quadro congelado para revisar a localização da bola" draggable={false} onLoad={event => { setSize([event.currentTarget.naturalWidth, event.currentTarget.naturalHeight]); setLoaded(true); }} onError={() => setError("Não foi possível abrir o exemplo.")} />
              <svg viewBox={`0 0 ${size[0]} ${size[1]}`} aria-label="Arraste para marcar a bola na imagem" onPointerDown={event => { if (!loaded) return; event.preventDefault(); event.currentTarget.setPointerCapture(event.pointerId); start.current = point(event); setBox(null); }} onPointerMove={event => { if (!start.current) return; const end = point(event); setBox([Math.min(start.current[0], end[0]), Math.min(start.current[1], end[1]), Math.abs(end[0] - start.current[0]), Math.abs(end[1] - start.current[1])]); }} onPointerUp={() => { start.current = null; }} onPointerCancel={() => { start.current = null; }}>
                {box && <rect x={box[0]} y={box[1]} width={box[2]} height={box[3]} fill="#c7f56b22" stroke="#c7f56b" strokeWidth={size[0] / 500} />}
              </svg>
            </div>
          </div>
          <div className="learning-example-note"><span>Quadro congelado · {new Date(item.timestamp * 1000).toLocaleTimeString("pt-BR")}</span><span>{item.split === "test" ? "Reservado para validação" : "Exemplo de treinamento"}</span></div>
          <details className="learning-coordinates"><summary>Ajustar retângulo pelo teclado</summary><div>{["X", "Y", "Largura", "Altura"].map((label, i) => <label key={label}>{label}<input type="number" min={0} value={Math.round(box?.[i] ?? 0)} onChange={event => { const next: Box = box ? [...box] : [0, 0, 10, 10]; next[i] = Number(event.target.value); setBox(next); }} /></label>)}</div></details>
          <div className="learning-actions"><button className="primary" disabled={busy || !loaded || !box || Math.min(box[2], box[3]) < 2} onClick={() => action(`/learning/review/${item.id}`, { label: 1, box })}><Check size={17} /> É a bola</button><button className="secondary" disabled={busy || !loaded || !box || Math.min(box[2], box[3]) < 2} onClick={() => action(`/learning/review/${item.id}`, { label: 0, box })}><X size={17} /> Não é bola</button><button className="secondary" disabled={busy} onClick={() => action(`/learning/review/${item.id}`, { label: null })}>Ignorar</button></div>
        </> : <div className="learning-empty"><Database size={38} /><h3>Nenhuma correção necessária agora.</h3><p>A assinatura visual aprende durante os arremessos. Se algum objeto for confundido, você pode congelar um quadro e corrigir manualmente.</p><button className="secondary" disabled={busy || !data?.running} onClick={() => action("/learning/capture", {})}>Capturar para correção</button></div>}
      </div>
      <aside className="learning-settings">
        <span className="eyebrow">02 / MODELO REVISADO</span><h3>Correção avançada.</h3>
        <p>Opcional: após 8 correções humanas, um segundo modelo pode treinar quando você encerrar a sessão. Ele fica separado da assinatura automática.</p>
        <label className="learning-toggle"><input type="checkbox" checked={data?.settings.collect ?? false} disabled={busy || !data} onChange={e => action("/learning/settings", { ...data?.settings, collect: e.target.checked })} /><span>Separar quadros para revisão<small>Até 40 por sessão. A imagem completa é descartada após a revisão.</small></span></label>
        <label className="learning-toggle"><input type="checkbox" checked={data?.settings.autoTrain ?? false} disabled={busy || !data} onChange={e => action("/learning/settings", { ...data?.settings, autoTrain: e.target.checked })} /><span>Treinar após novas revisões<small>O modelo só é substituído se passar na validação sem piorar o anterior.</small></span></label>
        <button className="primary" disabled={busy || data?.busy || data?.running || !data || data.newExamples === 0} onClick={() => action("/learning/train", {})}>{data?.busy ? <RefreshCw size={17} /> : <Play size={17} />}{data?.busy ? "Treinamento em andamento" : "Treinar com as revisões"}</button>
        {data?.running && <small>Encerre a sessão para liberar o processamento para o treino.</small>}
        {report && <div className="learning-report"><span className="eyebrow">RESULTADO DO TREINO</span><strong>{report.accepted ? "Modelo aprovado" : "Modelo anterior preservado"}</strong><p>{report.trainExamples} recortes de treino · {report.augmentedExamples} exemplos com variações de luz, tamanho e movimento.</p><p>{report.testExamples} recortes na validação: {report.metrics.tp} bolas reconhecidas, {report.metrics.fn} perdidas e {report.metrics.fp} objetos confundidos.</p><small>{report.scope}</small><small>Posição e velocidade ainda dependem da imagem, do tempo entre quadros e da calibração. Revisar uma bola não comprova contato com a parede.</small></div>}
      </aside>
    </div>
  </section>;
}
