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
            KnowledgeScope scope = catalog.scopes() == null ? null : catalog.scopes().get(t.id());
            if (scope == null
                    || scope.roles() == null
                    || scope.roles().isEmpty()
                    || scope.technology() == null
                    || scope.technology().isBlank()
                    || scope.versions() == null
                    || scope.versions().isEmpty())
                throw new IllegalArgumentException("Missing knowledge scope: " + t.id());
            if (!ids.add("t:" + t.id())
                    || t.aliases().isEmpty()
                    || t.rubricPoints().isEmpty()
                    || t.sources().isEmpty())
                throw new IllegalArgumentException("Invalid topic: " + t.id());
            if (t.candidates().size() > 5 || t.sources().size() > 6 || t.rubricPoints().size() > 10)
                throw new IllegalArgumentException("Catalog topic exceeds bounds");
            if (t.aliasGroups().size() > 20
                    || t.aliasGroups().stream().anyMatch(group -> group.size() < 2
                            || group.size() > 8 || group.stream().anyMatch(term -> term.isBlank()
                                    || term.length() > 100)))
                throw new IllegalArgumentException("Invalid compound aliases: " + t.id());
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
                                        .anyMatch(a -> text.contains(a.toLowerCase(Locale.ROOT)))
                                || t.aliasGroups().stream().anyMatch(group -> group.stream()
                                        .allMatch(a -> text.contains(a.toLowerCase(Locale.ROOT)))))
                .limit(5)
                .map(Topic::id)
                .toList();
    }

    public record Selection(Catalog catalog, Map<String, String> excluded) {}

    /** Filter before freezing the plan, so incompatible material never reaches the evaluator. */
    public static Selection select(Catalog catalog, RetrievalScope requested) {
        if (requested == null
                || requested.role() == null
                || !requested.role().matches("[a-z][a-z0-9-]{0,63}")
                || requested.technologyVersions() == null
                || requested.technologyVersions().size() > 20)
            throw new IllegalArgumentException("Invalid retrieval scope");
        requested
                .technologyVersions()
                .forEach(
                        (key, value) -> {
                            if (key == null
                                    || value == null
                                    || !key.matches("[a-z][a-z0-9-]{0,31}")
                                    || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,31}"))
                                throw new IllegalArgumentException("Invalid technology version");
                        });
        List<Topic> selected = new ArrayList<>();
        Map<String, String> excluded = new LinkedHashMap<>();
        Map<String, KnowledgeScope> scopes = new LinkedHashMap<>();
        for (Topic topic : catalog.topics()) {
            KnowledgeScope scope =
                    catalog.scopes() == null ? null : catalog.scopes().get(topic.id());
            String reason =
                    scope == null
                            ? "KNOWLEDGE_SCOPE_MISSING"
                            : !scope.roles().contains(requested.role())
                                    ? "ROLE_MISMATCH"
                                    : !scope.versions()
                                                    .contains(
                                                            requested
                                                                    .technologyVersions()
                                                                    .getOrDefault(
                                                                            scope.technology(), ""))
                                            ? "TECHNOLOGY_VERSION_MISMATCH"
                                            : null;
            if (reason == null) {
                selected.add(topic);
                scopes.put(topic.id(), scope);
            } else {
                for (Candidate candidate : topic.candidates()) excluded.put(candidate.id(), reason);
            }
        }
        return new Selection(
                new Catalog(catalog.version(), List.copyOf(selected), Map.copyOf(scopes)),
                Map.copyOf(excluded));
    }

    public static List<Topic> retrieve(Catalog catalog, List<String> allowedIds) {
        return catalog.topics().stream().filter(t -> allowedIds.contains(t.id())).toList();
    }

    public static List<Source> sources(List<Topic> topics) {
        return topics.stream().flatMap(t -> t.sources().stream()).limit(6).toList();
    }
}
