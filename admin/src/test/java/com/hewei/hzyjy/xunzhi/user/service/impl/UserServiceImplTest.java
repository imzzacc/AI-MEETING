package com.hewei.hzyjy.xunzhi.user.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import com.hewei.hzyjy.xunzhi.user.api.io.req.UserLoginReqDTO;
import com.hewei.hzyjy.xunzhi.user.api.io.req.UserRegisterReqDTO;
import com.hewei.hzyjy.xunzhi.user.dao.entity.UserDO;
import com.hewei.hzyjy.xunzhi.user.dao.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class UserServiceImplTest {
    private final UserMapper mapper = mock(UserMapper.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RLock lock = mock(RLock.class);
    private UserServiceImpl service;

    @BeforeEach
    void setup() {
        service = new UserServiceImpl(redisson, redis);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    private UserRegisterReqDTO registration() {
        var request = new UserRegisterReqDTO();
        request.setUsername("auth-regression-user");
        request.setPassword("test-only-password");
        return request;
    }

    private void acquireLock() {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
    }

    @Test
    void usernameAvailabilityUsesDatabaseEvenWithoutRegistrationCache() {
        when(mapper.exists(any())).thenReturn(false, true);
        assertTrue(service.hasUsername("not-in-database"));
        assertFalse(service.hasUsername("already-in-database"));
        verifyNoInteractions(redisson, redis);
    }

    @Test
    void registrationDoesNotDependOnBloomFilterAvailabilityOrStaleEntries() {
        acquireLock();
        when(mapper.exists(any())).thenReturn(false);
        when(mapper.insert(any(UserDO.class))).thenReturn(1);
        service.register(registration());
        var order = inOrder(lock, mapper);
        order.verify(lock).tryLock();
        order.verify(mapper).exists(any());
        order.verify(mapper).insert(any(UserDO.class));
        order.verify(lock).unlock();
        verify(redisson, never()).getBloomFilter(anyString());
    }

    @Test
    void registeredUsernameIsRejectedWithoutOverwritingAccount() {
        acquireLock();
        when(mapper.exists(any())).thenReturn(true);
        var error = assertThrows(ClientException.class, () -> service.register(registration()));
        assertEquals("B000201", error.getErrorCode());
        verify(mapper, never()).insert(any(UserDO.class));
        verify(lock).unlock();
    }

    @Test
    void concurrentRegistrationLockIsNotReportedAsExistingUser() {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);
        var error = assertThrows(ClientException.class, () -> service.register(registration()));
        assertTrue(error.getMessage().contains("处理中"));
        verifyNoInteractions(mapper);
        verify(lock, never()).unlock();
    }

    @Test
    void uniqueConstraintStillRejectsRaceAfterAvailabilityCheck() {
        acquireLock();
        when(mapper.exists(any())).thenReturn(false);
        when(mapper.insert(any(UserDO.class))).thenThrow(new DuplicateKeyException("synthetic race"));
        var error = assertThrows(ClientException.class, () -> service.register(registration()));
        assertEquals("B000202", error.getErrorCode());
        verify(lock).unlock();
    }

    @Test
    void failedInsertReleasesLockAndReportsSaveFailure() {
        acquireLock();
        when(mapper.exists(any())).thenReturn(false);
        when(mapper.insert(any(UserDO.class))).thenReturn(0);
        var error = assertThrows(ClientException.class, () -> service.register(registration()));
        assertEquals("B000203", error.getErrorCode());
        verify(lock).unlock();
    }

    @Test
    void unmatchedCredentialsDoNotClaimThatTheUsernameDoesNotExist() {
        var request = new UserLoginReqDTO();
        request.setUsername("auth-regression-user");
        request.setPassword("wrong-test-password");
        when(mapper.selectOne(any())).thenReturn(null);
        var error = assertThrows(ClientException.class, () -> service.login(request));
        assertTrue(error.getMessage().contains("用户名或密码错误"));
        verifyNoInteractions(redis, redisson);
    }
}
