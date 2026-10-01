package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import lombok.Data;

import java.util.Set;

@Data
public class AdaptiveRuleContext {
    private Session session;
    private Evaluation evaluation;
    private long now;
    private Mode mode;
    private boolean disabled;
    private Set<String> revoked;
    private Decision decision;
}
