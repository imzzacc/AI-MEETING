package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import static org.junit.jupiter.api.Assertions.*;

import com.yomahub.liteflow.builder.el.LiteFlowChainELBuilder;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.FlowBus;
import com.yomahub.liteflow.property.LiteflowConfig;

import org.junit.jupiter.api.Test;

import java.util.*;

class AdaptiveLiteFlowTest {
    @Test
    void actualChainExecutesCandidatePolicy() throws Exception {
        var spring =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        spring.register(com.yomahub.liteflow.spi.spring.SpringAware.class);
        spring.refresh();
        try {
            var config = new LiteflowConfig();
            config.setPrintBanner(false);
            var executor = new FlowExecutor(config);
            var policy = new AdaptiveFollowUpPolicy();
            FlowBus.addManagedNode("adaptiveSelect", new AdaptiveSelectNode(policy));
            var xml =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder()
                            .parse(
                                    getClass()
                                            .getResourceAsStream(
                                                    "/liteflow/interview-followup-chain.xml"));
            String el = null;
            var chains = xml.getElementsByTagName("chain");
            for (int i = 0; i < chains.getLength(); i++) {
                var chain = (org.w3c.dom.Element) chains.item(i);
                if (chain.getAttribute("name").equals("adaptive_followup_v1"))
                    el = chain.getTextContent();
            }
            assertNotNull(el);
            LiteFlowChainELBuilder.createChain()
                    .setChainName("adaptive_followup_v1")
                    .setEL(el)
                    .build();
            var engine = new AdaptiveRuleEngine(executor, policy, new AdaptiveConfiguration());
            var candidate =
                    new Candidate(
                            "probe",
                            "volatile",
                            "atomicity",
                            "Is increment atomic?",
                            1,
                            60,
                            true,
                            Set.of(EvidenceState.INCORRECT));
            var s = new Session();
            s.setStartedAt(1000);
            s.setTargetDurationSeconds(1200);
            s.setQuestions(
                    List.of(new MainQuestion("1", "volatile", "hash", List.of("volatile"), 120)));
            s.setCatalog(
                    new Catalog(
                            "v1",
                            List.of(
                                    new Topic(
                                            "volatile",
                                            List.of("volatile"),
                                            List.of("atomicity"),
                                            List.of(),
                                            List.of(candidate)))));
            var e =
                    new Evaluation(
                            40,
                            "feedback",
                            List.of(
                                    new Observation(
                                            "volatile",
                                            "atomicity",
                                            EvidenceState.INCORRECT,
                                            List.of("atomic"),
                                            List.of("source"),
                                            "reason")),
                            "test",
                            "1");
            var decision = engine.decide(s, e, 5000, Mode.ADAPTIVE);
            assertEquals("ASK_FOLLOW_UP", decision.action());
            assertEquals("probe", decision.candidate().id());
            assertEquals(
                    "TARGET_TIME_EXCEEDED",
                    engine.decide(s, e, 1_201_000, Mode.ADAPTIVE).reasonCode());
        } finally {
            spring.close();
        }
    }
}
