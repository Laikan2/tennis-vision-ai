#if canImport(Network)
import Foundation
import Network

public enum PublisherState: Equatable {
    case connecting, publishing, reconnecting(Int), stopped, failed(String)
}

/// A bounded, local RTSP/TCP publisher. It never sends H.264 predicted frames after a queue discard.
public final class RTSPPublisher {
    public var onState: ((PublisherState) -> Void)?
    public var onSent: ((Int, Double) -> Void)?
    public var requestKeyframe: (() -> Void)?
    public let outbox = FrameQueue()
    private let queue = DispatchQueue(label:"ai.tennisvision.ios.network",qos:.userInitiated)
    private var connection: NWConnection?
    private var decoder = RTSPDecoder()
    private var packetizer = RTPPacketizer()
    private var configuration: PairingConfiguration?
    private var sps = Data(), pps = Data()
    private var sequence = 0
    private var pending = [Int:(String,Double)]()
    private var sessionID: String?
    private var connected = false
    private var publishing = false
    private var stopped = true
    private var sending = false
    private var sendStarted = 0.0
    private var lastKeepalive = 0.0
    private var connectStarted = 0.0
    private var origin: Double?
    private var retry = 0
    private var generation = UUID()
    private var timer: DispatchSourceTimer?
    public init() {}
    deinit { timer?.cancel(); connection?.cancel() }

