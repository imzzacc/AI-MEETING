package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class MongoAdaptiveSessionStore implements AdaptiveSessionStore {
    private final MongoTemplate mongo;

    public Session find(String id) {
        return mongo.findById(id, Session.class);
    }

    public Session save(Session s) {
        return mongo.save(s);
    }

    public List<Session> work(long now) {
        return mongo.find(
                Query.query(
                                Criteria.where("finished")
                                        .is(true)
                                        .and("deleted")
                                        .is(false)
                                        .and("reportStatus")
                                        .nin("SUCCEEDED", "FAILED")
                                        .and("reportNextAttemptAt")
                                        .lte(now)
                                        .and("reportLeaseUntil")
                                        .lte(now))
                        .limit(20),
                Session.class);
    }

    public Mistake findMistake(String id) {
        return mongo.findById(id, Mistake.class);
    }

    public Mistake saveMistake(Mistake m) {
        return mongo.save(m);
    }

    public List<Session> pendingDeletions() {
        return mongo.find(Query.query(Criteria.where("deleted").is(true)
                .and("deletionComplete").ne(true)).limit(20), Session.class);
    }

    public List<Mistake> mistakesForSession(String sessionId, Long userId) {
        return mongo.find(Query.query(Criteria.where("userId").is(userId)
                .and("sessionIds").is(sessionId)), Mistake.class);
    }

    public void removeMistake(String id, Long userId) {
        mongo.remove(Query.query(Criteria.where("_id").is(id).and("userId").is(userId)), Mistake.class);
    }

    public List<Mistake> mistakes(Long userId, int offset, int size, String status, String search) {
        Criteria criteria = Criteria.where("userId").is(userId).and("dismissed").is(false);
        if (status != null && !status.isBlank()) criteria.and("status").is(status);
        if (search != null && !search.isBlank()) {
            var pattern =
                    java.util.regex.Pattern.compile(
                            java.util.regex.Pattern.quote(search),
                            java.util.regex.Pattern.CASE_INSENSITIVE);
            criteria.orOperator(
                    Criteria.where("knowledgePointId").regex(pattern),
                    Criteria.where("question").regex(pattern));
        }
        return mongo.find(
                Query.query(criteria)
                        .with(Sort.by(Sort.Direction.DESC, "updatedAt"))
                        .skip(offset)
                        .limit(size),
                Mistake.class);
    }
}
