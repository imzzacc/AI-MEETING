package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

class AdaptivePolicyTest {
    final AdaptiveFollowUpPolicy policy = new AdaptiveFollowUpPolicy();
    static final Source SOURCE =
            new Source("s", "Reference", "https://example.org/reference", "1", "A reference");
    static final Candidate CANDIDATE =
            new Candidate(
                    "c",
                    "redis",
                    "gap",
                    "Clarify the distinction?",
                    10,
                    90,
                    true,
                    Set.of(
                            EvidenceState.INCORRECT,
                            EvidenceState.PARTIAL,
                            EvidenceState.UNCERTAIN));

    static Session session() {
        Session s = new Session();
        s.setId("session");
        s.setUserId(1L);
        s.setRevision(0L);
        s.setMode(Mode.ADAPTIVE);
        s.setStartedAt(1_000);
        s.setCatalog(
                new Catalog(
                        "v1",
                        List.of(
                                new Topic(
                                        "redis",
                                        List.of("redis"),
                                        List.of("gap"),
                                        List.of(SOURCE),
                                        List.of(CANDIDATE)))));
        s.setQuestions(
                new ArrayList<>(
                        List.of(
                                new MainQuestion("1", "redis", "hash1", List.of("redis"), 120),
                                new MainQuestion("2", "other", "hash2", List.of(), 120))));
        return s;
    }

    static Evaluation evaluation(EvidenceState state) {
        return new Evaluation(
                60,
                "Feedback",
                List.of(
                        new Observation(
                                "redis",
                                "gap",
                                state,
                                List.of("answer"),
                                List.of("s"),
                                "Evidence")),
                "test",
                "1");
    }

    Decision decide(Session s, Evaluation e, long now) {
        return policy.decide(s, e, now, Mode.ADAPTIVE, false, Set.of());
    }

    @Test
    void selectsMissingPointFromCatalogWithoutChangingMainPlan() {
        Session s = session();
        List<MainQuestion> original = List.copyOf(s.getQuestions());
        assertEquals("c", decide(s, evaluation(EvidenceState.INCORRECT), 1_000).candidate().id());
        assertEquals(original, s.getQuestions());
    }

    @ParameterizedTest
    @ValueSource(ints = {89, 90, 91})
    void exactBudgetBoundary(int budget) {
        Session s = session();
        s.setTargetDurationSeconds(120 + 60 + 60 + budget);
        assertEquals(
                budget >= 90,
                decide(s, evaluation(EvidenceState.PARTIAL), 1_000).candidate() != null);
    }

    @Test
    void reservesAllFutureMainQuestions() {
        Session s = session();
        s.setTargetDurationSeconds(300);
        assertNull(decide(s, evaluation(EvidenceState.INCORRECT), 1_000).candidate());
        assertEquals(120, policy.budget(s, 1_000, true).mainReserveSeconds());
    }

    @Test
    void contradictoryCrossTurnEvidenceRequiresClarification() {
        var profile = new HashMap<String, Observation>();
        AdaptiveFollowUpPolicy.mergeEvidence(
                profile, evaluation(EvidenceState.INCORRECT).observations().get(0));
        AdaptiveFollowUpPolicy.mergeEvidence(
                profile, evaluation(EvidenceState.COVERED).observations().get(0));
        assertEquals(EvidenceState.UNCERTAIN, profile.values().iterator().next().state());
        AdaptiveFollowUpPolicy.mergeEvidence(
                profile, evaluation(EvidenceState.NOT_OBSERVED).observations().get(0));
        assertEquals(EvidenceState.UNCERTAIN, profile.values().iterator().next().state());
    }

    @Test
    void finishedTimerDoesNotKeepIncreasingOnReportPage() {
        Session s = session();
        s.setFinished(true);
        s.setFinishedAt(121_000);
        assertEquals(120, policy.budget(s, 9_000_000, false).elapsedSeconds());
    }

    @Test
    void zeroLimitsReallyDisableFollowups() {
        Session s = session();
        s.setMaxPerMain(0);
        assertEquals(
                "FOLLOW_UP_DISABLED",
                decide(s, evaluation(EvidenceState.INCORRECT), 1_000).reasonCode());
        s.setMaxPerMain(2);
        s.setMaxPerSession(0);
        assertNull(decide(s, evaluation(EvidenceState.INCORRECT), 1_000).candidate());
    }

    @Test
    void respectsBothCountersAndGapDeduplication() {
        Session s = session();
        s.setFollowUpCount(2);
        assertEquals(
                "PER_MAIN_LIMIT",
                decide(s, evaluation(EvidenceState.INCORRECT), 1_000).reasonCode());
        s.setFollowUpCount(0);
        s.setTotalFollowUps(6);
        assertEquals(
                "SESSION_LIMIT",
                decide(s, evaluation(EvidenceState.INCORRECT), 1_000).reasonCode());
        s.setTotalFollowUps(0);
        s.getProbedGaps().add("redis:gap");
        assertEquals(
                "ALREADY_PROBED",
                decide(s, evaluation(EvidenceState.INCORRECT), 1_000).excluded().get("c"));
    }

