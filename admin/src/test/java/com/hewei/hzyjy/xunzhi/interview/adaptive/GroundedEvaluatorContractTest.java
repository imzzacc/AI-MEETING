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
