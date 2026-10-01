package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class InterviewKnowledgeRetrievalTest {
    @Test
    void compoundAliasesRecognizeScenariosAndRequireTheirContext() throws Exception {
        var catalog = new InterviewKnowledgeCatalog(new ObjectMapper(), new DefaultResourceLoader(),
                new AdaptiveConfiguration()).snapshot();
        for (String q : List.of("Java 中两个对象的哈希值一致能推出相等吗？",
                "JAVA 对象的 hash(x) 与 hash(y) 是否必须相同？"))
            assertEquals(List.of("java.equals-hashcode"), InterviewKnowledgeCatalog.mapTopics(catalog, q));
        for (String q : List.of("MySQL 是否支持 SERIALIZABLE？", "InnoDB 的 READ UNCOMMITTED 有什么风险？"))
            assertEquals(List.of("mysql.isolation"), InterviewKnowledgeCatalog.mapTopics(catalog, q));
        for (String q : List.of("数据源和缓存里都查不到某个编号，反复查询怎么处理？",
                "过期的热门文章缓存遭遇大量请求怎么办？", "批量设置的缓存会在同一秒全部过期，如何错峰？",
                "大量缓存键同时到达过期时刻怎么办？"))
            assertEquals(List.of("redis.cache-failures"), InterviewKnowledgeCatalog.mapTopics(catalog, q));
        for (String q : List.of("Java 的 Serializable 接口有什么作用？", "文件的哈希值如何校验？",
                "hash(x) 如何计算文件摘要？", "浏览器缓存的过期时间如何配置？",
                "热门商品的库存怎么扣减？", "数据库都查不到表结构怎么办？"))
            assertTrue(InterviewKnowledgeCatalog.mapTopics(catalog, q).isEmpty(), q);
        var unsupported = InterviewKnowledgeCatalog.select(catalog,
                new AdaptiveModels.RetrievalScope("java-backend", java.util.Map.of("mysql", "5.7")));
        assertTrue(InterviewKnowledgeCatalog.mapTopics(unsupported.catalog(), "MySQL SERIALIZABLE").isEmpty());
    }

    @Test
    void missingCompoundAliasesKeepOldFrozenCatalogBehavior() throws Exception {
        var mapper = new ObjectMapper();
        var catalog = new InterviewKnowledgeCatalog(mapper, new DefaultResourceLoader(),
                new AdaptiveConfiguration()).snapshot();
        var oldJson = mapper.valueToTree(catalog);
        for (var topic : oldJson.get("topics"))
            ((com.fasterxml.jackson.databind.node.ObjectNode) topic).remove("aliasGroups");
        var old = mapper.treeToValue(oldJson, AdaptiveModels.Catalog.class);
        InterviewKnowledgeCatalog.validate(old);
        assertTrue(old.topics().stream().allMatch(t -> t.aliasGroups().isEmpty()));
        assertTrue(InterviewKnowledgeCatalog.mapTopics(old, "MySQL SERIALIZABLE").isEmpty());
        assertEquals(List.of("mysql.isolation"), InterviewKnowledgeCatalog.mapTopics(old, "快照读"));
    }

    @Test
    void rejectsEmptyOrSingleTermCompoundAliases() throws Exception {
        var mapper = new ObjectMapper();
        var catalog = new InterviewKnowledgeCatalog(mapper, new DefaultResourceLoader(),
                new AdaptiveConfiguration()).snapshot();
        for (String invalid : List.of("[[]]", "[[\"缓存\"]]", "[[\"缓存\",\" \" ]]")) {
            var json = mapper.valueToTree(catalog);
            ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("topics").get(0))
                    .set("aliasGroups", mapper.readTree(invalid));
            var changed = mapper.treeToValue(json, AdaptiveModels.Catalog.class);
            assertThrows(IllegalArgumentException.class, () -> InterviewKnowledgeCatalog.validate(changed));
        }
    }

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
