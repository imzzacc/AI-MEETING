import { beforeEach, describe, expect, it } from "vitest";
import {
  pendingAnswer,
  readPendingAnswer,
  clearPendingAnswer,
} from "./pendingAnswer";

describe("pending answers", () => {
  beforeEach(() => sessionStorage.clear());
  it("reuses the request after a reload and rejects a different body", () => {
    const first = pendingAnswer("s", "1", "original", () => "request-1");
    expect(readPendingAnswer("s")).toEqual(first);
    expect(pendingAnswer("s", "1", "original", () => "new-id")).toEqual(first);
    expect(() => pendingAnswer("s", "1", "changed", () => "new-id")).toThrow();
    expect(readPendingAnswer("s")?.answer).toBe("original");
  });
  it("separates sessions and clears only the completed request", () => {
    pendingAnswer("a", "1", "a", () => "a-id");
    pendingAnswer("b", "1", "b", () => "b-id");
    clearPendingAnswer("a");
    expect(readPendingAnswer("a")).toBeNull();
    expect(readPendingAnswer("b")?.requestId).toBe("b-id");
  });
  it("ignores corrupt stored data", () => {
    sessionStorage.setItem("interview-pending-answer:s", "{");
    expect(readPendingAnswer("s")).toBeNull();
  });
});
