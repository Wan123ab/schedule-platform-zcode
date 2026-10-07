package com.flowops.modules.asset.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.context.UserContext;
import com.flowops.common.context.ScopeContext;
import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.asset.Operator;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.mapper.asset.OperatorMapper;
import com.flowops.domain.mapper.asset.OperatorVersionMapper;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.modules.asset.converter.OperatorConverter;
import com.flowops.modules.asset.dto.OperatorVO;
import com.flowops.modules.asset.dto.SaveOperatorRequest;
import com.flowops.modules.governance.scope.ScopeGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 算子服务（docs/05 §3.3 operator；接口映射 docs/07 §5.4）。
 *
 * <p><b>红线</b>：</p>
 * <ul>
 *   <li>删除闸门用<b>引用反查</b>（42211：任一新版本已被工作流步骤引用即禁删）——
 *       不读 {@code version_count} 之类的快照列（docs/05 §6.3：冗余计数只用于展示）；</li>
 *   <li>单条读写走 {@link #requireVisible}：查不到时由 {@link ScopeGuard} 区分
 *       40400（真不存在）与 40301（存在但越权）—— 运维把"无权限"误读成"数据丢了"
 *       是排查事故的常见放大器；</li>
 *   <li>行级过滤（{@code operator.project_id}）由
 *       {@link com.flowops.config.FlowopsDataPermissionHandler} 在 Mapper 层注入
 *       （本表已登记在 PROJECT_SCOPED_COLUMNS），本类不手写数据范围条件；</li>
 *   <li>算子的<b>归属项目创建后不可改</b>：换项目等于把可执行代码搬出原项目边界，
 *       而已发布版本可能正被原项目的工作流引用，静默搬迁会同时破坏隔离与可追溯性。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorService {

    private final OperatorMapper operatorMapper;
    private final OperatorVersionMapper versionMapper;
    private final ProjectMapper projectMapper;
    private final IdGen idGen;
    private final OperatorConverter converter;
    private final ScopeGuard scopeGuard;

    // ── 查询 ────────────────────────────────────────────────

    public IPage<OperatorVO> page(long page, long size, String operatorType, String projectBusinessId,
                                  String keyword) {
        Long projectId = projectBusinessId == null || projectBusinessId.isBlank()
                ? null : resolveProjectId(projectBusinessId);
        Page<Operator> result = operatorMapper.selectPage(new Page<>(page, Math.min(size, 200)),
                Wrappers.<Operator>lambdaQuery()
                        .eq(Operator::getDeleted, false)
                        .eq(operatorType != null && !operatorType.isBlank(), Operator::getOperatorType, operatorType)
                        .eq(projectId != null, Operator::getProjectId, projectId)
                        .like(keyword != null && !keyword.isBlank(), Operator::getOperatorName, keyword)
                        .orderByAsc(Operator::getOperatorId));
        return result.convert(this::toVO);
    }

    public OperatorVO get(String operatorId) {
        return toVO(requireVisible(operatorId));
    }

    // ── 写操作 ──────────────────────────────────────────────

    /** 新建（必审动作 CREATE_OPERATOR）：编号取全局递增式 OP-####（docs/05 §6.2）。 */
    @Transactional(rollbackFor = Exception.class)
    public OperatorVO create(SaveOperatorRequest request) {
        Long projectId = resolveProjectId(request.getProjectId());
        requireNameAvailable(projectId, request.getOperatorName(), null);

        Operator operator = new Operator();
        operator.setOperatorId(idGen.next("OP", "op"));
        operator.setOperatorName(request.getOperatorName());
        operator.setOperatorType(request.getOperatorType());
        operator.setProjectId(projectId);
        operator.setDescription(request.getDescription());
        operator.setStatus(request.getStatus() != null ? request.getStatus() : "ENABLED");
        operator.setVersionCount(0);
        operator.setLatestVersion(null);      // 尚未上传任何版本
        operator.setCreator(currentUsername());
        operator.setVersion(0);
        operator.setDeleted(false);
        operatorMapper.insert(operator);
        log.info("算子已创建 operator={} name={} type={}",
                operator.getOperatorId(), operator.getOperatorName(), operator.getOperatorType());
        return toVO(operator);
    }

    /**
     * 编辑基础信息与启停。
     *
     * <p>未加 {@code @Audited}：docs/07 §7.3 的算子必审动作清单里只有
     * CREATE/UPLOAD/PUBLISH/OFFLINE/DELETE 五个，不含编辑。要补审计应先补文档
     * （审计动作清单是单一真源，不能在代码里凭空多出一个 action 码）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public OperatorVO update(String operatorId, SaveOperatorRequest request) {
        Operator operator = requireVisible(operatorId);
        Long requestedProjectId = resolveProjectId(request.getProjectId());
        if (!requestedProjectId.equals(operator.getProjectId())) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "算子归属项目创建后不可变更",
                    Map.of("rule", "OPERATOR_PROJECT_IMMUTABLE",
                            "current_project_id", request.getProjectId()));
        }
        requireNameAvailable(operator.getProjectId(), request.getOperatorName(), operator.getId());
        operator.setOperatorName(request.getOperatorName());
        operator.setOperatorType(request.getOperatorType());
        operator.setDescription(request.getDescription());
        if (request.getStatus() != null) {
            operator.setStatus(request.getStatus());
        }
        operatorMapper.updateById(operator);
        log.info("算子已更新 operator={} status={}", operatorId, operator.getStatus());
        return toVO(operator);
    }

    /** 删除（必审动作 DELETE_OPERATOR）：42211 引用闸门 + 连同版本一起软删。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String operatorId) {
        Operator operator = requireVisible(operatorId);
        long references = operatorMapper.countVersionReferences(operator.getId());
        if (references > 0) {
            throw new BizException(ErrorCode.OPERATOR_VERSION_REFERENCED,
                    "算子版本已被工作流引用，禁止删除",
                    Map.of("ref_count", references, "operator_id", operatorId));
        }
        // 先删版本再删算子：顺序反了也不出错，但反过来读日志会看到一个"算子已删、
        // 版本还在"的中间态，排查时容易误判
        int versions = versionMapper.softDeleteByOperatorId(operator.getId());
        operatorMapper.softDelete(operator.getId());
        log.info("算子已删除 operator={} 连同版本 {} 个", operatorId, versions);
    }

    // ── 供算子版本服务复用的归属解析 ──────────────────────────

    /** 按业务编号取算子（已应用数据范围）。 */
    public Operator requireVisible(String operatorId) {
        Operator visible = findByBusinessId(operatorId);
        if (visible != null) {
            return visible;
        }
        throw scopeGuard.notVisible("OPERATOR", operatorId, () -> findByBusinessId(operatorId) != null);
    }

    /** 按内部主键取算子（不做越权判定：调用方已在归属链上判过，如算子版本）。 */
    public Operator findById(Long id) {
        return id == null ? null : operatorMapper.selectById(id);
    }

    /**
     * 按内部主键取算子，<b>并判定可见性</b>（算子版本必须借这条路径做数据范围判断）。
     *
     * <p>存在的原因：{@code operator_version} 表没有 {@code project_id} 列，行级过滤
     * 是按 {@code operator.project_id} 注入的、覆盖不到版本表。若版本接口直接用
     * {@code selectOne(version_id)}，任何登录用户都能按编号读到别的项目的算子包元数据
     * （启动命令、环境变量、参数模板）—— 这是一个静默越权。故版本侧统一改为
     * "先取版本行 → 再回父算子做可见性判定"。</p>
     */
    public Operator requireVisibleById(Long id) {
        Operator visible = findById(id);
        if (visible != null) {
            return visible;
        }
        // 无过滤探测：区分"真不存在"（40400）与"存在但越权"（40301）。
        // 探测只取 boolean，返回值绝不用于组装响应体（ScopeContext 的纪律）
        throw scopeGuard.notVisible("OPERATOR", String.valueOf(id),
                () -> ScopeContext.withoutScope(() -> operatorMapper.selectById(id)) != null);
    }

    /**
     * 刷新展示快照列（{@code version_count} / {@code latest_version}）。
     *
     * <p>单独开一个小方法是刻意的：快照列只能由"算子版本服务"在写完版本后调用，
     * 若让版本服务直接持有 OperatorMapper 自己 update，{@code version_count} 的
     * 维护口径就会散到两个类里（docs/05 §6.3 明确它只是展示值，更该单点维护）。</p>
     */
    public void updateSnapshot(Operator operator) {
        operatorMapper.updateById(operator);
    }

    // ── 内部 ────────────────────────────────────────────────

    private Operator findByBusinessId(String operatorId) {
        return operatorMapper.selectOne(Wrappers.<Operator>lambdaQuery()
                .eq(Operator::getOperatorId, operatorId)
                .eq(Operator::getDeleted, false));
    }

    /** 归属项目解析：入参是业务编号 {@code PRJ-xxxx}（内部主键不出网，D-27）。 */
    Long resolveProjectId(String projectBusinessId) {
        if (projectBusinessId == null || projectBusinessId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "所属项目必填");
        }
        Project project = projectMapper.selectOne(Wrappers.<Project>lambdaQuery()
                .eq(Project::getProjectId, projectBusinessId)
                .eq(Project::getDeleted, false));
        if (project == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "归属项目不存在: " + projectBusinessId,
                    Map.of("resource_type", "PROJECT", "resource_id", projectBusinessId));
        }
        return project.getId();
    }

    /**
     * 同项目内算子名唯一（{@code uk_operator_project_name} 是 deleted=false 的部分唯一索引）
     * —— 先给人话错误，而不是让 DB 抛 23505。
     */
    private void requireNameAvailable(Long projectId, String name, Long excludeId) {
        Long existing = operatorMapper.selectCount(Wrappers.<Operator>lambdaQuery()
                .eq(Operator::getProjectId, projectId)
                .eq(Operator::getOperatorName, name)
                .eq(Operator::getDeleted, false)
                .ne(excludeId != null, Operator::getId, excludeId));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "同项目下算子名称已存在: " + name,
                    Map.of("rule", "OPERATOR_NAME_DUPLICATE"));
        }
    }

    /** 出参补齐：内部主键 → 业务编号 + 项目名（converter 已 ignore 这两个字段）。 */
    private OperatorVO toVO(Operator operator) {
        OperatorVO vo = converter.toVO(operator);
        if (operator.getProjectId() != null) {
            Project project = projectMapper.selectById(operator.getProjectId());
            if (project != null) {
                vo.setProjectId(project.getProjectId());
                vo.setProjectName(project.getProjectName());
            }
        }
        return vo;
    }

    private String currentUsername() {
        var ctx = UserContext.get();
        return ctx != null ? ctx.getUsername() : "system";
    }
}
