"use client";
import { useEffect, useRef, useState, PointerEvent } from "react";
import { createPortal } from "react-dom";
import { AnalysisMode, api, Status, TargetZone } from "../lib/types";

type XY = [number, number];
type Mode = "corners" | "rectangle" | "court" | "floor" | null;
type Saved = {
  captureRegion?: Status["captureRegion"];
  capturePolygon?: XY[] | null;
  floorBoundary?: XY[] | null;
  analysisMode?: AnalysisMode;
  targetZone?: TargetZone;
};

const singlesInset = 1.37 / 10.97;
const serviceLine = .5 - 6.4 / 23.77;
const zones: Record<TargetZone, { label: string; bounds: [number, number, number, number] }> = {
  full: { label: "Quadra de duplas inteira", bounds: [0, 0, 1, 1] },
  singles: { label: "Quadra de simples inteira", bounds: [singlesInset, 0, 1 - singlesInset, 1] },
  far_half: { label: "Metade do fundo", bounds: [0, 0, 1, .5] },
  near_half: { label: "Metade próxima", bounds: [0, .5, 1, 1] },
  far_left_service: { label: "Saque fundo · esquerda", bounds: [singlesInset, serviceLine, .5, .5] },
  far_right_service: { label: "Saque fundo · direita", bounds: [.5, serviceLine, 1 - singlesInset, .5] },
  near_left_service: { label: "Saque próximo · esquerda", bounds: [singlesInset, .5, .5, 1 - serviceLine] },
  near_right_service: { label: "Saque próximo · direita", bounds: [.5, .5, 1 - singlesInset, 1 - serviceLine] },
};

function project(points: XY[], u: number, v: number): XY {
  const [tl, tr, br, bl] = points;
  // Exact projective transform from the normalized court to the four points.
  // This mirrors OpenCV's homography used by the backend for IN/OUT, so the
  // highlighted service box is the same region that makes the decision.
  const dx1=tr[0]-br[0],dx2=bl[0]-br[0],dx3=tl[0]-tr[0]+br[0]-bl[0];
  const dy1=tr[1]-br[1],dy2=bl[1]-br[1],dy3=tl[1]-tr[1]+br[1]-bl[1];
  let g=0,h=0;
  const denominator=dx1*dy2-dx2*dy1;
  if (Math.abs(dx3)+Math.abs(dy3)>.000001 && Math.abs(denominator)>.000001) {
    g=(dx3*dy2-dx2*dy3)/denominator;
    h=(dx1*dy3-dx3*dy1)/denominator;
  }
  const a=tr[0]-tl[0]+g*tr[0],b=bl[0]-tl[0]+h*bl[0];
  const d=tr[1]-tl[1]+g*tr[1],e=bl[1]-tl[1]+h*bl[1];
  const scale=g*u+h*v+1;
  return [(a*u+b*v+tl[0])/scale,(d*u+e*v+tl[1])/scale];
}

