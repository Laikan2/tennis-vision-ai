#if targetEnvironment(simulator)
import SwiftUI
import UIKit

/// Opt-in simulator check; synthetic frames and isolated simulator archive, no camera/voice.
/// Measures the real fullscreen SwiftUI/UIKit tree under changing live states.
@MainActor enum LocalLayoutAudit {
    static var enabled:Bool{ProcessInfo.processInfo.arguments.contains("--vision-layout-check")}
    private static var running=false
    static func prepare(_ model:LocalTrainingModel){
        guard model.frame==nil else{return}
        let renderer=UIGraphicsImageRenderer(size:CGSize(width:360,height:640))
        let image=renderer.image{ctx in
            UIColor(white:0.26,alpha:1).setFill();ctx.fill(CGRect(x:0,y:0,width:360,height:640))
            UIColor(white:0.55,alpha:1).setStroke()
            for y in stride(from:80,to:640,by:80){ctx.cgContext.move(to:CGPoint(x:0,y:y));ctx.cgContext.addLine(to:CGPoint(x:360,y:y))}
            ctx.cgContext.strokePath()
            UIColor.systemGreen.setFill();ctx.cgContext.fillEllipse(in:CGRect(x:162,y:430,width:36,height:36))
            ("IMAGEM SIMULADA" as NSString).draw(at:CGPoint(x:20,y:30),withAttributes:[.font:UIFont.systemFont(ofSize:18),.foregroundColor:UIColor.white])
        }.cgImage!
        var config=VisionSettings()
        config.polygon=[VisionPoint(0.1,0.2),VisionPoint(0.9,0.2),VisionPoint(0.9,0.95),VisionPoint(0.1,0.95)]
        config.floorLine=[VisionPoint(0,0.15),VisionPoint(1,0.15)]
        model.config=config;model.ready=true;model.active=true;model.confirmed=true
        model.frame=LocalCameraFrame(image:image,width:360,height:640,time:0,result:VisionResult(area:config),
            key:"layout-audit",generation:UUID(),fps:0,requestedFPS:60,conversionMs:0,analysisMs:0,
            startedAt:ProcessInfo.processInfo.systemUptime,exposureMs:0,profile:"SIMULADO",lens:"SIMULADO",evidence:nil,evidenceOffset:nil)
        model.status="Teste de layout · sem câmera"
    }
    static func run(_ model:LocalTrainingModel){
        guard enabled,!running else{return};running=true
        Task{
            var rows=[[String:Any]]()
            for i in 0..<6{
                model.editing=0
                var f=model.frame!
                f.result=VisionResult(area:model.config);f.startedAt=ProcessInfo.processInfo.systemUptime
                switch i{
                case 0:model.status="Bola observada"
                case 1:model.status=String(repeating:"Aviso de diagnóstico com texto longo. ",count:5)
                case 2:f.result.movingCamera=true;f.result.areaNeedsConfirmation=true;model.status="Aguarde a câmera estabilizar · impactos suspensos"
                case 3:f.result.areaNeedsConfirmation=true;model.status="Área a conferir · contatos para revisão"
                case 4:
                    let hit=VisionImpact(id:1,time:0,point:VisionPoint(0.5,0.8),radius:18,call:.review,speed:8.2,uncertainty:6,reason:"Candidato simulado",surface:.floor)
                    model.session.events=[hit];f.result.impact=hit;model.status="Contato estimado · toque na marca"
                default:
                    model.editing=2;model.draft=[VisionPoint(0,0.15),VisionPoint(1,0.15)]
                }
                model.frame=f
                try? await Task.sleep(nanoseconds:650_000_000)
                guard let window=UIApplication.shared.connectedScenes.compactMap({$0 as? UIWindowScene}).flatMap({$0.windows}).first(where:{$0.isKeyWindow}) else{continue}
                var root=window.rootViewController
                while let presented=root?.presentedViewController{root=presented}
                guard let view=root?.view else{continue}
                view.layoutIfNeeded()
                func find(_ view:UIView)->LocalCourtSurface?{
                    if let surface=view as? LocalCourtSurface{return surface}
                    for child in view.subviews{if let found=find(child){return found}};return nil
                }
                guard let surface=find(view) else{continue}
                let image=surface.measuredImageFrame()
                rows.append(["state":i,"imageRect":[image.minX,image.minY,image.width,image.height]])
                let folder=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0]
                let screenshot=UIGraphicsImageRenderer(bounds:view.bounds).image{_ in view.drawHierarchy(in:view.bounds,afterScreenUpdates:true)}
                try? screenshot.pngData()?.write(to:folder.appendingPathComponent("layout-\(i).png"))
            }
            let folder=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0]
            model.editing=0
            let old=model.session,area=model.config
            model.finishTraining()
            try? await Task.sleep(nanoseconds:600_000_000)
            let finish:[String:Any]=["marks":model.visibleEvents.count,"newContacts":model.session.events.count,
                "newSession":model.session.id != old.id,"areaRetained":model.config==area,"active":model.active,
                "historyRetained":model.store.history().contains{$0.id==old.id && $0.events.map{$0.id}==old.events.map{$0.id}}]
            let output:[String:Any]=["kind":"simulator-native-ui-not-camera-validation","states":rows,"finishTraining":finish]
            try? JSONSerialization.data(withJSONObject:output,options:[.prettyPrinted,.sortedKeys]).write(to:folder.appendingPathComponent("layout-audit.json"),options:.atomic)
        }
    }
}
#endif

