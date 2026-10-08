import Foundation

public struct EncodedFrame {
    public let nals: [Data]
    public let pts: Double
    public let keyframe: Bool
    public let sps: Data
    public let pps: Data
    public let createdAt: Double
    public var bytes: Int { nals.reduce(0) { $0 + $1.count } }
    public init(nals: [Data], pts: Double, keyframe: Bool, sps: Data = Data(),
                pps: Data = Data(), createdAt: Double = ProcessInfo.processInfo.systemUptime) {
        self.nals = nals; self.pts = pts; self.keyframe = keyframe
        self.sps = sps; self.pps = pps; self.createdAt = createdAt
    }
}

public enum AVCC {
    public static func nals(_ data: Data, lengthSize: Int = 4) throws -> [Data] {
        guard (1...4).contains(lengthSize), data.count <= 16_777_216 else {
            throw CameraError.invalid("O codificador retornou um quadro inválido.")
        }
        let bytes = [UInt8](data); var offset = 0; var result = [Data]()
        while offset < bytes.count {
            guard offset + lengthSize <= bytes.count else { throw CameraError.invalid("Cabeçalho H.264 incompleto.") }
            var length = 0
            for byte in bytes[offset..<offset+lengthSize] { length = (length << 8) | Int(byte) }
            offset += lengthSize
            guard length > 0, length <= bytes.count-offset, (1...23).contains(Int(bytes[offset] & 0x1f)) else {
                throw CameraError.invalid("Conteúdo H.264 incompleto ou inválido.")
            }
            result.append(Data(bytes[offset..<offset+length])); offset += length
        }
        guard !result.isEmpty else { throw CameraError.invalid("Quadro H.264 vazio.") }
        return result
    }
}

public struct RTPPacketizer {
    public private(set) var sequence: UInt16
    public let ssrc: UInt32
    public let maxPacketBytes: Int
    public init(sequence: UInt16 = UInt16.random(in: .min ... .max),
                ssrc: UInt32 = UInt32.random(in: 1 ... .max), maxPacketBytes: Int = 1200) {
        self.sequence = sequence; self.ssrc = ssrc; self.maxPacketBytes = max(64,min(1400,maxPacketBytes))
    }
    public mutating func packets(nals: [Data], timestamp: UInt32) -> [Data] {
        var result = [Data]()
        for (index,nal) in nals.enumerated() where !nal.isEmpty {
            let lastNAL = index == nals.count-1
            if nal.count <= maxPacketBytes-12 {
                result.append(packet(payload:nal,timestamp:timestamp,marker:lastNAL))
            } else {
                let header = nal[nal.startIndex]; let content = [UInt8](nal.dropFirst())
                let capacity = maxPacketBytes-14; var offset = 0
                while offset < content.count {
                    let end = min(content.count,offset+capacity)
                    let indicator = (header & 0xe0) | 28
                    let fragment = (header & 0x1f) | (offset == 0 ? 0x80 : 0) | (end == content.count ? 0x40 : 0)
                    result.append(packet(payload:Data([indicator,fragment])+Data(content[offset..<end]),
                        timestamp:timestamp,marker:lastNAL && end == content.count))
                    offset = end
                }
            }
        }
        return result
    }
    private mutating func packet(payload: Data, timestamp: UInt32, marker: Bool) -> Data {
        var packet = Data([0x80, marker ? 0xe0 : 0x60])
        packet.append(UInt8(sequence >> 8)); packet.append(UInt8(sequence & 255)); sequence &+= 1
        for shift in [24,16,8,0] { packet.append(UInt8((timestamp >> shift) & 255)) }
        for shift in [24,16,8,0] { packet.append(UInt8((ssrc >> shift) & 255)) }
        packet.append(payload); return packet
    }
    public static func interleave(_ packets: [Data]) -> Data {
        var data = Data()
        for packet in packets {
            data.append(contentsOf:[0x24,0,UInt8(packet.count >> 8),UInt8(packet.count & 255)])
            data.append(packet)
        }
        return data
    }
    public static func timestamp(pts: Double, origin: Double) throws -> UInt32 {
        let ticks = (pts-origin)*90_000
        guard ticks.isFinite, ticks >= 0, ticks < Double(UInt64.max) else {
            throw CameraError.invalid("O relógio do vídeo é inválido.")
        }
        return UInt32(truncatingIfNeeded:UInt64(ticks.rounded()))
    }
}
