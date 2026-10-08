import Foundation

/// Streaming keeps one orientation until restart, so saved surface coordinates stay stable.
public enum CaptureGeometry {
    public static func rightAngle(_ degrees: Double) -> Int {
        guard degrees.isFinite else { return 0 }
        return ((Int((degrees / 90).rounded()) % 4) + 4) % 4 * 90
    }
    public static func dimensions(width: Int, height: Int, rotation: Int) -> (width: Int, height: Int) {
        rotation == 90 || rotation == 270 ? (height, width) : (width, height)
    }
}
