package com.hewei.hzyjy.xunzhi.interview.adaptive;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

import lombok.RequiredArgsConstructor;

@LiteflowComponent("adaptiveSelect")
@RequiredArgsConstructor
public class AdaptiveSelectNode extends NodeComponent {
    private final AdaptiveFollowUpPolicy policy;

    @Override
    public void process() {
        var c = getContextBean(AdaptiveRuleContext.class);
        c.setDecision(
                policy.decide(
                        c.getSession(),
                        c.getEvaluation(),
                        c.getNow(),
                        c.getMode(),
                        c.isDisabled(),
                        c.getRevoked()));
    }
}
