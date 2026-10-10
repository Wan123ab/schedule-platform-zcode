package com.flowops.modules.dashboard.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 平台健康度（CONTRACT §11.1 GET /platform/health，docs/07 §6.7）。
 *
 * <p><b>可得性口径（O-71）</b>：scheduler 段从 Redis 选主锁 {@code flowops:scheduler:leader}
 * 推导（D-08 下 server 看不到调度器内存）—— leaderId=锁值、leaseRemainingSeconds=锁 TTL、
 * isLeader/standbyCount/lastTickAt 调度器未上报，返回 null；db 段池统计经 Hikari MXBean，
 * slow_query_count_1m 无慢查询登记设施返回 null。</p>
 */
@Data
public class PlatformHealthVO {

    private Scheduler scheduler;

    private Db db;

    private Redis redis;

    private Nodes nodes;

    private List<QueueWater> queues;

    private Today today;

    private List<RecentAlert> recentAlerts;

    @Data
    public static class Scheduler {
        private String leaderId;
        private Boolean schedulerAlive;
        private Long leaseRemainingSeconds;
        private Boolean isLeader;
        private OffsetDateTime lastTickAt;
        private Integer tickIntervalMs;
        private Integer standbyCount;
    }

    @Data
    public static class Db {
        private Integer poolActive;
        private Integer poolIdle;
        private Integer poolMax;
        private Integer slowQueryCount1m;
    }

    @Data
    public static class Redis {
        private Boolean connected;
        private String usedMemoryHuman;
        private String evictionPolicy;
        private Long queueDepth;
    }

    @Data
    public static class Nodes {
        private Long online;
        private Long offline;
        private Long total;
        private Double onlineRate;
    }

    @Data
    public static class QueueWater {
        private Long queueRowId;
        private String queueId;
        private String name;
        private Integer waiting;
        private Integer running;
        private Integer maxConcurrentTasks;
        private Integer maxWaitingTasks;
        private Double waterLevel;
    }

    @Data
    public static class Today {
        private Long dispatchedCount;
        private Long failedCount;
        private Long logIngestBytes;
    }

    @Data
    public static class RecentAlert {
        private String alertId;
        private String level;
        private String title;
        private OffsetDateTime firedAt;
    }
}
