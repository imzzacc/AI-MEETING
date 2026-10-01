package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;

@Component
public class InterviewEvidenceValidator {
    public Evaluation validate(
            Evaluation input, String answer, List<Topic> topics, List<Source> sources) {
        if (input == null
                || input.score() < 0
                || input.score() > 100
                || input.feedback() == null
                || input.feedback().length() > 6000
                || input.observations() != null && input.observations().size() > 50)
            throw new IllegalArgumentException("Invalid evaluation score or feedback");
        boolean chinese = GroundedFeedback.chinese(answer);
        var allowedSources =
                sources.stream().map(Source::id).collect(java.util.stream.Collectors.toSet());
        var observations = new LinkedHashMap<String, Observation>();
        if (input.observations() != null)
            for (Observation o : input.observations()) {
                if (o == null
                        || o.state() == null
                        || o.rationale() != null && o.rationale().length() > 2000) continue;
                Topic topic =
                        topics.stream()
                                .filter(
                                        t ->
                                                t.id().equals(o.knowledgePointId())
                                                        && t.rubricPoints()
                                                                .contains(o.rubricPointId()))
                                .findFirst()
                                .orElse(null);
                if (topic == null) continue;
                boolean validQuotes =
                        o.answerQuotes() != null
                                && !o.answerQuotes().isEmpty()
                                && o.answerQuotes().stream()
                                        .allMatch(
                                                q ->
                                                        q != null
                                                                && !q.isBlank()
                                                                && normalize(answer)
                                                                        .contains(normalize(q)));
                boolean validSources =
                        o.sourceChunkIds() != null
                                && !o.sourceChunkIds().isEmpty()
                                && allowedSources.containsAll(o.sourceChunkIds())
                                && o.sourceChunkIds().stream()
                                        .allMatch(
                                                id ->
                                                        topic.sources().stream()
                                                                .anyMatch(s -> s.id().equals(id)));
                boolean factual =
                        o.state() == EvidenceState.COVERED
                                || o.state() == EvidenceState.PARTIAL
                                || o.state() == EvidenceState.INCORRECT;
                EvidenceState state =
                        factual && (!validQuotes || !validSources)
                                ? EvidenceState.UNCERTAIN
                                : o.state();
                Observation checked =
                        new Observation(
                                o.knowledgePointId(),
                                o.rubricPointId(),
                                state,
                                validQuotes ? List.copyOf(o.answerQuotes()) : List.of(),
                                validSources ? List.copyOf(o.sourceChunkIds()) : List.of(),
                                GroundedFeedback.rationale(state, chinese));
                String key = o.knowledgePointId() + ":" + o.rubricPointId();
                Observation previous = observations.get(key);
                if (previous != null && previous.state() != checked.state())
                    checked =
                            new Observation(
                                    o.knowledgePointId(),
                                    o.rubricPointId(),
                                    EvidenceState.UNCERTAIN,
                                    List.of(),
                                    List.of(),
                                    GroundedFeedback.rationale(EvidenceState.UNCERTAIN, chinese));
                observations.put(key, checked);
            }
        return new Evaluation(
                input.score(),
                topics.isEmpty()
                        ? input.feedback()
                        : GroundedFeedback.render(input.score(), List.copyOf(observations.values()), sources, chinese),
                List.copyOf(observations.values()),
                input.modelVersion(),
                "1");
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replaceAll("\\s+", " ").trim();
    }
}
