package com.flowops.modules.asset.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.common.api.PageResult;
import com.flowops.modules.asset.dto.CredentialVO;
import com.flowops.modules.asset.dto.SaveCredentialRequest;
import com.flowops.modules.asset.service.CredentialService;
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

import java.util.Map;

/**
 * 凭据接口（docs/07 §5.4 / §6.2）。四个动作全部必审（docs/07 §7.3 凭据域）。
 */
@RestController
@RequestMapping("/credentials")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;

    @GetMapping
    @RequiresPermission("schedule:credential:read")
    public ApiResult<PageResult<CredentialVO>> page(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long pageSize,
                                                    @RequestParam(required = false) String keyword) {
        IPage<CredentialVO> result = credentialService.page(page, pageSize, keyword);
        return ApiResult.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords()));
    }

    @GetMapping("/{credentialId}")
    @RequiresPermission("schedule:credential:read")
    public ApiResult<CredentialVO> get(@PathVariable String credentialId) {
        return ApiResult.ok(credentialService.get(credentialId));
    }

    @PostMapping
    @RequiresPermission("schedule:credential:write")
    @Audited(action = "CREATE_CREDENTIAL", targetType = "CREDENTIAL")
    public ApiResult<CredentialVO> create(@RequestBody @Valid SaveCredentialRequest request) {
        return ApiResult.ok(credentialService.create(request));
    }

    @PutMapping("/{credentialId}")
    @RequiresPermission("schedule:credential:write")
    @Audited(action = "UPDATE_CREDENTIAL", targetType = "CREDENTIAL", targetIdExpr = "#credentialId")
    public ApiResult<CredentialVO> update(@PathVariable String credentialId,
                                          @RequestBody @Valid SaveCredentialRequest request) {
        return ApiResult.ok(credentialService.update(credentialId, request));
    }

    @PostMapping("/{credentialId}/rotate")
    @RequiresPermission("schedule:credential:rotate")
    @Audited(action = "ROTATE_CREDENTIAL", targetType = "CREDENTIAL", targetIdExpr = "#credentialId")
    public ApiResult<CredentialVO> rotate(@PathVariable String credentialId,
                                          @RequestBody Map<String, String> body) {
        return ApiResult.ok(credentialService.rotate(credentialId, body.get("secret")));
    }

    @DeleteMapping("/{credentialId}")
    @RequiresPermission("schedule:credential:write")
    @Audited(action = "DELETE_CREDENTIAL", targetType = "CREDENTIAL", targetIdExpr = "#credentialId")
    public ApiResult<Void> delete(@PathVariable String credentialId) {
        credentialService.delete(credentialId);
        return ApiResult.ok();
    }
}
