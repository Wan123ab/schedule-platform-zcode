package com.flowops.modules.auth.manager;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flowops.domain.entity.auth.AppUser;
import com.flowops.domain.mapper.auth.AppUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 登录锁定管理器（docs/07 §5.1 / PRD §11.2：连续 5 次锁定 15 分钟）。
 *
 * <p><b>双层设计</b>：Redis 计数器（`login:fail:{username}`，TTL 15 分钟）承载"连续失败"
 * 的快速判定；DB `locked_until` 承载锁定的权威状态 —— 计数器过期/Redis 重启只影响
 * "是否更快解锁"，不影响"正在锁定中"的判定（安全状态不依赖易失存储）。</p>
 *
 * <p><b>manager 层职责（docs/03 §2.1）</b>：跨域组合（Redis + DB）与计数编排，
 * 不定事务边界 —— 事务在 AuthService（service 层）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAttemptManager {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final String FAIL_KEY_PREFIX = "login:fail:";

    private final StringRedisTemplate redis;
    private final AppUserMapper appUserMapper;

    /** 锁定中返回解锁时刻（40101 附带 locked_until，前端渲染 mm:ss 倒计时）；未锁定返回 empty。 */
    public OffsetDateTime lockedUntil(String username) {
        AppUser user = appUserMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getUsername, username).eq(AppUser::getDeleted, false));
        return user != null && user.getLockedUntil() != null
                && user.getLockedUntil().isAfter(OffsetDateTime.now())
                ? user.getLockedUntil() : null;
    }

    /** 登录失败记账：计数满 5 次落 DB 锁定（Redis 键过期后计数归零 = "连续"语义的自然实现）。 */
    public void onFailure(String username) {
        Long failures = redis.opsForValue().increment(FAIL_KEY_PREFIX + username);
        if (failures != null) {
            redis.expire(FAIL_KEY_PREFIX + username, LOCK_DURATION);
            if (failures >= MAX_FAILURES) {
                OffsetDateTime until = OffsetDateTime.now().plus(LOCK_DURATION);
                appUserMapper.update(null, Wrappers.<AppUser>lambdaUpdate()
                        .eq(AppUser::getUsername, username)
                        .set(AppUser::getLockedUntil, until)
                        .set(AppUser::getStatus, "LOCKED"));
                log.warn("账号锁定 username={} failures={} until={}", username, failures, until);
            }
        }
    }

    /** 登录成功清账：计数删除 + DB 锁定/状态复位。 */
    public void onSuccess(String username) {
        redis.delete(FAIL_KEY_PREFIX + username);
        appUserMapper.update(null, Wrappers.<AppUser>lambdaUpdate()
                .eq(AppUser::getUsername, username)
                .set(AppUser::getLockedUntil, null)
                .set(AppUser::getStatus, "ENABLED")
                .set(AppUser::getLoginFailCount, 0));
    }
}
