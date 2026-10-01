package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.*;

class InterviewKnowledgeScopeTest {
    private Catalog catalog() throws Exception {
        return new InterviewKnowledgeCatalog(
                        new ObjectMapper(),
                        new DefaultResourceLoader(),
                        new AdaptiveConfiguration())
                .snapshot();
    }

    @Test
    void incompatibleRoleCannotRetrieveSourcesOrFollowUps() throws Exception {
        var selected =
                InterviewKnowledgeCatalog.select(
                        catalog(),
                        new RetrievalScope(
                                "frontend", RetrievalScope.defaults().technologyVersions()));
        assertTrue(selected.catalog().topics().isEmpty());
        assertTrue(InterviewKnowledgeCatalog.sources(selected.catalog().topics()).isEmpty());
        assertEquals(Set.of("ROLE_MISMATCH"), new HashSet<>(selected.excluded().values()));
        assertEquals(5, selected.excluded().size());
    }

    @Test
    void versionMismatchAndUnspecifiedVersionCloseOnlyAffectedTopics() throws Exception {
        var selected =
                InterviewKnowledgeCatalog.select(
                        catalog(),
                        new RetrievalScope("java-backend", Map.of("java", "21", "mysql", "8.0")));
        assertEquals(
                List.of("mysql.isolation"),
                selected.catalog().topics().stream().map(Topic::id).toList());
        assertEquals(
                "TECHNOLOGY_VERSION_MISMATCH", selected.excluded().get("volatile-atomicity-v1"));
        assertTrue(
                InterviewKnowledgeCatalog.mapTopics(selected.catalog(), "volatile i++").isEmpty());
        assertEquals(
                List.of("mysql.isolation"),
                InterviewKnowledgeCatalog.mapTopics(selected.catalog(), "事务隔离"));
        assertEquals(
                4, catalog().topics().size(), "Selection must not mutate the published catalog");
    }

    @Test
    void defaultsMatchAllStarterTopicsAndMissingMetadataFailsClosed() throws Exception {
        var all = InterviewKnowledgeCatalog.select(catalog(), RetrievalScope.defaults());
        assertEquals(4, all.catalog().topics().size());
        assertTrue(all.excluded().isEmpty());
        var legacy = new Catalog("legacy", catalog().topics());
        assertThrows(
                IllegalArgumentException.class, () -> InterviewKnowledgeCatalog.validate(legacy));
        assertTrue(
                InterviewKnowledgeCatalog.select(legacy, RetrievalScope.defaults())
                        .catalog()
                        .topics()
                        .isEmpty());
    }

    @Test
    void rejectsUnboundedOrMalformedUserScope() throws Exception {
        var catalog = catalog();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        InterviewKnowledgeCatalog.select(
                                catalog,
                                new RetrievalScope("java-backend", Map.of("java", "17 OR 21"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> InterviewKnowledgeCatalog.select(catalog, new RetrievalScope("", Map.of())));
    }
}
