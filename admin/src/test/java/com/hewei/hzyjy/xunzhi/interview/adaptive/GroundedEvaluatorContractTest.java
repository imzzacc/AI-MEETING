package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hewei.hzyjy.xunzhi.agent.application.*;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentPropertiesDO;
import com.hewei.hzyjy.xunzhi.interview.shared.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class GroundedEvaluatorContractTest {
    @Test
    void acceptsXingChenNamedEndNodeOutputInsideDeltaContent() throws Exception {
        String evaluation =
                "{\"score\":25,\"feedback\":\"Increment is not atomic.\","
                        + "\"analysisSchemaVersion\":\"1\",\"observations\":[]}";
        String wrapped =
                com.alibaba.fastjson2.JSON.toJSONString(
                        Map.of(
                                "code", 0,
                                "choices", List.of(Map.of("delta", Map.of("content",
                                        com.alibaba.fastjson2.JSON.toJSONString(
                                                Map.of("result", evaluation)))))));
        var result = evaluatorReturning(wrapped)
                .evaluate("s", "r", "question", "answer", List.of(), List.of());
        assertEquals(25, result.score());
        assertEquals("Increment is not atomic.", result.feedback());
    }

    @Test
    void rejectsLanguageMapFeedbackInsteadOfStoringItAsUserFacingText() throws Exception {
        String evaluation =
                "{\"score\":25,\"feedback\":{\"en\":\"Increment is not atomic.\"},"
                        + "\"analysisSchemaVersion\":\"1\",\"observations\":[]}";
        var evaluator = evaluatorReturning(evaluation);
        var error = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate("s", "r", "question", "answer", List.of(), List.of()));
        assertEquals("Grounded evaluation requires text feedback", error.getCause().getMessage());
    }

    private WorkflowGroundedInterviewEvaluator evaluatorReturning(String response) throws Exception {
        var agents = mock(BusinessAgentResolver.class);
        var invoker = mock(InterviewAiInvoker.class);
        when(agents.resolveRequired(BusinessAgentScene.INTERVIEW_GROUNDED_EVALUATION))
                .thenReturn(new AgentPropertiesDO());
        when(invoker.buildSingleFlightKey(any(), anyString(), anyString(), anyString()))
                .thenReturn("flight");
        when(invoker.callAiSyncWithParameters(anyString(), any(), anyMap(), any(), anyString()))
                .thenReturn(response);
        return new WorkflowGroundedInterviewEvaluator(agents, invoker,
                new InterviewResponseParser(), new InterviewEvidenceValidator());
    }

    @Test
    void requiresGroundedSchemaInsteadOfSilentlyAcceptingLegacyWorkflow() throws Exception {
        var agents = mock(BusinessAgentResolver.class);
        var invoker = mock(InterviewAiInvoker.class);
        when(agents.resolveRequired(BusinessAgentScene.INTERVIEW_GROUNDED_EVALUATION))
                .thenReturn(new AgentPropertiesDO());
        when(invoker.buildSingleFlightKey(any(), anyString(), anyString(), anyString()))
                .thenReturn("flight");
        when(invoker.callAiSyncWithParameters(anyString(), any(), anyMap(), any(), anyString()))
                .thenReturn("{\"score\":80,\"feedback\":\"legacy\",\"follow_up_needed\":true}");
        var evaluator =
                new WorkflowGroundedInterviewEvaluator(
                        agents,
                        invoker,
                        new InterviewResponseParser(),
                        new InterviewEvidenceValidator());
        assertThrows(
                IllegalStateException.class,
                () -> evaluator.evaluate("s", "r", "question", "answer", List.of(), List.of()));
    }

    @Test
    void ignoresModelFollowupInstructionsAndRetainsFullAnswerInput() throws Exception {
        var agents = mock(BusinessAgentResolver.class);
        var invoker = mock(InterviewAiInvoker.class);
        when(agents.resolveRequired(BusinessAgentScene.INTERVIEW_GROUNDED_EVALUATION))
                .thenReturn(new AgentPropertiesDO());
        when(invoker.buildSingleFlightKey(any(), anyString(), anyString(), anyString()))
                .thenReturn("flight");
        String answer = "a".repeat(1400) + "TAIL";
        when(invoker.callAiSyncWithParameters(anyString(), any(), anyMap(), any(), anyString()))
                .thenAnswer(
                        call -> {
                            Map<String, Object> input = call.getArgument(2);
                            var json =
                                    com.alibaba.fastjson2.JSON.parseObject(
                                            (String) input.get("AGENT_USER_INPUT"));
                            assertEquals(answer, json.getString("answer"));
                            assertFalse(json.containsKey("candidates"));
                            return "{\"score\":80,\"feedback\":\"ok\",\"analysisSchemaVersion\":\"1\",\"observations\":[],\"follow_up_question\":\"replace"
                                       + " main plan\"}";
                        });
        var evaluator =
                new WorkflowGroundedInterviewEvaluator(
                        agents,
                        invoker,
                        new InterviewResponseParser(),
                        new InterviewEvidenceValidator());
        assertEquals(
                80, evaluator.evaluate("s", "r", "question", answer, List.of(), List.of()).score());
    }
}
