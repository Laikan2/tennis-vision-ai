import Foundation

/// Rank formats reported by AVFoundation, never rates inferred from a model name.
public enum CameraPolicy {
    public static func ranked(_ profiles: [CaptureProfile]) -> [CaptureProfile] {
        profiles.filter { [30,60,120,240].contains($0.fps) && [720,1080].contains($0.height) }
            .sorted { $0.fps == $1.fps ? $0.height > $1.height : $0.fps > $1.fps }
    }
    public static func best(_ profiles: [CaptureProfile]) -> CaptureProfile? { ranked(profiles).first }
    public static func next(after current: CaptureProfile, in profiles: [CaptureProfile]) -> CaptureProfile? {
        let ordered=ranked(profiles)
        guard let index=ordered.firstIndex(where:{$0.id == current.id}), index+1 < ordered.count else { return nil }
        return ordered[index+1]
    }
}

/// Ignore startup and short oscillations. Sustained capture/encoding starvation
/// may adapt auto mode; network loss never masquerades as camera incompatibility.
public struct ProfileHealth {
    private var slowSamples=0
    public init() {}
    public mutating func sample(elapsed: Double, requested: Int, capture: Double, encoded: Double) -> Bool {
        guard elapsed >= 12, capture.isFinite, encoded.isFinite else { slowSamples=0; return false }
        slowSamples = min(capture,encoded) < Double(requested)*0.65 ? slowSamples+1 : 0
        return slowSamples >= 6
    }
}

public struct SourceRelease: Decodable, Equatable {
    public let version: String
    public let buildVersion: String?
    public let downloadURL: URL
    public let size: Int
    public let minOSVersion: String?
    public func newer(than current: String) -> Bool { version.compare(current,options:.numeric) == .orderedDescending }
}
public enum UpdateSource {
    public static let url=URL(string:"https://raw.githubusercontent.com/Laikan2/tennis-vision-releases/main/altstore.json")!
    public static var addURL: URL {
        var value=URLComponents(string:"altstore://source")!
        value.queryItems=[URLQueryItem(name:"url",value:url.absoluteString)]
        return value.url!
    }
    public static func installURL(for download: URL) -> URL? {
        guard let source=URLComponents(url:download,resolvingAgainstBaseURL:false),source.scheme=="https",source.host=="github.com",source.user==nil,source.password==nil,source.port==nil,source.query==nil,source.fragment==nil else{return nil}
        let prefix="/Laikan2/tennis-vision-releases/releases/download/"
        guard download.path.hasPrefix(prefix),download.pathExtension=="ipa",download.path.dropFirst(prefix.count).split(separator:"/").count==2 else{return nil}
        var destination=URLComponents(string:"altstore://install")!
        destination.queryItems=[URLQueryItem(name:"url",value:download.absoluteString)]
        return destination.url
    }
    public static func latest(_ data: Data, os: String) throws -> SourceRelease {
        struct App: Decodable { let bundleIdentifier: String; let versions: [SourceRelease] }
        struct Feed: Decodable { let apps: [App] }
        guard data.count <= 1_000_000 else { throw CameraError.invalid("Fonte de atualização inválida.") }
        let feed=try JSONDecoder().decode(Feed.self,from:data)
        guard let app=feed.apps.first(where:{$0.bundleIdentifier == "ai.tennisvision.camera.ios"}),
              let version=app.versions.first(where:{ ($0.minOSVersion ?? "0").compare(os,options:.numeric) != .orderedDescending }),
              version.version.range(of:#"^\d+\.\d+\.\d+$"#,options:.regularExpression) != nil,
              installURL(for:version.downloadURL) != nil, version.size > 0 else {
            throw CameraError.invalid("Nenhuma versão compatível encontrada na fonte oficial.")
        }
        return version
    }
}
