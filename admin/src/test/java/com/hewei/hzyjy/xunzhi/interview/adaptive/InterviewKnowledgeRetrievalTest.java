package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class InterviewKnowledgeRetrievalTest {
    @Test
    void mapsConcreteReadAndCacheFailureTermsWithoutRequiringCategoryNames() throws Exception {
        var catalog = new InterviewKnowledgeCatalog(new ObjectMapper(), new DefaultResourceLoader(),
                new AdaptiveConfiguration()).snapshot();
        for (String question : List.of("READ COMMITTED 与 REPEATABLE READ 的快照有什么区别？",
                "普通一致性读和锁定读有何不同？", "next-key locking 如何影响范围查询？")) {
            assertEquals(List.of("mysql.isolation"), InterviewKnowledgeCatalog.mapTopics(catalog, question));
        }
        for (String question : List.of("热点键失效后如何避免大量并发回源？", "缓存同时过期如何保护数据源？",
                "缓存和数据库都不存在的数据如何处理？")) {
            assertEquals(List.of("redis.cache-failures"), InterviewKnowledgeCatalog.mapTopics(catalog, question));
        }
        for (String question : List.of("数据库如何设计联合索引？", "缓存容量和命中率如何衡量？",
                "对象存储的快照如何备份？", "共享计数器使用何种数据结构？")) {
            assertTrue(InterviewKnowledgeCatalog.mapTopics(catalog, question).isEmpty(), question);
        }
    }

    @Test
    void expandedAliasesCannotBypassTechnologyScope() throws Exception {
        var catalog = new InterviewKnowledgeCatalog(new ObjectMapper(), new DefaultResourceLoader(),
                new AdaptiveConfiguration()).snapshot();
        var selected = InterviewKnowledgeCatalog.select(catalog,
                new AdaptiveModels.RetrievalScope("java-backend", java.util.Map.of("mysql", "5.7")));
        assertTrue(InterviewKnowledgeCatalog.mapTopics(selected.catalog(),
                "READ COMMITTED 下的快照读如何工作？").isEmpty());
        assertEquals("TECHNOLOGY_VERSION_MISMATCH", selected.excluded().get("mysql-isolation-v1"));
    }
}
