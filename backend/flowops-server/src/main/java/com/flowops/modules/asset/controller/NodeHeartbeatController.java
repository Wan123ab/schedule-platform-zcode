package com.flowops.modules.asset.controller;

import com.flowops.common.api.ApiResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 节点心跳上报（docs/06 §8.1 ①）：Agent / 执行节点每 15s 上报。
 *
 * <p><b>内网端点（I-06 口径）</b>：不经鉴权（WebMvcConfig 已排除 /internal/**），
 * 部署边界 = 内网网络边界；上报幂等 —— 重复心跳只刷新时间戳。</p>
 *
 * <p><b>职责刻意最小</b>：只刷新 last_heartbeat_at 与清零 miss 计数；
 * 离线判定（45s）、恢复窗口（5min）全部在 scheduler 的 HeartbeatScanner ——
 * 判定逻辑单点，避免 server 与 scheduler 两套口径。</p>
 */
@Slf4j
@RestController
@RequestMapping("/internal/nodes")
@RequiredArgsConstructor
public class NodeHeartbeatController {

    private final NodeHeartbeatMapper heartbeatMapper;

    @PostMapping("/{nodeId}/heartbeat")
    public ApiResult<Map<String, Object>> heartbeat(@PathVariable Long nodeId) {
        int updated = heartbeatMapper.touch(nodeId, OffsetDateTime.now());
        if (updated == 0) {
            log.warn("心跳来自未知或已删除节点 nodeId={}（可能是残留 Agent）", nodeId);
            return ApiResult.ok(Map.of("recognized", false));
        }
        return ApiResult.ok(Map.of("recognized", true));
    }

    /** 心跳触点 SQL 极小化（docs/06 §8.1 ①：刷 last_heartbeat_at、清零 miss）。 */
    @Mapper
    public interface NodeHeartbeatMapper {
        int touch(@Param("nodeId") Long nodeId, @Param("at") OffsetDateTime at);
    }
}
