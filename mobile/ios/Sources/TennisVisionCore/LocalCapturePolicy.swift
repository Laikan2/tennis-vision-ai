import Foundation

/// A rate must be supplied by a real camera format, never by the phone model name.
public enum LocalCapturePolicy {
    public static func select(_ formats:[CaptureProfile],height:Int,rate:Int) -> CaptureProfile? {
        guard [0,30,60,120,240].contains(rate) else{return nil}
        return formats.filter{p in
            [720,1080].contains(p.height) && p.height<=height && [30,60,120,240].contains(p.fps) &&
            (rate==0 || rate==60 && p.fps<=60 || p.fps==rate)
        }.sorted{a,b in a.height==b.height ? a.fps>b.fps:a.height>b.height}.first
    }
}