    @Test
    void coveredAndUnobservedAreNotAnExcuseToProbe() {
        assertNull(decide(session(), evaluation(EvidenceState.COVERED), 1_000).candidate());
        assertNull(decide(session(), evaluation(EvidenceState.NOT_OBSERVED), 1_000).candidate());
    }

    @Test
    void futureMainCoverageAndRevocationWinOverAi() {
        Session s = session();
        s.getQuestions().set(1, new MainQuestion("2", "redis next", "h", List.of("redis"), 120));
        assertEquals(
                "RESERVED_FOR_LATER_MAIN",
                decide(s, evaluation(EvidenceState.INCORRECT), 1_000).excluded().get("c"));
        assertNull(
                policy.decide(
                                session(),
                                evaluation(EvidenceState.INCORRECT),
                                1_000,
                                Mode.ADAPTIVE,
                                false,
                                Set.of("c"))
                        .candidate());
        assertNull(
                policy.decide(
                                session(),
                                evaluation(EvidenceState.INCORRECT),
                                1_000,
                                Mode.ADAPTIVE,
                                true,
                                Set.of())
                        .candidate());
    }

    @Test
    void expiredTargetKeepsMainSequence() {
        Session s = session();
        Decision d = decide(s, evaluation(EvidenceState.INCORRECT), 1_801_000);
        assertEquals("ADVANCE_MAIN", d.action());
        assertEquals("TARGET_TIME_EXCEEDED", d.reasonCode());
        assertEquals(0, d.budget().remainingSeconds());
    }

    @Test
    void lastMainMayStillHaveFollowup() {
        Session s = session();
        s.getQuestions().remove(1);
        assertEquals(
                "ASK_FOLLOW_UP", decide(s, evaluation(EvidenceState.INCORRECT), 1_000).action());
        assertEquals("COMPLETE", decide(s, evaluation(EvidenceState.COVERED), 1_000).action());
    }

    @Test
    void timeOnlyDoesNotUseAiCoverage() {
        assertNotNull(
                policy.decide(
                                session(),
                                evaluation(EvidenceState.COVERED),
                                1_000,
                                Mode.TIME_ONLY,
                                false,
                                Set.of())
                        .candidate());
    }

    @Test
    void repeatWithSameSnapshotIsDeterministic() {
        Session s = session();
        Evaluation e = evaluation(EvidenceState.PARTIAL);
        assertEquals(decide(s, e, 1_000), decide(s, e, 1_000));
    }

    @Test
    void fabricatedQuoteAndSourceCannotBecomeKnowledgeError() {
        InterviewEvidenceValidator validator = new InterviewEvidenceValidator();
        Session s = session();
        Evaluation invalid = evaluation(EvidenceState.INCORRECT);
        Evaluation checked =
                validator.validate(
                        invalid, "actual response", s.getCatalog().topics(), List.of(SOURCE));
        assertEquals(EvidenceState.UNCERTAIN, checked.observations().get(0).state());
        assertTrue(checked.observations().get(0).answerQuotes().isEmpty());
    }

    @Test
    void unknownRubricIsDiscardedAndLongAnswerTailIsPreserved() {
        var validator = new InterviewEvidenceValidator();
        var s = session();
        String text = "prefix".repeat(200) + "answer";
        assertEquals(
                EvidenceState.INCORRECT,
                validator
                        .validate(
                                evaluation(EvidenceState.INCORRECT),
                                text,
                                s.getCatalog().topics(),
                                List.of(SOURCE))
                        .observations()
                        .get(0)
                        .state());
        Evaluation unknown =
                new Evaluation(
                        80,
                        "ok",
                        List.of(
                                new Observation(
                                        "other",
                                        "gap",
                                        EvidenceState.INCORRECT,
                                        List.of("answer"),
                                        List.of("s"),
                                        "")),
                        "test",
                        "1");
        assertTrue(
                validator
                        .validate(unknown, text, s.getCatalog().topics(), List.of(SOURCE))
                        .observations()
                        .isEmpty());
    }

    @Test
    void conflictingObservationsAreUncertain() {
        var a = evaluation(EvidenceState.INCORRECT).observations().get(0);
        var b = evaluation(EvidenceState.COVERED).observations().get(0);
        var checked =
                new InterviewEvidenceValidator()
                        .validate(
                                new Evaluation(60, "ok", List.of(a, b), "test", "1"),
                                "answer",
                                session().getCatalog().topics(),
                                List.of(SOURCE));
        assertEquals(EvidenceState.UNCERTAIN, checked.observations().get(0).state());
    }
}
