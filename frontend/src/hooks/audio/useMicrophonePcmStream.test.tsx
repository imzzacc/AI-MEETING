import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useMicrophonePcmStream } from "./useMicrophonePcmStream";

afterEach(() => vi.unstubAllGlobals());

describe("microphone lifecycle", () => {
  it("releases a device acquired after the user stops while permission is pending", async () => {
    let grant!: (stream: MediaStream) => void;
    const getUserMedia = vi.fn(
      () =>
        new Promise<MediaStream>((resolve) => {
          grant = resolve;
        }),
    );
    vi.stubGlobal("navigator", { mediaDevices: { getUserMedia } });
    const stopTrack = vi.fn();
    const { result, unmount } = renderHook(() =>
      useMicrophonePcmStream({
        sampleRate: 16000,
        onChunk: vi.fn(),
        onError: vi.fn(),
      }),
    );
    let starting!: Promise<void>;
    await act(async () => {
      starting = result.current.start();
    });
    await act(async () => {
      await result.current.stop();
    });
    await act(async () => {
      grant({
        getTracks: () => [{ stop: stopTrack }],
      } as unknown as MediaStream);
      await starting;
    });
    expect(stopTrack).toHaveBeenCalledOnce();
    unmount();
  });

  it("resumes suspended audio processing before wiring the PCM stream", async () => {
    const source = { connect: vi.fn(), disconnect: vi.fn() };
    const processor = {
      connect: vi.fn(),
      disconnect: vi.fn(),
      onaudioprocess: null,
    };
    const resume = vi.fn(async () => undefined),
      close = vi.fn(async () => undefined);
    const stopTrack = vi.fn();
    vi.stubGlobal("navigator", {
      mediaDevices: {
        getUserMedia: vi.fn(async () => ({
          getTracks: () => [{ stop: stopTrack }],
        })),
      },
    });
    vi.stubGlobal(
      "AudioContext",
      class {
        resume = resume;
        close = close;
        destination = {};
        createMediaStreamSource = () => source;
        createScriptProcessor = () => processor;
      },
    );
    const { result, unmount } = renderHook(() =>
      useMicrophonePcmStream({
        sampleRate: 16000,
        onChunk: vi.fn(),
        onError: vi.fn(),
      }),
    );
    await act(async () => {
      await result.current.start();
    });
    expect(resume).toHaveBeenCalledOnce();
    expect(source.connect).toHaveBeenCalledWith(processor);
    await act(async () => {
      await result.current.stop();
    });
    expect(stopTrack).toHaveBeenCalledOnce();
    expect(close).toHaveBeenCalledOnce();
    unmount();
  });
});
