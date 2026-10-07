export type TargetZone = "full" | "singles" | "far_half" | "near_half" |
  "far_left_service" | "far_right_service" | "near_left_service" | "near_right_service";
export type AnalysisMode = "court" | "room" | "tracking";
export type AutoLearning = {
  enabled: boolean;
  learnedWithoutLabels: boolean;
  sampleCount: number;
  ready: boolean;
  lastSimilarity?: number | null;
  updatedAt?: number | null;
  stage: string;
  cameraId?: string;
};
export type Camera = {
  id: string;
  name: string;
  host: string;
  port: number;
  path: string;
  calibrationId?: string;
  requestedFps?: number;
  codec?: string;
  ballDiameterMm?: number;
  targetZone?: TargetZone;
  ballColor?: "auto" | "tennis_green";
  dominantHand?: "right" | "left";
  courtOrientation?: "normal" | "opposite";
  analysisMode?: AnalysisMode;
  captureRegion?: [number, number, number, number] | null;
  capturePolygon?: [number, number][] | null;
};
export type Track = {
  centerX: number;
  centerY: number;
  x: number;
  y: number;
  width: number;
  height: number;
  confidence: number;
  predicted: boolean;
  predictionAge?: number;
  trackID: number;
  source: string;
  label?: string;
  classID?: number;
  velocityX?: number;
  velocityY?: number;
  previewPredicted?: boolean;
  metersPerPixel?: number | null;
  depthMeters?: number | null;
  depthApproximate?: boolean;
  tennisColor?: boolean;
  insidePerson?: boolean;
  insideTarget?: boolean;
  appearanceVerified?: boolean;
  appearanceScore?: number;
  appearanceVersion?: string;
  autoAppearanceVerified?: boolean;
  autoAppearanceScore?: number;
  autoAppearanceVersion?: number;
};
export type Person = Track & { keypoints?: [number, number, number][]; poseAgeMs?: number };
export type Point = { x: number; y: number; predicted: boolean; timestamp?: number };
export type Speed = {
  projectedKmh: number;
  physicalKmh: null;
  directionDegrees: number;
  method?: string;
  approximate?: boolean;
};
export type Event = {
  kind?: string;
  targetPosition?: [number, number] | null;
  targetMode?: "surface" | "image";
  targetZone?: TargetZone;
  courtSector?: string | null;
  courtOrientation?: "normal" | "opposite";
  position?: [number, number];
  resolution?: [number, number];
  id: string;
  timestamp: number;
  status: string;
  createdAt?: number;
  inOrOut: string;
  courtPosition: [number, number] | null;
  confidence: number | null;
  speedBefore: number | null;
  speedAfter?: number | null;
  speedMethod?: string | null;
  positionApproximate?: boolean;
  ballColor?: "tennis_green" | null;
  sessionId: string;
  cameraId?: string;
  cameraIds?: string[];
  observations?: EventObservation[];
  observationCount?: number;
  multiCamera?: boolean;
  throwNumber?: number;
  targetDecision?: "IN" | "OUT" | "REVISAR" | "INCONCLUSIVO";
  decisionAgreement?: string;
  varAlert?: boolean;
  updatedAt?: number;
  strokeType?: "forehand" | "backhand" | null;
  strokeConfidence?: number | null;
  strokeMethod?: string | null;
  dominantHand?: "right" | "left";
  spatialSpread?: number | null;
  ballDiameterPx?: number;
  positionUncertaintyPx?: number;
  decisionReason?: string;
  contactEvidence?: string;
  impactEstimatedBetweenFrames?: boolean;
  replayIds?: string[];
  manualReview?: { reason: string; timestamp: number; reviewer: string; cameraId?: string; replayId?: string; frameIndex?: number };
};
export type EventObservation = Partial<Event> & {
  cameraId: string;
  timestamp: number;
};
export type Stats = {
  estimatedMaxKmh?: number | null;
  estimatedMeanKmh?: number | null;
  estimatedMedianKmh?: number | null;
  estimatedP90Kmh?: number | null;
  longestInsideStreak?: number;
  decidedImpacts?: number;
  bounces: number;
  candidates: number;
  inside: number;
  outside: number;
  accuracy: number | null;
  maxKmh: number | null;
  meanKmh: number | null;
  minKmh: number | null;
  medianKmh: number | null;
  p90Kmh: number | null;
  throws?: number;
  impacts?: number;
  inconclusive?: number;
  insideRate?: number | null;
  varAlerts?: number;
  camerasUsed?: number;
  multiCameraEvents?: number;
  averageDetectionConfidence?: number | null;
  strokeStats?: Record<string, {
    total: number; inside: number; outside: number; insideRate: number | null;
    maxKmh: number | null; meanKmh: number | null;
  }>;
  courtSectors?: Record<string, {
    total: number; forehand: number; backhand: number; unclassified: number;
    inside: number; outside: number;
  }>;
};
export type Session = {
  cameraId: string;
  cameraIds?: string[];
  id: string;
  name: string;
  createdAt: number;
  endedAt: number | null;
  kind: string;
  player1: string;
  player2: string;
  statistics?: Stats;
};
export type Calibration = {
  id: string;
  name: string;
  mode?: AnalysisMode;
  scaleDistanceM?: number;
  quality: string;
  rmseMeters: number | null;
};
export type Status = {
  cameraId?: string;
  cameraName?: string;
  cameraFeeds?: Status[];
  cameraCount?: number;
  onlineCameras?: number;
  capturePolygon?: [number, number][] | null;
  targetZone?: TargetZone;
  courtOrientation?: "normal" | "opposite";
  autoLearning?: AutoLearning | null;
  poseStatus?: string;
  poseInferenceMs?: number | null;
  captureRegion?: [number, number, number, number] | null;
  regionChangedAt?: number;
  running: boolean;
  session: Session | null;
  cameraStatus: string;
  cameraFps: number | null;
  previewFps?: number | null;
  processingFps?: number | null;
  resolution: [number, number] | null;
  track?: Track | null;
  persons?: Person[];
  cameraMoving?: boolean;
  trajectory?: Point[];
  speed?: Speed | null;
  framesProcessed?: number;
  framesReceived: number;
  droppedFrames: number;
  inferenceMs?: number;
  processingMs?: number;
  jpegEncodingMs?: number | null;
  captureGapP95Ms?: number | null;
  analysisStale?: boolean;
  analysisAgeMs?: number | null;
  sourceTimingAvailable?: boolean;
  frameLatencyMs?: number;
  networkLatencyMs?: number | null;
  detectedFrames?: number;
  detector: string;
  device: string | null;
  ballDiameterMm?: number;
  analysisMode?: AnalysisMode;
  calibration: Calibration | null;
  statistics?: Stats;
  stale: boolean;
  sequence?: number;
  error?: string | null;
  lastEvent?: Event;
  captureFpsTotal?: number | null;
  processingFpsTotal?: number | null;
  fusionWindowMs?: number;
  poseDevice?: string;
  acceleration?: { device: string; validated: boolean; parity: boolean; cpuMs: number | null; gpuMs: number | null; speedup: number | null; reason?: string };
};
export type Replay = {
  id: string;
  sessionId: string;
  createdAt: number;
  reason: string;
  frames: number;
  truncated: boolean;
  cameraId?: string;
  eventIds?: string[];
  triggerTimestamp?: number;
};
export type ReplayFrame = {
  index: number;
  timestamp: number;
  overlay: {
    track?: Track;
    persons?: Person[];
    trajectory: Point[];
    speed?: Speed;
    resolution: [number, number];
  };
};
export async function api<T>(path: string, body?: unknown): Promise<T> {
  const r = await fetch(
    "/api" + path,
    body === undefined
      ? { cache: "no-store" }
      : {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        },
  );
  if (!r.ok) {
    const error = await r
      .json()
      .catch(() => ({ detail: "Backend indisponível." }));
    throw new Error(
      typeof error.detail === "string"
        ? error.detail
        : "Confira os campos informados.",
    );
  }
  return r.json();
}
export const number = (n: number | null | undefined, digits = 1) =>
  n == null || !Number.isFinite(n)
    ? "—"
    : n.toLocaleString("pt-BR", { maximumFractionDigits: digits });
export function color(speed: number | null | undefined) {
  if (speed == null) return "#b2c0c5";
  const stops = [0, 40, 70, 100, 130],
    rgb = [
      [60, 150, 255],
      [70, 220, 155],
      [235, 225, 75],
      [255, 148, 55],
      [248, 80, 85],
    ];
  const i = stops.findIndex((v, j) => j > 0 && speed <= v);
  if (i < 0) return "rgb(248,80,85)";
  const t = Math.max(0, (speed - stops[i - 1]) / (stops[i] - stops[i - 1]));
  return `rgb(${rgb[i].map((v, j) => Math.round(rgb[i - 1][j] + (v - rgb[i - 1][j]) * t)).join(",")})`;
}
