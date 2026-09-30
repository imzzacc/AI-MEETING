package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import java.util.*;

/** Renders checked evidence without publishing the model's unverified technical explanations. */
final class GroundedFeedback {
    private GroundedFeedback() {}

    static boolean chinese(String answer) {
        return answer != null && answer.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    }

    static String rationale(EvidenceState state, boolean chinese) {
        return switch (state) {
            case COVERED -> chinese ? "本轮暂判断为已覆盖，请结合回答原文和参考资料复核。" : "Tentatively covered; review the answer evidence against the references.";
            case PARTIAL -> chinese ? "本轮暂判断为部分覆盖；是否缺少必要内容仍需对照题目与参考资料核对。" : "Tentatively partial; check the question and references before treating anything as a required omission.";
            case INCORRECT -> chinese ? "本轮提示可能存在概念错误，请核对回答原文与参考要点。" : "A possible misconception was identified; compare the answer evidence with the reference material.";
            case NOT_OBSERVED -> chinese ? "本轮没有足够证据判断该要点，未涉及不等于答错。" : "This turn does not establish knowledge of this point; an unobserved point is not an error.";
            case UNCERTAIN -> chinese ? "证据不足、引用未通过校验或判断存在冲突，暂不作知识结论。" : "Evidence is insufficient, a citation failed validation, or judgments conflict; no knowledge conclusion is established.";
        };
    }

    static String render(int score, List<Observation> observations, List<Source> sources, boolean chinese) {
        StringBuilder out = new StringBuilder(chinese ? "本轮参考评分（AI 辅助）：" : "AI-assisted reference score: ").append(score).append("/100.\n");
        if (observations.isEmpty()) {
            return out.append(chinese ? "本轮未取得有效的知识点证据，暂不作自动知识结论。" : "No valid knowledge evidence was obtained; no automatic knowledge conclusion is established.").toString();
        }
        Set<String> cited = new LinkedHashSet<>();
        for (Observation observation : observations) {
            out.append("\n").append(observation.rubricPointId()).append(": ").append(observation.rationale()).append("\n");
            for (String quote : observation.answerQuotes().stream().limit(2).toList()) {
                out.append(chinese ? "回答摘录：" : "Answer excerpt: ").append(excerpt(quote, 240)).append("\n");
            }
            cited.addAll(observation.sourceChunkIds());
        }
        for (Source source : sources) {
            if (!cited.remove(source.id())) continue;
            out.append("\n").append(chinese ? "参考资料节选（" : "Reference excerpt (").append(source.title()).append(" / ").append(source.version()).append("): ").append(excerpt(source.text(), 900)).append("\n");
        }
        // Stay within the persisted feedback contract; full quotes and sources remain in the turn.
        return excerpt(out.toString(), 5900);
    }

    private static String excerpt(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}
