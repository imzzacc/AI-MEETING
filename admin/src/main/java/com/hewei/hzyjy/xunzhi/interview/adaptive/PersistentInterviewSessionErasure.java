package com.hewei.hzyjy.xunzhi.interview.adaptive;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentFileAssetDO;
import com.hewei.hzyjy.xunzhi.agent.dao.mapper.AgentFileAssetMapper;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewRecordDO;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.xunzhi.interview.dao.mapper.InterviewRecordMapper;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.cache.FlightReplayLocalCache;
import com.hewei.hzyjy.xunzhi.interview.service.cache.InterviewCacheKeys;
import com.hewei.hzyjy.xunzhi.interview.service.cache.InterviewCacheStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class PersistentInterviewSessionErasure implements InterviewSessionErasure {
    private final MongoTemplate mongo;
    private final InterviewRecordMapper records;
    private final AgentFileAssetMapper files;
    private final InterviewCacheStore cache;
    private final StringRedisTemplate redis;
    private final FlightReplayLocalCache localReplay;

    public void erase(String id, Long userId) {
        Query owned = Query.query(Criteria.where("sessionId").is(id).and("userId").is(userId));
        mongo.updateMulti(owned, new Update().set("delFlag", 1).set("status", "DELETED")
                .unset("conversationTitle").unset("resumeFileUrl").unset("interviewType"), InterviewSession.class);
        Query session = Query.query(Criteria.where("sessionId").is(id));
        for (String collection : List.of("interview_question", "interview_session_runtime_hot_snapshot",
                "interview_session_runtime_cold_snapshot", "interview_session_turn_archive"))
            mongo.remove(session, collection);
        Query conversations = Query.query(Criteria.where("sessionId").in(id, id + "_grounded"));
        mongo.remove(conversations, "agent_message");
        mongo.remove(conversations, "agent_conversation");
        records.delete(new LambdaQueryWrapper<InterviewRecordDO>()
                .eq(InterviewRecordDO::getSessionId, id).eq(InterviewRecordDO::getUserId, userId));
        // Remove local references to supplier-hosted uploads. This is not a claim that the
        // supplier has physically erased its file; no supplier deletion API is configured.
        files.delete(new LambdaQueryWrapper<AgentFileAssetDO>().eq(AgentFileAssetDO::getSessionId, id));
        cache.deleteKeys(List.of(InterviewCacheKeys.questions(id), InterviewCacheKeys.suggestions(id),
                InterviewCacheKeys.resumeScore(id), InterviewCacheKeys.resumeContext(id),
                InterviewCacheKeys.demeanorScore(id), InterviewCacheKeys.direction(id),
                InterviewCacheKeys.flow(id), InterviewCacheKeys.followUpQuestions(id),
                InterviewCacheKeys.answerRequest(id), InterviewCacheKeys.turns(id), InterviewCacheKeys.turnRequest(id),
                InterviewCacheKeys.sessionScore(id), InterviewCacheKeys.sessionScoreSum(id),
                InterviewCacheKeys.sessionScoreCount(id), InterviewCacheKeys.demeanorPanic(id),
                InterviewCacheKeys.demeanorSeriousness(id), InterviewCacheKeys.demeanorEmoticon(id),
                InterviewCacheKeys.demeanorComposite(id)));
        // Scan only result/meta namespaces. Match a complete session field and never touch locks.
        for (String prefix : List.of("ai:flight:result:", "ai:flight:meta:")) {
            try (var keys = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(100).build())) {
                while (keys.hasNext()) {
                    String key = keys.next();
                    String[] fields = key.substring(prefix.length()).split("\\|", -1);
                    if (fields.length > 1 && (fields[1].equals(id) || fields[1].equals(id + "_grounded"))) redis.delete(key);
                }
            }
        }
        localReplay.invalidateSession(id);
        localReplay.invalidateSession(id + "_grounded");
    }
}
