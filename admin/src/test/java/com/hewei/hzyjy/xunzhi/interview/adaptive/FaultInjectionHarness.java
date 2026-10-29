package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.xunzhi.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.xunzhi.interview.flow.answer.InterviewQuestionLockService;
import com.hewei.hzyjy.xunzhi.interview.service.*;
import com.hewei.hzyjy.xunzhi.interview.service.cache.*;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;

import org.redisson.api.RLock;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * An adaptive interview service whose storage, model call and projection can "crash" at a chosen
 * point. Everything else is the production code.
 */
class FaultInjectionHarness {
    /**
     * Simulated process death. It is an Error so that the application's {@code catch (Exception)}
     * blocks cannot turn it into a handled failure.
     */
    static final class Crash extends Error {
        Crash(Object point) {
            super("simulated crash: " + point, null, false, false);
        }
    }

    /** Crash points of one answer submission, in execution order (docs/adaptive-interview.md 7.3). */
    enum Point {
        BEFORE_PENDING_SAVE,
        AFTER_PENDING_SAVE,
        DURING_MODEL_CALL,
        BEFORE_EVALUATION_SAVE,
        AFTER_EVALUATION_SAVE,
        BEFORE_COMMIT,
        AFTER_COMMIT,
        DURING_PROJECTION
    }

    /** Wraps a real store; can fail before or after a write that matches a predicate. */
    static final class FaultyStore implements AdaptiveSessionStore {
        final AdaptiveSessionStore delegate;
        final List<String> fired = new ArrayList<>();
        final List<String> publishedLeases = new ArrayList<>();
        private Point armed;
        private Predicate<Session> customWhen;
        private boolean customAfterWrite;

        FaultyStore(AdaptiveSessionStore delegate) {
            this.delegate = delegate;
        }

        void arm(Point point) {
            armed = point;
        }

        void crashOnSave(Predicate<Session> when, boolean afterWrite) {
            customWhen = when;
            customAfterWrite = afterWrite;
        }

        void disarm() {
            armed = null;
            customWhen = null;
        }

        public synchronized Session save(Session s) {
            if (customWhen != null && customWhen.test(s)) {
                boolean after = customAfterWrite;
                customWhen = null;
                fired.add("custom");
                if (after) delegate.save(s);
                throw new Crash("custom save");
            }
            Point before = null, after = null;
            if (s.getPending() != null && s.getPending().getEvaluation() == null) {
                before = Point.BEFORE_PENDING_SAVE;
                after = Point.AFTER_PENDING_SAVE;
            } else if (s.getPending() != null) {
                before = Point.BEFORE_EVALUATION_SAVE;
                after = Point.AFTER_EVALUATION_SAVE;
            } else if (armed == Point.BEFORE_COMMIT || armed == Point.AFTER_COMMIT) {
                Session persisted = delegate.find(s.getId());
                if (persisted != null && s.getTurns().size() > persisted.getTurns().size()) {
                    before = Point.BEFORE_COMMIT;
                    after = Point.AFTER_COMMIT;
                }
            }
            if (armed != null && armed == before) {
                armed = null;
                fired.add(before.name());
                throw new Crash(before);
            }
            Session saved = delegate.save(s);
            if ("SUCCEEDED".equals(s.getReportStatus()) && s.getReportMarkdown() != null)
                publishedLeases.add(s.getReportLease());
            if (armed != null && armed == after) {
                armed = null;
                fired.add(after.name());
                throw new Crash(after);
            }
            return saved;
        }

        public Session find(String id) {
            return delegate.find(id);
        }

        public List<Session> work(long now) {
            return delegate.work(now);
        }

        public Mistake findMistake(String id) {
            return delegate.findMistake(id);
        }

        public synchronized Mistake saveMistake(Mistake m) {
            return delegate.saveMistake(m);
        }

        public List<Session> pendingDeletions() {
            return delegate.pendingDeletions();
        }