export function CaptureArea({ status, cameraId, width, height, onEditing, controlsMount, locked = false }: {
  status: Status; cameraId?: string; width: number; height: number; onEditing?: (editing: boolean) => void; controlsMount?: HTMLElement | null; locked?: boolean;
}) {
  const [mode, setMode] = useState<Mode>(null);
  const [draft, setDraft] = useState<XY[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [local, setLocal] = useState<Saved | null>(null);
  const [showLimit, setShowLimit] = useState(true);
  const [environment, setEnvironment] = useState(status.analysisMode || "room");
  const [targetZone, setTargetZone] = useState<TargetZone>(status.targetZone || "full");
  const drag = useRef<{ start: XY; corner?: number; original: XY[] } | null>(null);

  useEffect(() => {
    setMode(null); setDraft([]); setLocal(null); setError("");
    setShowLimit(Boolean(status.capturePolygon?.length || status.captureRegion || status.floorBoundary?.length));
    setEnvironment(status.analysisMode || "room");
    setTargetZone(status.targetZone || "full");
    onEditing?.(false);
  }, [status.session?.id, cameraId]);
  useEffect(() => {
    setLocal(null); setEnvironment(status.analysisMode || "room");
    setTargetZone(status.targetZone || "full");
    if (status.capturePolygon?.length || status.captureRegion || status.floorBoundary?.length) setShowLimit(true);
  }, [status.regionChangedAt, status.analysisMode, status.targetZone]);

  const current = local || status;
  const rect = current.captureRegion;
  const saved: XY[] = current.capturePolygon || (rect ? [[rect[0],rect[1]],[rect[2],rect[1]],[rect[2],rect[3]],[rect[0],rect[3]]] : []);
  const editingFloor = mode === "floor";
  const points = mode && !editingFloor ? draft : saved;
  const floorPoints = editingFloor ? draft : current.floorBoundary || [];
  const handles = editingFloor ? floorPoints : points;
  const wall = environment === "room";
  const sortedFloor = [...floorPoints].sort((a,b)=>a[0]-b[0]);
  const validFloor = sortedFloor.length >= 2 && sortedFloor.at(-1)![0]-sortedFloor[0][0] >= .08 && sortedFloor.slice(1).every((p,i)=>p[0]-sortedFloor[i][0]>=.02);
  const valid = editingFloor ? validFloor : points.length === 4 && Math.abs(points.reduce((sum,p,i) => {
    const next=points[(i+1)%4]; return sum+p[0]*next[1]-next[0]*p[1];
  },0)) > .004;

  function start(next: Mode) {
    setMode(next);
    if (next === "court") {
      setEnvironment("court");
      setDraft(saved.length === 4 ? saved : [[.18,.18],[.82,.18],[.96,.9],[.04,.9]]);
    } else if (next === "floor") {
      setDraft(current.floorBoundary || []);
    } else {
      setDraft(next === "corners" ? saved : []);
    }
    setError(""); onEditing?.(true);
  }
  function cancel() { setMode(null); drag.current=null; setError(""); onEditing?.(false); }
  function point(e: PointerEvent<SVGSVGElement>): XY {
    const p=e.currentTarget.createSVGPoint(); p.x=e.clientX; p.y=e.clientY;
    const matrix=e.currentTarget.getScreenCTM(); const q=matrix ? p.matrixTransform(matrix.inverse()) : p;
    return [Math.max(0,Math.min(1,q.x/width)),Math.max(0,Math.min(1,q.y/height))];
  }
  function move(e: PointerEvent<SVGSVGElement>) {
    if (!drag.current) return;
    const p=point(e), d=drag.current;
    if (d.corner != null) setDraft(d.original.map((old,i) => i===d.corner ? p : old));
    else if (mode === "rectangle") {
      const x=Math.min(p[0],d.start[0]),y=Math.min(p[1],d.start[1]);
      const r=Math.max(p[0],d.start[0]),b=Math.max(p[1],d.start[1]);
      setDraft([[x,y],[r,y],[r,b],[x,b]]);
    }
  }
  async function save(polygon: XY[] | null, nextEnvironment = environment, nextZone = targetZone) {
    setSaving(true); setError("");
    const appliedZone: TargetZone = nextEnvironment === "court" ? nextZone : "full";
    try {
      const result=await api<Saved>("/capture-region", {
        cameraId: cameraId || status.cameraId || status.session?.cameraId,
        polygon, region:null, analysisMode:nextEnvironment, targetZone:appliedZone,
      });
      setLocal({...current,...result}); setEnvironment(nextEnvironment); setTargetZone(result.targetZone || appliedZone);
      setShowLimit(Boolean(polygon?.length || current.floorBoundary?.length));
      cancel();
    } catch(e) { setError(e instanceof Error ? e.message : "Não foi possível salvar."); }
    finally { setSaving(false); }
  }
  async function saveFloor(boundary: XY[] | null) {
    setSaving(true); setError("");
    try {
      const id = cameraId || status.cameraId || status.session?.cameraId;
      if (!id) throw new Error("Selecione uma câmera antes de marcar o piso.");
      const result=await api<Saved>(`/cameras/${encodeURIComponent(id)}/floor-boundary`,{boundary});
      setLocal({...current,floorBoundary:result.floorBoundary});
      setShowLimit(Boolean(boundary?.length || saved.length)); cancel();
    } catch(e) { setError(e instanceof Error ? e.message : "Não foi possível salvar o limite do piso."); }
    finally { setSaving(false); }
  }

  const courtVisible = environment === "court" && points.length === 4 && Boolean(mode || showLimit);
  const line = (u1:number,v1:number,u2:number,v2:number,key:string) => {
    const a=project(points,u1,v1),b=project(points,u2,v2);
    return <line key={key} x1={a[0]*width} y1={a[1]*height} x2={b[0]*width} y2={b[1]*height}
      stroke="#efffea" strokeWidth="2" strokeOpacity=".78" vectorEffect="non-scaling-stroke" pointerEvents="none" />;
  };
  const globalBounds=zones[targetZone].bounds;
  const selectedBounds: [number,number,number,number] =
    status.courtOrientation === "opposite"
      ? [1-globalBounds[2],1-globalBounds[3],1-globalBounds[0],1-globalBounds[1]]
      : globalBounds;
  const selectedCourtPoints=courtVisible ? [
    project(points,selectedBounds[0],selectedBounds[1]), project(points,selectedBounds[2],selectedBounds[1]),
    project(points,selectedBounds[2],selectedBounds[3]), project(points,selectedBounds[0],selectedBounds[3]),
  ] : [];
  const extrapolate = (x:number,a:XY,b:XY):XY=>[x,a[1]+(x-a[0])*(b[1]-a[1])/(b[0]-a[0])];
  const floorEnds: XY[] = validFloor ? [extrapolate(0,sortedFloor[0],sortedFloor[1]),...sortedFloor,
    extrapolate(1,sortedFloor.at(-2)!,sortedFloor.at(-1)!)] : [];

  return <>
    <svg className="video-overlay target-overlay" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="xMidYMid meet"
      style={{zIndex:5,visibility:status.resolution ? "visible" : "hidden",pointerEvents:mode && !saving ? "auto" : "none",touchAction:"none",cursor:mode ? "crosshair" : "default"}}
      aria-label={editingFloor ? "Limite do piso: pontos onde o chão encontra a parede" : "Marcar superfície: quatro cantos em volta da parede ou quadra"} tabIndex={mode ? 0 : -1}
      onKeyDown={e => { if(e.key === "Escape") cancel(); }}
      onPointerDown={e => {
        if(e.button!==0 || !mode || saving) return;
        const p=point(e); const target=e.target as SVGElement;
        const corner=target.dataset.corner == null ? undefined : +target.dataset.corner;
        if(corner!=null || mode==="rectangle") { e.currentTarget.setPointerCapture(e.pointerId); drag.current={start:p,corner,original:draft}; }
        else if(draft.length<(editingFloor ? 6 : 4)) setDraft([...draft,p]);
      }} onPointerMove={move}
      onPointerUp={e => {move(e);drag.current=null;if(e.currentTarget.hasPointerCapture(e.pointerId))e.currentTarget.releasePointerCapture(e.pointerId);}}
      onPointerCancel={() => {drag.current=null;}}>
      {(mode || showLimit) && points.length>0 && <>
        {points.length===4 && <polygon points={points.map(p=>`${p[0]*width},${p[1]*height}`).join(" ")} fill="#8dcaff" fillOpacity={mode ? .09 : .04} stroke="#b9e4ff" strokeWidth="3" strokeDasharray={mode ? undefined : "12 8"} vectorEffect="non-scaling-stroke" />}
        {courtVisible && <g className="court-template" pointerEvents="none">
          <polygon points={selectedCourtPoints.map(p=>`${p[0]*width},${p[1]*height}`).join(" ")} fill="#c9f56b" fillOpacity=".17" stroke="#c9f56b" strokeWidth="2" vectorEffect="non-scaling-stroke" />
          {line(singlesInset,0,singlesInset,1,"single-left")}
          {line(1-singlesInset,0,1-singlesInset,1,"single-right")}
          {line(0,.5,1,.5,"net")}
          {line(singlesInset,serviceLine,1-singlesInset,serviceLine,"service-far")}
          {line(singlesInset,1-serviceLine,1-singlesInset,1-serviceLine,"service-near")}
          {line(.5,serviceLine,.5,1-serviceLine,"service-centre")}
        </g>}
        {points.length<4 && <polyline points={points.map(p=>`${p[0]*width},${p[1]*height}`).join(" ")} fill="none" stroke="#b9e4ff" strokeWidth="3" />}
      </>}
      {environment === "court" && (mode || showLimit) && floorPoints.length > 0 && <g className="floor-boundary">
        {floorEnds.length >= 2 && <>
          <polyline points={floorEnds.map(p=>`${p[0]*width},${p[1]*height}`).join(" ")} fill="none"
            stroke="#ffd28d" strokeWidth="3" strokeDasharray={editingFloor ? undefined : "10 6"} vectorEffect="non-scaling-stroke" pointerEvents="none"/>
          <text x={Math.min(...floorPoints.map(p=>p[0]))*width+12} y={Math.min(...floorPoints.map(p=>p[1]))*height-14}
            fill="#ffd28d" stroke="#132021" strokeWidth="5" paintOrder="stroke" fontSize={Math.max(18,width/85)} pointerEvents="none">LIMITE DO PISO</text>
        </>}
      </g>}
      {mode && handles.map((p,i)=><g key={i}>
          <circle data-corner={i} cx={p[0]*width} cy={p[1]*height} r={Math.max(14,width/70)} fill="#17384b" stroke="#e4f6ff" strokeWidth="3"
            tabIndex={0} role="slider" aria-label={`${editingFloor ? "Ponto do piso" : "Canto"} ${i+1}; use as setas para ajustar`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(p[0]*100)}
            style={{cursor:"grab"}} onKeyDown={e=>{
              const direction:Record<string,XY>={ArrowLeft:[-.005,0],ArrowRight:[.005,0],ArrowUp:[0,-.005],ArrowDown:[0,.005]};
              const delta=direction[e.key]; if(!delta)return;e.preventDefault();e.stopPropagation();
              setDraft(d=>d.map((q,j)=>j===i ? [Math.max(0,Math.min(1,q[0]+delta[0])),Math.max(0,Math.min(1,q[1]+delta[1]))] : q));
            }} />
          <text x={p[0]*width} y={p[1]*height+6} fill="white" fontSize={Math.max(18,width/100)} textAnchor="middle" pointerEvents="none">{i+1}</text>
        </g>)}
    </svg>
    {controlsMount && createPortal(<div className={`capture-area-controls surface-tools ${mode ? "is-editing" : ""}`}>
      {mode ? <>
        <strong>{editingFloor ? "Separar chão da parede" : mode === "court" ? "Modelo da quadra" : wall ? "Parede" : environment === "court" ? "Quadra" : "Área de rastreamento"} · imagem pausada</strong>
        <span>{editingFloor ? `${draft.length} pontos · mínimo 2. Clique no rodapé à esquerda e à direita. Se a parede fizer uma esquina, marque também o canto. Arraste os círculos para ajustar; até 6 pontos.` : mode==="rectangle" ? "Clique e arraste para desenhar a área. Depois, ajuste os cantos." : mode==="court" ? "Arraste os quatro círculos para os cantos externos da quadra. As linhas internas acompanham a perspectiva." : draft.length<4 ? `${draft.length}/4 cantos. Comece no alto à esquerda e siga ao redor da superfície.` : "Arraste os quatro círculos até os cantos. Pode incluir a parede atrás de você."}</span>
        {editingFloor && <p className="surface-explanation">O piso fica abaixo desta linha. A bola continua sendo acompanhada no ar; um contato acima da linha não conta como quicada no chão. A área de IN/OUT permanece salva.</p>}
        <div><button className="primary" disabled={!valid||saving} onClick={()=>editingFloor ? saveFloor(draft) : save(draft,mode === "court" ? "court" : environment)}>{saving ? "Salvando…" : editingFloor ? "Salvar limite do piso" : mode === "court" ? "Usar esta quadra" : "Usar esta superfície"}</button>
          {editingFloor && draft.length>0 && <button disabled={saving} onClick={()=>setDraft(draft.slice(0,-1))}>Remover último ponto</button>}
          <button disabled={saving} onClick={()=>setDraft(mode === "court" ? [[.18,.18],[.82,.18],[.96,.9],[.04,.9]] : [])}>Recomeçar</button><button disabled={saving} onClick={cancel}>Cancelar</button></div>
      </> : <>
        <button className={!saved.length ? "selected" : ""} disabled={locked||!status.session||saving} onClick={()=>save(null,environment,"full")}>Imagem inteira</button>
        {environment === "court" ? <button disabled={locked||!status.session||status.stale||saving} onClick={()=>start("court")}>{saved.length ? "Ajustar modelo da quadra" : "Encaixar modelo da quadra"}</button> :
          <button disabled={locked||!status.session||status.stale||saving} onClick={()=>start("corners")}>{saved.length ? "Ajustar 4 cantos" : "Marcar 4 cantos"}</button>}
        <button disabled={locked||!status.session||status.stale||saving} onClick={()=>start("rectangle")}>
          {environment === "court" ? "Arrastar chão" : environment === "room" ? "Arrastar parede" : "Arrastar área"}
        </button>
        {environment === "court" && saved.length>0 && <select className="court-zone-select" aria-label="Zona válida da quadra" value={targetZone} disabled={locked||saving} onChange={e=>save(saved,"court",e.target.value as TargetZone)}>
          {Object.entries(zones).map(([value,zone])=><option key={value} value={value}>{zone.label}</option>)}
        </select>}
        {environment === "court" && <button className="floor-boundary-button" disabled={locked||!status.session||status.stale||saving} onClick={()=>start("floor")}>{floorPoints.length ? "Ajustar limite do piso" : "Separar chão da parede"}</button>}
        {environment === "court" && floorPoints.length>0 && <button disabled={locked||!status.session||saving} onClick={()=>saveFloor(null)}>Remover limite do piso</button>}
        {(saved.length>0 || environment === "court" && floorPoints.length>0) && <button aria-pressed={showLimit} onClick={()=>setShowLimit(!showLimit)}>{showLimit ? "Ocultar linhas" : "Mostrar linhas"}</button>}
        <span className="surface-hint">{
          environment === "tracking"
            ? "Modo de teste: acompanha pessoa e bola, mas não cria marca, estatística nem som OUT."
            : saved.length
              ? environment === "court"
                ? `Chão salvo · ${zones[targetZone].label}. Só uma quicada medida, com a bola descendo e subindo, pode gerar IN/OUT.`
                : "Parede salva. Um impacto exige trajetória rápida fora do corpo; apenas atravessar a área não conta."
              : environment === "court"
                ? "Marque o chão visível com quatro cantos. A bola é procurada na imagem inteira e só o contato com o chão conta."
                : "Área opcional: use a imagem inteira, arraste um retângulo ou encaixe quatro cantos com perspectiva."
        }</span>
      </>}
      {error && <span role="alert">{error}</span>}
      {!mode && environment !== "tracking" && <p className="surface-explanation">
        A área define o que vale dentro ou fora; a bola continua sendo procurada na imagem inteira.
        Calibre medidas reais para projetar posições em metros. Velocidade pelo tamanho da bola é uma estimativa.
        Com duas câmeras, marque a mesma superfície física, na mesma ordem, para comparar os contatos.
        {environment === "court" && (floorPoints.length ? " Limite do piso salvo para esta câmera. Ele separa o chão da parede e exige trajetória de descida e retorno." : " No quarto, use Separar chão da parede para evitar contar uma batida na porta como quicada no chão.")}
      </p>}
    </div>, controlsMount)}
  </>;
}
