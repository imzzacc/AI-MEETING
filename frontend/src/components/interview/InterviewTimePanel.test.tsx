import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup } from "@testing-library/react";
import InterviewTimePanel from "./InterviewTimePanel";
const mocks = vi.hoisted(() => ({ restore: vi.fn(), extend: vi.fn() }));
vi.mock("@/services/interviewService", () => ({
  interviewService: { restoreInterviewSession: mocks.restore },
}));
vi.mock("@/services/adaptiveInterviewService", () => ({
  adaptiveInterviewService: { extend: mocks.extend },
}));
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});
describe("interview time panel", () => {
  it("keeps main questions available after the soft target and reuses failed extension IDs", async () => {
    mocks.restore.mockResolvedValue({
      canResume: true,
      timeBudget: {
        interviewStartedAt: 1000,
        remainingSeconds: 0,
        targetDurationSeconds: 1200,
        grantedExtensionSeconds: 0,
        mainQuestionsRemaining: 2,
        status: "OVERTIME",
      },
    });
    mocks.extend
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue({});
    const end = vi.fn();
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <InterviewTimePanel sessionId="s" onEnd={end} />
      </QueryClientProvider>,
    );
    await screen.findByText(/继续完成主问题/);
    fireEvent.click(screen.getByRole("button", { name: "延长 5 分钟" }));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "延长 5 分钟" }));
    await waitFor(() => expect(mocks.extend).toHaveBeenCalledTimes(2));
    expect(mocks.extend.mock.calls[0]).toEqual(mocks.extend.mock.calls[1]);
    expect(end).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "结束并生成复盘" }));
    expect(end).toHaveBeenCalledTimes(1);
    client.clear();
  });
});
