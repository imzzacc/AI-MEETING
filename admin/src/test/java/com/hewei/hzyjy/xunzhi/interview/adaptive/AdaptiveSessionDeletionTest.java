package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import org.junit.jupiter.api.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

class AdaptiveSessionDeletionTest {
    AdaptiveInterviewServiceTest.MemoryStore store;
    AdaptiveInterviewService interviews;
    InterviewSessionErasure erasure;
    AdaptiveSessionDeletionService deletion;

    @BeforeEach
    void setup() {
        store = new AdaptiveInterviewServiceTest.MemoryStore();
        interviews = mock(AdaptiveInterviewService.class);
        when(interviews.locked(anyString(), any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        when(interviews.owned(anyString(), anyLong())).thenAnswer(i -> {
            Session s = store.find(i.getArgument(0));
            if (s == null || s.isDeleted() || !s.getUserId().equals(i.getArgument(1))) throw new ClientException("not found");
            return s;
        });
        erasure = mock(InterviewSessionErasure.class);
        deletion = new AdaptiveSessionDeletionService(store, interviews, erasure, Clock.systemUTC());
        Session s = new Session();
        s.setId("s"); s.setUserId(1L); s.setFinished(true);
        s.setReportMarkdown("private report");
        s.setQuestions(List.of(new MainQuestion("1", "private question", "hash", List.of(), 120)));
        Pending pending = new Pending(); pending.setAnswer("private pending answer"); s.setPending(pending);
        store.save(s);
    }

    Mistake mistake(String id, String... sessions) {
        Mistake m = new Mistake(); m.setId(id); m.setUserId(1L); m.setQuestion("private question");
        m.setKnowledgePointId("java.volatile"); m.setGapKey("atomicity"); m.setType("CONCEPT_ERROR");
        for (String s : sessions) {
            m.getSessionIds().add(s);
            m.getEvidence().add(new MistakeEvidence(s, "request", "private " + s, "reason", List.of(), "UNRESOLVED"));
        }
        store.saveMistake(m); return m;
    }

    @Test void deniesOtherUsersAndActiveSessionsBeforeAnyMutation() {
        assertThrows(ClientException.class, () -> deletion.delete("s", 2L));
        Session s = store.find("s"); s.setFinished(false); store.save(s);
        assertThrows(ClientException.class, () -> deletion.delete("s", 1L));
        assertFalse(store.find("s").isDeleted()); verifyNoInteractions(erasure);
    }

    @Test void scrubsAggregateAndSoleSourceMistakesAndIsIdempotent() {
        mistake("m", "s"); deletion.delete("s", 1L); deletion.delete("s", 1L);
        Session marker = store.find("s");
        assertTrue(marker.isDeleted()); assertTrue(marker.isDeletionComplete());
        assertNull(marker.getPending()); assertNull(marker.getReportMarkdown());
        assertNull(marker.getCatalog()); assertTrue(marker.getQuestions().isEmpty()); assertTrue(marker.getTurns().isEmpty());
        assertNull(store.findMistake("m")); verify(erasure, times(1)).erase("s", 1L);
        assertThrows(ClientException.class, () -> deletion.delete("s", 2L));
    }

    @Test void failedCleanupRemainsInaccessibleAndWorkerRetries() {
        doThrow(new IllegalStateException("offline")).doNothing().when(erasure).erase("s", 1L);
        assertThrows(IllegalStateException.class, () -> deletion.delete("s", 1L));
        assertTrue(store.find("s").isDeleted()); assertFalse(store.find("s").isDeletionComplete());
        assertThrows(ClientException.class, () -> interviews.owned("s", 1L));
        deletion.retryPending(); assertTrue(store.find("s").isDeletionComplete());
    }

    @Test void retainsOtherSessionEvidenceAndIndependentPractice() {
        Mistake m = mistake("m", "s", "other");
        Evaluation evaluation = new Evaluation(90, "correct", List.of(), "test", "1");
        m.getPractices().add(new Practice("p1", "h1", "deleted-source practice", 1, evaluation, true, "s"));
        m.getPractices().add(new Practice("p2", "h2", "surviving practice", 2, evaluation, true, "other"));
        m.setMasterySource("PRACTICE_VERIFIED"); m.setStatus("MASTERED"); store.saveMistake(m);
        deletion.delete("s", 1L);
        m = store.findMistake("m");
        assertEquals(Set.of("other"), m.getSessionIds()); assertEquals(1, m.getEvidence().size());
        assertEquals("private other", m.getEvidence().get(0).quote());
        assertEquals(1, m.getPractices().size()); assertEquals("p2", m.getPractices().get(0).requestId());
        assertFalse(m.getQuestion().contains("private")); assertEquals("REVIEWING", m.getStatus());
        assertNull(m.getMasterySource());
    }

    @Test void preservesDismissalWithoutPersonalTextAndPreventsReportResurrection() {
        Mistake m = mistake("m", "s"); m.setDismissed(true); m.setNote("private note"); store.saveMistake(m);
        deletion.delete("s", 1L);
        m = store.findMistake("m"); assertTrue(m.isDismissed()); assertNull(m.getNote());
        assertNull(m.getQuestion()); assertTrue(m.getEvidence().isEmpty());
        var evaluator = mock(GroundedInterviewEvaluator.class);
        var reviews = new InterviewReviewService(store, interviews, evaluator, Clock.systemUTC(), new AdaptiveConfiguration());
        reviews.process("s"); verify(interviews, never()).archive(any());
        assertEquals("DELETED", store.find("s").getReportStatus());
    }

    @Test void reviewCannotPublishIfSourceIsDeletedDuringModelCall() {
        Session s = store.find("s");
        s.setCatalog(new Catalog("test", List.of(new Topic("java.volatile", List.of(), List.of("atomicity"), List.of(), List.of()))));
        store.save(s); mistake("m", "s");
        var evaluator = mock(GroundedInterviewEvaluator.class);
        when(evaluator.evaluate(anyString(), anyString(), anyString(), anyString(), anyList(), anyList())).thenAnswer(i -> {
            Session marker = store.find("s"); marker.setDeleted(true); store.save(marker);
            return new Evaluation(100, "correct", List.of(), "test", "1");
        });
        var reviews = new InterviewReviewService(store, interviews, evaluator, Clock.systemUTC(), new AdaptiveConfiguration());
        assertThrows(ClientException.class, () -> reviews.review("m", 1L, "p", "my answer"));
        assertTrue(store.findMistake("m").getPractices().isEmpty());
    }
}
