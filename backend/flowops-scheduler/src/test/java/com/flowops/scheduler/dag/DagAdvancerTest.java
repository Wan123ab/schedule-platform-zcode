package com.flowops.scheduler.dag;

import com.flowops.common.enums.StepStatus;
import com.flowops.scheduler.state.StepStateTransitions.StepEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DagAdvancer 推进单测（docs/06 §16：线性/菱形/多分支/含 NOTE/含环已拒于图构建）。
 * 模拟方式：图内 setStatus 等价于"DB 侧状态已变"（真实闭环中由 CAS 落库后同步）。
 */
class DagAdvancerTest {

    private final DagAdvancer advancer = new DagAdvancer();

    private static DagGraph.StepDef def(long id, String name) {
        return new DagGraph.StepDef(id, name, false);
    }

    private static List<DagAdvancer.Transition> advance(DagAdvancer advancer, DagGraph graph) {
        return advancer.advance(graph);
    }

    @Test
    void 线性链_首tick按入度分流() {
        DagGraph graph = DagGraph.of(
                List.of(def(1, "拉取"), def(2, "清洗"), def(3, "入库")),
                List.of(new DagGraph.Edge(1L, 2L), new DagGraph.Edge(2L, 3L)));

        List<DagAdvancer.Transition> commands = advance(advancer, graph);

        assertThat(commands).extracting(DagAdvancer.Transition::stepId)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(graph.statusOf(1L)).isEqualTo(StepStatus.WAITING_RESOURCE);   // 入度 0 → 直达可调度
        assertThat(graph.statusOf(2L)).isEqualTo(StepStatus.WAITING_DEPENDENCY); // 有上游 → 等依赖
        assertThat(graph.statusOf(3L)).isEqualTo(StepStatus.WAITING_DEPENDENCY);
        assertThat(commands).filteredOn(c -> c.stepId() == 1L)
                .extracting(DagAdvancer.Transition::event)
                .containsExactly(StepEvent.DEP_READY);
    }

    @Test
    void 上游成功_下游解锁_且幂等不重复发命令() {
        DagGraph graph = DagGraph.of(
                List.of(def(1, "拉取"), def(2, "清洗")),
                List.of(new DagGraph.Edge(1L, 2L)));
        advance(advancer, graph);

        // 步骤 1 执行成功（真实闭环：RUNNING → SUCCESS 由执行回执驱动，此处直设终态）
        graph.setStatus(1L, StepStatus.SUCCESS);
        List<DagAdvancer.Transition> tick1 = advance(advancer, graph);
        assertThat(tick1).extracting(DagAdvancer.Transition::stepId).containsExactly(2L);
        assertThat(graph.statusOf(2L)).isEqualTo(StepStatus.WAITING_RESOURCE);
        assertThat(tick1.get(0).event()).isEqualTo(StepEvent.UPSTREAM_DONE);

        // 下一 tick 再推进：步骤 1 的 consumed 已标记、步骤 2 已非 NOT_STARTED → 无新命令（幂等，docs/06 §3.3）
        assertThat(advance(advancer, graph)).isEmpty();
    }

    @Test
    void 菱形_必须等两条分支都成功() {
        DagGraph graph = DagGraph.of(
                List.of(def(1, "A"), def(2, "B"), def(3, "C"), def(4, "D")),
                List.of(new DagGraph.Edge(1L, 2L), new DagGraph.Edge(1L, 3L),
                        new DagGraph.Edge(2L, 4L), new DagGraph.Edge(3L, 4L)));
        advance(advancer, graph);

        graph.setStatus(1L, StepStatus.SUCCESS);
        advance(advancer, graph);   // B/C → WAITING_RESOURCE

        graph.setStatus(2L, StepStatus.SUCCESS);   // 仅 B 成功
        List<DagAdvancer.Transition> partial = advance(advancer, graph);
        assertThat(graph.statusOf(4L)).isEqualTo(StepStatus.WAITING_DEPENDENCY);   // C 未成功，D 继续等

        graph.setStatus(3L, StepStatus.SUCCESS);   // C 也成功
        List<DagAdvancer.Transition> done = advance(advancer, graph);
        assertThat(done).extracting(DagAdvancer.Transition::stepId).containsExactly(4L);
        assertThat(graph.statusOf(4L)).isEqualTo(StepStatus.WAITING_RESOURCE);
        assertThat(partial).isEmpty();
    }

    @Test
    void 上游失败_下游STOPPED_不再执行() {
        DagGraph graph = DagGraph.of(
                List.of(def(1, "拉取"), def(2, "清洗")),
                List.of(new DagGraph.Edge(1L, 2L)));
        advance(advancer, graph);

        graph.setStatus(1L, StepStatus.FAILED);   // 重试耗尽后的终态（RETRY 耗尽 ≡ TERMINATE，docs/06 §9.2）
        List<DagAdvancer.Transition> commands = advance(advancer, graph);

        assertThat(commands).extracting(DagAdvancer.Transition::stepId).containsExactly(2L);
        assertThat(graph.statusOf(2L)).isEqualTo(StepStatus.STOPPED);
        assertThat(commands.get(0).event()).isEqualTo(StepEvent.UPSTREAM_FAIL);
    }

    @Test
    void 上游重试中_下游继续等待_不提前终止() {
        DagGraph graph = DagGraph.of(
                List.of(def(1, "拉取"), def(2, "清洗")),
                List.of(new DagGraph.Edge(1L, 2L)));
        advance(advancer, graph);

        // RETRYING 不是 FAILED：下游不应被判 STOPPED（失败判定只认终态，docs/06 §2.2 判定依据）
        graph.setStatus(1L, StepStatus.RETRYING);
        assertThat(advance(advancer, graph)).isEmpty();
        assertThat(graph.statusOf(2L)).isEqualTo(StepStatus.WAITING_DEPENDENCY);
    }

    @Test
    void NOTE步骤_任何tick都不产生命令() {
        DagGraph graph = DagGraph.of(
                List.of(def(1, "A"), new DagGraph.StepDef(9L, "备注", true)),
                List.of());
        List<DagAdvancer.Transition> commands = advance(advancer, graph);

        assertThat(commands).extracting(DagAdvancer.Transition::stepId).containsExactly(1L);
        // 步骤 9 不存在图中：pendingSteps/命令均不含它（PRD §10.8 规则 1"备注除外"）
        assertThat(graph.pendingSteps()).doesNotContain(9L);
    }
}
