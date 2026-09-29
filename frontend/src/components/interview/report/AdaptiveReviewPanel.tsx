import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Button } from "@/components/ui/button";
import {
  adaptiveInterviewService,
  type InterviewMistake,
} from "@/services/adaptiveInterviewService";

const statuses: Record<string, string> = {
  TO_REVIEW: "待复习",
  REVIEWING: "复习中",
  MASTERED: "已掌握",
  NEEDS_CONFIRMATION: "待确认",
};
function MistakeCard({
  item,
  refresh,
}: {
  item: InterviewMistake;
  refresh: () => Promise<unknown>;
}) {
  const [answer, setAnswer] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const pending = useRef<{ id: string; answer: string } | null>(null);
  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError("");
    try {
      await action();
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作失败，请重试");
    } finally {
      setBusy(false);
    }
  };
  const practice = () =>
    run(async () => {
      if (!pending.current || pending.current.answer !== answer)
        pending.current = { id: crypto.randomUUID(), answer };
      await adaptiveInterviewService.practice(
        item.id,
        pending.current.id,
        answer,
      );
      pending.current = null;
      setAnswer("");
    });
  return (
    <article className="space-y-3 rounded-xl border bg-white p-4">
      <p className="font-medium">{item.question}</p>
      <p className="text-sm text-slate-500">
        {statuses[item.status] ?? item.status} · {item.knowledgePointId}
        {item.masterySource === "SELF_REPORTED" ? " · 用户自行标记" : ""}
      </p>
      {item.evidence.map((e, index) => (
        <details key={index}>
          <summary>查看回答依据与参考资料</summary>
          <blockquote className="my-2 whitespace-pre-wrap border-l-2 pl-3">
            {e.quote || "证据不足，待确认"}
          </blockquote>
          <p>{e.rationale}</p>
          {e.resolution === "RESOLVED_WITH_PROMPT" && (
            <p>已在追问提示后补全，建议继续巩固。</p>
          )}
          {e.sources.map((s) => (
            <p key={s.url} className="my-2 text-sm">
              <a
                href={s.url}
                target="_blank"
                rel="noreferrer"
                className="text-indigo-700 underline"
              >
                {s.title}
              </a>
              ：{s.text}
            </p>
          ))}
        </details>
      ))}
      <label className="block text-sm">
        重新作答
        <textarea
          value={answer}
          maxLength={5000}
          disabled={busy}
          onChange={(e) => setAnswer(e.target.value)}
          className="mt-1 block min-h-24 w-full rounded border p-2"
        />
      </label>
      <div className="flex flex-wrap gap-2">
        <Button
          disabled={busy || !answer.trim()}
          onClick={() => void practice()}
        >
          提交复习回答
        </Button>
        <Button
          variant="outline"
          disabled={busy}
          onClick={() =>
            void run(() =>
              adaptiveInterviewService.updateMistake(
                item,
                item.status === "MASTERED" ? "TO_REVIEW" : "MASTERED",
              ),
            )
          }
        >
          {item.status === "MASTERED" ? "恢复待复习" : "自行标记掌握"}
        </Button>
        <Button
          variant="outline"
          disabled={busy}
          onClick={() =>
            void run(() =>
              adaptiveInterviewService.updateMistake(
                item,
                "NEEDS_CONFIRMATION",
                "用户认为自动分类需要核实",
              ),
            )
          }
        >
          标记误判待核实
        </Button>
        <Button
          variant="outline"
          disabled={busy}
          onClick={() =>
            void run(() => adaptiveInterviewService.deleteMistake(item.id))
          }
        >
          移出错题集
        </Button>
      </div>
      {item.practices.at(-1) && (
        <p className="rounded bg-slate-50 p-3">
          最近练习反馈：{item.practices.at(-1)?.evaluation.feedback}
        </p>
      )}
      {error && (
        <p role="alert" className="text-red-700">
          {error}
        </p>
      )}
    </article>
  );
}