        public List<Mistake> mistakesForSession(String sessionId, Long userId) {
            return delegate.mistakesForSession(sessionId, userId);
        }

        public void removeMistake(String id, Long userId) {
            delegate.removeMistake(id, userId);
        }

        public List<Mistake> mistakes(Long userId, int offset, int size, String status, String search) {
            return delegate.mistakes(userId, offset, size, status, search);
        }
    }

    /**
     * Deterministic stand-in for the grounded evaluator. The answer prefix decides the evidence
     * state: BAD → INCORRECT, HALF → PARTIAL, NONE → no observation, anything else → COVERED.
     */
    static final class ScriptedEvaluator implements GroundedInterviewEvaluator {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger returned = new AtomicInteger();
        final List<String> fired;
        volatile boolean crashNextCall;

        ScriptedEvaluator(List<String> fired) {
            this.fired = fired;
        }

        public Evaluation evaluate(
                String sessionId,
                String requestId,
                String question,
                String answer,
                List<Topic> topics,
                List<Source> sources) {
            calls.incrementAndGet();
            if (crashNextCall) {
                crashNextCall = false;
                fired.add(Point.DURING_MODEL_CALL.name());
                throw new Crash(Point.DURING_MODEL_CALL);
            }
            returned.incrementAndGet();
            return evaluationFor(answer, topics);
        }

        static int scoreFor(String answer) {
            if (answer.startsWith("BAD")) return 30;
            if (answer.startsWith("HALF")) return 60;
            if (answer.startsWith("NONE")) return 50;
            return 90;
        }

        static Evaluation evaluationFor(String answer, List<Topic> topics) {
            EvidenceState state =
                    answer.startsWith("BAD")
                            ? EvidenceState.INCORRECT
                            : answer.startsWith("HALF")
                                    ? EvidenceState.PARTIAL
                                    : answer.startsWith("NONE") ? null : EvidenceState.COVERED;
            List<Observation> observations = new ArrayList<>();
            if (state != null)
                for (Topic topic : topics)
                    for (String rubric : topic.rubricPoints())
                        observations.add(
                                new Observation(
                                        topic.id(),
                                        rubric,
                                        state,
                                        List.of(answer),
                                        topic.sources().stream().map(Source::id).limit(1).toList(),
                                        "scripted"));
            int score = scoreFor(answer);
            return new Evaluation(score, "feedback " + score, observations, "scripted", "1");
        }
    }

    static final class MutableClock extends Clock {
        private volatile long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advanceSeconds(long seconds) {
            millis += seconds * 1000;
        }

