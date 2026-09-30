package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.xunzhi.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.xunzhi.interview.flow.answer.InterviewQuestionLockService;
import com.hewei.hzyjy.xunzhi.interview.service.*;
import com.hewei.hzyjy.xunzhi.interview.service.cache.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.redisson.api.RLock;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.*;
import java.util.*;

class AdaptiveInterviewServiceTest {
    static class MemoryStore implements AdaptiveSessionStore {
        final ObjectMapper mapper = new ObjectMapper();
        final Map<String, Session> sessions = new HashMap<>();
        final Map<String, Mistake> mistakes = new HashMap<>();

        <T> T copy(T value, Class<T> type) {
            return value == null ? null : mapper.convertValue(value, type);
        }

        public Session find(String id) {
            return copy(sessions.get(id), Session.class);
        }

        public Session save(Session s) {
            Session current = sessions.get(s.getId());
            if (current != null && !Objects.equals(current.getRevision(), s.getRevision()))
                throw new OptimisticLockingFailureException("stale");
            s.setRevision(s.getRevision() == null ? 0 : s.getRevision() + 1);
            sessions.put(s.getId(), copy(s, Session.class));
            return s;
        }

        public List<Session> work(long now) {
            return sessions.values().stream()
                    .filter(Session::isFinished)
                    .map(s -> copy(s, Session.class))
                    .toList();
        }

        public Mistake findMistake(String id) {
            return copy(mistakes.get(id), Mistake.class);
        }

        public Mistake saveMistake(Mistake m) {
            m.setRevision(m.getRevision() == null ? 0 : m.getRevision() + 1);
            mistakes.put(m.getId(), copy(m, Mistake.class));
            return m;
        }

        public List<Session> pendingDeletions() {
            return sessions.values().stream().filter(s -> s.isDeleted() && !s.isDeletionComplete())
                    .map(s -> copy(s, Session.class)).toList();
        }

        public List<Mistake> mistakesForSession(String sessionId, Long userId) {
            return mistakes.values().stream().filter(m -> m.getUserId().equals(userId)
                    && m.getSessionIds().contains(sessionId)).map(m -> copy(m, Mistake.class)).toList();
        }

        public void removeMistake(String id, Long userId) {
            if (mistakes.get(id) != null && mistakes.get(id).getUserId().equals(userId)) mistakes.remove(id);
        }

        public List<Mistake> mistakes(Long id, int offset, int size, String status, String search) {
            return mistakes.values().stream()
                    .filter(m -> m.getUserId().equals(id) && !m.isDismissed())
                    .skip(offset)
                    .limit(size)
                    .map(m -> copy(m, Mistake.class))
                    .toList();
        }
    }

