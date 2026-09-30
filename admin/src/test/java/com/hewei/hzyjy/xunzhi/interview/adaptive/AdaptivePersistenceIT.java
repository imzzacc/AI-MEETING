package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import org.junit.jupiter.api.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.*;
import java.util.concurrent.*;

/** Requires a real, isolated MongoDB. Never silently skips a missing database. */
class AdaptivePersistenceIT {
    MongoClient client;
    MongoTemplate mongo;
    MongoAdaptiveSessionStore store;

    @BeforeEach
    void setup() throws Exception {
        String uri =
                System.getenv()
                        .getOrDefault(
                                "AI_MEETING_TEST_MONGO_URI",
                                "mongodb://127.0.0.1:28029/?serverSelectionTimeoutMS=3000");
        client = MongoClients.create(uri);
        mongo =
                new MongoTemplate(
                        client, "ai_meeting_it_" + UUID.randomUUID().toString().replace("-", ""));
        mongo.executeCommand("{ping:1}");
        store = new MongoAdaptiveSessionStore(mongo);
        var manifest =
                org.bson.Document.parse(
                        java.nio.file.Files.readString(
                                java.nio.file.Path.of("../scripts/adaptive-indexes.json")));
        for (String collection : manifest.keySet()) {
            for (var index : manifest.getList(collection, org.bson.Document.class)) {
                mongo.getCollection(collection)
                        .createIndex(
                                index.get("keys", org.bson.Document.class),
                                new com.mongodb.client.model.IndexOptions()
                                        .name(index.getString("name"))
                                        .unique(index.getBoolean("unique", false)));
            }
        }
    }

    @AfterEach
    void cleanup() {
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
    }

    Session create() {
        Session s = new Session();
        s.setId("session");
        s.setUserId(7L);
        s.setMode(Mode.ADAPTIVE);
        s.setCatalog(new Catalog("v1", List.of()));
        s.setQuestions(List.of(new MainQuestion("1", "fixed question", "hash", List.of(), 120)));
        s.setStartedAt(123456);
        store.save(s);
        return s;
    }

