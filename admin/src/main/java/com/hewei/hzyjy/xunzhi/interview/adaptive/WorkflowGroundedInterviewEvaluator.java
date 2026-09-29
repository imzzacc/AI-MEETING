package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import com.alibaba.fastjson2.JSON;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentResolver;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentScene;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardStage;
import com.hewei.hzyjy.xunzhi.interview.shared.InterviewAiInvoker;
import com.hewei.hzyjy.xunzhi.interview.shared.InterviewResponseParser;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class WorkflowGroundedInterviewEvaluator implements GroundedInterviewEvaluator {
    private final BusinessAgentResolver agents;
    private final InterviewAiInvoker invoker;
    private final InterviewResponseParser parser;
    private final InterviewEvidenceValidator validator;

    public Evaluation evaluate(
            String sessionId,
            String requestId,
            String question,
            String answer,
            List<Topic> topics,
            List<Source> sources) {
        var agent = agents.resolveRequired(BusinessAgentScene.INTERVIEW_GROUNDED_EVALUATION);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("question", question);
        payload.put("answer", answer);
        payload.put(
                "rubrics",
                topics.stream()
                        .map(
                                t ->
                                        Map.of(
                                                "knowledgePointId",
                                                t.id(),
                                                "rubricPointIds",
                                                t.rubricPoints()))
                        .toList());
        payload.put("sources", sources);
        String prompt = JSON.toJSONString(payload);
        try {
            // The existing workflow receives the instructions through its existing input field, not
            // an unused extra parameter.
            String raw =
                    invoker.callAiSyncWithParameters(
                            sessionId + "_grounded",
                            agent,
                            Map.of(
                                    "AGENT_USER_INPUT",
                                    prompt,
                                    "question",
                                    question,
                                    "resume_context",
                                    ""),
                            InterviewAiGuardStage.INTERVIEW_EVALUATION,
                            invoker.buildSingleFlightKey(
                                    InterviewAiGuardStage.INTERVIEW_EVALUATION,
                                    sessionId,
                                    requestId,
                                    prompt));
            Map<String, Object> result = parser.parseEvaluationResult(raw);
            if (result == null)
                throw new IllegalStateException("Grounded evaluation returned no JSON");
            Integer score = parser.parseScoreFromResponse(result, "score");
            if (score == null)
                throw new IllegalStateException("Grounded evaluation returned no score");
            if (!"1".equals(String.valueOf(result.get("analysisSchemaVersion"))))
                throw new IllegalStateException("Grounded workflow contract v1 required");
            List<Observation> observations = List.of();
            if ("1".equals(String.valueOf(result.get("analysisSchemaVersion")))
                    && result.get("observations") instanceof List<?>) {
                try {
                    observations =
                            JSON.parseArray(
                                    JSON.toJSONString(result.get("observations")),
                                    Observation.class);
                } catch (RuntimeException ignored) {
                    /* Invalid evidence does not invent a knowledge error. */
                }
            }
            return validator.validate(
                    new Evaluation(
                            score,
                            Objects.toString(result.get("feedback"), ""),
                            observations,
                            "workflow:" + agent.getId(),
                            "1"),
                    answer,
                    topics,
                    sources);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Grounded evaluation unavailable; retry the same request", e);
        }
    }
}
