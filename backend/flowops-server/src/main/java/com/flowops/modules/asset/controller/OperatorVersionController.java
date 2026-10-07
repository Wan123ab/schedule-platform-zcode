package com.flowops.modules.asset.controller;

import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.modules.asset.dto.OperatorReferenceVO;
import com.flowops.modules.asset.dto.OperatorVersionVO;
import com.flowops.modules.asset.service.OperatorVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 算子版本接口（docs/07 §6.3 上传契约；CONTRACT §5）。
 *
 * <p><b>上传为什么是 multipart 两段（{@code file} + {@code meta}）</b>：
 * 见 {@link com.flowops.modules.asset.dto.OperatorVersionMeta} 的类注释 ——
 * 嵌套集合无法用扁平 multipart 字段优雅表达。</p>
 *
 * <p><b>必审动作</b>：UPLOAD_VERSION / PUBLISH_VERSION / OFFLINE_VERSION 三个
 * （docs/07 §7.3 算子域清单）。DRYRUN_OPERATOR 属试运行接口，M3 后半程落地。</p>
 */
@RestController
@RequiredArgsConstructor
public class OperatorVersionController {

    private final OperatorVersionService versionService;

    /**
     * 上传新版本。
     *
     * <p>校验失败返回 <b>42210 + errors[]</b>（docs/07 §6.3），而不是 40001：
     * 上传是一个长表单，错误需要逐字段回填到对应输入框。</p>
     */
    @PostMapping("/operators/{operatorId}/versions")
    @RequiresPermission("schedule:operator:publish")
    @ResponseStatus(HttpStatus.CREATED)
    @Audited(action = "UPLOAD_VERSION", targetType = "OPERATOR_VERSION")
    public ApiResult<OperatorVersionVO> upload(@PathVariable String operatorId,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam("meta") String meta) {
        return ApiResult.ok(versionService.upload(operatorId, file, meta));
    }

    @GetMapping("/operator-versions/{versionId}")
    @RequiresPermission("schedule:operator:read")
    public ApiResult<OperatorVersionVO> get(@PathVariable String versionId) {
        return ApiResult.ok(versionService.get(versionId));
    }

    /** 编辑草稿版本（非 DRAFT → 42212）。文件不替换：换包应上传新版本，保持"版本=不可变快照"。 */
    @PutMapping("/operator-versions/{versionId}")
    @RequiresPermission("schedule:operator:publish")
    public ApiResult<OperatorVersionVO> updateDraft(@PathVariable String versionId,
                                                    @RequestParam("meta") String meta) {
        return ApiResult.ok(versionService.updateDraft(versionId, meta));
    }

    @PostMapping("/operator-versions/{versionId}/publish")
    @RequiresPermission("schedule:operator:publish")
    @Audited(action = "PUBLISH_VERSION", targetType = "OPERATOR_VERSION", targetIdExpr = "#versionId")
    public ApiResult<OperatorVersionVO> publish(@PathVariable String versionId) {
        return ApiResult.ok(versionService.publish(versionId));
    }

    /**
     * 下线。
     *
     * <p><b>权限点取值说明</b>：docs/07 §5.4 的端点映射表把 {@code /offline} 同时列在
     * "publish" 行与 "delete" 行（文档内部冲突）。此处取 {@code operator:publish}：
     * §5.2 对 publish 的描述是"算子版本新增/编辑/删除/发布"，下线属版本生命周期管理；
     * 冲突已登记到 docs/00 §2 决策日志（D-29）。</p>
     */
    @PostMapping("/operator-versions/{versionId}/offline")
    @RequiresPermission("schedule:operator:publish")
    @Audited(action = "OFFLINE_VERSION", targetType = "OPERATOR_VERSION", targetIdExpr = "#versionId")
    public ApiResult<OperatorVersionVO> offline(@PathVariable String versionId) {
        return ApiResult.ok(versionService.offline(versionId));
    }

    /** 被哪些工作流步骤引用（删除版本前的"影响面"预览）。 */
    @GetMapping("/operator-versions/{versionId}/references")
    @RequiresPermission("schedule:operator:read")
    public ApiResult<List<OperatorReferenceVO>> references(@PathVariable String versionId) {
        return ApiResult.ok(versionService.references(versionId));
    }
}