    public func start(configuration: PairingConfiguration, sps: Data, pps: Data) {
        queue.async { [weak self] in
            guard let self else{return}
            self.stopInternal()
            self.configuration=configuration; self.sps=sps; self.pps=pps
            self.stopped=false; self.retry=0
            self.timer=DispatchSource.makeTimerSource(queue:self.queue)
            self.timer?.schedule(deadline:.now()+0.25,repeating:0.25)
            self.timer?.setEventHandler { [weak self] in self?.watchdog() }
            self.timer?.resume()
            self.connect()
        }
    }
    public func stop() { queue.async{[weak self] in self?.stopInternal()} }
    public func enqueue(_ frame: EncodedFrame) {
        let offered=outbox.offer(frame)
        if offered.needsKeyframe { requestKeyframe?() }
        if offered.accepted { queue.async{[weak self] in self?.pump()} }
    }
    private func stopInternal() {
        stopped=true; publishing=false; connected=false; generation=UUID()
        timer?.cancel(); timer=nil; connection?.cancel(); connection=nil
        pending.removeAll(); outbox.reset(); sending=false; origin=nil
        onState?(.stopped)
    }
    private func connect() {
        guard !stopped, let config=configuration, let port=NWEndpoint.Port(rawValue:UInt16(config.port)) else{return}
        generation=UUID(); let current=generation
        connectStarted=ProcessInfo.processInfo.systemUptime
        connection?.cancel(); connected=false; publishing=false; sending=false; origin=nil
        decoder=RTSPDecoder(); packetizer=RTPPacketizer(); pending.removeAll(); sessionID=nil; outbox.reset()
        let parameters=NWParameters.tcp
        if let tcp=parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options { tcp.noDelay=true }
        let peer=NWConnection(host:NWEndpoint.Host(config.host),port:port,using:parameters)
        connection=peer; onState?(retry == 0 ? .connecting : .reconnecting(retry))
        peer.stateUpdateHandler = { [weak self] state in
            guard let self, self.generation==current, !self.stopped else{return}
            switch state {
            case .ready: self.connected=true; self.receive(current); self.control("OPTIONS")
            case .failed: self.recover("Não foi possível conectar ao PC. Confira o Wi-Fi, o IP e Preparar rede do PC.")
            case .waiting: self.recover("A rede local está indisponível. Confira a permissão Rede local do iPhone.")
            default: break
            }
        }
        peer.start(queue:queue)
    }
    private func receive(_ current: UUID) {
        connection?.receive(minimumIncompleteLength:1,maximumLength:16_384) { [weak self] data,_,ended,error in
            guard let self, self.generation==current, !self.stopped else{return}
            if let data {
                do { for response in try self.decoder.append(data) { try self.response(response) } }
                catch { self.fail(error.localizedDescription); return }
            }
            if ended || error != nil { self.recover("A conexão com o computador foi interrompida."); return }
            self.receive(current)
        }
    }
    private func control(_ method: String) {
        guard connected, !stopped, let config=configuration, let peer=connection else{return}
        sequence+=1; let number=sequence; var headers=[String:String](); var body=""; var url=config.rtspURL
        if method == "ANNOUNCE" {
            do { body=try RTSPMessages.sdp(sps:sps,pps:pps,fps:config.profile.fps) }
            catch { fail(error.localizedDescription); return }
            headers["Content-Type"]="application/sdp"
        } else if method == "SETUP" {
            url+="/trackID=0"; headers["Transport"]="RTP/AVP/TCP;unicast;interleaved=0-1;mode=record"
        }
        if let sessionID { headers["Session"]=sessionID }
        if method == "RECORD" { headers["Range"]="npt=0.000-" }
        pending[number]=(method,ProcessInfo.processInfo.systemUptime)
        let current=generation
        peer.send(content:RTSPMessages.request(method,url:url,sequence:number,headers:headers,body:body),
                  completion:.contentProcessed{[weak self] error in
            guard let self, self.generation==current, !self.stopped else{return}
            if error != nil { self.recover("Falha ao enviar a configuração de vídeo ao PC.") }
        })
    }
    private func response(_ response: RTSPResponse) throws {
        guard let (method,_)=pending.removeValue(forKey:response.sequence) else {
            throw CameraError.invalid("O receptor respondeu a uma configuração antiga.")
        }
        guard response.status == 200 else {
            if response.status == 401 || response.status == 403 {
                throw CameraError.invalid("O receptor exige autenticação. Use o QR local gerado pelo Tennis Vision Studio.")
            }
            throw CameraError.invalid("O receptor recusou \(method) (\(response.status)). Confira o caminho da câmera.")
        }
        if let session=response.headers["session"]?.split(separator:";").first {
            guard !session.isEmpty else { throw CameraError.invalid("Sessão RTSP vazia.") }
            sessionID=String(session)
        }
        switch method {
        case "OPTIONS": if !publishing { control("ANNOUNCE") }
        case "ANNOUNCE": control("SETUP")
        case "SETUP":
            guard sessionID != nil,
                  response.headers["transport"]?.lowercased().contains("interleaved=0-1") == true else {
                throw CameraError.invalid("O PC não confirmou o transporte de vídeo solicitado.")
            }
            control("RECORD")
        case "RECORD":
            publishing=true; retry=0; lastKeepalive=ProcessInfo.processInfo.systemUptime
            outbox.reset(); requestKeyframe?(); onState?(.publishing)
        default: break
        }
    }
    private func pump() {
        guard publishing, !stopped, !sending, let peer=connection else{return}
        guard let frame=outbox.take(now:ProcessInfo.processInfo.systemUptime) else {
            if outbox.needsKeyframe { requestKeyframe?() }; return
        }
        if origin == nil { origin=frame.pts }
        let stamp: UInt32
        do { stamp=try RTPPacketizer.timestamp(pts:frame.pts,origin:origin!) }
        catch { fail(error.localizedDescription); return }
        // VideoToolbox may refresh parameter sets. A recovered IDR must carry
        // the sets from that access unit, not the first frame of the session.
        let headers = frame.keyframe ? [frame.sps.isEmpty ? sps : frame.sps, frame.pps.isEmpty ? pps : frame.pps] : []
        let data=RTPPacketizer.interleave(packetizer.packets(nals:headers+frame.nals,timestamp:stamp))
        sending=true; sendStarted=ProcessInfo.processInfo.systemUptime
        let current=generation
        peer.send(content:data,completion:.contentProcessed{[weak self] error in
            guard let self, self.generation==current, !self.stopped else{return}
            self.sending=false
            if error != nil { self.recover("O Wi-Fi interrompeu o envio do vídeo."); return }
            self.onSent?(data.count,ProcessInfo.processInfo.systemUptime); self.pump()
        })
    }
    private func watchdog() {
        guard !stopped else{return}
        let now=ProcessInfo.processInfo.systemUptime
        if !connected && connection != nil && now-connectStarted > 8 {
            recover("O PC não aceitou a conexão. Confira o IP e Preparar rede do PC."); return
        }
        if pending.values.contains(where:{now-$0.1 > 6}) {
            recover("O receptor não respondeu. Abra o programa Windows e prepare a rede."); return
        }
        if sending && now-sendStarted > 1 {
            recover("O envio ficou parado; reconectando para descartar o atraso."); return
        }
        if publishing && now-lastKeepalive >= 15 && pending.isEmpty {
            lastKeepalive=now; control("OPTIONS")
        }
        pump()
    }
    private func recover(_ reason: String) {
        guard !stopped else{return}
        generation=UUID(); let current=generation
        connection?.cancel(); connection=nil; connected=false; publishing=false; sending=false
        pending.removeAll(); outbox.reset(); retry+=1
        guard retry <= 3 else { fail(reason); return }
        onState?(.reconnecting(retry))
        queue.asyncAfter(deadline:.now()+Double(retry)) { [weak self] in
            guard let self, !self.stopped, self.generation==current else{return}
            self.connect()
        }
    }
    private func fail(_ reason: String) {
        stopInternal(); onState?(.failed(reason))
    }
}
#endif
