import service from "@/lib/request";

export interface InterviewTimeBudget {
  serverTime: number;
  interviewStartedAt: number;
  targetDurationSeconds: number;
  grantedExtensionSeconds: number;
  elapsedSeconds: number;
  remainingSeconds: number;
  overtimeSeconds: number;
  mainQuestionsRemaining: number;
  status: "ON_TRACK" | "TIME_TIGHT" | "OVERTIME";
}
export interface ReviewReport {
  status: "NOT_REQUESTED" | "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED";
  markdown: string;
  error: string;
  evidenceCompleteness: string;
}
export interface InterviewMistake {
  id: string;
  revision: number;
  knowledgePointId: string;
  gapKey: string;
  type: string;
  question: string;
  status: string;
  note?: string;
  masterySource?: string;
  evidence: {
    quote: string;
    rationale: string;
    resolution: string;
    sources: { title: string; url: string; text: string }[];
  }[];
  practices: {
    requestId: string;
    answer: string;
    evaluation: { feedback: string };
    independentlyCorrect: boolean;
  }[];
}
const base = "/xunzhi/v1/interview";
const session = (id: string) => `${base}/sessions/${encodeURIComponent(id)}`;
export const adaptiveInterviewService = {
  capabilities: () =>
    service.get<{ enabled: boolean; durations: number[] }>(
      `${base}/adaptive-capabilities`,
    ),
  configure: (id: string, targetDurationSeconds: number) =>
    service.put(`${session(id)}/policy`, { targetDurationSeconds }),
  extend: (id: string, requestId: string) =>
    service.post<InterviewTimeBudget>(`${session(id)}/extensions`, {
      requestId,
      extensionSeconds: 300,
    }),
  report: (id: string) => service.get<ReviewReport>(`${session(id)}/report`),
  retryReport: (id: string, requestId: string) =>
    service.post(`${session(id)}/report/retry`, { requestId }),
  mistakes: (page = 1, status = "", search = "") =>
    service.get<InterviewMistake[]>(`${base}/mistakes`, {
      params: { page, size: 20, status, search },
    }),
  updateMistake: (item: InterviewMistake, status: string, note?: string) =>
    service.patch<InterviewMistake>(
      `${base}/mistakes/${encodeURIComponent(item.id)}`,
      { expectedRevision: item.revision, status, note },
    ),
  deleteMistake: (id: string) =>
    service.delete(`${base}/mistakes/${encodeURIComponent(id)}`),
  practice: (id: string, requestId: string, answerContent: string) =>
    service.post<InterviewMistake>(
      `${base}/mistakes/${encodeURIComponent(id)}/reviews`,
      { requestId, answerContent },
    ),
};
