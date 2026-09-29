import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { interviewService } from "@/services/interviewService";
import { adaptiveInterviewService } from "@/services/adaptiveInterviewService";
import { Button } from "@/components/ui/button";

export default function InterviewTimePanel({
  sessionId,
  onEnd,
}: {
  sessionId: string;
  onEnd: () => void;
}) {
  const query = useQuery({
    queryKey: ["interview-time", sessionId],
    queryFn: () => interviewService.restoreInterviewSession(sessionId),
    refetchInterval: 10_000,
  });
  const [now, setNow] = useState(() => Date.now());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const extensionId = useRef<string | null>(null);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const budget = query.data?.timeBudget;
  if (!budget || !budget.interviewStartedAt) return null;
  const sinceFetch = Math.max(
    0,
    Math.floor((now - query.dataUpdatedAt) / 1000),
  );
  const remaining = Math.max(0, budget.remainingSeconds - sinceFetch);
  const ended = query.data?.canResume === false;
  const extend = async () => {
    setBusy(true);
    setError("");
    extensionId.current ??= crypto.randomUUID();
    try {
      await adaptiveInterviewService.extend(sessionId, extensionId.current);
      extensionId.current = null;
      await query.refetch();
    } catch (e) {
      setError(e instanceof Error ? e.message : "延期失败，请重试");
    } finally {
      setBusy(false);
    }
  };
  return (
    <section
      className="rounded-xl border border-indigo-100 bg-indigo-50 p-4"
      aria-label="面试时间"
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p>
          目标{" "}
          {Math.round(
            (budget.targetDurationSeconds + budget.grantedExtensionSeconds) /
              60,
          )}{" "}
          分钟 · 剩余 {Math.floor(remaining / 60)}:
          {String(remaining % 60).padStart(2, "0")}
        </p>
        <p>待答主问题 {budget.mainQuestionsRemaining} 道</p>
      </div>
      {remaining === 0 && !ended ? (
        <p className="mt-2">
          已达到目标时长。你可以继续完成主问题，系统将停止新增追问。
        </p>
      ) : budget.status === "TIME_TIGHT" ? (
        <p className="mt-2">将减少追问，优先完成剩余主问题。</p>
      ) : null}
      {!ended && (
        <div className="mt-3 flex gap-2">
          <Button
            variant="outline"
            disabled={busy || budget.grantedExtensionSeconds >= 900}
            onClick={() => void extend()}
          >
            延长 5 分钟
          </Button>
          <Button variant="outline" onClick={onEnd}>
            结束并生成复盘
          </Button>
        </div>
      )}
      {error && (
        <p role="alert" className="mt-2 text-red-700">
          {error}
        </p>
      )}
    </section>
  );
}