        public long millis() {
            return millis;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    static final String SESSION = "s";
    static final long USER = 1L;

    final FaultyStore store;
    final ScriptedEvaluator evaluator;
    final MutableClock clock = new MutableClock(10_000);
    final AdaptiveConfiguration config = new AdaptiveConfiguration();
    final AdaptiveFollowUpPolicy policy = new AdaptiveFollowUpPolicy();
    final InterviewQuestionCacheService cache = mock(InterviewQuestionCacheService.class);
    final InterviewCacheStore cacheStore = mock(InterviewCacheStore.class);
    final InterviewQuestionLockService locks = mock(InterviewQuestionLockService.class);
    final InterviewRecordService records = mock(InterviewRecordService.class);
    final AdaptiveRuleEngine rules = mock(AdaptiveRuleEngine.class);
    final AdaptiveInterviewService service;

    /** requestIds of turns that reached the Redis projection. */
    final Set<String> projected = Collections.synchronizedSet(new LinkedHashSet<>());
    volatile String projectedScoreSum;
    volatile boolean crashNextProjection;
    /** Runs when a lock with the given key prefix is acquired (once). */
    volatile Consumer<String> lockHook;
    volatile String lockHookPrefix;
    /** Runs before each rule decision. */
    volatile Consumer<Session> decideHook;

    FaultInjectionHarness(
            AdaptiveSessionStore delegate,
            Map<String, String> questions,
            Mode mode,
            int maxPerMain,
            int maxPerSession)
            throws Exception {
        store = new FaultyStore(delegate);
        evaluator = new ScriptedEvaluator(store.fired);
        config.setEnabled(true);
        config.setMode(mode);
        config.setMaxPerMain(maxPerMain);
        config.setMaxPerSession(maxPerSession);
        var catalog =
                new InterviewKnowledgeCatalog(
                        new ObjectMapper(), new DefaultResourceLoader(), config);
        var sessions = mock(InterviewSessionService.class);
        var base = new InterviewSession();
        base.setStatus("READY");
        base.setUserId(USER);
        when(sessions.requireOwnedSession(SESSION, USER)).thenReturn(base);
        when(cache.getSessionInterviewQuestions(SESSION)).thenReturn(questions);
        when(cache.appendInterviewTurnIfAbsent(anyString(), any()))
                .thenAnswer(
                        i -> {
                            if (crashNextProjection) {
                                crashNextProjection = false;
                                store.fired.add(Point.DURING_PROJECTION.name());
                                throw new Crash(Point.DURING_PROJECTION);
                            }
                            projected.add(i.<InterviewTurnLog>getArgument(1).getRequestId());
                            return true;
                        });
        doAnswer(
                        i -> {
                            if (InterviewCacheKeys.sessionScoreSum(SESSION).equals(i.getArgument(0)))
                                projectedScoreSum = i.getArgument(1);
                            return null;
                        })
                .when(cacheStore)
                .setValue(anyString(), anyString(), anyLong());
        RLock lock = mock(RLock.class);
        when(locks.acquireAdaptive(anyString()))
                .thenAnswer(
                        i -> {
                            String key = i.getArgument(0);
                            Consumer<String> hook = lockHook;
                            if (hook != null && key.startsWith(lockHookPrefix)) {
                                lockHook = null;
                                hook.accept(key);
                            }
                            return lock;
                        });
        when(rules.decide(any(), any(), anyLong(), any()))
                .thenAnswer(
                        i -> {
                            Consumer<Session> hook = decideHook;
                            if (hook != null) hook.accept(i.getArgument(0));
                            return policy.decide(
                                    i.getArgument(0),
                                    i.getArgument(1),
                                    i.getArgument(2),
                                    i.getArgument(3),
                                    config.isEmergencyDisableFollowUp(),
                                    Set.of());
                        });
        service =
                new AdaptiveInterviewService(
                        store,
                        config,
                        catalog,
                        policy,
                        rules,
                        evaluator,
                        sessions,
                        cache,
                        cacheStore,
                        locks,
                        mock(InterviewSessionRuntimeSnapshotService.class),
                        records,
                        clock);
    }

    FaultInjectionHarness start(int targetSeconds) {
        service.configure(SESSION, USER, targetSeconds, null);
        service.current(SESSION, USER);
        return this;
    }

    /** Arms one crash point for the next operation. */
    void arm(Point point) {
        switch (point) {
            case DURING_MODEL_CALL -> evaluator.crashNextCall = true;
            case DURING_PROJECTION -> crashNextProjection = true;
            default -> store.arm(point);
        }
    }

    void disarm() {
        store.disarm();
        evaluator.crashNextCall = false;
        crashNextProjection = false;
    }

    Session session() {
        return store.find(SESSION);
    }

    InterviewAnswerRespDTO answer(String requestId, String questionNumber, String body) {
        var r = new InterviewAnswerReqDTO();
        r.setRequestId(requestId);
        r.setQuestionNumber(questionNumber);
        r.setAnswerContent(body);
        return service.answer(SESSION, USER, r);
    }

    InterviewReviewService reviews() {
        return new InterviewReviewService(store, service, evaluator, clock, config);
    }
}
