package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import org.springframework.stereotype.Component;

import java.util.*;

/** Pure policy. Its persisted input can be replayed without a model or wall clock. */
@Component
public class AdaptiveFollowUpPolicy {
    public Budget budget(Session s, long now, boolean answeredCurrent) {
        long elapsed =
                s.getStartedAt() == 0
                        ? 0
                        : Math.max(
                                0,
                                ((s.isFinished() ? s.getFinishedAt() : now) - s.getStartedAt())
                                        / 1000);
        long remaining = s.getTargetDurationSeconds() + s.getExtensionSeconds() - elapsed;
        int start =
                Math.min(
                        s.getQuestions().size(),
                        s.getMainIndex()
                                + (answeredCurrent || s.getCurrentCandidate() != null ? 1 : 0));
        long reserve =
                s.getQuestions().subList(start, s.getQuestions().size()).stream()
                        .mapToLong(MainQuestion::seconds)
                        .sum();
        long available = remaining - reserve - s.getClosingSeconds() - s.getBufferSeconds();
        return new Budget(
                now,
                s.getStartedAt(),
                s.getTargetDurationSeconds(),
                s.getExtensionSeconds(),
                elapsed,
                Math.max(0, remaining),
                Math.max(0, -remaining),
                s.getQuestions().size() - start,
                reserve,
                s.getClosingSeconds(),
                s.getBufferSeconds(),
                available,
                remaining <= 0 ? "OVERTIME" : available < 90 ? "TIME_TIGHT" : "ON_TRACK");
    }

    public Decision decide(
            Session s,
            Evaluation evaluation,
            long now,
            Mode mode,
            boolean disabled,
            Set<String> revoked) {
        Budget budget = budget(s, now, true);
        Map<String, String> excluded = new LinkedHashMap<>();
        if (disabled || s.getMaxPerMain() == 0 || s.getMaxPerSession() == 0)
            return advance(s, budget, "FOLLOW_UP_DISABLED", excluded);
        if (s.getFollowUpCount() >= s.getMaxPerMain())
            return advance(s, budget, "PER_MAIN_LIMIT", excluded);
        if (s.getTotalFollowUps() >= s.getMaxPerSession())
            return advance(s, budget, "SESSION_LIMIT", excluded);
        if (budget.followUpBudgetSeconds() <= 0)
            return advance(
                    s,
                    budget,
                    budget.status().equals("OVERTIME")
                            ? "TARGET_TIME_EXCEEDED"
                            : "TIME_RESERVED_FOR_MAIN",
                    excluded);
        if (s.isFinished() || s.getMainIndex() >= s.getQuestions().size())
            return advance(s, budget, "INTERVIEW_COMPLETED", excluded);
        List<String> allowed = s.getQuestions().get(s.getMainIndex()).topicIds();
        if (allowed.isEmpty()) return advance(s, budget, "KNOWLEDGE_UNMAPPED", excluded);
        Map<String, Observation> profile = new HashMap<>();
        for (Turn turn : s.getTurns())
            for (Observation o : turn.evaluation().observations()) mergeEvidence(profile, o);
        for (Observation o : evaluation.observations()) mergeEvidence(profile, o);
        Set<String> future = new HashSet<>();
        for (int i = s.getMainIndex() + 1; i < s.getQuestions().size(); i++)
            future.addAll(s.getQuestions().get(i).topicIds());
        List<Candidate> candidates = new ArrayList<>();
        for (Topic topic : InterviewKnowledgeCatalog.retrieve(s.getCatalog(), allowed))
            for (Candidate c : topic.candidates()) {
                String key = c.knowledgePointId() + ":" + c.gapKey();
                Observation observation = profile.get(key);
                EvidenceState state =
                        observation == null ? EvidenceState.NOT_OBSERVED : observation.state();
                String reason = null;
                if (revoked.contains(c.id())) reason = "CANDIDATE_REVOKED";
                else if (s.getProbedGaps().contains(key)) reason = "ALREADY_PROBED";
                else if (future.contains(c.knowledgePointId())) reason = "RESERVED_FOR_LATER_MAIN";
                else if (mode == Mode.TIME_ONLY && !c.neutral()) reason = "NOT_NEUTRAL";
                else if (mode != Mode.TIME_ONLY && state == EvidenceState.COVERED)
                    reason = "ALREADY_COVERED";
                else if (mode != Mode.TIME_ONLY
                        && (!c.states().contains(state)
                                || state == EvidenceState.UNCERTAIN && !c.neutral()))
                    reason = "EVIDENCE_INSUFFICIENT";
                else if (budget.followUpBudgetSeconds() < c.seconds())
                    reason = "TIME_RESERVED_FOR_MAIN";
                if (reason != null) excluded.put(c.id(), reason);
                else candidates.add(c);
            }
        candidates.sort(
                Comparator.<Candidate>comparingInt(
                                c ->
                                        mode == Mode.TIME_ONLY
                                                ? 0
                                                : priority(
                                                        profile.get(
                                                                c.knowledgePointId()
                                                                        + ":"
                                                                        + c.gapKey())))
                        .thenComparingInt(Candidate::priority)
                        .thenComparingInt(Candidate::seconds)
                        .thenComparing(Candidate::id));
        if (candidates.isEmpty()) return advance(s, budget, "NO_ELIGIBLE_CANDIDATE", excluded);
        Candidate selected = candidates.get(0);
        Observation o = profile.get(selected.knowledgePointId() + ":" + selected.gapKey());
        String reason =
                mode == Mode.TIME_ONLY
                        ? "FIXED_CATALOG_ORDER"
                        : o == null
                                ? "PROBE_OPTIONAL_COVERAGE"
                                : switch (o.state()) {
                                    case INCORRECT -> "CLARIFY_INCORRECT_POINT";
                                    case PARTIAL -> "PROBE_REQUIRED_GAP";
                                    case UNCERTAIN -> "CLARIFY_UNCERTAIN_EVIDENCE";
                                    default -> "PROBE_OPTIONAL_COVERAGE";
                                };
        return new Decision(
                "ASK_FOLLOW_UP",
                reason,
                selected,
                budget,
                s.getPolicyVersion(),
                s.getCatalog().version(),
                excluded);
    }

    public Decision advance(Session s, Budget budget, String reason, Map<String, String> excluded) {
        return new Decision(
                s.getMainIndex() + 1 >= s.getQuestions().size() ? "COMPLETE" : "ADVANCE_MAIN",
                reason,
                null,
                budget,
                s.getPolicyVersion(),
                s.getCatalog().version(),
                excluded);
    }

    private int priority(Observation o) {
        if (o == null) return 3;
        return switch (o.state()) {
            case INCORRECT -> 0;
            case PARTIAL -> 1;
            case UNCERTAIN -> 2;
            default -> 3;
        };
    }

    static void mergeEvidence(Map<String, Observation> profile, Observation next) {
        String key = next.knowledgePointId() + ":" + next.rubricPointId();
        Observation previous = profile.get(key);
        if (next.state() == EvidenceState.NOT_OBSERVED && previous != null) return;
        if (previous != null
                && previous.state() != EvidenceState.NOT_OBSERVED
                && previous.state() != next.state()) {
            profile.put(
                    key,
                    new Observation(
                            next.knowledgePointId(),
                            next.rubricPointId(),
                            EvidenceState.UNCERTAIN,
                            List.of(),
                            List.of(),
                            "Conflicting evidence across answers; clarification required"));
        } else profile.put(key, next);
    }
}
