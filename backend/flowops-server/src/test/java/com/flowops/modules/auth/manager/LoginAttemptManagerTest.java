package com.flowops.modules.auth.manager;

import com.flowops.domain.entity.auth.AppUser;
import com.flowops.domain.mapper.auth.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录锁定单测（docs/07 §5.1：连续 5 次锁定 15 分钟；40101 附 locked_until）。
 * 双层设计：Redis 计数（"连续"语义）+ DB locked_until（权威锁定状态）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginAttemptManagerTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private AppUserMapper appUserMapper;

    private LoginAttemptManager manager;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        manager = new LoginAttemptManager(redis, appUserMapper);
    }

    @Test
    void 未满5次_只计数_不落库锁定() {
        when(valueOps.increment("login:fail:alice")).thenReturn(3L);

        manager.onFailure("alice");

        verify(redis).expire("login:fail:alice", LoginAttemptManager.LOCK_DURATION);
        verify(appUserMapper, never()).update(any(), any());   // 未达阈值：DB 不动
    }

    @Test
    void 满5次_落库锁定15分钟() {
        when(valueOps.increment("login:fail:alice")).thenReturn(5L);

        manager.onFailure("alice");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<AppUser>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(appUserMapper).update(any(), captor.capture());   // set locked_until + status=LOCKED
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    void 锁定中_返回解锁时刻() {
        AppUser locked = new AppUser();
        locked.setLockedUntil(OffsetDateTime.now().plusMinutes(10));
        when(appUserMapper.selectOne(any())).thenReturn(locked);

        assertThat(manager.lockedUntil("alice")).isNotNull();
    }

    @Test
    void 锁定已过期_视为未锁定() {
        AppUser expired = new AppUser();
        expired.setLockedUntil(OffsetDateTime.now().minusMinutes(1));
        when(appUserMapper.selectOne(any())).thenReturn(expired);

        assertThat(manager.lockedUntil("alice")).isNull();
    }

    @Test
    void 成功登录_清账() {
        manager.onSuccess("alice");

        verify(redis).delete("login:fail:alice");
        verify(appUserMapper, times(1)).update(any(), any());   // 复位 locked_until/status/计数
    }
}
