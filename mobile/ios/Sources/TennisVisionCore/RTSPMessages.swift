import Foundation

public struct RTSPResponse {
    public let status: Int
    public let sequence: Int
    public let headers: [String:String]
    public let body: Data
}

public struct RTSPDecoder {
    private var buffer = Data()
    public init() {}
    public mutating func append(_ data: Data) throws -> [RTSPResponse] {
        buffer.append(data)
        guard buffer.count <= 131_072 else { throw CameraError.invalid("Resposta do receptor excedeu o limite.") }
        var responses = [RTSPResponse]()
        while !buffer.isEmpty {
            if buffer.first == 0x24 {
                guard buffer.count >= 4 else { break }
                let bytes = [UInt8](buffer.prefix(4)); let count = Int(bytes[2])*256+Int(bytes[3])
                guard buffer.count >= count+4 else { break }
                buffer.removeFirst(count+4)
                buffer = Data([UInt8](buffer)); continue
            }
            guard let end = buffer.range(of:Data([13,10,13,10])) else {
                guard buffer.count <= 16_384 else { throw CameraError.invalid("Cabeçalho do receptor excedeu o limite.") }
                break
            }
            guard end.upperBound <= 16_384, let text = String(data:buffer[..<end.lowerBound],encoding:.utf8) else {
                throw CameraError.invalid("Cabeçalho do receptor é inválido.")
            }
            let lines = text.components(separatedBy:"\r\n")
            let statusParts = lines[0].split(separator:" ")
            guard statusParts.count >= 2, statusParts[0] == "RTSP/1.0", let status = Int(statusParts[1]) else {
                throw CameraError.invalid("O computador não respondeu como um receptor RTSP.")
            }
            var headers = [String:String]()
            for line in lines.dropFirst() {
                guard let colon = line.firstIndex(of:":") else { throw CameraError.invalid("Resposta RTSP malformada.") }
                let key = line[..<colon].lowercased()
                guard headers[key] == nil else { throw CameraError.invalid("O receptor repetiu um cabeçalho.") }
                headers[key] = line[line.index(after:colon)...].trimmingCharacters(in:.whitespaces)
            }
            guard let sequence = headers["cseq"].flatMap(Int.init), sequence > 0 else {
                throw CameraError.invalid("Resposta do receptor sem número de sequência.")
            }
            let length = headers["content-length"].flatMap(Int.init) ?? 0
            guard length >= 0, length <= 32_768,
                  headers["content-length"] == nil || headers["content-length"].flatMap(Int.init) != nil else {
                throw CameraError.invalid("Tamanho da resposta RTSP é inválido.")
            }
            let total = end.upperBound+length
            guard buffer.count >= total else { break }
            responses.append(RTSPResponse(status:status,sequence:sequence,headers:headers,
                body:Data(buffer[end.upperBound..<total])))
            buffer.removeFirst(total)
            // Data slices can retain a nonzero startIndex; normalize before parsing again.
            buffer = Data([UInt8](buffer))
        }
        return responses
    }
}

public enum RTSPMessages {
    public static func sdp(sps: Data, pps: Data, fps: Int) throws -> String {
        guard sps.count >= 4, !pps.isEmpty, sps.first.map({ $0 & 31 }) == 7, pps.first.map({ $0 & 31 }) == 8 else {
            throw CameraError.invalid("O codificador ainda não forneceu o formato H.264.")
        }
        let profile = sps.dropFirst().prefix(3).map{String(format:"%02x",$0)}.joined()
        return ["v=0","o=- 0 0 IN IP4 127.0.0.1","s=Tennis Vision Camera","t=0 0",
            "a=control:*","m=video 0 RTP/AVP 96","a=rtpmap:96 H264/90000",
            "a=fmtp:96 packetization-mode=1;profile-level-id=\(profile);sprop-parameter-sets=\(sps.base64EncodedString()),\(pps.base64EncodedString())",
            "a=framerate:\(fps)","a=control:trackID=0",""].joined(separator:"\r\n")
    }
    public static func request(_ method: String, url: String, sequence: Int,
                               headers: [String:String] = [:], body: String = "") -> Data {
        var lines = ["\(method) \(url) RTSP/1.0","CSeq: \(sequence)","User-Agent: TennisVision-iOS/0.1.0"]
        for (key,value) in headers.sorted(by:{$0.key < $1.key}) { lines.append("\(key): \(value)") }
        if !body.isEmpty { lines.append("Content-Length: \(body.utf8.count)") }
        return Data((lines.joined(separator:"\r\n")+"\r\n\r\n"+body).utf8)
    }
}
