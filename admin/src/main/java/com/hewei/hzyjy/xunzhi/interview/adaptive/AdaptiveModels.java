package com.hewei.hzyjy.xunzhi.interview.adaptive;

import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewAnswerRespDTO;

import lombok.Data;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.*;

/** Durable aggregate: a committed turn, score and cursor change are one MongoDB write. */
public final class AdaptiveModels {
    private AdaptiveModels() {}

    public enum Mode {
        TIME_ONLY,
        SHADOW,
        ADAPTIVE
    }

    public enum EvidenceState {
        COVERED,
        PARTIAL,
        INCORRECT,
        NOT_OBSERVED,
        UNCERTAIN
    }

    public record Source(String id, String title, String url, String version, String text) {}

    public record Candidate(
            String id,
            String knowledgePointId,
            String gapKey,
            String question,
            int priority,
            int seconds,
            boolean neutral,
            Set<EvidenceState> states) {}

    public record Topic(
            String id,
            List<String> aliases,
            List<String> rubricPoints,
            List<Source> sources,
            List<Candidate> candidates) {}

    public record KnowledgeScope(Set<String> roles, String technology, Set<String> versions) {}

    public record RetrievalScope(String role, Map<String, String> technologyVersions) {
        public static RetrievalScope defaults() {
            return new RetrievalScope(
                    "java-backend", Map.of("java", "17", "mysql", "8.0", "redis", "general-v1"));
        }
    }

    public record Catalog(String version, List<Topic> topics, Map<String, KnowledgeScope> scopes) {
        public Catalog(String version, List<Topic> topics) {
            this(version, topics, Map.of());
        }
    }

    public record MainQuestion(
            String number, String text, String hash, List<String> topicIds, int seconds) {}

    public record Observation(
            String knowledgePointId,
            String rubricPointId,
            EvidenceState state,
            List<String> answerQuotes,
            List<String> sourceChunkIds,
            String rationale) {}

    public record Evaluation(
            int score,
            String feedback,
            List<Observation> observations,
            String modelVersion,
            String schemaVersion) {}

    public record Budget(
            long serverTime,
            long interviewStartedAt,
            int targetDurationSeconds,
            int grantedExtensionSeconds,
            long elapsedSeconds,
            long remainingSeconds,
            long overtimeSeconds,
            int mainQuestionsRemaining,
            long mainReserveSeconds,
            int closingReserveSeconds,
            int uncertaintyBufferSeconds,
            long followUpBudgetSeconds,
            String status) {}

    public record Decision(
            String action,
            String reasonCode,
            Candidate candidate,
            Budget budget,
            String policyVersion,
            String catalogVersion,
            Map<String, String> excluded) {}

    public record Turn(
            String requestId,
            String answerHash,
            String questionNumber,
            String question,
            String answer,
            long committedAt,
            Evaluation evaluation,
            List<Source> sources,
            Decision decision,
            Decision shadowDecision,
            InterviewAnswerRespDTO response) {}

    @Data
    public static class Pending {
        private String requestId;
        private String questionNumber;
        private String answer;
        private String answerHash;
        private Evaluation evaluation;
    }

    @Data
    @Document("interview_adaptive_session")
    public static class Session {
        @Id private String id;
        @Version private Long revision;
        private Long userId;
        private Mode mode;
        private String policyVersion = "adaptive-v1";
        private Catalog catalog;
        private String catalogHash;
        private RetrievalScope retrievalScope;
        private Map<String, String> catalogExclusions = new LinkedHashMap<>();
        private List<MainQuestion> questions = new ArrayList<>();
        private String questionSetHash;
        private int targetDurationSeconds = 1800;
        private int extensionSeconds;
        private Map<String, Integer> extensions = new LinkedHashMap<>();
        private int maxPerMain = 2;
        private int maxPerSession = 6;
        private int closingSeconds = 60;
        private int bufferSeconds = 60;
        private long startedAt;
        private int mainIndex;
        private int followUpCount;
        private int totalFollowUps;
        private Candidate currentCandidate;
        private Set<String> probedGaps = new LinkedHashSet<>();
        private List<Turn> turns = new ArrayList<>();
        private Pending pending;
        private boolean finished;
        private long finishedAt;
        private String finishReason;
        private boolean archived;
        private boolean deleted;
        private String reportStatus = "NOT_REQUESTED";
        private String reportError;
        private String reportMarkdown;
        private String reportLease;
        private long reportLeaseUntil;
        private long reportNextAttemptAt;
        private int reportAttempts;
        private Set<String> reportRetryIds = new HashSet<>();

        public String currentNumber() {
            if (finished || mainIndex >= questions.size()) return null;
            String main = questions.get(mainIndex).number();
            return currentCandidate == null ? main : main + "-F" + followUpCount;
        }

        public String currentText() {
            if (finished || mainIndex >= questions.size()) return null;
            return currentCandidate == null
                    ? questions.get(mainIndex).text()
                    : currentCandidate.question();
        }

        public int totalScore() {
            return (int)
                    Math.round(
                            turns.stream()
                                    .filter(t -> !t.questionNumber().contains("-F"))
                                    .mapToInt(t -> t.evaluation().score())
                                    .average()
                                    .orElse(0));
        }
    }

    @Data
    @Document("interview_adaptive_mistake")
    public static class Mistake {
        @Id private String id;
        @Version private Long revision;
        private Long userId;
        private String knowledgePointId;
        private String gapKey;
        private String type;
        private String question;
        private String status = "TO_REVIEW";
        private String masterySource;
        private String note;
        private boolean dismissed;
        private Set<String> sessionIds = new LinkedHashSet<>();
        private List<MistakeEvidence> evidence = new ArrayList<>();
        private List<Practice> practices = new ArrayList<>();
        private long updatedAt;
    }

    public record MistakeEvidence(
            String sessionId,
            String requestId,
            String quote,
            String rationale,
            List<Source> sources,
            String resolution) {}

    public record Practice(
            String requestId,
            String answerHash,
            String answer,
            long timestamp,
            Evaluation evaluation,
            boolean independentlyCorrect) {}
}
