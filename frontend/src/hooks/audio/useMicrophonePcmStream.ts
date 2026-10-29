import { useCallback, useEffect, useMemo, useRef } from "react";

type UseMicrophonePcmStreamParams = {
  sampleRate: number;
  onChunk: (data: ArrayBuffer) => void;
  onError: (error: unknown) => void;
};

export function useMicrophonePcmStream({
  sampleRate,
  onChunk,
  onError,
}: UseMicrophonePcmStreamParams) {
  const audioContextRef = useRef<AudioContext | null>(null);
  const sourceRef = useRef<MediaStreamAudioSourceNode | null>(null);
  const processorRef = useRef<ScriptProcessorNode | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const generationRef = useRef(0);
  const onChunkRef = useRef(onChunk);
  const onErrorRef = useRef(onError);

  useEffect(() => {
    onChunkRef.current = onChunk;
  }, [onChunk]);

  useEffect(() => {
    onErrorRef.current = onError;
  }, [onError]);

  const stop = useCallback(async () => {
    generationRef.current += 1;
    if (processorRef.current) {
      processorRef.current.disconnect();
      processorRef.current = null;
    }

    if (sourceRef.current) {
      sourceRef.current.disconnect();
      sourceRef.current = null;
    }

    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    }

    if (audioContextRef.current) {
      const context = audioContextRef.current;
      audioContextRef.current = null;
      await context.close().catch(() => undefined);
    }
  }, []);

  const start = useCallback(async () => {
    const stopping = stop();
    const generation = generationRef.current;
    await stopping;
    if (generationRef.current !== generation) return;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          sampleRate,
        },
      });
      if (generationRef.current !== generation) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }
      streamRef.current = stream;

      const AudioContextCtor =
        window.AudioContext ||
        (window as Window & { webkitAudioContext?: typeof AudioContext })
          .webkitAudioContext;

      if (!AudioContextCtor) {
        throw new Error("AudioContext is not supported in current browser");
      }

      const audioContext = new AudioContextCtor({ sampleRate });
      audioContextRef.current = audioContext;
      await audioContext.resume();
      if (generationRef.current !== generation) return;

      const source = audioContext.createMediaStreamSource(stream);
      sourceRef.current = source;

      const processor = audioContext.createScriptProcessor(4096, 1, 1);
      processor.onaudioprocess = (event) => {
        try {
          const inputData = event.inputBuffer.getChannelData(0);
          const pcm16Data = new Int16Array(inputData.length);

          for (let index = 0; index < inputData.length; index += 1) {
            const sample = Math.max(-1, Math.min(1, inputData[index]));
            pcm16Data[index] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
          }

          const chunkSamples = 640;
          for (
            let offset = 0;
            offset < pcm16Data.length;
            offset += chunkSamples
          ) {
            const end = Math.min(offset + chunkSamples, pcm16Data.length);
            const chunk = pcm16Data.subarray(offset, end);
            const chunkBuffer = chunk.buffer.slice(
              chunk.byteOffset,
              chunk.byteOffset + chunk.byteLength,
            );
            onChunkRef.current(chunkBuffer);
          }
        } catch (error) {
          onErrorRef.current(error);
        }
      };

      source.connect(processor);
      processor.connect(audioContext.destination);
      processorRef.current = processor;
    } catch (error) {
      if (generationRef.current !== generation) return;
      await stop();
      throw error;
    }
  }, [sampleRate, stop]);

  useEffect(() => {
    return () => {
      void stop();
    };
  }, [stop]);

  return useMemo(
    () => ({
      start,
      stop,
    }),
    [start, stop],
  );
}
