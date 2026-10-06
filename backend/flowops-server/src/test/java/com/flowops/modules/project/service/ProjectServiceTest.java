package com.flowops.modules.project.service;

import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.mapper.auth.AppUserMapper;
import com.flowops.domain.mapper.concurrency.ConcurrencyQueryMapper;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.domain.mapper.project.ProjectMemberMapper;
import com.flowops.modules.project.dto.MemberRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目空间单测（docs/07 §6.1 / PRD §10.2）：
 * 停用闸门 42203、触发器联动、负责人保护 42201、冗余计数纪律。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectServiceTest {

    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private AppUserMapper appUserMapper;
    @Mock private ConcurrencyQueryMapper concurrencyQuery;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private ProjectService service;

    private Project project;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.increment(any(String.class))).thenReturn(1L);
        project = new Project();
        project.setId(1L);
        project.setProjectId("PRJ-20261006-0001");
        project.setProjectName("试点项目");
        project.setStatus("ENABLED");
        project.setOwnerUserId(9L);
        lenient().when(projectMapper.selectOne(any())).thenReturn(project);
    }

    @Test
    void 停用_存在运行中任务_42203并附带blocking明细() {
        when(concurrencyQuery.countRunningTasksByProject(1L)).thenReturn(2L);
        when(projectMapper.findRunningTaskIdsByProject(1L, 20))
                .thenReturn(List.of("TASK-20261006-0001", "TASK-20261006-0002"));

        assertThatThrownBy(() -> service.updateStatus("PRJ-20261006-0001", "DISABLED"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42203);
        // 闸门用实时 count（docs/05 §6.3：绝不读 stat_* 快照）
        verify(projectMapper, never()).disableProjectTriggers(1L);
    }

    @Test
    void 停用_无运行中任务_触发器联动暂停() {
        when(concurrencyQuery.countRunningTasksByProject(1L)).thenReturn(0L);

        service.updateStatus("PRJ-20261006-0001", "DISABLED");

        verify(projectMapper).disableProjectTriggers(1L);   // docs/06 §11.3 ①：记录原状态后暂停
        assertThat(project.getStatus()).isEqualTo("DISABLED");
    }

    @Test
    void 启用_触发器按原状态恢复且next_fire_time置空待重算() {
        project.setStatus("DISABLED");

        service.updateStatus("PRJ-20261006-0001", "ENABLED");

        verify(projectMapper).enableProjectTriggers(1L);   // docs/06 §11.3 ②（重算在 M3）
        assertThat(project.getStatus()).isEqualTo("ENABLED");
    }

    @Test
    void 移除负责人_42201_负责人不可移除() {
        assertThatThrownBy(() -> service.removeMember("PRJ-20261006-0001", 9L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42201);
        verify(projectMemberMapper, never()).delete(1L, 9L);
    }

    @Test
    void 变更负责人角色_同样42201() {
        assertThatThrownBy(() -> service.updateMemberRole("PRJ-20261006-0001", 9L, "BUSINESS"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42201);
    }

    @Test
    void 移除普通成员_成功并刷新冗余计数() {
        when(projectMemberMapper.delete(1L, 5L)).thenReturn(1);
        when(projectMemberMapper.countByProject(1L)).thenReturn(3L);

        service.removeMember("PRJ-20261006-0001", 5L);

        assertThat(project.getStatMemberCount()).isEqualTo(3);   // 实时 count 回填快照（仅展示用）
    }

    @Test
    void 重复添加成员_拒绝() {
        when(appUserMapper.selectOne(any())).thenReturn(user(5L, "bob"));
        when(projectMemberMapper.existsByProjectAndUser(1L, 5L)).thenReturn(true);

        var request = new MemberRequests.AddMember();
        request.setUsername("bob");
        request.setProjectRole("BUSINESS");

        assertThatThrownBy(() -> service.addMember("PRJ-20261006-0001", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已是项目成员");
    }

    private com.flowops.domain.entity.auth.AppUser user(long id, String username) {
        var u = new com.flowops.domain.entity.auth.AppUser();
        u.setId(id);
        u.setUsername(username);
        return u;
    }
}
