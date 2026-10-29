package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;
import static com.hewei.hzyjy.xunzhi.interview.adaptive.FaultInjectionHarness.*;

import static org.junit.jupiter.api.Assertions.*;

import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewAnswerRespDTO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.*;
import java.util.concurrent.*;

/**
 * The crash table in docs/adaptive-interview.md 7.3, as tests. Each case kills the "process" at
 * one point of an answer submission, retries the same request and compares the outcome with a
 * run that never crashed. Runs against the in-memory store (unit test) and real MongoDB (IT).
 */
abstract class AnswerCrashMatrixCases {
    static final Map<String, String> QUESTIONS =
            Map.of("1", "volatile 能保证 i++ 的原子性吗？", "2", "介绍一个你做过的项目");
    /** An incorrect answer on a mapped topic, so the decision also publishes a follow-up. */
    static final String WRONG = "BAD volatile 能保证 i++ 原子性";

    abstract AdaptiveSessionStore newStore() throws Exception;

    FaultInjectionHarness harness() throws Exception {
        return new FaultInjectionHarness(newStore(), QUESTIONS, Mode.ADAPTIVE, 2, 6).start(1800);
    }

    @ParameterizedTest
    @EnumSource(Point.class)
    void retryAfterCrashCommitsTheAnswerExactlyOnce(Point point) throws Exception {
        var clean = harness();
        InterviewAnswerRespDTO expected = clean.answer("r1", "1", WRONG);
        Session expectedState = clean.session();

        var h = harness();
        h.arm(point);
        assertThrows(Crash.class, () -> h.answer("r1", "1", WRONG));
        assertEquals(List.of(point.name()), h.store.fired, "crash point was not reached");

        InterviewAnswerRespDTO actual = h.answer("r1", "1", WRONG);
        assertEquals(expected, actual);
        Session s = h.session();
        assertNull(s.getPending());
        assertEquals(1, s.getTurns().size());
        assertEquals(expectedState.getQuestions(), s.getQuestions());
        assertEquals(expectedState.currentNumber(), s.currentNumber());
        assertEquals(expectedState.getMainIndex(), s.getMainIndex());
        assertEquals(1, s.getTotalFollowUps());
        assertEquals(expectedState.getProbedGaps(), s.getProbedGaps());
        assertEquals(expectedState.totalScore(), s.totalScore());
        assertTrue(h.projected.contains("r1"), "committed turn was never projected");
        assertEquals(clean.projectedScoreSum, h.projectedScoreSum);

        // Only a result that came back but was never saved costs a second model call.
        int results = point == Point.BEFORE_EVALUATION_SAVE ? 2 : 1;
        assertEquals(results, h.evaluator.returned.get());

        // Any further retry is a pure replay.
        assertEquals(expected, h.answer("r1", "1", WRONG));
        assertEquals(1, h.session().getTurns().size());
        assertEquals(results, h.evaluator.returned.get());
    }

    @Test
    void pendingAnswerBlocksADifferentRequestUntilTheOriginalIsRetried() throws Exception {
        var h = harness();
        h.arm(Point.AFTER_PENDING_SAVE);
        assertThrows(Crash.class, () -> h.answer("r1", "1", WRONG));

        assertThrows(ClientException.class, () -> h.answer("r2", "1", "GOOD 另一个回答"));
        var restored = h.service.current(SESSION, USER);
        assertEquals("r1", restored.getPendingAnswer().get("requestId"));
        assertEquals(WRONG, restored.getPendingAnswer().get("answer"));

        h.answer("r1", "1", WRONG);
        Session s = h.session();
        assertEquals(List.of("r1"), s.getTurns().stream().map(Turn::requestId).toList());
        assertEquals(0, h.evaluator.calls.get() - h.evaluator.returned.get());
    }

    @Test
    void concurrentRetriesWithoutTheLockStillCommitOnce() throws Exception {
        var h = harness();
        h.arm(Point.AFTER_EVALUATION_SAVE);
        assertThrows(Crash.class, () -> h.answer("r1", "1", WRONG));

        // Simulate a lost session lock: both retries read the same revision and meet at the
        // decision step before either commits.
        var barrier = new CyclicBarrier(2);
        Set<Thread> waited = ConcurrentHashMap.newKeySet();
        h.decideHook =
                s -> {
                    if (!waited.add(Thread.currentThread())) return;
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> h.answer("r1", "1", WRONG));
            var second = executor.submit(() -> h.answer("r1", "1", WRONG));
            List<InterviewAnswerRespDTO> responses = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (var future : List.of(first, second)) {
                try {
                    responses.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            assertEquals(1, responses.size(), "exactly one retry may win: " + failures);
            assertInstanceOf(OptimisticLockingFailureException.class, failures.get(0));
            h.decideHook = null;
            assertEquals(responses.get(0), h.answer("r1", "1", WRONG));
        } finally {
            executor.shutdownNow();
        }
        Session s = h.session();
        assertEquals(1, s.getTurns().size());
        assertEquals(1, s.getTotalFollowUps());
        assertEquals(1, h.evaluator.returned.get());
    }
}
