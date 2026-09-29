package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import cn.hutool.crypto.digest.DigestUtil;

import com.alibaba.fastjson2.JSON;
import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewRecordSaveReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.xunzhi.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.hewei.hzyjy.xunzhi.interview.flow.answer.InterviewQuestionLockService;
import com.hewei.hzyjy.xunzhi.interview.service.*;
import com.hewei.hzyjy.xunzhi.interview.service.cache.*;
import com.hewei.hzyjy.xunzhi.interview.service.model.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class AdaptiveInterviewService {
    private final AdaptiveSessionStore store;
    private final AdaptiveConfiguration config;
    private final InterviewKnowledgeCatalog catalog;
    private final AdaptiveFollowUpPolicy policy;
    private final AdaptiveRuleEngine rules;
    private final GroundedInterviewEvaluator evaluator;
    private final InterviewSessionService sessions;
    private final InterviewQuestionCacheService cache;
    private final InterviewCacheStore cacheStore;
    private final InterviewQuestionLockService locks;
    private final InterviewSessionRuntimeSnapshotService snapshots;
    private final InterviewRecordService records;
    private final Clock clock;

    public boolean active(String id) {
        return store.find(id) != null;
    }

    public boolean enabled() {
        return config.isEnabled();
    }

    public Session owned(String id, Long userId) {
        sessions.requireOwnedSession(id, userId);
        Session s = store.find(id);
        if (s == null || s.isDeleted() || !Objects.equals(userId, s.getUserId()))
            throw new ClientException("Session not found");
        return s;
    }

    public Session configure(String id, Long userId, int seconds, Long expectedRevision) {
        if (!config.isEnabled()) throw new ClientException("Adaptive interview is not enabled");
        if (!Set.of(1200, 1800, 2700).contains(seconds))
            throw new ClientException("Unsupported target duration");
        return locked(
                id,
                () -> {
                    var base = sessions.requireOwnedSession(id, userId);
                    if (!Set.of("DRAFT", "READY").contains(base.getStatus())
                            || base.getStartTime() != null)
                        throw new ClientException("POLICY_LOCKED");
                    Session s = store.find(id);
                    if (s == null) {
                        if (expectedRevision != null)
                            throw new ClientException("REVISION_CONFLICT");
                        s = new Session();
                        s.setId(id);
                        s.setUserId(userId);
                        s.setCatalog(catalog.snapshot());
                        s.setMode(config.getMode());
                        s.setCatalogHash(DigestUtil.sha256Hex(JSON.toJSONString(s.getCatalog())));
                        if (config.getMaxPerMain() < 0
                                || config.getMaxPerMain() > 5
                                || config.getMaxPerSession() < 0
                                || config.getMaxPerSession() > 20)
                            throw new ClientException("Invalid follow-up limits");
                        s.setMaxPerMain(config.getMaxPerMain());
                        s.setMaxPerSession(config.getMaxPerSession());
                    } else {
                        if (s.getStartedAt() != 0
                                || !Objects.equals(s.getRevision(), expectedRevision))
                            throw new ClientException("POLICY_LOCKED_OR_REVISION_CONFLICT");
                    }
                    s.setTargetDurationSeconds(seconds);
                    return store.save(s);
                });
    }

    public Budget extend(String id, Long userId, String requestId, int seconds) {
        validateRequestId(requestId);
        return locked(
                id,
                () -> {
                    Session s = owned(id, userId);
                    String key = DigestUtil.sha256Hex(requestId);
                    if (s.getExtensions().containsKey(key)) {
                        if (s.getExtensions().get(key) != seconds)
                            throw new ClientException("REQUEST_PAYLOAD_CONFLICT");
                        return policy.budget(s, clock.millis(), false);
                    }
                    if (s.isFinished() || s.getStartedAt() == 0)
                        throw new ClientException("SESSION_NOT_ACTIVE");
                    if (seconds != 300 || s.getExtensionSeconds() + seconds > 900)
                        throw new ClientException("Extension limit exceeded");
                    s.getExtensions().put(key, seconds);
                    s.setExtensionSeconds(s.getExtensionSeconds() + seconds);
                    store.save(s);
                    return policy.budget(s, clock.millis(), false);
                });
    }

    public InterviewAnswerRespDTO current(String id, Long userId) {
        return locked(
                id,
                () -> {
                    Session s = owned(id, userId);
                    ensureReady(s);
                    if (s.getStartedAt() == 0 && !s.isFinished()) {
                        s.setStartedAt(clock.millis());
                        store.save(s);
                        sessions.markInProgressIfReady(id, userId);
                    }
                    project(s);
                    return currentResponse(s);
                });
    }

    public Budget budget(String id, Long userId) {
        return policy.budget(owned(id, userId), clock.millis(), false);
    }

    public InterviewAnswerRespDTO answer(String id, Long userId, InterviewAnswerReqDTO request) {
        if (request == null
                || request.getAnswerContent() == null
                || request.getAnswerContent().isBlank()
                || request.getAnswerContent().length() > 5000)
            throw new ClientException("Answer must contain 1 to 5000 characters");
        if (request.getQuestionNumber() == null
                || request.getQuestionNumber().isBlank()
                || request.getQuestionNumber().length() > 32)
            throw new ClientException("Invalid question number");
        String hash =
                DigestUtil.sha256Hex(
                        request.getQuestionNumber() + "\n" + request.getAnswerContent());
        String key =
                request.getRequestId() == null || request.getRequestId().isBlank()
                        ? "auto-" + hash.substring(0, 32)
                        : request.getRequestId().trim();
        validateRequestId(key);
        return locked(
                id,
                () -> {
                    Session s = owned(id, userId);
                    for (Turn t : s.getTurns())
                        if (t.requestId().equals(key)) {
                            if (!t.answerHash().equals(hash))
                                throw new ClientException("REQUEST_PAYLOAD_CONFLICT");
                            project(s);
                            return t.response();
                        }
                    ensureReady(s);
                    if (s.isFinished() || s.getStartedAt() == 0)
                        throw new ClientException("SESSION_NOT_ACTIVE");
                    if (!Objects.equals(s.currentNumber(), request.getQuestionNumber()))
                        throw new ClientException(
                                "stale question number, please refresh current question");
                    Pending pending = s.getPending();
                    if (pending != null
                            && (!pending.getRequestId().equals(key)
                                    || !pending.getAnswerHash().equals(hash)))
                        throw new ClientException(
                                "A different answer is pending; retry the original request");
                    if (pending == null) {
                        pending = new Pending();
                        pending.setRequestId(key);
                        pending.setQuestionNumber(s.currentNumber());
                        pending.setAnswer(request.getAnswerContent());
                        pending.setAnswerHash(hash);
                        s.setPending(pending);
                        store.save(s);
                    }
                    List<Topic> topics =
                            InterviewKnowledgeCatalog.retrieve(
                                    s.getCatalog(),
                                    s.getQuestions().get(s.getMainIndex()).topicIds());
                    List<Source> sources = InterviewKnowledgeCatalog.sources(topics);
                    if (pending.getEvaluation() == null) {
                        pending.setEvaluation(
                                evaluator.evaluate(
                                        id,
                                        key,
                                        s.currentText(),
                                        pending.getAnswer(),
                                        topics,
                                        sources));
                        store.save(s);
                    }
                    Evaluation evaluation = pending.getEvaluation();
                    Mode executedMode = s.getMode() == Mode.SHADOW ? Mode.TIME_ONLY : s.getMode();
                    Decision shadow =
                            s.getMode() == Mode.SHADOW
                                    ? rules.decide(s, evaluation, clock.millis(), Mode.ADAPTIVE)
                                    : null;
                    Decision decision = rules.decide(s, evaluation, clock.millis(), executedMode);
                    // This second policy execution is the final publication gate, not a second
                    // model call.
                    if (decision.candidate() != null) {
                        Decision checked =
                                rules.decide(s, evaluation, clock.millis(), executedMode);
                        if (checked.candidate() == null
                                || !checked.candidate().id().equals(decision.candidate().id()))
                            decision =
                                    policy.advance(
                                            s,
                                            policy.budget(s, clock.millis(), true),
                                            "BUDGET_CHANGED_BEFORE_COMMIT",
                                            checked.excluded());
                        else decision = checked;
                    }
                    String questionNumber = s.currentNumber(), question = s.currentText();
                    if (decision.candidate() != null) {
                        s.setCurrentCandidate(decision.candidate());
                        s.setFollowUpCount(s.getFollowUpCount() + 1);
                        s.setTotalFollowUps(s.getTotalFollowUps() + 1);
                        s.getProbedGaps()
                                .add(
                                        decision.candidate().knowledgePointId()
                                                + ":"
                                                + decision.candidate().gapKey());
                    } else {
                        s.setMainIndex(s.getMainIndex() + 1);
                        s.setCurrentCandidate(null);
                        s.setFollowUpCount(0);
                        if (s.getMainIndex() >= s.getQuestions().size())
                            finishState(s, "COMPLETED");
                    }
                    int sum =
                            s.getTurns().stream()
                                    .filter(t -> !t.questionNumber().contains("-F"))
                                    .mapToInt(t -> t.evaluation().score())
                                    .sum();
                    long count =
                            s.getTurns().stream()
                                    .filter(t -> !t.questionNumber().contains("-F"))
                                    .count();
                    if (!questionNumber.contains("-F")) {
                        sum += evaluation.score();
                        count++;
                    }
                    InterviewAnswerRespDTO response =
                            currentResponse(s)
                                    .withCurrentQuestion(questionNumber, question)
                                    .withEvaluation(
                                            evaluation.score(),
                                            evaluation.feedback(),
                                            count == 0 ? 0 : (int) Math.round((double) sum / count))
                                    .success();
                    response.setDecisionSummary(
                            Map.of(
                                    "action",
                                    decision.action(),
                                    "reasonCode",
                                    decision.reasonCode()));
                    response.setPendingAnswer(null);
                    response.setTimeBudget(decision.budget());
                    s.getTurns()
                            .add(
                                    new Turn(
                                            key,
                                            hash,
                                            questionNumber,
                                            question,
                                            pending.getAnswer(),
                                            clock.millis(),
                                            evaluation,
                                            sources,
                                            decision,
                                            shadow,
                                            response));
                    s.setPending(null);
                    // Versioned single-document commit is the authority; Redis and the old report
                    // are repairable projections.
                    store.save(s);
                    project(s);
                    return response;
                });
    }

    public void finish(String id, Long userId) {
        locked(
                id,
                () -> {
                    Session s = owned(id, userId);
                    if (!s.isFinished()) {
                        s.setPending(null);
                        finishState(s, "USER_ENDED");
                        store.save(s);
                    }
                    project(s);
                    archive(s);
                    return null;
                });
    }

    private void finishState(Session s, String reason) {
        s.setFinished(true);
        s.setFinishedAt(clock.millis());
        s.setFinishReason(reason);
        s.setReportStatus("PENDING");
    }

    private void ensureReady(Session s) {
        var base = sessions.requireOwnedSession(s.getId(), s.getUserId());
        if (!s.isFinished() && !Set.of("READY", "IN_PROGRESS").contains(base.getStatus()))
            throw new ClientException("SESSION_NOT_ACTIVE");
        Map<String, String> questions = cache.getSessionInterviewQuestions(s.getId());
        if (questions == null || questions.isEmpty()) {
            cache.loadInterviewQuestionsFromDatabase(s.getId());
            questions = cache.getSessionInterviewQuestions(s.getId());
        }
        if (questions == null || questions.isEmpty())
            throw new ClientException("Questions not ready");
        var ordered = new TreeMap<Integer, String>();
        questions.forEach(
                (number, text) -> {
                    if (number.matches("[0-9]+")) ordered.put(Integer.parseInt(number), text);
                });
        if (ordered.isEmpty() || ordered.size() > 100)
            throw new ClientException("Invalid main question plan");
        String hash = DigestUtil.sha256Hex(JSON.toJSONString(ordered));
        if (s.getQuestions().isEmpty()) {
            List<MainQuestion> plan = new ArrayList<>();
            ordered.forEach(
                    (number, text) ->
                            plan.add(
                                    new MainQuestion(
                                            number.toString(),
                                            text,
                                            DigestUtil.sha256Hex(text),
                                            InterviewKnowledgeCatalog.mapTopics(
                                                    s.getCatalog(), text),
                                            120)));
            s.setQuestions(plan);
            s.setQuestionSetHash(hash);
            store.save(s);
        } else if (!hash.equals(s.getQuestionSetHash()))
            throw new ClientException("Fixed question plan changed; cannot continue");
    }

    private InterviewAnswerRespDTO currentResponse(Session s) {
        var response = InterviewAnswerRespDTO.init();
        if (s.isFinished()) response.finish();
        else
            response.withCurrentQuestion(s.currentNumber(), s.currentText())
                    .withNextQuestion(
                            s.currentNumber(),
                            s.currentText(),
                            s.getCurrentCandidate() != null,
                            s.getFollowUpCount());
        response.setTotalScore(s.totalScore());
        response.setTimeBudget(policy.budget(s, clock.millis(), false));
        if (s.getPending() != null)
            response.setPendingAnswer(
                    Map.of(
                            "requestId",
                            s.getPending().getRequestId(),
                            "questionNumber",
                            s.getPending().getQuestionNumber(),
                            "answer",
                            s.getPending().getAnswer()));
        return response.success();
    }

    public void project(Session s) {
        long count = s.getTurns().stream().filter(t -> !t.questionNumber().contains("-F")).count();
        int sum =
                s.getTurns().stream()
                        .filter(t -> !t.questionNumber().contains("-F"))
                        .mapToInt(t -> t.evaluation().score())
                        .sum();
        cacheStore.setValue(
                InterviewCacheKeys.sessionScore(s.getId()), String.valueOf(s.totalScore()), 24);
        cacheStore.setValue(InterviewCacheKeys.sessionScoreSum(s.getId()), String.valueOf(sum), 24);
        cacheStore.setValue(
                InterviewCacheKeys.sessionScoreCount(s.getId()), String.valueOf(count), 24);
        InterviewFlowState flow = new InterviewFlowState();
        flow.setCurrentIndex(s.getMainIndex());
        flow.setCurrentQuestionNumber(s.currentNumber());
        flow.setTotalQuestions(s.getQuestions().size());
        flow.setFollowUpCount(s.getFollowUpCount());
        flow.setMaxFollowUp(s.getMaxPerMain());
        flow.setVersion(s.getRevision().intValue());
        flow.setStatus(
                s.isFinished()
                        ? "COMPLETED"
                        : s.getCurrentCandidate() == null ? "ASKING" : "FOLLOW_UP");
        cache.restoreInterviewFlow(s.getId(), flow);
        if (s.getCurrentCandidate() != null)
            cache.cacheFollowUpQuestion(s.getId(), s.currentNumber(), s.currentText());
        for (Turn t : s.getTurns()) {
            var log =
                    InterviewTurnLog.builder()
                            .timestamp(t.committedAt())
                            .requestId(t.requestId())
                            .questionNumber(t.questionNumber())
                            .questionContent(t.question())
                            .answerContent(t.answer())
                            .score(t.evaluation().score())
                            .feedback(t.evaluation().feedback())
                            .totalScore(t.response().getTotalScore())
                            .isFollowUp(t.questionNumber().contains("-F"))
                            .followUpNeeded(t.decision().candidate() != null)
                            .nextQuestionNumber(t.response().getNextQuestionNumber())
                            .nextQuestion(t.response().getNextQuestion())
                            .finished(t.response().getFinished())
                            .build();
            if (!cache.appendInterviewTurnIfAbsent(s.getId(), log))
                throw new IllegalStateException("Turn projection failed; retry");
            snapshots.refreshAfterAnswerCommitted(s.getId(), t.requestId(), log);
        }
    }

    public void archive(Session s) {
        if (s.isArchived()) return;
        project(s);
        InterviewRecordSaveReqDTO request = new InterviewRecordSaveReqDTO();
        request.setSessionId(s.getId());
        request.setInterviewScore(s.totalScore());
        records.saveInterviewRecord(s.getId(), s.getUserId(), request);
        sessions.finishSession(s.getId(), s.getUserId());
        records.saveInterviewRecord(s.getId(), s.getUserId(), request);
        s.setArchived(true);
        store.save(s);
    }

    public <T> T locked(String id, Supplier<T> action) {
        org.redisson.api.RLock lock = null;
        try {
            lock = locks.acquireAdaptive(id);
            if (lock == null)
                throw new ClientException("Session is processing; retry the same request");
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("Request interrupted");
        } finally {
            locks.release(lock);
        }
    }

    public static void validateRequestId(String id) {
        if (id == null || id.isBlank() || id.length() > 64)
            throw new ClientException("Invalid requestId");
    }
}
