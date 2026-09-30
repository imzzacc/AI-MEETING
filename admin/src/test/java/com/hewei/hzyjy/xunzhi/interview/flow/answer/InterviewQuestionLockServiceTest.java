package com.hewei.hzyjy.xunzhi.interview.flow.answer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hewei.hzyjy.xunzhi.interview.config.InterviewAnswerGuardConfiguration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class InterviewQuestionLockServiceTest {
    @Test
    void adaptiveReadsWaitForBriefContentionWithoutChangingLegacyLockPolicy() throws Exception {
        var configuration = new InterviewAnswerGuardConfiguration();
        var redis = mock(RedissonClient.class);
        var adaptive = mock(RLock.class);
        var legacy = mock(RLock.class);
        when(redis.getLock("interview:answer:lock:session:adaptive-session")).thenReturn(adaptive);
        when(redis.getLock("interview:answer:lock:session:1")).thenReturn(legacy);
        when(adaptive.tryLock(2000L, -1L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(legacy.tryLock(0L, 120000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        var service = new InterviewQuestionLockService(redis, configuration);
        assertSame(adaptive, service.acquireAdaptive("session"));
        assertSame(legacy, service.acquire("session", "1"));
        verify(adaptive).tryLock(2000L, -1L, TimeUnit.MILLISECONDS);
        verify(legacy).tryLock(0L, 120000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void sustainedContentionStillReturnsNoLockAndExplicitZeroWaitIsHonored() throws Exception {
        var configuration = new InterviewAnswerGuardConfiguration();
        configuration.setAdaptiveLockWaitMillis(0L);
        var redis = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        var service = new InterviewQuestionLockService(redis, configuration);
        assertNull(service.acquireAdaptive("session"));
        service.release(null);
        verify(lock).tryLock(0L, -1L, TimeUnit.MILLISECONDS);
        verify(lock, never()).unlock();
    }
}
