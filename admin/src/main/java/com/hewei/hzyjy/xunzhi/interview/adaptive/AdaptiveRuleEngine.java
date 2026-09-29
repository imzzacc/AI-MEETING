package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import com.yomahub.liteflow.core.FlowExecutor;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AdaptiveRuleEngine {
    private final FlowExecutor executor;
    private final AdaptiveFollowUpPolicy policy;
    private final AdaptiveConfiguration config;

    public Decision decide(Session s, Evaluation e, long now, Mode mode) {
        var context = new AdaptiveRuleContext();
        context.setSession(s);
        context.setEvaluation(e);
        context.setNow(now);
        context.setMode(mode);
        context.setDisabled(config.isEmergencyDisableFollowUp());
        context.setRevoked(config.getRevokedCandidateIds());
        try {
            var response = executor.execute2Resp("adaptive_followup_v1", null, context);
            if (response == null || !response.isSuccess() || context.getDecision() == null)
                throw new IllegalStateException("Rule execution failed");
            return context.getDecision();
        } catch (Exception e1) {
            return policy.advance(s, policy.budget(s, now, true), "POLICY_UNAVAILABLE", Map.of());
        }
    }
}
