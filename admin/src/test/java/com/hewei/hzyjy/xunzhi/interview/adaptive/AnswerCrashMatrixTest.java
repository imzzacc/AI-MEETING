package com.hewei.hzyjy.xunzhi.interview.adaptive;

/** Crash matrix against the in-memory store. */
class AnswerCrashMatrixTest extends AnswerCrashMatrixCases {
    @Override
    AdaptiveSessionStore newStore() {
        return new AdaptiveInterviewServiceTest.MemoryStore();
    }
}
