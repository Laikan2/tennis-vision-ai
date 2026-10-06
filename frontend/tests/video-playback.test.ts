import { test } from "node:test";
import assert from "node:assert/strict";
import { videoEndpoints, VideoProgress, PlaybackMeter } from "../lib/video-playback.ts";

test("Windows fallback reaches the bundled backend through its random-port relay", () => {
  const media = videoEndpoints("http://127.0.0.1:51346/", true, "/galaxy");
  assert.equal(media.previewOrigin, "http://127.0.0.1:51346");
  assert.equal(media.directUrl, "http://127.0.0.1:8889/galaxy/whep");
});

test("a LAN viewer receives video from the PC rather than its own localhost", () => {
  const media = videoEndpoints("http://192.168.15.2:3000/", false, "/camera b");
  assert.equal(media.previewOrigin, "http://192.168.15.2:8000");
  assert.equal(media.directUrl, "http://192.168.15.2:8889/camera%20b/whep");
});

test("HTTPS preview keeps its trusted origin instead of using mixed HTTP content", () => {
  const media = videoEndpoints("https://tennis.example/", false, "galaxy");
  assert.equal(media.previewOrigin, "https://tennis.example");
  assert.equal(media.directUrl, undefined);
});

test("direct video is ready only after a frame and detects a frozen decoded image", () => {
  const progress = new VideoProgress(100);
  assert.equal(progress.observe(0, 2000), "waiting");
  assert.equal(progress.observe(1, 9000), "ready");
  assert.equal(progress.observe(60, 10000), "playing");
  assert.equal(progress.observe(60, 13499), "waiting");
  assert.equal(progress.observe(60, 13501), "stalled");
});

test("missing first frames time out and a new peer has an independent startup window", () => {
  const first = new VideoProgress(0);
  assert.equal(first.observe(0, 10001), "stalled");
  const retry = new VideoProgress(15000);
  assert.equal(retry.observe(0, 15500), "waiting");
  assert.equal(retry.observe(1, 16000), "ready");
});

test("playback measures delivered frames, handles freezes and resets after reconnect", () => {
  const meter = new PlaybackMeter();
  assert.equal(meter.sample(0,100,3),null);
  assert.equal(meter.sample(250,115,3),null);
  assert.deepEqual(meter.sample(1000,160,5), {fps:58,dropped:2});
  assert.deepEqual(meter.sample(2000,160,5), {fps:0,dropped:0});
  assert.equal(meter.sample(2100,0,0),null);
  assert.deepEqual(meter.sample(3100,120,0), {fps:120,dropped:0});
});
