package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/** Local, versioned retrieval avoids an unbounded network request in the answer path. */
@Component
public class InterviewKnowledgeCatalog {
    private final Catalog catalog;

    public InterviewKnowledgeCatalog(
            ObjectMapper mapper, ResourceLoader resources, AdaptiveConfiguration config)
            throws IOException {
        try (var stream = resources.getResource(config.getCatalogLocation()).getInputStream()) {
            catalog = mapper.readValue(stream, Catalog.class);
        }
        validate(catalog);
    }

    public Catalog snapshot() {
        return catalog;
    }

    public static void validate(Catalog catalog) {
        if (catalog == null
                || catalog.version() == null
                || catalog.topics() == null
                || catalog.topics().size() > 100)
            throw new IllegalArgumentException("Invalid catalog");
        Set<String> ids = new HashSet<>();
        for (Topic t : catalog.topics()) {
            if (!ids.add("t:" + t.id())
                    || t.aliases().isEmpty()
                    || t.rubricPoints().isEmpty()
                    || t.sources().isEmpty())
                throw new IllegalArgumentException("Invalid topic: " + t.id());
            if (t.candidates().size() > 5 || t.sources().size() > 6 || t.rubricPoints().size() > 10)
                throw new IllegalArgumentException("Catalog topic exceeds bounds");
            for (Source s : t.sources()) {
                if (!ids.add("s:" + s.id())
                        || s.text().isBlank()
                        || s.text().length() > 2000
                        || s.version().isBlank()
                        || !s.url().startsWith("https://"))
                    throw new IllegalArgumentException("Invalid source: " + s.id());
            }
            for (Candidate c : t.candidates()) {
                if (!ids.add("c:" + c.id())
                        || !c.knowledgePointId().equals(t.id())
                        || !t.rubricPoints().contains(c.gapKey())
                        || c.question().isBlank()
                        || c.seconds() < 15
                        || c.seconds() > 600
                        || c.states().isEmpty())
                    throw new IllegalArgumentException("Invalid candidate: " + c.id());
            }
        }
    }

    public static List<String> mapTopics(Catalog catalog, String question) {
        String text = question.toLowerCase(Locale.ROOT);
        return catalog.topics().stream()
                .filter(
                        t ->
                                t.aliases().stream()
                                        .anyMatch(a -> text.contains(a.toLowerCase(Locale.ROOT))))
                .limit(5)
                .map(Topic::id)
                .toList();
    }

    public static List<Topic> retrieve(Catalog catalog, List<String> allowedIds) {
        return catalog.topics().stream().filter(t -> allowedIds.contains(t.id())).toList();
    }

    public static List<Source> sources(List<Topic> topics) {
        return topics.stream().flatMap(t -> t.sources().stream()).limit(6).toList();
    }
}
