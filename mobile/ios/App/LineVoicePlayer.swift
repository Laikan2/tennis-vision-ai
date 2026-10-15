import AVFoundation
import Foundation

@MainActor final class LineVoicePlayer: NSObject, AVAudioPlayerDelegate {
    private var players=[LineVoice:AVAudioPlayer]()
    private var current: LineVoice?
    private var ownsSession=false
    var onStatus: ((String)->Void)?
    private(set) var startedCount=0
    private var interruption: NSObjectProtocol?

    override init(){
        super.init()
        interruption=NotificationCenter.default.addObserver(forName:AVAudioSession.interruptionNotification,object:nil,queue:.main){[weak self] notification in
            guard let type=notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  type==AVAudioSession.InterruptionType.began.rawValue else{return}
            Task{@MainActor in self?.stop();self?.onStatus?("Áudio interrompido · toque em Testar para conferir a saída.")}
        }
    }
    deinit { if let interruption{NotificationCenter.default.removeObserver(interruption)} }
    var outputDescription: String {
        let session=AVAudioSession.sharedInstance()
        let route=session.currentRoute.outputs.map(\.portName).joined(separator:", ")
        return "Volume de mídia \(Int((session.outputVolume*100).rounded()))% · \(route.isEmpty ? "saída do iPhone":route)"
    }
    func play(_ voice: LineVoice, test: Bool=false){
        guard test || LineVoicePolicy.canStart(voice,current:current) else{return}
        stop()
        do{
            let session=AVAudioSession.sharedInstance()
            try session.setCategory(.playback,mode:.spokenAudio,options:[.duckOthers])
            try session.setActive(true);ownsSession=true
            if players[voice] == nil {
                guard let url=Bundle.main.url(forResource:voice.rawValue,withExtension:"mp3") else{throw CameraError.invalid("Áudio \(voice.rawValue) não encontrado no aplicativo.")}
                let player=try AVAudioPlayer(contentsOf:url);player.delegate=self;player.volume=1
                guard player.prepareToPlay() else{throw CameraError.invalid("Não preparou o áudio \(voice.rawValue).")}
                players[voice]=player
            }
            guard let player=players[voice] else{return}
            player.currentTime=0;current=voice
            guard player.play() else{throw CameraError.invalid("O iPhone não iniciou o áudio \(voice.rawValue).")}
            startedCount+=1
            let muted=session.outputVolume<=0.01 ? " · aumente o volume lateral enquanto testa":""
            onStatus?("Reprodução \(voice.rawValue) iniciada · \(outputDescription)\(muted)")
        }catch{stop();onStatus?("Falha no som: \(error.localizedDescription)")}
    }
    func stop(){
        for player in players.values{player.stop();player.currentTime=0}
        current=nil
        if ownsSession{try? AVAudioSession.sharedInstance().setActive(false,options:.notifyOthersOnDeactivation);ownsSession=false}
    }
    private func finished(_ id: ObjectIdentifier,success: Bool){
        guard let voice=current,let player=players[voice],ObjectIdentifier(player)==id else{return}
        let output=outputDescription
        stop()
        onStatus?(success ? "Reprodução \(voice.rawValue) concluída · \(output)":"Falha durante a reprodução \(voice.rawValue).")
    }
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer,successfully flag: Bool){
        let id=ObjectIdentifier(player);Task{@MainActor [weak self] in self?.finished(id,success:flag)}
    }
    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer,error: Error?){
        let id=ObjectIdentifier(player);Task{@MainActor [weak self] in self?.finished(id,success:false)}
    }
}
