package com.hewei.hzyjy.xunzhi.interview.adaptive;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import org.junit.jupiter.api.AfterEach;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

import java.util.*;

/**
 * Same crash matrix against real MongoDB: what the retry reads back must be exactly what was
 * written, including the stored response it replays.
 */
class AnswerCrashMatrixIT extends AnswerCrashMatrixCases {
    final List<MongoClient> clients = new ArrayList<>();
    final List<MongoTemplate> databases = new ArrayList<>();

    @Override
    AdaptiveSessionStore newStore() {
        String uri =
                System.getenv()
                        .getOrDefault(
                                "AI_MEETING_TEST_MONGO_URI",
                                "mongodb://127.0.0.1:28029/?serverSelectionTimeoutMS=3000");
        MongoClient client = MongoClients.create(uri);
        clients.add(client);
        var mongo =
                new MongoTemplate(
                        client, "ai_meeting_it_" + UUID.randomUUID().toString().replace("-", ""));
        // Same as AdaptiveMongoMappingConfiguration: knowledge IDs contain dots.
        ((MappingMongoConverter) mongo.getConverter()).preserveMapKeys(true);
        mongo.executeCommand("{ping:1}");
        databases.add(mongo);
        return new MongoAdaptiveSessionStore(mongo);
    }

    @AfterEach
    void dropDatabases() {
        databases.forEach(mongo -> mongo.getDb().drop());
        clients.forEach(MongoClient::close);
    }
}