    MemoryStore store;
    AdaptiveInterviewService service;
    GroundedInterviewEvaluator evaluator;
    InterviewQuestionCacheService cache;
    AdaptiveFollowUpPolicy policy;
    AdaptiveRuleEngine rules;
    Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC);

    @BeforeEach
    void setup() throws Exception {
        store = new MemoryStore();
        var config = new AdaptiveConfiguration();
        config.setEnabled(true);
        config.setMode(Mode.ADAPTIVE);
        var catalog =
                new InterviewKnowledgeCatalog(
                        new ObjectMapper(), new DefaultResourceLoader(), config);
        evaluator = mock(GroundedInterviewEvaluator.class);
        cache = mock(InterviewQuestionCacheService.class);
        var sessions = mock(InterviewSessionService.class);
        var base = new InterviewSession();
        base.setStatus("READY");
        base.setUserId(1L);
        when(sessions.requireOwnedSession("s", 1L)).thenReturn(base);
        when(sessions.requireOwnedSession("s", 2L))
                .thenThrow(new IllegalArgumentException("not owned"));
        when(cache.getSessionInterviewQuestions("s"))
                .thenReturn(Map.of("1", "volatile i++", "2", "project example"));
        when(cache.appendInterviewTurnIfAbsent(anyString(), any())).thenReturn(true);
        var locks = mock(InterviewQuestionLockService.class);
        when(locks.acquireAdaptive(anyString())).thenReturn(mock(RLock.class));
        policy = new AdaptiveFollowUpPolicy();
        rules = mock(AdaptiveRuleEngine.class);
        when(rules.decide(any(), any(), anyLong(), any()))
                .thenAnswer(
                        i ->
                                policy.decide(
                                        i.getArgument(0),
                                        i.getArgument(1),
                                        i.getArgument(2),
                                        i.getArgument(3),
                                        false,
                                        Set.of()));
        when(evaluator.evaluate(
                        anyString(), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(new Evaluation(75, "ok", List.of(), "fake", "1"));
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
                        mock(InterviewCacheStore.class),
                        locks,
                        mock(InterviewSessionRuntimeSnapshotService.class),
                        mock(InterviewRecordService.class),
                        clock);
        service.configure("s", 1L, 1800, null);
        service.current("s", 1L);
    }

    InterviewAnswerReqDTO request(String id, String number, String answer) {
        var r = new InterviewAnswerReqDTO();
        r.setRequestId(id);
        r.setQuestionNumber(number);
        r.setAnswerContent(answer);
        return r;
    }

    @ParameterizedTest
    @CsvSource({"1200,30,true", "1800,30,true", "2700,30,true",
            "1200,1201,false", "1800,1801,false", "2700,2701,false"})
    void durationAndPaceMatrixKeepsEveryMainQuestion(int targetSeconds, int answerSeconds,
            boolean expectsFollowup) {
        var now = new java.util.concurrent.atomic.AtomicLong(10_000);
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.millis()).thenAnswer(i -> now.get());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "clock", advancingClock);
        store.sessions.clear();
        service.configure("s", 1L, targetSeconds, null);
        service.current("s", 1L);
        var plan = List.copyOf(store.find("s").getQuestions());
        when(evaluator.evaluate(anyString(), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(new Evaluation(10, "Synthetic incorrect answer", List.of(new Observation(
                        "java.volatile", "atomicity", EvidenceState.INCORRECT,
                        List.of("atomic"), List.of("java-jls17-memory"), "Synthetic evidence")), "test", "1"));
        int submitted = 0;
        while (!store.find("s").isFinished() && submitted < 5) {
            String number = store.find("s").currentNumber();
            now.addAndGet(answerSeconds * 1000L);
            var response = service.answer("s", 1L, request("pace-" + submitted++, number, "atomic"));
            assertEquals(plan, store.find("s").getQuestions());
            if (now.get() >= 10_000 + targetSeconds * 1000L) {
                assertFalse(Boolean.TRUE.equals(response.getIsFollowUp()));
                assertEquals("TARGET_TIME_EXCEEDED", response.getDecisionSummary().get("reasonCode"));
            }
        }
        Session finished = store.find("s");
        assertTrue(finished.isFinished());
        assertEquals(2, finished.getTurns().stream().filter(t -> !t.questionNumber().contains("-F")).count());
        assertEquals(expectsFollowup ? 1 : 0, finished.getTotalFollowUps());
        assertEquals("PENDING", finished.getReportStatus());
        assertEquals(10_000, finished.getStartedAt());
    }

    @Test
    void publicationGateSuppressesCandidateWhenBudgetExpiresAfterFirstDecision() {
        var now = new java.util.concurrent.atomic.AtomicLong(10_000);
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.millis()).thenAnswer(i -> now.get());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "clock", advancingClock);
        var evidence = new Evaluation(10, "Synthetic incorrect answer", List.of(new Observation(
                "java.volatile", "atomicity", EvidenceState.INCORRECT,
                List.of("atomic"), List.of("java-jls17-memory"), "Synthetic evidence")), "test", "1");
        when(evaluator.evaluate(anyString(), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(evidence);
        doAnswer(i -> {
            Decision result = policy.decide(i.getArgument(0), i.getArgument(1), i.getArgument(2),
                    i.getArgument(3), false, Set.of());
            now.set(1_811_000);
            return result;
        }).when(rules).decide(any(), any(), anyLong(), any());
        var result = service.answer("s", 1L, request("publish-expiry", "1", "atomic"));
        assertEquals("2", result.getNextQuestionNumber());
        assertFalse(Boolean.TRUE.equals(result.getIsFollowUp()));
        assertEquals("BUDGET_CHANGED_BEFORE_COMMIT", result.getDecisionSummary().get("reasonCode"));
        assertEquals(0, store.find("s").getTotalFollowUps());
        verify(evaluator, times(1)).evaluate(anyString(), anyString(), anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void incompatibleScopePreservesMainQuestionsAndDoesNotSendKnowledgeToModel() {
        store.sessions.clear();
        service.configure(
                "s",
                1L,
                1800,
                null,
                new RetrievalScope("other", RetrievalScope.defaults().technologyVersions()));
        service.current("s", 1L);
        Session s = store.find("s");
        assertEquals(
                List.of("volatile i++", "project example"),
                s.getQuestions().stream().map(MainQuestion::text).toList());
        assertTrue(s.getQuestions().get(0).topicIds().isEmpty());
        service.answer("s", 1L, request("scope-answer", "1", "answer"));
        verify(evaluator)
                .evaluate(
                        anyString(),
                        eq("scope-answer"),
                        eq("volatile i++"),
                        eq("answer"),
                        eq(List.of()),
                        eq(List.of()));
        s = store.find("s");
        assertEquals("project example", s.currentText());
        assertEquals(
                "ROLE_MISMATCH",
                s.getTurns().get(0).decision().excluded().get("volatile-atomicity-v1"));
    }

    @Test
    void duplicateAnswerReplaysWithoutScoringOrAdvancingAgain() {
        var request = request("r1", "1", "answer");
        var first = service.answer("s", 1L, request);
        var second = service.answer("s", 1L, request);
        assertEquals(first.getNextQuestionNumber(), second.getNextQuestionNumber());
        assertEquals(1, store.find("s").getTurns().size());
        verify(evaluator, times(1))
                .evaluate(anyString(), anyString(), anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void sameIdWithDifferentPayloadAndStaleNumberAreRejected() {
        service.answer("s", 1L, request("r1", "1", "answer"));
        assertThrows(
                RuntimeException.class,
                () -> service.answer("s", 1L, request("r1", "1", "different")));
        assertThrows(
                RuntimeException.class,
                () -> service.answer("s", 1L, request("r2", "1", "answer")));
    }

    @Test
    void completeAnswerIsDurableBeforeEvaluationAndRetryUsesSameBody() {
        String body = "z".repeat(4999);
        when(evaluator.evaluate(
                        anyString(), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("model unavailable"));
        assertThrows(
                RuntimeException.class, () -> service.answer("s", 1L, request("r1", "1", body)));
        assertEquals(body, store.find("s").getPending().getAnswer());
        assertEquals(0, store.find("s").totalScore());
    }

    @Test
    void projectionFailureDoesNotRepeatScoreOnRetry() {
        when(cache.appendInterviewTurnIfAbsent(anyString(), any())).thenReturn(false);
        assertThrows(
                RuntimeException.class,
                () -> service.answer("s", 1L, request("r1", "1", "answer")));
        assertEquals(1, store.find("s").getTurns().size());
        when(cache.appendInterviewTurnIfAbsent(anyString(), any())).thenReturn(true);
        assertEquals(75, service.answer("s", 1L, request("r1", "1", "answer")).getTotalScore());
        verify(evaluator, times(1))
                .evaluate(anyString(), anyString(), anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void restoreDoesNotResetTimerOrSkipQuestion() {
        long started = store.find("s").getStartedAt();
        service.current("s", 1L);
        service.current("s", 1L);
        assertEquals(started, store.find("s").getStartedAt());
        assertEquals("1", store.find("s").currentNumber());
    }

    @Test
    void cannotMutateMainQuestionsAfterStart() {
        when(cache.getSessionInterviewQuestions("s"))
                .thenReturn(Map.of("1", "changed", "2", "project example"));
        assertThrows(RuntimeException.class, () -> service.current("s", 1L));
    }

    @Test
    void extensionsAreIdempotentAndBounded() {
        service.extend("s", 1L, "extension-1", 300);
        service.extend("s", 1L, "extension-1", 300);
        assertEquals(300, store.find("s").getExtensionSeconds());
        assertThrows(RuntimeException.class, () -> service.extend("s", 1L, "extension-1", 600));
        service.extend("s", 1L, "extension-2", 300);
        service.extend("s", 1L, "extension-3", 300);
        assertThrows(RuntimeException.class, () -> service.extend("s", 1L, "extension-4", 300));
    }

    @Test
    void crossUserAndOversizedAnswerAreRejected() {
        assertThrows(RuntimeException.class, () -> service.current("s", 2L));
        assertThrows(
                RuntimeException.class,
                () -> service.answer("s", 1L, request("r1", "1", "x".repeat(5001))));
    }

    @Test
    void lastAnswerDurablySchedulesReportAndAllowsReplay() {
        service.answer("s", 1L, request("r1", "1", "answer"));
        assertTrue(service.answer("s", 1L, request("r2", "2", "answer")).getFinished());
        assertEquals("PENDING", store.find("s").getReportStatus());
        assertTrue(service.answer("s", 1L, request("r2", "2", "answer")).getFinished());
    }

    @Test
    void reportJobIsIdempotentAndRetainsLongAnswer() throws Exception {
        service.answer("s", 1L, request("r1", "1", "x".repeat(1500) + "TAIL"));
        service.answer("s", 1L, request("r2", "2", "answer"));
        var reports =
                new InterviewReviewService(
                        store, service, evaluator, clock, new AdaptiveConfiguration());
        reports.process("s");
        reports.process("s");
        assertEquals("SUCCEEDED", store.find("s").getReportStatus());
        assertTrue(store.find("s").getReportMarkdown().contains("TAIL"));
        assertEquals(1, store.find("s").getReportAttempts());
    }
}
