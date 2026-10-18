// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TennisVisionCore",
    platforms: [.iOS("17.4"), .macOS(.v13)],
    products: [
        .library(name: "TennisVisionCore", targets: ["TennisVisionCore"]),
        .executable(name: "TransportSmoke", targets: ["TransportSmoke"]),
        .executable(name: "VisionBenchmark", targets: ["VisionBenchmark"]),
    ],
    targets: [
        .target(name: "TennisVisionCore"),
        .executableTarget(name: "TransportSmoke", dependencies: ["TennisVisionCore"], path: "Smoke"),
        .executableTarget(name: "VisionBenchmark", dependencies: ["TennisVisionCore"], path: "Performance"),
        .testTarget(name: "TennisVisionCoreTests", dependencies: ["TennisVisionCore"],resources:[.copy("Fixtures")]),
    ]
)
