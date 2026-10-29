import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/authToken", () => ({
  getAuthToken: vi.fn(() => "token"),
}));

vi.mock("@/config/env", () => ({
  resolveApiBaseUrl: vi.fn(() => "/api"),
  resolveRuntimeWsBaseUrl: vi.fn(() => "ws://localhost:8080"),
  resolveWsBaseUrl: vi.fn(() => "ws://localhost:8080"),
}));

import { AudioToTextWebSocket } from "@/services/audioToTextWs";

describe("AudioToTextWebSocket message handling", () => {
  let instance: AudioToTextWebSocket;

  beforeEach(() => {
    instance = new AudioToTextWebSocket("tester");
  });

  it("ignores out-of-order transcription packets", () => {
    const onTranscription = vi.fn();
    instance.onTranscription = onTranscription;

    (
      instance as unknown as {
        handleMessage: (message: Record<string, unknown>) => void;
      }
    ).handleMessage({
      type: "transcription",
      data: "最新快照",
      timestamp: 20,
    });
    (
      instance as unknown as {
        handleMessage: (message: Record<string, unknown>) => void;
      }
    ).handleMessage({
      type: "transcription",
      data: "旧快照",
      timestamp: 10,
    });

    expect(onTranscription).toHaveBeenCalledTimes(1);
    expect(onTranscription).toHaveBeenCalledWith("最新快照");
  });

  it("deduplicates identical packets with the same timestamp", () => {
    const onTranscription = vi.fn();
    instance.onTranscription = onTranscription;

    const message = {
      type: "transcription",
      data: "重复快照",
      timestamp: 30,
    };
    (
      instance as unknown as {
        handleMessage: (incoming: typeof message) => void;
      }
    ).handleMessage(message);
    (
      instance as unknown as {
        handleMessage: (incoming: typeof message) => void;
      }
    ).handleMessage(message);

    expect(onTranscription).toHaveBeenCalledTimes(1);
  });

  it("clears the current snapshot when the server starts a new transcription session", () => {
    const onTranscription = vi.fn();
    instance.onTranscription = onTranscription;

    (
      instance as unknown as {
        handleMessage: (message: Record<string, unknown>) => void;
      }
    ).handleMessage({
      type: "transcription_started",
      timestamp: 40,
    });

    expect(onTranscription).toHaveBeenCalledWith("");
  });
});

describe("AudioToTextWebSocket lifecycle", () => {
  class FakeSocket {
    static CONNECTING = 0;
    static OPEN = 1;
    static latest: FakeSocket;
    readyState = 0;
    send = vi.fn();
    close = vi.fn();
    onopen?: () => void;
    onmessage?: (event: { data: string }) => void;
    onclose?: (event: {
      code: number;
      reason: string;
      wasClean: boolean;
    }) => void;
    constructor() {
      FakeSocket.latest = this;
    }
    receive(type: string) {
      this.onmessage?.({ data: JSON.stringify({ type }) });
    }
  }
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal("WebSocket", FakeSocket);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("sends start before audio and waits for server acknowledgement", () => {
    const ws = new AudioToTextWebSocket("tester");
    ws.onConnected = () => ws.sendCommand("start_transcription");
    ws.onReady = vi.fn();
    ws.connect();
    const socket = FakeSocket.latest;
    const pcm = new ArrayBuffer(1280);
    ws.sendAudio(pcm);
    socket.readyState = 1;
    socket.onopen?.();
    expect(socket.send).not.toHaveBeenCalled();
    socket.receive("connected");
    expect(socket.send).toHaveBeenCalledExactlyOnceWith(
      JSON.stringify({ type: "start_transcription" }),
    );
    socket.receive("transcription_started");
    expect(socket.send).toHaveBeenNthCalledWith(2, pcm);
    expect(ws.onReady).toHaveBeenCalledOnce();
    ws.disconnect();
  });

  it("reports a dropped established connection instead of leaving a fake listening state", () => {
    const ws = new AudioToTextWebSocket("tester");
    ws.onError = vi.fn();
    ws.connect();
    const socket = FakeSocket.latest;
    socket.readyState = 1;
    socket.onopen?.();
    socket.onclose?.({ code: 1006, reason: "", wasClean: false });
    expect(ws.onError).toHaveBeenCalledOnce();
    ws.disconnect();
  });

  it("times out startup and ignores callbacks from a disposed connection", () => {
    const ws = new AudioToTextWebSocket("tester");
    ws.onError = vi.fn();
    ws.connect();
    const socket = FakeSocket.latest;
    vi.advanceTimersByTime(10000);
    expect(ws.onError).toHaveBeenCalledExactlyOnceWith(
      "Transcription startup timed out",
    );
    expect(socket.close).toHaveBeenCalledOnce();
    socket.onclose?.({ code: 1006, reason: "", wasClean: false });
    expect(ws.onError).toHaveBeenCalledOnce();
  });
});
