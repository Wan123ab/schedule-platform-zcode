package com.flowops.modules.auth.scope;

import com.flowops.common.context.ScopeContext;
import com.flowops.domain.mapper.asset.ClusterMapper;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * AuthScopeQueries 的 MyBatis 实现（查询见各 Mapper 的 XML）。
 *
 * <p><b>为什么全部包在 {@link ScopeContext#withoutScope} 里</b>：这三个查询是<b>授权凭据本身</b>
 * （"我能看哪些项目/集群"）——它们若再被数据权限过滤，就变成"先有鸡还是先有蛋"，
 * 且会因上一条请求残留的 ThreadLocal 而静默少算。故一律在无过滤临界区内执行。</p>
 */
@Component
@RequiredArgsConstructor
public class MyBatisAuthScopeQueries implements DataScopeResolver.AuthScopeQueries {

    private final AuthScopeMapper authScopeMapper;
    private final ClusterMapper clusterMapper;

    @Override
    public Set<String> roleScopeTypes(Long userId) {
        return Set.copyOf(ScopeContext.withoutScope(() -> authScopeMapper.selectScopeTypes(userId)));
    }

    @Override
    public Set<Long> memberProjectIds(Long userId) {
        return Set.copyOf(ScopeContext.withoutScope(() -> authScopeMapper.selectMemberProjectIds(userId)));
    }

    /**
     * 被授权集群：复用 {@link ClusterMapper#findAuthorizedClusterIds} 的 SQL
     * （同一口径两处复用，避免"授权含义"出现第二份实现）。
     */
    @Override
    public Set<Long> authorizedClusterIds(Long userId) {
        return Set.copyOf(ScopeContext.withoutScope(() -> clusterMapper.findAuthorizedClusterIds(userId)));
    }

    /** 聚合既有查询为范围专用读模型（SQL 复用 XML，见 AuthScopeMapper.xml）。 */
    @Mapper
    public interface AuthScopeMapper {

        List<String> selectScopeTypes(@org.apache.ibatis.annotations.Param("userId") Long userId);

        List<Long> selectMemberProjectIds(@org.apache.ibatis.annotations.Param("userId") Long userId);
    }
}
