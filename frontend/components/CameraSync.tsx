"use client";
import { useEffect, useState } from "react";
import { Link2, Plus } from "lucide-react";
import { api, Camera, number, Replay, ReplayFrame, Status } from "../lib/types";
import { ReplayPlayer } from "./Visuals";
type Anchor={referenceReplayId:string;referenceFrame:number;secondaryReplayId:string;secondaryFrame:number};
type Sync={referenceCameraId:string;secondaryCameraId:string;offsetSeconds:number;jitterMs:number;frameUncertaintyMs:number;quality:string};
export function CameraSync({cameras,status}:{cameras:Camera[];status:Status}) {
  const [clips,setClips]=useState<Replay[]>([]),[a,setA]=useState(""),[b,setB]=useState(""),[fa,setFa]=useState<ReplayFrame>(),[fb,setFb]=useState<ReplayFrame>(),
    [anchors,setAnchors]=useState<Anchor[]>([]),[syncs,setSyncs]=useState<Sync[]>([]),[message,setMessage]=useState(""),[busy,setBusy]=useState(false);
  async function refresh(){try{setClips(await api<Replay[]>("/replays"));setSyncs(await api<Sync[]>("/camera-sync"));}catch(e){setMessage((e as Error).message);}}
  useEffect(()=>{refresh();},[]);
  const ca=clips.find(c=>c.id===a),cb=clips.find(c=>c.id===b);
  const compatible=!!(ca && cb && ca.cameraId && cb.cameraId && ca.cameraId!==cb.cameraId && ca.sessionId===cb.sessionId);
  return <section className="panel padded camera-sync">
    <h2><Link2 size={20}/> Sincronizar duas câmeras</h2><p>Grave um gesto curto visível nas duas câmeras. Escolha o mesmo instante em cada replay e adicione três referências de gestos diferentes.</p>
    {syncs.map((s,i)=><p className="calibration-why" key={i}>{cameras.find(c=>c.id===s.secondaryCameraId)?.name}: {number(s.offsetSeconds*1000,0)} ms de diferença · variação {number(s.jitterMs,0)} ms · {s.quality}</p>)}
    <div className="controls"><button disabled={busy || (status.cameraCount ?? 0)<2 || status.stale} onClick={async()=>{setBusy(true);try {
      await api("/replays",{allCameras:true});setMessage("Trechos iniciados. Aguarde três segundos após o gesto e toque em Atualizar trechos.");
    }catch(e){setMessage((e as Error).message);}finally{setBusy(false);}}}>Gravar referência nas duas câmeras</button><button onClick={refresh}>Atualizar trechos</button></div>
    <div className="sync-views">{[0,1].map(i=><div key={i}><label>{i===0?"Câmera de referência":"Segunda câmera"}<select value={i===0?a:b} onChange={e=>{(i===0?setA:setB)(e.target.value);(i===0?setFa:setFb)(undefined);setAnchors([]);}}>
      <option value="">Escolha um trecho</option>{clips.filter(c=>c.cameraId).slice(0,80).map(c=><option key={c.id} value={c.id}>{cameras.find(cam=>cam.id===c.cameraId)?.name} · {new Date((c.triggerTimestamp ?? c.createdAt)*1000).toLocaleTimeString("pt-BR")}</option>)}</select></label>
      {(i===0?ca:cb) && <ReplayPlayer key={(i===0?ca:cb)!.id} replay={(i===0?ca:cb)!} onFrame={i===0?setFa:setFb}/>}</div>)}</div>
    <div className="controls"><button disabled={!compatible || !fa || !fb || anchors.length>=10} onClick={()=>{const next={referenceReplayId:a,referenceFrame:fa!.index,secondaryReplayId:b,secondaryFrame:fb!.index};
      if(!anchors.some(x=>JSON.stringify(x)===JSON.stringify(next)))setAnchors([...anchors,next]);}}><Plus size={16}/> Adicionar instante correspondente</button>
      <span>{anchors.length} referência{anchors.length!==1?"s":""}</span><button onClick={()=>setAnchors([])} disabled={!anchors.length}>Recomeçar</button>
      <button className="primary" disabled={busy || !anchors.length || !compatible} onClick={async()=>{setBusy(true);try{const result=await api<Sync>("/camera-sync",{referenceCameraId:ca!.cameraId,secondaryCameraId:cb!.cameraId,anchors});
        setMessage(`Sincronização salva: ${number(result.offsetSeconds*1000,0)} ms. Precisão temporal dos quadros: aproximadamente ${number(result.frameUncertaintyMs,0)} ms.`);await refresh();
      }catch(e){setMessage((e as Error).message);}finally{setBusy(false);}}}>Salvar sincronização</button></div>
    {message && <p role="status">{message}</p>}<small>Recalibre se mudar a rede ou a transmissão. Esta compensação ajuda a unir eventos; não cria triangulação 3D nem garante decisões próximas à linha.</small>
  </section>;
}
