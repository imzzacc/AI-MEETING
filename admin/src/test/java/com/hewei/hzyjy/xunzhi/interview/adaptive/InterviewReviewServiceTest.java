package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.*;

class InterviewReviewServiceTest {
    AdaptiveInterviewServiceTest fixture;
    InterviewReviewService reviews;
    String mistakeId;

    @Test
    void sameReportRetryIdCannotResetAnotherFailedAttempt() {
        Session s = fixture.store.find("s");
        s.setReportStatus("FAILED");
        fixture.store.save(s);
        reviews.retry("s", 1L, "retry-1");
        s = fixture.store.find("s");
        s.setReportStatus("FAILED");
        s.setReportAttempts(3);
        fixture.store.save(s);
        reviews.retry("s", 1L, "retry-1");
        assertEquals("FAILED", fixture.store.find("s").getReportStatus());
        assertEquals(3, fixture.store.find("s").getReportAttempts());
    }

    Evaluation evaluation(EvidenceState state) {
        return new Evaluation(
                40,
                "feedback",
                List.of(
                        new Observation(
                                "java.volatile",
                                "atomicity",
                                state,
                                List.of("volatile makes i++ atomic"),
                                List.of("java-jls17-memory"),
                                "reason")),
                "test",
                "1");
    }

    @BeforeEach
    void setup() throws Exception {
        fixture = new AdaptiveInterviewServiceTest();
        fixture.setup();
        when(fixture.evaluator.evaluate(
                        anyString(), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(evaluation(EvidenceState.INCORRECT));
        fixture.service.answer("s", 1L, fixture.request("r", "1", "volatile makes i++ atomic"));
        fixture.service.finish("s", 1L);
        var catalog =
                new InterviewKnowledgeCatalog(
                        new ObjectMapper(),
                        new DefaultResourceLoader(),
                        new AdaptiveConfiguration());
        reviews =
                new InterviewReviewService(
                        fixture.store,
                        fixture.service,
                        fixture.evaluator,
                        catalog,
                        fixture.clock,
                        new AdaptiveConfiguration());
        reviews.process("s");
        mistakeId = fixture.store.mistakes.keySet().iterator().next();
    }

    @Test
    void reportRetryDoesNotDuplicateEvidenceOrUndoUserCorrection() {
        var original = fixture.store.findMistake(mistakeId);
        reviews.update(
                mistakeId,
                1L,
                original.getRevision(),
                "NEEDS_CONFIRMATION",
                "please review this classification");
        Session s = fixture.store.find("s");
        s.setReportStatus("PENDING");
        fixture.store.save(s);
        reviews.process("s");
        var latest = fixture.store.findMistake(mistakeId);
        assertEquals(1, latest.getEvidence().size());
        assertEquals("NEEDS_CONFIRMATION", latest.getStatus());
        assertEquals("please review this classification", latest.getNote());
    }

    @Test
    void deletedMistakeIsNotRecreatedByRetry() {
        reviews.delete(mistakeId, 1L);
        Session s = fixture.store.find("s");
        s.setReportStatus("PENDING");
        fixture.store.save(s);
        reviews.process("s");
        assertTrue(fixture.store.findMistake(mistakeId).isDismissed());
        assertTrue(reviews.mistakes(1L, 1, 20, null, null).isEmpty());
    }

    @Test
    void practiceRetriesAndRepeatedTextDoNotInflateMastery() {
        when(fixture.evaluator.evaluate(
                        anyString(), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(evaluation(EvidenceState.COVERED));
        reviews.review(mistakeId, 1L, "p1", "increment needs atomic operations");
        reviews.review(mistakeId, 1L, "p1", "increment needs atomic operations");
        assertThrows(
                RuntimeException.class,
                () -> reviews.review(mistakeId, 1L, "p2", " increment needs atomic operations "));
        assertEquals(1, fixture.store.findMistake(mistakeId).getPractices().size());
        assertEquals("REVIEWING", fixture.store.findMistake(mistakeId).getStatus());
        reviews.review(
                mistakeId,
                1L,
                "p3",
                "volatile has visibility guarantees but a compound increment needs a lock");
        assertEquals("PRACTICE_VERIFIED", fixture.store.findMistake(mistakeId).getMasterySource());
    }

    @Test
    void failedEvaluationDoesNotRecordAWrongPractice() {
        when(fixture.evaluator.evaluate(
                        anyString(), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("offline"));
        assertThrows(RuntimeException.class, () -> reviews.review(mistakeId, 1L, "p1", "answer"));
        assertTrue(fixture.store.findMistake(mistakeId).getPractices().isEmpty());
    }

    @Test
    void privateRecordsRejectOtherUsersAndDeletedSources() {
        assertThrows(RuntimeException.class, () -> reviews.report("s", 2L));
        assertThrows(RuntimeException.class, () -> reviews.review(mistakeId, 2L, "p1", "answer"));
        Session s = fixture.store.find("s");
        s.setDeleted(true);
        fixture.store.save(s);
        assertTrue(reviews.mistakes(1L, 1, 20, null, null).isEmpty());
        assertThrows(RuntimeException.class, () -> reviews.report("s", 1L));
    }
}
