import Foundation

public struct CaptureProfile: Codable, Hashable, Identifiable {
    public let width: Int
    public let height: Int
    public let fps: Int
    public let bitrate: Int
    public var id: String { "\(width)x\(height)@\(fps)" }
    public var label: String { "\(height)p · \(fps) fps" }

    public init(width: Int, height: Int, fps: Int, bitrate: Int) {
        self.width = width; self.height = height; self.fps = fps; self.bitrate = bitrate
    }
    public static let standard = CaptureProfile(width: 1920, height: 1080, fps: 60, bitrate: 12_000_000)
}

public enum CameraError: LocalizedError, Equatable {
    case invalid(String)
    public var errorDescription: String? {
        switch self { case .invalid(let message): return message }
    }
}

public struct PairingConfiguration: Codable, Equatable {
    public let host: String
    public let port: Int
    public let path: String
    public let profile: CaptureProfile
    public let panelPort: Int?
    public let panelToken: String?
    public var panelURL: URL? { panelPort.flatMap { URL(string:"http://\(host):\($0)") } }
    public var rtspURL: String { "rtsp://\(host):\(port)/\(path)" }

    public init(host: String, port: Int = 8554, path: String = "iphone",
                profile: CaptureProfile = .standard, panelPort: Int? = nil, panelToken: String? = nil) throws {
        let host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...253).contains(host.utf8.count), labels.allSatisfy({
            $0.range(of: #"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$"#, options: .regularExpression) != nil
        }) else { throw CameraError.invalid("Informe um IP ou nome válido do computador.") }
        if host.allSatisfy({ ("0"..."9").contains($0) || $0 == "." }) {
            guard labels.count == 4, labels.allSatisfy({ Int($0).map { (0...255).contains($0) } ?? false }) else {
                throw CameraError.invalid("O IP do computador é inválido.")
            }
        }
        guard (1...65535).contains(port) else { throw CameraError.invalid("A porta precisa estar entre 1 e 65535.") }
        guard path.range(of: #"^[A-Za-z0-9_-]{1,64}$"#, options: .regularExpression) != nil else {
            throw CameraError.invalid("O caminho aceita letras, números, hífen e sublinhado.")
        }
        guard [30, 60, 120, 240].contains(profile.fps),
              [(1280,720), (1920,1080)].contains(where: { $0.0 == profile.width && $0.1 == profile.height }),
              (500_000...50_000_000).contains(profile.bitrate) else {
            throw CameraError.invalid("Escolha 720p ou 1080p, com 30, 60, 120 ou 240 fps.")
        }
        self.host = host; self.port = port; self.path = path; self.profile = profile
        guard (panelPort == nil && panelToken == nil) ||
              (panelPort.map { (1...65535).contains($0) } == true &&
               panelToken?.range(of: #"^[a-f0-9]{64}$"#, options:.regularExpression) != nil) else {
            throw CameraError.invalid("O QR do painel está incompleto. Leia novamente o QR do Windows atualizado.")
        }
        self.panelPort=panelPort; self.panelToken=panelToken
    }

    public static func parse(_ value: String) throws -> PairingConfiguration {
        guard value.utf8.count <= 4096,
              let url = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme == "tennisvision", url.host == "pair", url.user == nil, url.password == nil,
              url.port == nil, url.path.isEmpty, url.fragment == nil else {
            throw CameraError.invalid("Este QR não é um pareamento do Tennis Vision.")
        }
        var fields = [String:String]()
        for item in url.queryItems ?? [] {
            guard fields[item.name] == nil, let value = item.value else {
                throw CameraError.invalid("O QR contém campos repetidos ou incompletos.")
            }
            fields[item.name] = value
        }
        guard fields["transport"] == "rtsp" else {
            throw CameraError.invalid("No Windows, escolha iPhone / iOS antes de ler o QR. O QR Android usa outro transporte.")
        }
        func number(_ key: String, _ fallback: Int) throws -> Int {
            guard let value = fields[key] else { return fallback }
            guard let result = Int(value), String(result) == value else {
                throw CameraError.invalid("Valor inválido no QR: \(key).")
            }
            return result
        }
        return try PairingConfiguration(host: fields["host"] ?? "", port: number("port",8554),
            path: fields["path"] ?? "iphone", profile: CaptureProfile(
                width: number("width",1920), height: number("height",1080),
                fps: number("fps",60), bitrate: number("bitrate",12_000_000)),
            panelPort: fields["panelPort"] == nil ? nil : number("panelPort",8894), panelToken:fields["panelToken"])
    }

    public static func restore(_ data: Data) throws -> PairingConfiguration {
        let stored = try JSONDecoder().decode(PairingConfiguration.self, from: data)
        return try PairingConfiguration(host: stored.host, port: stored.port, path: stored.path, profile: stored.profile,
                                        panelPort:stored.panelPort,panelToken:stored.panelToken)
    }
}
