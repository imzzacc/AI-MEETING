package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;
import static com.hewei.hzyjy.xunzhi.interview.adaptive.FaultInjectionHarness.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** The report worker dies or stalls at different points; another worker must finish the job. */
class ReportWorkerCrashTest {
    static final long LEASE_SECONDS = 120;

    AdaptiveInterviewServiceTest.MemoryStore memory;
    FaultInjectionHarness h;
    InterviewReviewService reviews;

    @BeforeEach
    void finishedInterviewWithTwoWrongAnswers() throws Exception {
        memory = new AdaptiveInterviewServiceTest.MemoryStore();
        h =
                new FaultInjectionHarness(
                                memory,
                                Map.of("1", "volatile 能保证 i++ 的原子性吗？", "2", "hashcode 和 equals 有什么关系？"),
                                Mode.ADAPTIVE,
                                0,
                                0)
                        .start(1800);
        h.answer("r1", "1", "BAD volatile 能保证原子性");
        h.answer("r2", "2", "BAD 只重写 equals 就够了");
        assertEquals("PENDING", h.session().getReportStatus());
        reviews = h.reviews();
    }

    void assertPublishedOnceWithoutDuplicateEvidence(int attempts) {
        Session s = h.session();
        assertEquals("SUCCEEDED", s.getReportStatus());
        assertNotNull(s.getReportMarkdown());
        assertEquals(attempts, s.getReportAttempts());
        assertEquals(1, h.store.publishedLeases.size(), "report published more than once");
        assertEquals(2, memory.mistakes.size());
        for (Mistake m : memory.mistakes.values()) {
            assertEquals(1, m.getEvidence().size(), "duplicate evidence in " + m.getKnowledgePointId());
            assertEquals(Set.of(SESSION), m.getSessionIds());
        }
    }

    @Test
    void workerThatDiesHoldingTheLeaseIsTakenOverAfterExpiry() {
        doThrow(new Crash("archive")).doNothing().when(h.records).saveInterviewRecord(any(), any(), any());
        assertThrows(Crash.class, () -> reviews.process(SESSION));
        Session s = h.session();
        assertEquals("RUNNING", s.getReportStatus());
        assertNotNull(s.getReportLease());

        // Another worker inside the lease window must leave the job alone.
        h.clock.advanceSeconds(LEASE_SECONDS - 1);
        reviews.process(SESSION);
        assertEquals("RUNNING", h.session().getReportStatus());
        assertEquals(1, h.session().getReportAttempts());

        h.clock.advanceSeconds(2);
        reviews.process(SESSION);
        assertPublishedOnceWithoutDuplicateEvidence(2);
    }

    @Test
    void crashAfterCollectingMistakesBeforePublishingDoesNotDuplicateEvidence() {
        h.store.crashOnSave(s -> "SUCCEEDED".equals(s.getReportStatus()), false);
        assertThrows(Crash.class, () -> reviews.process(SESSION));
        assertEquals(2, memory.mistakes.size());
        assertNull(h.session().getReportMarkdown());

        h.clock.advanceSeconds(LEASE_SECONDS + 1);
        reviews.process(SESSION);
        assertPublishedOnceWithoutDuplicateEvidence(2);
    }

    @Test
    void lateWorkerCannotOverwriteTheWorkerThatTookOver() {
        // Worker A stalls (GC pause, lost lock) while collecting mistakes. Its lease expires and
        // worker B finishes the job. Then A wakes up.
        h.lockHookPrefix = "mistake-";
        h.lockHook =
                key -> {
                    h.clock.advanceSeconds(LEASE_SECONDS + 1);
                    reviews.process(SESSION);
                    assertEquals("SUCCEEDED", h.session().getReportStatus());
                };
        reviews.process(SESSION);
        assertPublishedOnceWithoutDuplicateEvidence(2);
    }

    @Test
    void sessionDeletedWhileTheWorkerRunsLeavesNoReportOrMistakes() {
        // Without the lock (lost or expired), deletion and its cleanup can finish while the
        // worker is still collecting mistakes from the copy it loaded earlier.
        var erasure = mock(InterviewSessionErasure.class);
        var deletion = new AdaptiveSessionDeletionService(h.store, h.service, erasure, h.clock);
        h.lockHookPrefix = "mistake-";
        h.lockHook = key -> deletion.delete(SESSION, USER);
        reviews.process(SESSION);

        Session s = h.session();
        assertTrue(s.isDeleted());
        assertTrue(s.isDeletionComplete());
        assertNull(s.getReportMarkdown());
        assertTrue(h.store.publishedLeases.isEmpty());
        assertTrue(memory.mistakes.isEmpty(), "mistakes written from a deleted session");
    }
}
