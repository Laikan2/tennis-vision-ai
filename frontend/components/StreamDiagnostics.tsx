"use client";
import { Activity, ChevronDown } from "lucide-react";
import { number, Status } from "../lib/types";

export function StreamDiagnostics({ status }: { status: Status }) {
  const feeds = status.cameraFeeds?.length ? status.cameraFeeds : [status];
  return <details className="stream-diagnostics">
    <summary><span><Activity size={16} /> Sinal e desempenho</span>
      <span>{status.running ? `${feeds.length} câmera${feeds.length > 1 ? "s" : ""} em análise` : "Aguardando sessão"} <ChevronDown size={15} /></span>
    </summary>
    <div className="stream-diagnostics-body">
      <div className="stream-diagnostics-grid">
        {feeds.map((feed, index) => {
          const online = feed.cameraStatus === "online";
          const warning = online && (feed.analysisStale || (feed.captureGapP95Ms ?? 0) > 100);
          return <section className="stream-health-card" key={feed.cameraId ?? index}>
            <header><strong>{feed.cameraName || `Câmera ${index + 1}`}</strong>
              <span className={warning ? "health-warning" : online ? "health-online" : "health-offline"}>
                {warning ? "Verificar sinal" : online ? "Recebendo vídeo" : "Sem sinal"}
              </span>
            </header>
            <dl>
              <div><dt>Recebidos no PC</dt><dd>{number(feed.cameraFps)} <small>fps</small></dd></div>
              <div><dt>Analisados pela IA</dt><dd>{number(feed.processingFps)} <small>fps</small></dd></div>
              <div><dt>Análise completa</dt><dd>{number(feed.processingMs)} <small>ms</small></dd></div>
              <div><dt>Fila + análise</dt><dd>{number(feed.frameLatencyMs)} <small>ms</small></dd></div>
              <div><dt>Codificação da prévia</dt><dd>{number(feed.jpegEncodingMs)} <small>ms</small></dd></div>
              <div><dt>Intervalo entre quadros · P95</dt><dd>{number(feed.captureGapP95Ms)} <small>ms</small></dd></div>
              <div><dt>Pose · {feed.poseDevice || "CPU"}</dt><dd>{number(feed.poseInferenceMs)} <small>ms</small></dd></div>
            </dl>
            {feed.acceleration?.validated && <small>GPU validada com os mesmos pesos · {number(feed.acceleration.speedup)}× na inferência de pose.</small>}
            <p>{!online ? "Inicie a transmissão no celular e confira a conexão."
              : feed.analysisStale ? "A análise está atrasada. As marcações antigas foram ocultadas; o vídeo continua independente."
              : feed.sourceTimingAvailable ? "Movimento calculado com o relógio do vídeo."
              : "Câmera sem relógio de vídeo disponível. Oscilações de rede podem afetar a velocidade."}</p>
          </section>;
        })}
      </div>
      <p className="diagnostics-note">FPS da câmera, da IA e da reprodução podem ser diferentes. Estes tempos medem o trabalho no PC; o atraso total desde a captura no celular ainda não está medido. Para conferir o contorno quadro a quadro, ative “Alinhar vídeo à IA”. O vídeo direto prioriza fluidez e pode apresentar diferença de tempo nas marcações.</p>
    </div>
  </details>;
}
