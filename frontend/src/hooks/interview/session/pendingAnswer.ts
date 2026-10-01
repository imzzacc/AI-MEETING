type PendingAnswer = {
  requestId: string;
  questionNumber: string;
  answer: string;
};
const key = (sessionId: string) => `interview-pending-answer:${sessionId}`;
export function readPendingAnswer(sessionId: string): PendingAnswer | null {
  try {
    const value = JSON.parse(
      sessionStorage.getItem(key(sessionId)) ?? "null",
    ) as PendingAnswer | null;
    return value &&
      typeof value.requestId === "string" &&
      typeof value.questionNumber === "string" &&
      typeof value.answer === "string"
      ? value
      : null;
  } catch {
    return null;
  }
}
export function pendingAnswer(
  sessionId: string,
  questionNumber: string,
  answer: string,
  createId: () => string,
): PendingAnswer {
  const previous = readPendingAnswer(sessionId);
  if (previous?.questionNumber === questionNumber) {
    if (previous.answer !== answer)
      throw new Error("上一份回答尚未确认，请先重试原回答，或结束本场面试。");
    return previous;
  }
  const next = { requestId: createId(), questionNumber, answer };
  try {
    sessionStorage.setItem(key(sessionId), JSON.stringify(next));
  } catch {
    /* Private browser mode. */
  }
  return next;
}
export function clearPendingAnswer(sessionId: string) {
  try {
    sessionStorage.removeItem(key(sessionId));
  } catch {
    /* Private browser mode. */
  }
}