export default function AdaptiveReviewPanel({
  sessionId,
}: {
  sessionId: string;
}) {
  const restore = useQuery({
    queryKey: ["adaptive-review-session", sessionId],
    queryFn: () =>
      import("@/services/interviewService").then(({ interviewService }) =>
        interviewService.restoreInterviewSession(sessionId),
      ),
    retry: false,
  });
  const active = Boolean(restore.data?.timeBudget);
  const report = useQuery({
    queryKey: ["adaptive-review", sessionId],
    queryFn: () => adaptiveInterviewService.report(sessionId),
    enabled: active,
    refetchInterval: (query) =>
      ["PENDING", "RUNNING", "NOT_REQUESTED"].includes(
        query.state.data?.status ?? "PENDING",
      )
        ? 3000
        : false,
    retry: false,
  });
  const [page, setPage] = useState(1);
  const [filter, setFilter] = useState("");
  const [status, setStatus] = useState("");
  const mistakes = useQuery({
    queryKey: [
      "interview-mistakes",
      sessionId,
      page,
      status,
      filter,
      report.data?.status,
    ],
    queryFn: () => adaptiveInterviewService.mistakes(page, status, filter),
    enabled: active,
  });

  const [retryError, setRetryError] = useState("");
  const [retrying, setRetrying] = useState(false);
  if (!active) return null;
  const retry = async () => {
    setRetrying(true);
    setRetryError("");
    try {
      await adaptiveInterviewService.retryReport(
        sessionId,
        crypto.randomUUID(),
      );
      await report.refetch();
    } catch (e) {
      setRetryError(e instanceof Error ? e.message : "重试失败");
    } finally {
      setRetrying(false);
    }
  };
  const download = () => {
    const url = URL.createObjectURL(
      new Blob([report.data?.markdown ?? ""], {
        type: "text/markdown;charset=utf-8",
      }),
    );
    const a = document.createElement("a");
    a.href = url;
    a.download = "面试复盘.md";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  const visible = mistakes.data ?? [];
  return (
    <section
      className="space-y-5 rounded-2xl border bg-slate-50 p-6"
      aria-label="面经与错题复习"
    >
      <h2 className="text-xl font-semibold">面经与错题复习</h2>
      {report.isLoading ||
      ["PENDING", "RUNNING", "NOT_REQUESTED"].includes(
        report.data?.status ?? "",
      ) ? (
        <p role="status">面经正在整理中，完成后会自动显示。</p>
      ) : null}
      {(report.error || report.data?.status === "FAILED") && (
        <div role="alert">
          <p>{report.data?.error || "暂时无法读取面经"}</p>
          <Button
            variant="outline"
            disabled={retrying}
            onClick={() => void retry()}
          >
            重试生成面经
          </Button>
        </div>
      )}
      {retryError && <p role="alert">{retryError}</p>}
      {report.data?.status === "SUCCEEDED" && (
        <>
          <Button variant="outline" onClick={download}>
            导出 Markdown 面经
          </Button>
          <details>
            <summary className="cursor-pointer font-medium">
              查看完整面经
            </summary>
            <div className="prose mt-4 max-w-none">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {report.data.markdown}
              </ReactMarkdown>
            </div>
          </details>
        </>
      )}
      <h3 className="font-semibold">我的错题集</h3>
      <div className="flex flex-wrap gap-3">
        <input
          aria-label="搜索错题"
          placeholder="搜索题目或知识点"
          value={filter}
          onChange={(e) => {
            setPage(1);
            setFilter(e.target.value);
          }}
          className="rounded border p-2"
        />
        <select
          aria-label="复习状态"
          value={status}
          onChange={(e) => {
            setPage(1);
            setStatus(e.target.value);
          }}
          className="rounded border p-2"
        >
          <option value="">全部状态</option>
          {Object.entries(statuses).map(([key, label]) => (
            <option key={key} value={key}>
              {label}
            </option>
          ))}
        </select>
      </div>
      {mistakes.isLoading ? (
        <p>正在加载错题…</p>
      ) : mistakes.error ? (
        <p role="alert">错题加载失败，请刷新重试。</p>
      ) : visible.length === 0 ? (
        <p>没有符合条件的错题。</p>
      ) : (
        visible.map((m) => (
          <MistakeCard key={m.id} item={m} refresh={mistakes.refetch} />
        ))
      )}
      <div className="flex items-center gap-3">
        <Button
          variant="outline"
          disabled={page === 1}
          onClick={() => setPage((p) => p - 1)}
        >
          上一页
        </Button>
        <span>第 {page} 页</span>
        <Button
          variant="outline"
          disabled={(mistakes.data?.length ?? 0) < 20}
          onClick={() => setPage((p) => p + 1)}
        >
          下一页
        </Button>
      </div>
    </section>
  );
}
