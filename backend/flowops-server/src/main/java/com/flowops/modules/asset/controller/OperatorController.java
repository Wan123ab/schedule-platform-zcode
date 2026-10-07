package com.flowops.modules.asset.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.DataScope;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.common.api.PageResult;
import com.flowops.modules.asset.dto.OperatorVO;
import com.flowops.modules.asset.dto.OperatorVersionVO;
import com.flowops.modules.asset.dto.SaveOperatorRequest;
import com.flowops.modules.asset.service.OperatorService;
import com.flowops.modules.asset.service.OperatorVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 算子接口（docs/07 §5.4 / CONTRACT §5；PRD §10.6）。
 *
 * <p><b>数据范围</b>：算子归属项目（R8 NOT NULL），故读接口标注
 * {@code @DataScope({"PROJECT","SELF_CREATED"})} —— 与 §5.4 的映射一致。
 * 行级条件（{@code operator.project_id IN (...)}）由
 * {@link com.flowops.config.FlowopsDataPermissionHandler} 在 Mapper 层注入，
 * Controller 只声明语义、不拼 SQL。</p>
 *
 * <p><b>必审动作</b>（docs/07 §7.3 算子域 6 个）：本类承载
 * CREATE_OPERATOR / DELETE_OPERATOR；版本类动作（UPLOAD / PUBLISH / OFFLINE / DRYRUN）
 * 在 {@link OperatorVersionController} 与 M3 的试运行接口里。</p>
 */
@RestController
@RequestMapping("/operators")
@RequiredArgsConstructor
public class OperatorController {

    private final OperatorService operatorService;
    private final OperatorVersionService versionService;

    @GetMapping
    @RequiresPermission("schedule:operator:read")
    @DataScope({"PROJECT", "SELF_CREATED"})
    public ApiResult<PageResult<OperatorVO>> page(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long pageSize,
                                                  @RequestParam(required = false) String operatorType,
                                                  @RequestParam(required = false) String projectId,
                                                  @RequestParam(required = false) String keyword) {
        IPage<OperatorVO> result = operatorService.page(page, pageSize, operatorType, projectId, keyword);
        return ApiResult.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords()));
    }

    @GetMapping("/{operatorId}")
    @RequiresPermission("schedule:operator:read")
    public ApiResult<OperatorVO> get(@PathVariable String operatorId) {
        return ApiResult.ok(operatorService.get(operatorId));
    }

    /** 版本列表随详情页一起取：算子详情的第一屏就是"有哪些版本、哪版在跑"。 */
    @GetMapping("/{operatorId}/versions")
    @RequiresPermission("schedule:operator:read")
    public ApiResult<List<OperatorVersionVO>> versions(@PathVariable String operatorId) {
        return ApiResult.ok(versionService.listByOperator(operatorId));
    }

    @PostMapping
    @RequiresPermission("schedule:operator:write")
    @Audited(action = "CREATE_OPERATOR", targetType = "OPERATOR")
    public ApiResult<OperatorVO> create(@RequestBody @Valid SaveOperatorRequest request) {
        return ApiResult.ok(operatorService.create(request));
    }

    @PutMapping("/{operatorId}")
    @RequiresPermission("schedule:operator:write")
    public ApiResult<OperatorVO> update(@PathVariable String operatorId,
                                        @RequestBody @Valid SaveOperatorRequest request) {
        return ApiResult.ok(operatorService.update(operatorId, request));
    }

    @DeleteMapping("/{operatorId}")
    @RequiresPermission("schedule:operator:delete")
    @Audited(action = "DELETE_OPERATOR", targetType = "OPERATOR", targetIdExpr = "#operatorId")
    public ApiResult<Void> delete(@PathVariable String operatorId) {
        operatorService.delete(operatorId);
        return ApiResult.ok(null);
    }
}
