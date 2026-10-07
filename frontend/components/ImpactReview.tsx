"use client";
import { useCallback, useEffect, useState } from "react";
import { Check, Crosshair, ShieldCheck } from "lucide-react";
import { api, Camera, Event, number, Replay, ReplayFrame } from "../lib/types";
import { ReplayPlayer } from "./Visuals";

export function ImpactReview({ event, cameras, onSaved }: { event: Event; cameras: Camera[]; onSaved: () => void }) {
  const [clips,setClips]=useState<Replay[]>([]), [selected,setSelected]=useState(""), [frame,setFrame]=useState<ReplayFrame>(),
    [point,setPoint]=useState<[number,number]>(), [box,setBox]=useState<[number,number,number,number]>(),
    [reason,setReason]=useState("decision_error"), [decision,setDecision]=useState("REVISAR"),
    [loading,setLoading]=useState(true),[error,setError]=useState(""),[saved,setSaved]=useState(""),[busy,setBusy]=useState(false);
  useEffect(() => {
    let alive=true, timer: ReturnType<typeof setTimeout>, attempts=0;
    async function refresh() {
      try {
        const r=await api<{ready:Replay[];pending:{id:string}[]}>(`/events/${event.id}/replays`);
        if (!alive) return; setClips(r.ready); setSelected(current => current || r.ready[0]?.id || "");
        if (r.pending.length && attempts++<25) timer=setTimeout(refresh,1200);
        else { setLoading(false); if (!r.ready.length) setError("Não há um trecho salvo para este impacto. A gravação pode ter atingido o limite ou sido interrompida."); }
      } catch(e) { if(alive) { setLoading(false);setError((e as Error).message); } }
    }
    refresh(); return () => { alive=false; clearTimeout(timer); };
  },[event.id]);
  const selectFrame=useCallback((f:ReplayFrame) => { setFrame(f);setPoint(undefined);setBox(undefined); },[]);
  const clip=clips.find(c=>c.id===selected);
  const observation=event.observations?.find(o=>o.cameraId===clip?.cameraId);
  return <div className="review-workspace">
    <div className="review-summary"><ShieldCheck size={24}/><div><span className="eyebrow">REVISÃO DO CONTATO</span>
      <h3>{event.inOrOut === "IN" ? "Dentro" : event.inOrOut === "OUT" ? "Fora" : "Decisão para revisar"} · {number(event.speedBefore)} km/h estimados</h3>
      <p>{event.decisionReason || "Confira o movimento antes e depois do contato."}</p></div></div>
    {loading && <p role="status">Concluindo o trecho com os quadros após o contato…</p>}
    {error && <p className="error" role="alert">{error}</p>}
    {!!clips.length && <label>Câmera / evidência<select value={selected} onChange={e=>{setSelected(e.target.value);setFrame(undefined);setPoint(undefined);setBox(undefined);setSaved("");}}>
      {clips.map(c=><option key={c.id} value={c.id}>{cameras.find(cam=>cam.id===c.cameraId)?.name || "Câmera"} · {c.frames} quadros {c.truncated ? "· trecho parcial" : ""}</option>)}</select></label>}
    {clip && <ReplayPlayer key={clip.id} replay={clip} onFrame={selectFrame} focusTimestamp={observation?.timestamp ?? event.timestamp}
      annotation={reason === "impact_position" ? "point" : reason === "ball_missed" || reason === "false_ball" ? "box" : "none"}
      onPoint={setPoint} onBox={setBox} />}
    {clip && <div className="review-form">
      <label>O que precisa corrigir?<select value={reason} disabled={busy} onChange={e=>{setReason(e.target.value);setSaved("");setPoint(undefined);setBox(undefined);}}>
        <option value="decision_error">Decisão dentro / fora</option><option value="impact_position">Local do contato</option>
        <option value="false_impact">Este impacto não aconteceu</option><option value="ball_missed">Bola não detectada neste quadro</option>
        <option value="false_ball">Objeto confundido com bola</option></select></label>
      {(reason === "decision_error" || reason === "impact_position") && <label>Decisão revisada<select value={decision} onChange={e=>setDecision(e.target.value)}>
        <option value="REVISAR">Manter para revisão</option><option value="IN">Dentro</option><option value="OUT">Fora</option></select></label>}
      {reason === "impact_position" && <p className="calibration-why"><Crosshair size={16}/> Pause no quadro do contato e clique no local exato. A velocidade antiga será retirada.</p>}
      {(reason === "ball_missed" || reason === "false_ball") && <p className="calibration-why">Pause o vídeo e arraste uma caixa justa sobre a bola ou o objeto confundido. Este exemplo revisado entra no treinamento local.</p>}
      {saved && <p role="status" className="review-success">{saved}</p>}
      <button className="primary" disabled={busy || !frame || (reason === "impact_position" && !point) || ((reason === "ball_missed" || reason === "false_ball") && !box)}
        onClick={async()=>{setBusy(true);setError("");try {await api(`/events/${event.id}/review`,{replayId:clip.id,frameIndex:frame!.index,reason,
          ...(reason === "decision_error" || reason === "impact_position" ? {decision} : {}),...(point?{position:point}:{}),...(box?{box}: {})});
          setSaved("Correção salva. O histórico original foi preservado.");onSaved();}catch(e){setError((e as Error).message);}finally{setBusy(false);}}}>
        <Check size={16}/>{busy ? "Salvando…" : "Salvar revisão"}</button>
    </div>}
  </div>;
}
