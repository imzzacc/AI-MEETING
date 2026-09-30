package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import cn.hutool.crypto.digest.DigestUtil;

import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewReviewService {
    private final AdaptiveSessionStore store;
    private final AdaptiveInterviewService interviews;
    private final GroundedInterviewEvaluator evaluator;
    private final Clock clock;
    private final AdaptiveConfiguration configuration;

    public Map<String, Object> report(String sessionId, Long userId) {
        Session s = interviews.owned(sessionId, userId);
        return Map.of(
                "status",
                s.getReportStatus(),
                "version",
                "1",
                "evidenceCompleteness",
                "COMPLETE",
                "markdown",
                Objects.toString(s.getReportMarkdown(), ""),
                "error",
                Objects.toString(s.getReportError(), ""));
    }

    public void retry(String sessionId, Long userId, String requestId) {
        AdaptiveInterviewService.validateRequestId(requestId);
        interviews.locked(
                sessionId,
                () -> {
                    Session s = interviews.owned(sessionId, userId);
                    String key = DigestUtil.sha256Hex(requestId);
                    if (s.getReportRetryIds().contains(key)) return null;
                    if (!s.isFinished()) throw new ClientException("REPORT_NOT_READY");
                    if (!"FAILED".equals(s.getReportStatus())) return null;
                    if (s.getReportRetryIds().size() >= 20)
                        throw new ClientException("Report retry limit reached");
                    s.getReportRetryIds().add(key);
                    s.setReportStatus("PENDING");
                    s.setReportAttempts(0);
                    s.setReportNextAttemptAt(0);
                    s.setReportError(null);
                    store.save(s);
                    return null;
                });
    }

    @Scheduled(fixedDelayString = "${xunzhi-agent.interview.adaptive.report-poll-ms:5000}")
    public void poll() {
        if (!configuration.isReportWorkerEnabled()) return;
        try {
            for (Session pending : store.work(clock.millis())) {
                try {
                    process(pending.getId());
                } catch (Exception e) {
                    log.warn(
                            "Review job deferred, sessionId={}, errorType={}",
                            pending.getId(),
                            e.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            log.debug("Adaptive report store unavailable: {}", e.getClass().getSimpleName());
        }
    }

    public void process(String id) {
        interviews.locked(
                id,
                () -> {
                    Session s = store.find(id);
                    if (s == null
                            || s.isDeleted()
                            || !s.isFinished()
                            || Set.of("SUCCEEDED", "FAILED").contains(s.getReportStatus())
                            || s.getReportLeaseUntil() > clock.millis()
                            || s.getReportNextAttemptAt() > clock.millis()) return null;
                    String lease = UUID.randomUUID().toString();
                    s.setReportStatus("RUNNING");
                    s.setReportLease(lease);
                    s.setReportLeaseUntil(clock.millis() + 120_000);
                    s.setReportAttempts(s.getReportAttempts() + 1);
                    store.save(s);
                    try {
                        interviews.owned(id, s.getUserId());
                        interviews.archive(s);
                        String markdown = render(s);
                        collectMistakes(s);
                        Session latest = interviews.owned(id, s.getUserId());
                        if (!lease.equals(latest.getReportLease())
                                || latest.getReportLeaseUntil() < clock.millis()) return null;
                        latest.setReportMarkdown(markdown);
                        latest.setReportStatus("SUCCEEDED");
                        latest.setReportError(null);
                        latest.setReportLeaseUntil(0);
                        store.save(latest);
                    } catch (Exception e) {
                        Session latest = store.find(id);
                        if (latest != null
                                && !latest.isDeleted()
                                && lease.equals(latest.getReportLease())) {
                            latest.setReportStatus(
                                    latest.getReportAttempts() >= 3 ? "FAILED" : "PENDING");
                            latest.setReportError("报告暂时无法生成，请重试。");
                            latest.setReportLeaseUntil(0);
                            long delay =
                                    latest.getReportAttempts() == 1
                                            ? 10_000
                                            : latest.getReportAttempts() == 2 ? 30_000 : 120_000;
                            latest.setReportNextAttemptAt(clock.millis() + delay);
                            store.save(latest);
                        }
                    }
                    return null;
                });
    }

    static String render(Session s) {
        StringBuilder out = new StringBuilder("# 面试复盘\n\n");
        long answered =
                s.getTurns().stream().filter(t -> !t.questionNumber().contains("-F")).count();
        out.append("已回答主问题：")
                .append(answered)
                .append(" / ")
                .append(s.getQuestions().size())
                .append("；追问：")
                .append(s.getTotalFollowUps())
                .append("；主问题平均分：")
                .append(s.totalScore())
                .append("。\n\n");
        out.append("目标时长：")
                .append((s.getTargetDurationSeconds() + s.getExtensionSeconds()) / 60)
                .append(" 分钟。");
        if (s.getStartedAt() > 0)
            out.append("实际时长：")
                    .append(Math.max(0, (s.getFinishedAt() - s.getStartedAt()) / 1000))
                    .append(" 秒。");
        out.append("\n\n以下评价依据本场回答生成，参考要点来自标明的资料。未作答不计为知识错误。\n\n");
        for (MainQuestion q : s.getQuestions()) {
            out.append("## 主问题 ")
                    .append(q.number())
                    .append("\n\n")
                    .append(escape(q.text()))
                    .append("\n\n");
            List<Turn> turns =
                    s.getTurns().stream()
                            .filter(
                                    t ->
                                            t.questionNumber().equals(q.number())
                                                    || t.questionNumber()
                                                            .startsWith(q.number() + "-F"))
                            .toList();
            if (turns.isEmpty()) out.append("未作答。\n\n");
            for (Turn t : turns) {
                if (t.questionNumber().contains("-F"))
                    out.append("### 追问\n\n").append(escape(t.question())).append("\n\n");
                out.append("**用户原回答**\n\n")
                        .append(escape(t.answer()))
                        .append("\n\n**本轮反馈（AI 辅助）**\n\n")
                        .append(escape(t.evaluation().feedback()))
                        .append("\n\n");
                if (t.evaluation().observations().isEmpty()) out.append("知识点证据不足，本轮未作自动知识结论。\n\n");
                for (Observation o : t.evaluation().observations()) {
                    out.append("- ")
                            .append(escape(o.rubricPointId()))
                            .append("：")
                            .append(label(o.state()))
                            .append("；")
                            .append(escape(o.rationale()))
                            .append("\n");
                    for (String quote : o.answerQuotes())
                        out.append("  - 回答依据：").append(escape(quote)).append("\n");
                }
                out.append("\n");
            }
            var sources = new LinkedHashMap<String, Source>();
            for (Turn t : turns) for (Source source : t.sources()) sources.put(source.id(), source);
            if (!sources.isEmpty()) out.append("**参考要点与来源**\n\n");
            for (Source source : sources.values())
                out.append("- [")
                        .append(escape(source.title()))
                        .append("](")
                        .append(source.url())
                        .append(")（")
                        .append(escape(source.version()))
                        .append("）：")
                        .append(escape(source.text()))
                        .append("\n\n");
        }
        out.append("## 复习建议\n\n优先复习错题集中尚未解决的概念与必要要点；经追问补全的内容作为巩固项练习。资料不足的判断需先确认。\n");
        return out.toString();
    }

    private void collectMistakes(Session s) {
        Map<String, Observation> latest = new LinkedHashMap<>();
        for (Turn t : s.getTurns())
            for (Observation o : t.evaluation().observations())
                latest.put(o.knowledgePointId() + ":" + o.rubricPointId(), o);
        for (Turn t : s.getTurns())
            for (Observation o : t.evaluation().observations()) {
                if (o.state() == EvidenceState.COVERED || o.state() == EvidenceState.NOT_OBSERVED)
                    continue;
                String gap = o.knowledgePointId() + ":" + o.rubricPointId();
                String type =
                        o.state() == EvidenceState.INCORRECT
                                ? "CONCEPT_ERROR"
                                : o.state() == EvidenceState.PARTIAL
                                        ? "REQUIRED_GAP"
                                        : "NEEDS_CONFIRMATION";
                String identity = s.getUserId() + "|" + gap + "|" + type;
                // Keep legacy IDs so retrying an old report cannot bypass a user's dismissal.
                if (s.getRetrievalScope() != null)
                    identity +=
                            "|"
                                    + s.getCatalog().version()
                                    + "|"
                                    + s.getRetrievalScope().role()
                                    + "|"
                                    + new TreeMap<>(s.getRetrievalScope().technologyVersions());
                String id = DigestUtil.sha256Hex(identity);
                boolean resolved = latest.get(gap).state() == EvidenceState.COVERED;
                interviews.locked(
                        "mistake-" + id,
                        () -> {
                            Mistake m = store.findMistake(id);
                            if (m != null && m.isDismissed()) return null;
                            if (m == null) {
                                m = new Mistake();
                                m.setId(id);
                                m.setUserId(s.getUserId());
                                m.setKnowledgePointId(o.knowledgePointId());
                                m.setGapKey(o.rubricPointId());
                                m.setType(type);
                                m.setQuestion(t.question());
                                m.setStatus(
                                        resolved
                                                ? "REVIEWING"
                                                : o.state() == EvidenceState.INCORRECT
                                                        ? "TO_REVIEW"
                                                        : "NEEDS_CONFIRMATION");
                            }
                            boolean duplicate =
                                    m.getEvidence().stream()
                                            .anyMatch(
                                                    e ->
                                                            e.sessionId().equals(s.getId())
                                                                    && e.requestId()
                                                                            .equals(t.requestId()));
                            if (!duplicate) {
                                m.getSessionIds().add(s.getId());
                                m.getEvidence()
                                        .add(
                                                new MistakeEvidence(
                                                        s.getId(),
                                                        t.requestId(),
                                                        String.join("\n", o.answerQuotes()),
                                                        o.rationale(),
                                                        t.sources().stream()
                                                                .filter(
                                                                        source ->
                                                                                o.sourceChunkIds()
                                                                                        .contains(
                                                                                                source
                                                                                                        .id()))
                                                                .toList(),
                                                        resolved
                                                                ? "RESOLVED_WITH_PROMPT"
                                                                : "UNRESOLVED"));
                                if (m.getNote() == null
                                        && !resolved
                                        && o.state() != EvidenceState.UNCERTAIN)
                                    m.setStatus(o.state() == EvidenceState.INCORRECT
                                            ? "TO_REVIEW" : "NEEDS_CONFIRMATION");
                                m.setUpdatedAt(clock.millis());
                                store.saveMistake(m);
                            }
                            return null;
                        });
            }
    }

    public List<Mistake> mistakes(Long userId, int page, int size, String status, String search) {
        if (page < 1 || size < 1 || size > 100 || page > 10000)
            throw new ClientException("Invalid page");
        if (search != null && search.length() > 100) throw new ClientException("Search too long");
        return store.mistakes(userId, (page - 1) * size, size, status, search).stream()
                .filter(m -> sourcesAvailable(m, userId))
                .toList();
    }

    private boolean sourcesAvailable(Mistake m, Long userId) {
        try {
            for (String id : m.getSessionIds()) interviews.owned(id, userId);
            return true;
        } catch (ClientException e) {
            return false;
        }
    }

    private Mistake ownedMistake(String id, Long userId) {
        Mistake m = store.findMistake(id);
        if (m == null || !Objects.equals(m.getUserId(), userId) || m.isDismissed())
            throw new ClientException("Mistake not found");
        // The originating session may have been deleted while a report worker was running.
        for (String sessionId : m.getSessionIds()) interviews.owned(sessionId, userId);
        return m;
    }

    public Mistake update(String id, Long userId, Long revision, String status, String note) {
        return interviews.locked(
                "mistake-" + id,
                () -> {
                    Mistake m = ownedMistake(id, userId);
                    if (!Objects.equals(m.getRevision(), revision))
                        throw new ClientException("REVISION_CONFLICT");
                    if (status != null) {
                        if (!Set.of(
                                        "NEEDS_CONFIRMATION",
                                        "TO_REVIEW",
                                        "REVIEWING",
                                        "MASTERED",
                                        "DISMISSED")
                                .contains(status)) throw new ClientException("Invalid status");
                        m.setStatus(status);
                        m.setMasterySource("SELF_REPORTED");
                        m.setDismissed(status.equals("DISMISSED"));
                    }
                    if (note != null && note.length() > 2000)
                        throw new ClientException("Note too long");
                    m.setNote(note == null ? "" : note);
                    m.setUpdatedAt(clock.millis());
                    return store.saveMistake(m);
                });
    }

    public void delete(String id, Long userId) {
        interviews.locked(
                "mistake-" + id,
                () -> {
                    Mistake raw = store.findMistake(id);
                    if (raw != null && Objects.equals(raw.getUserId(), userId) && raw.isDismissed())
                        return null;
                    Mistake m = ownedMistake(id, userId);
                    m.setDismissed(true);
                    m.setStatus("DISMISSED");
                    store.saveMistake(m);
                    return null;
                });
    }

    public Mistake review(String id, Long userId, String requestId, String answer) {
        AdaptiveInterviewService.validateRequestId(requestId);
        if (answer == null || answer.isBlank() || answer.length() > 5000)
            throw new ClientException("Invalid answer length");
        return interviews.locked(
                "mistake-" + id,
                () -> {
                    Mistake m = ownedMistake(id, userId);
                    String hash = DigestUtil.sha256Hex(answer.trim().replaceAll("\\s+", " "));
                    for (Practice p : m.getPractices())
                        if (p.requestId().equals(requestId)) {
                            if (!p.answerHash().equals(hash))
                                throw new ClientException("REQUEST_PAYLOAD_CONFLICT");
                            return m;
                        }
                    // A new request ID with the same body is not an independent practice.
                    if (m.getPractices().stream().anyMatch(p -> p.answerHash().equals(hash)))
                        throw new ClientException(
                                "Please write a new answer for an independent practice");
                    if (m.getPractices().size() >= 100)
                        throw new ClientException("Practice history limit reached");
                    if (m.getSessionIds().isEmpty())
                        throw new ClientException("Review reference unavailable");
                    String sourceSessionId = m.getSessionIds().iterator().next();
                    List<Topic> topics =
                            InterviewKnowledgeCatalog.retrieve(
                                    interviews
                                            .owned(sourceSessionId, userId)
                                            .getCatalog(),
                                    List.of(m.getKnowledgePointId()));
                    if (topics.isEmpty()) throw new ClientException("Review reference unavailable");
                    Evaluation result =
                            evaluator.evaluate(
                                    "review-" + id,
                                    requestId,
                                    m.getQuestion(),
                                    answer,
                                    topics,
                                    InterviewKnowledgeCatalog.sources(topics));
                    // A session can be tombstoned while the model is running. Never publish
                    // practice feedback derived from a source whose deletion has started.
                    ownedMistake(id, userId);
                    boolean correct =
                            result.observations().stream()
                                    .anyMatch(
                                            o ->
                                                    o.knowledgePointId()
                                                                    .equals(m.getKnowledgePointId())
                                                            && o.rubricPointId()
                                                                    .equals(m.getGapKey())
                                                            && o.state() == EvidenceState.COVERED);
                    boolean wrong =
                            result.observations().stream()
                                    .anyMatch(
                                            o ->
                                                    o.knowledgePointId()
                                                                    .equals(m.getKnowledgePointId())
                                                            && o.rubricPointId()
                                                                    .equals(m.getGapKey())
                                                            && o.state()
                                                                    == EvidenceState.INCORRECT);
                    m.getPractices()
                            .add(
                                    new Practice(
                                            requestId,
                                            hash,
                                            answer,
                                            clock.millis(),
                                            result,
                                            correct,
                                            sourceSessionId));
                    if (wrong) {
                        m.setStatus("TO_REVIEW");
                        m.setMasterySource(null);
                    } else if (correct) {
                        int streak = 0;
                        for (int i = m.getPractices().size() - 1;
                                i >= 0 && m.getPractices().get(i).independentlyCorrect();
                                i--) streak++;
                        m.setStatus(streak >= 2 ? "MASTERED" : "REVIEWING");
                        m.setMasterySource(streak >= 2 ? "PRACTICE_VERIFIED" : null);
                    }
                    m.setUpdatedAt(clock.millis());
                    return store.saveMistake(m);
                });
    }

    private static String label(EvidenceState state) {
        return switch (state) {
            case COVERED -> "本轮已覆盖";
            case PARTIAL -> "部分覆盖，必要缺口待核对";
            case INCORRECT -> "存在错误";
            case NOT_OBSERVED -> "未涉及";
            case UNCERTAIN -> "待确认";
        };
    }

    private static String escape(String s) {
        return Objects.toString(s, "")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("*", "\\*")
                .replace("`", "\\`")
                .replace("#", "\\#");
    }
}
