"use client";
import { useEffect, useState } from "react";
import { FileVideo, Upload } from "lucide-react";
import { api, Camera, Event, number, Session, Stats, Status } from "../lib/types";
import { CaptureArea } from "./CaptureArea";
type Recording={id:string;name:string;cameraId:string;status:string;sourceFps:number;sourceFrames:number;framesProcessed?:number;resolution:[number,number];
  progress?:number;durationSeconds:number;sessionId?:string;statistics?:Stats;error?:string;calibrationWarning?:string;createdAt:number;};
export function RecordingPanel({cameras,onReview,onCalibrate,reviewRevision=0}:{cameras:Camera[];onReview:(event:Event)=>void;onCalibrate:(id:string,cameraId:string)=>void;reviewRevision?:number}) {
  const [jobs,setJobs]=useState<Recording[]>([]),[cameraId,setCameraId]=useState(cameras[0]?.id ?? ""),[selected,setSelected]=useState(""),
    [currentCamera,setCurrentCamera]=useState<Camera>(),[events,setEvents]=useState<Event[]>([]),[busy,setBusy]=useState(false),[message,setMessage]=useState(""),
    [mount,setMount]=useState<HTMLDivElement|null>(null),[editing,setEditing]=useState(false);
  async function refresh(){setJobs(await api<Recording[]>("/recordings"));}
  useEffect(()=>{let alive=true;const get=async()=>{try{const r=await api<Recording[]>("/recordings");if(alive)setJobs(r);}catch(e){if(alive)setMessage((e as Error).message);}};
    get();const timer=setInterval(get,2000);return()=>{alive=false;clearInterval(timer);};},[]);
  useEffect(()=>{if(!cameraId && cameras[0])setCameraId(cameras[0].id);},[cameras,cameraId]);
  const job=jobs.find(j=>j.id===selected);
  const camera=currentCamera?.id===job?.cameraId ? currentCamera : cameras.find(c=>c.id===job?.cameraId);
  useEffect(()=>{let active=true;setEvents([]);if(job?.sessionId && job.status!=="running") api<Event[]>(`/events?sessionId=${encodeURIComponent(job.sessionId)}`).then(value=>{if(active)setEvents(value);}).catch(e=>{if(active)setMessage(e.message);});return()=>{active=false;};},[job?.sessionId,job?.status,reviewRevision]);
  const state=job && camera ? {...camera,cameraId:camera.id,running:false,stale:false,resolution:job.resolution,
    cameraStatus:"offline",cameraFps:job.sourceFps,framesReceived:0,droppedFrames:0,detector:"Gravação",device:null,calibration:null,
    session:{id:job.id,cameraId:camera.id,name:job.name,createdAt:job.createdAt,endedAt:null,kind:"Treino",player1:"",player2:""} as Session} satisfies Status : null;
  return <section className="panel padded recording-panel"><h2><FileVideo size={20}/> Analisar gravação original</h2>
    <p>Abra o MP4 gravado no celular. O Windows analisa todos os quadros decodificados, preservando o relógio do vídeo. Encerre a sessão ao vivo antes de iniciar.</p>
    <div className="recording-upload"><label>Perfil da câmera<select value={cameraId} disabled={busy} onChange={e=>setCameraId(e.target.value)}>
      <option value="">Escolha uma câmera salva</option>{cameras.map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
      <label className="button-link"><Upload size={16}/>{busy?"Enviando vídeo…":"Abrir vídeo MP4"}<input type="file" accept="video/mp4,.mp4" hidden disabled={busy || !cameraId}
        onChange={async e=>{const file=e.target.files?.[0];if(!file)return;const input=e.currentTarget;setBusy(true);setMessage("");try{
          const response=await fetch(`/api/recordings?cameraId=${encodeURIComponent(cameraId)}&name=${encodeURIComponent(file.name.slice(0,150))}`,{method:"POST",headers:{"Content-Type":"application/octet-stream"},body:file});
          const data=await response.json();if(!response.ok)throw Error(typeof data.detail==="string"?data.detail:"Falha no envio.");
          await refresh();setSelected(data.id);setCurrentCamera(undefined);
        }catch(error){setMessage((error as Error).message);}finally{setBusy(false);input.value="";}}}/></label></div>
    <small>Até 1 GB / 10 minutos. A análise fica no computador e pode levar mais tempo que a duração do vídeo.</small>
    {!!jobs.length && <label className="recording-select">Vídeo<select value={selected} onChange={e=>{setSelected(e.target.value);setCurrentCamera(undefined);}}>
      <option value="">Escolha uma gravação</option>{jobs.map(j=><option key={j.id} value={j.id}>{j.name} · {j.status==="complete"?"analisado":j.status==="running"?"em análise":j.status==="uploaded"?"pronto":j.status}</option>)}</select></label>}
    {job && <><div className="recording-metrics"><span>{number(job.sourceFps)} fps no arquivo</span><span>{job.sourceFrames} quadros</span><span>{number(job.durationSeconds)} s</span></div>
      <div className="recording-preview"><img src={`/api/recordings/${job.id}/frame`} alt="Primeiro quadro do vídeo para marcar a superfície"/>
        {state && <CaptureArea key={job.id} status={state} cameraId={job.cameraId} width={job.resolution[0]} height={job.resolution[1]} controlsMount={mount} locked={job.status==="running"}
          onEditing={value=>{setEditing(value);if(!value)api<Camera[]>("/cameras").then(c=>setCurrentCamera(c.find(item=>item.id===job.cameraId))).catch(()=>{});}}/>}</div>
      <div ref={setMount}/>
      <div className="controls"><button onClick={()=>onCalibrate(job.id,job.cameraId)} disabled={job.status==="running"}>Calibrar medidas neste vídeo</button>
        <button className="primary" disabled={busy || editing || jobs.some(j=>j.status==="running")} onClick={async()=>{setBusy(true);try{await api(`/recordings/${job.id}/analyze`,{});await refresh();setMessage("Análise iniciada. A área e a escala deste perfil serão usadas.");}catch(e){setMessage((e as Error).message);}finally{setBusy(false);}}}>Analisar todos os quadros</button>
        {job.status==="running" && <button onClick={()=>api(`/recordings/${job.id}/cancel`,{}).then(()=>setMessage("Encerrando análise; resultados parciais serão preservados.")).catch(e=>setMessage(e.message))}>Encerrar análise</button>}</div>
      {job.status==="running" && <div className="recording-progress"><progress max={job.sourceFrames} value={job.framesProcessed ?? 0}/><span>{job.framesProcessed ?? 0} / {job.sourceFrames} quadros analisados</span></div>}
      {(job.error || job.calibrationWarning) && <p className="error" role="alert">{job.error || job.calibrationWarning}</p>}
      {job.statistics && <div className="recording-metrics"><span>{job.statistics.impacts} contatos</span><span>{job.statistics.inside} dentro</span><span>{job.statistics.outside} fora</span><span>{job.statistics.inconclusive} para revisar</span></div>}
      {!!events.length && <details><summary>Revisar impactos detectados ({events.length})</summary>{events.map(e=><div key={e.id} className="list-row"><span>{new Date(e.timestamp*1000).toLocaleTimeString("pt-BR")} · {e.inOrOut} · {number(e.speedBefore)} km/h estimados</span><button onClick={()=>onReview(e)}>Revisar contato</button></div>)}</details>}
      {job.status!=="running" && <a href={`/api/recordings/${job.id}/original`} className="button-link">Baixar original preservado</a>}
    </>}
    {message && <p role="status">{message}</p>}
  </section>;
}
