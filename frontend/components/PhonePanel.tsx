"use client";
import { useEffect, useState } from "react";
import { Play, Smartphone } from "lucide-react";
import { api } from "../lib/types";

export function PhonePanel({ running }: { running: boolean }) {
  const [phone, setPhone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  useEffect(() => {
    const enabled = Boolean(window.tennisPhonePanel) || new URLSearchParams(location.search).get("phone") === "1";
    setPhone(enabled);
    if (enabled) document.body.classList.add("phone-panel");
    return () => document.body.classList.remove("phone-panel");
  }, []);
  if (!phone) return null;
  return <section className="phone-control-card">
    <div><Smartphone size={20} /><strong>{running ? "A mesma sessão do seu PC" : "Pronto para analisar seu treino"}</strong></div>
    <p>{running ? "Arraste a área sobre o vídeo ou ajuste os cantos. As alterações são salvas para esta câmera e aparecem no Windows." : "Primeiro toque em Transmitir na aba Câmera. Depois, inicie a análise aqui."}</p>
    {!running && <button className="primary" disabled={busy} onClick={async () => {
      setBusy(true); setMessage("");
      try { await api("/mobile-camera/connect?platform=ios", {}); setMessage("Análise iniciada. Aguarde os primeiros quadros."); }
      catch (error) { setMessage(error instanceof Error ? error.message : "Confira a conexão do PC."); }
      finally { setBusy(false); }
    }}><Play size={16}/>{busy ? "Conectando…" : "Analisar câmera do iPhone"}</button>}
    <small>O processamento acontece no PC. Para ouvir as decisões, toque em Testar som OUT nos controles do vídeo.</small>
    {message && <p role="status">{message}</p>}
  </section>;
}
