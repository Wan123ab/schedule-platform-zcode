package com.flowops.scheduler.match;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点预留账本（D-22，docs/06 §5.1）—— <b>准入判定的唯一依据</b>。
 *
 * <p><b>为什么不用实际用量（v3 评审 A-3，必修项）</b>：算子加载期实际 CPU 只占 0.5 核但申请 4 核，
 * 节点心跳上报的"实际用量"仍是低的 → 调度器认为余量充足 → 连发 10 个 → 30 秒后全部进入
 * 计算密集阶段 → 节点被打爆。"峰值滞后"导致的超卖，靠缩短心跳间隔解决不了。
 * 调度器的职责是<b>不制造过载</b>，不是观测到过载后再反应。</p>
 *
 * <p><b>口径</b>：reserved = Σ 该节点上所有非终态步骤的资源<b>申请量</b>；
 * 余量 = total − reserved；{@code >=} 恰好满足也算匹配（docs/06 §16 边界用例 1）。</p>
 *
 * <p><b>可靠性纪律（§5.1）</b>：① 启动时由 DB 全量重建（rebuild）；② <b>先改 DB 状态再改账本</b>
 * （先账本后 DB 且崩溃会永久少算）；③ Redis 只做跨进程共享，不是权威源 —— 本类即调度器内存权威副本，
 * 非终态步骤进入终态时调用方必须 release，否则账本虚高、节点被"幽灵占用"。</p>
 */
public class ReservedLedger {

    /** 四维资源申请量（与 docs/05 resource_request jsonb 对应）。 */
    public record Resource(double cpu, double gpu, long memoryMB, long diskMB) {

        public static final Resource ZERO = new Resource(0, 0, 0, 0);

        public Resource plus(Resource other) {
            return new Resource(cpu + other.cpu, gpu + other.gpu,
                    memoryMB + other.memoryMB, diskMB + other.diskMB);
        }
    }

    /** 节点余量视图（准入判定输入）。 */
    public record Available(double cpu, double gpu, long memoryMB, long diskMB) {

        public boolean fits(Resource request) {
            return cpu >= request.cpu && gpu >= request.gpu
                    && memoryMB >= request.memoryMB && diskMB >= request.diskMB;
        }
    }

    /** nodeId → (stepInstanceId → 申请量)；内层 Map 兼做"该节点上的在途步骤清单"。 */
    private final Map<Long, Map<Long, Resource>> ledger = new HashMap<>();

    /** 记账：步骤被分配到节点（节点匹配成功、进入 SCHEDULING 时）。幂等：同键覆盖。 */
    public void acquire(long nodeId, long stepInstanceId, Resource request) {
        ledger.computeIfAbsent(nodeId, k -> new HashMap<>()).put(stepInstanceId, request);
    }

    /** 扣回：步骤进入终态。幂等：不存在时无操作。 */
    public void release(long nodeId, long stepInstanceId) {
        Map<Long, Resource> steps = ledger.get(nodeId);
        if (steps != null) {
            steps.remove(stepInstanceId);
            if (steps.isEmpty()) {
                ledger.remove(nodeId);
            }
        }
    }

    /** 该节点上的在途步骤数（max_concurrent_steps 闸门的另一半，docs/05 §3.3 v3 新增列）。 */
    public int reservedSteps(long nodeId) {
        return ledger.getOrDefault(nodeId, Map.of()).size();
    }

    /** Σ 申请量。 */
    public Resource reservedOf(long nodeId) {
        return ledger.getOrDefault(nodeId, Map.of()).values().stream()
                .reduce(Resource.ZERO, Resource::plus);
    }

    /** 余量视图（total − reserved，逐维）。 */
    public Available availableOf(long nodeId, Resource totals) {
        Resource reserved = reservedOf(nodeId);
        return new Available(
                totals.cpu() - reserved.cpu(),
                totals.gpu() - reserved.gpu(),
                totals.memoryMB() - reserved.memoryMB(),
                totals.diskMB() - reserved.diskMB());
    }

    /** 启动重建（§10.2 ④）：以 DB 查询结果为准整体覆盖 —— 账本是派生态，重建即真源。 */
    public void rebuild(List<NodeReservation> rows) {
        ledger.clear();
        rows.forEach(row -> acquire(row.nodeId(), row.stepInstanceId(), row.request()));
    }

    /** 重建输入：一条非终态步骤的（节点，申请量）投影。 */
    public record NodeReservation(long nodeId, long stepInstanceId, Resource request) {}
}
