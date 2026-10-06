package com.flowops.modules.auth.scope;

import com.flowops.domain.mapper.project.ProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** AuthScopeQueries 的 MyBatis 实现（查询见各 Mapper 的 XML）。 */
@Component
@RequiredArgsConstructor
public class MyBatisAuthScopeQueries implements DataScopeResolver.AuthScopeQueries {

    private final AuthScopeMapper authScopeMapper;

    @Override
    public Set<String> roleScopeTypes(Long userId) {
        return Set.copyOf(authScopeMapper.selectScopeTypes(userId));
    }

    @Override
    public Set<Long> memberProjectIds(Long userId) {
        return Set.copyOf(authScopeMapper.selectMemberProjectIds(userId));
    }

    /** 聚合两个既有查询为范围专用读模型（SQL 复用 XML，见 AuthScopeMapper.xml）。 */
    @Mapper
    public interface AuthScopeMapper {

        List<String> selectScopeTypes(@org.apache.ibatis.annotations.Param("userId") Long userId);

        List<Long> selectMemberProjectIds(@org.apache.ibatis.annotations.Param("userId") Long userId);
    }
}
