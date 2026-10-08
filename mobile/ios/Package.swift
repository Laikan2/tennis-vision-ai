// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TennisVisionCore",
    platforms: [.iOS("17.4"), .macOS(.v13)],
    products: [
        .library(name: "TennisVisionCore", targets: ["TennisVisionCore"]),
        .executable(name: "TransportSmoke", targets: ["TransportSmoke"]),
    ],
    targets: [
        .target(name: "TennisVisionCore"),
        .executableTarget(name: "TransportSmoke", dependencies: ["TennisVisionCore"], path: "Smoke"),
        .testTarget(name: "TennisVisionCoreTests", dependencies: ["TennisVisionCore"]),
    ]
)
