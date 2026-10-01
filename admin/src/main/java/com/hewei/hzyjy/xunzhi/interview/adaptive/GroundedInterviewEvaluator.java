package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import java.util.List;

public interface GroundedInterviewEvaluator {
    Evaluation evaluate(
            String sessionId,
            String requestId,
            String question,
            String answer,
            List<Topic> topics,
            List<Source> sources);
}