    @Test
    void realCatalogAndDecisionKeysRoundTripThroughApplicationMapping() throws Exception {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.register(AdaptiveMongoMappingConfiguration.class);
            context.registerBean("mongoConverter",
                    org.springframework.data.mongodb.core.convert.MappingMongoConverter.class,
                    () -> (org.springframework.data.mongodb.core.convert.MappingMongoConverter) mongo.getConverter());
            context.refresh();
            var catalog = new InterviewKnowledgeCatalog(
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    new org.springframework.core.io.DefaultResourceLoader(), new AdaptiveConfiguration()).snapshot();
            Session s = create();
            s.setCatalog(catalog);
            s.setRetrievalScope(RetrievalScope.defaults());
            var keys = Map.of("java.volatile", "VERSION_MISMATCH", "java_volatile", "DISTINCT_KEY");
            s.setCatalogExclusions(keys);
            var decision = new Decision("NEXT_MAIN", "NO_CANDIDATE", null, null, "v1", catalog.version(), keys);
            s.getTurns().add(new Turn("r", "h", "1", "volatile", "answer", 1,
                    new Evaluation(50, "feedback", List.of(), "test", "1"), List.of(), decision, null, null));
            store.save(s);
            Session restored = new MongoAdaptiveSessionStore(new MongoTemplate(client, mongo.getDb().getName())).find(s.getId());
            assertEquals(catalog, restored.getCatalog());
            assertEquals(RetrievalScope.defaults(), restored.getRetrievalScope());
            assertEquals(keys, restored.getCatalogExclusions());
            assertEquals(keys, restored.getTurns().get(0).decision().excluded());
            var raw = mongo.getCollection("interview_adaptive_session").find().first();
            assertNotNull(raw);
            var scopes = raw.get("catalog", org.bson.Document.class).get("scopes", org.bson.Document.class);
            assertTrue(scopes.containsKey("java.volatile"));
        }
    }

    @Test
    void staleWriterCannotOverwriteCommittedAnswerAndScore() {
        create();
        Session first = store.find("session"), stale = store.find("session");
        Evaluation evaluation = new Evaluation(80, "feedback", List.of(), "test", "1");
        first.getTurns()
                .add(
                        new Turn(
                                "r1",
                                "answer-hash",
                                "1",
                                "fixed question",
                                "x".repeat(4999),
                                200000,
                                evaluation,
                                List.of(),
                                null,
                                null,
                                null));
        first.setMainIndex(1);
        first.setFinished(true);
        first.setReportStatus("PENDING");
        store.save(first);
        stale.setMainIndex(5);
        assertThrows(OptimisticLockingFailureException.class, () -> store.save(stale));
        // A fresh template has no reference to the original Java objects or cache.
        Session restored =
                new MongoAdaptiveSessionStore(new MongoTemplate(client, mongo.getDb().getName()))
                        .find("session");
        assertEquals(80, restored.totalScore());
        assertEquals(1, restored.getMainIndex());
        assertEquals(4999, restored.getTurns().get(0).answer().length());
        assertEquals(123456, restored.getStartedAt());
        assertEquals(1, store.work(300000).size());
    }

    @Test
    void concurrentCommitsHaveExactlyOneWinner() throws Exception {
        create();
        var first = store.find("session");
        var second = store.find("session");
        var executor = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (Session s : List.of(first, second))
                results.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    s.setExtensionSeconds(300);
                                    try {
                                        store.save(s);
                                        return true;
                                    } catch (OptimisticLockingFailureException e) {
                                        return false;
                                    }
                                }));
            gate.countDown();
            int wins = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) wins++;
            assertEquals(1, wins);
            assertEquals(300, store.find("session").getExtensionSeconds());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void filteringAndPagingStayInsideOwnerBoundary() {
        for (int i = 0; i < 25; i++) {
            Mistake m = new Mistake();
            m.setId("m" + i);
            m.setUserId(i == 24 ? 8L : 7L);
            m.setKnowledgePointId("java.volatile");
            m.setGapKey("gap" + i);
            m.setType("CONCEPT_ERROR");
            m.setQuestion("volatile i++");
            m.setUpdatedAt(i);
            store.saveMistake(m);
        }
        assertEquals(20, store.mistakes(7L, 0, 20, "TO_REVIEW", "volatile").size());
        assertEquals(4, store.mistakes(7L, 20, 20, "TO_REVIEW", "volatile").size());
        assertEquals(0, store.mistakes(7L, 0, 20, "MASTERED", "").size());
        assertEquals(0, store.mistakes(7L, 0, 20, null, ".*").size());
    }

    @Test
    void sameGapInDifferentScopesCanPersistButSameIdentityCannotDuplicate() {
        for (String scope : List.of("java17", "java21")) {
            Mistake m = new Mistake(); m.setId("user7-volatile-atomicity-" + scope);
            m.setUserId(7L); m.setKnowledgePointId("java.volatile");
            m.setGapKey("atomicity"); m.setType("CONCEPT_ERROR");
            store.saveMistake(m);
        }
        assertEquals(2, store.mistakes(7L, 0, 20, null, null).size());
        var stale = store.findMistake("user7-volatile-atomicity-java17");
        var fresh = store.findMistake(stale.getId()); fresh.setNote("new revision"); store.saveMistake(fresh);
        assertThrows(OptimisticLockingFailureException.class, () -> store.saveMistake(stale));
    }

    @Test
    void deletionRemovesOnlyOwnedSessionProjectionsAndPersistsRetryMarker() {
        var session = create();
        session.setDeleted(true);
        store.save(session);
        assertEquals(1, store.pendingDeletions().size());
        for (String id : List.of("session", "other")) {
            var base = new com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSession();
            base.setSessionId(id); base.setUserId(id.equals("session") ? 7L : 8L);
            base.setDelFlag(0); base.setResumeFileUrl("private-file"); mongo.save(base);
            for (String collection : List.of("interview_question", "interview_session_runtime_hot_snapshot",
                    "interview_session_runtime_cold_snapshot", "interview_session_turn_archive", "agent_message", "agent_conversation")) {
                mongo.getCollection(collection).insertOne(new org.bson.Document("sessionId", id).append("content", "private"));
            }
            Mistake m = new Mistake(); m.setId("m-" + id); m.setUserId(base.getUserId());
            m.getSessionIds().add(id); store.saveMistake(m);
        }
        var redis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        var cursor = (org.springframework.data.redis.core.Cursor<String>) org.mockito.Mockito.mock(org.springframework.data.redis.core.Cursor.class);
        org.mockito.Mockito.when(redis.scan(org.mockito.ArgumentMatchers.any())).thenReturn(cursor);
        var erasure = new PersistentInterviewSessionErasure(mongo,
                org.mockito.Mockito.mock(com.hewei.hzyjy.xunzhi.interview.dao.mapper.InterviewRecordMapper.class),
                org.mockito.Mockito.mock(com.hewei.hzyjy.xunzhi.agent.dao.mapper.AgentFileAssetMapper.class),
                org.mockito.Mockito.mock(com.hewei.hzyjy.xunzhi.interview.service.cache.InterviewCacheStore.class),
                redis, org.mockito.Mockito.mock(com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.cache.FlightReplayLocalCache.class));
        erasure.erase("session", 7L);
        erasure.erase("session", 7L);
        for (String collection : List.of("interview_question", "interview_session_runtime_hot_snapshot",
                "interview_session_runtime_cold_snapshot", "interview_session_turn_archive", "agent_message", "agent_conversation")) {
            assertEquals(1, mongo.getCollection(collection).countDocuments());
            assertEquals("other", mongo.getCollection(collection).find().first().getString("sessionId"));
        }
        var deleted = mongo.getCollection("interview_session").find(new org.bson.Document("sessionId", "session")).first();
        assertEquals(1, deleted.getInteger("delFlag")); assertFalse(deleted.containsKey("resumeFileUrl"));
        assertEquals(1, store.mistakesForSession("session", 7L).size());
        assertTrue(store.mistakesForSession("session", 8L).isEmpty());
        store.removeMistake("m-session", 8L); assertNotNull(store.findMistake("m-session"));
        store.removeMistake("m-session", 7L); assertNull(store.findMistake("m-session"));
        assertNotNull(store.findMistake("m-other"));
        session = store.find("session"); session.setDeletionComplete(true); store.save(session);
        assertTrue(store.pendingDeletions().isEmpty());
    }
}
