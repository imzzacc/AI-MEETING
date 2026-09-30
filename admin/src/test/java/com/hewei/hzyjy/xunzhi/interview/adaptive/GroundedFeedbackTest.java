package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;

class GroundedFeedbackTest {
    private Topic topic(String id) throws Exception {
        try (var input = getClass().getResourceAsStream("/knowledge/interview-catalog-v1.json")) {
            return new ObjectMapper().readValue(input, Catalog.class).topics().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
        }
    }

    private Evaluation validate(Topic topic, String answer, EvidenceState state, String quote, String unsupported) {
        return new InterviewEvidenceValidator().validate(new Evaluation(80, unsupported,
                List.of(new Observation(topic.id(), topic.rubricPoints().get(0), state,
                        List.of(quote), List.of(topic.sources().get(0).id()), unsupported)), "recorded-provider", "1"),
                answer, List.of(topic), topic.sources());
    }

    @Test
    void mysqlRecordedFailureCannotPublishAnUnsupportedTechnicalExplanation() throws Exception {
        Topic topic = topic("mysql.isolation");
        String answer = "它们的语义不同。可重复读的普通一致性读使用首次读的快照，而范围锁定操作可能使用 gap 或 next-key locking，因此应说明数据库版本和读取类型。";
        String unsupported = "锁定读可能因gap锁未覆盖完整范围而出现幻读，这是区分二者的关键原因。";
        Evaluation checked = validate(topic, answer, EvidenceState.PARTIAL, answer, unsupported);
        assertFalse(checked.feedback().contains(unsupported));
        assertFalse(checked.observations().get(0).rationale().contains(unsupported));
        assertTrue(checked.feedback().contains(answer));
        assertTrue(checked.feedback().contains(topic.sources().get(0).text()));
        // This guard does not pretend to resolve the remaining semantic state disagreement.
        assertEquals(EvidenceState.PARTIAL, checked.observations().get(0).state());
        assertTrue(checked.observations().get(0).rationale().contains("是否缺少必要内容"));
    }

    @Test
    void questionQuoteDowngradeAlsoRemovesOriginalPraise() throws Exception {
        Topic topic = topic("redis.cache-failures");
        String answer = "应该让所有请求同时独立查询数据库重建缓存，这样一定能减少回源压力。";
        String praise = "答案正确识别了热点键失效后大量并发请求回源。";
        Evaluation checked = validate(topic, answer, EvidenceState.COVERED, "热点键失效后大量并发请求回源", praise);
        assertEquals(EvidenceState.UNCERTAIN, checked.observations().get(0).state());
        assertTrue(checked.observations().get(0).answerQuotes().isEmpty());
        assertFalse(checked.feedback().contains(praise));
        assertFalse(checked.observations().get(0).rationale().contains(praise));
        assertTrue(checked.feedback().contains("暂不作知识结论"));
    }

    @Test
    void validEvidenceStillDistinguishesCorrectAndIncorrectAndKeepsFullQuotes() throws Exception {
        Topic topic = topic("java.volatile");
        String answer = "volatile makes i++ atomic." + " details".repeat(200);
        Evaluation checked = validate(topic, answer, EvidenceState.INCORRECT, answer, "unverified explanation");
        assertEquals(EvidenceState.INCORRECT, checked.observations().get(0).state());
        assertEquals(answer, checked.observations().get(0).answerQuotes().get(0));
        assertTrue(checked.feedback().contains("possible misconception"));
        assertTrue(checked.feedback().contains("Reference excerpt"));
        assertFalse(checked.feedback().contains("unverified explanation"));
        assertTrue(validate(topic, "volatile does not make i++ atomic.", EvidenceState.COVERED,
                "volatile does not make i++ atomic.", "unverified explanation").feedback().contains("Tentatively covered"));
    }

    @Test
    void discardedEvidenceCannotLeaveTheModelKnowledgeClaimInFeedback() throws Exception {
        Topic topic = topic("java.volatile");
        Evaluation checked = new InterviewEvidenceValidator().validate(new Evaluation(100, "所有知识点都已掌握", List.of(), "test", "1"),
                "我不知道", List.of(topic), topic.sources());
        assertFalse(checked.feedback().contains("所有知识点都已掌握"));
        assertTrue(checked.feedback().contains("未取得有效"));
        assertTrue(checked.observations().isEmpty());
    }

    @Test
    void renderedFeedbackIsBoundedAndRevalidationIsStable() throws Exception {
        Topic topic = topic("java.volatile");
        String answer = "volatile does not guarantee compound increment atomicity.";
        Evaluation first = validate(topic, answer, EvidenceState.COVERED, answer, "untrusted");
        assertEquals(first, new InterviewEvidenceValidator().validate(first, answer, List.of(topic), topic.sources()));
        List<Observation> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) many.add(new Observation("topic", "rubric-" + i, EvidenceState.PARTIAL,
                List.of("a".repeat(1000)), List.of(), GroundedFeedback.rationale(EvidenceState.PARTIAL, false)));
        assertTrue(GroundedFeedback.render(50, many, List.of(), false).length() <= 6000);
    }
}
