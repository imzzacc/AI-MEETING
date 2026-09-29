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
}
