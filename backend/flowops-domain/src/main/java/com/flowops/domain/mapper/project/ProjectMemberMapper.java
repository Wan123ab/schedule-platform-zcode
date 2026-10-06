package com.flowops.domain.mapper.project;

import com.flowops.domain.entity.project.ProjectMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 项目成员 Mapper（复合主键表，XML 手写 —— docs/05 §3.2 R2）。
 */
@Mapper
public interface ProjectMemberMapper {

    int insert(ProjectMember member);

    int delete(@Param("projectId") Long projectId, @Param("userId") Long userId);

    int updateRole(@Param("projectId") Long projectId, @Param("userId") Long userId,
                   @Param("projectRole") String projectRole);

    /** 成员列表（join app_user 展示用户信息；行键 userId 为 Map["userId"]）。 */
    List<Map<String, Object>> selectByProject(@Param("projectId") Long projectId);

    long countByProject(@Param("projectId") Long projectId);

    boolean existsByProjectAndUser(@Param("projectId") Long projectId, @Param("userId") Long userId);

    /** 用户在项目内的角色（DataScope 判定输入）。 */
    String findRole(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
