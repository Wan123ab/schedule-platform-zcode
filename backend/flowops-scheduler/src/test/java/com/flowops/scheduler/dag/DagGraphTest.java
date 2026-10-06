package com.flowops.scheduler.dag;

import com.flowops.common.enums.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DagGraph 拓扑与运行时视图单测（docs/06 §16：内存 DagGraph，无 DB）。 */
class DagGraphTest {

    private static DagGraph.StepDef def(long id, String name) {
        return new DagGraph.StepDef(id, name, false);
    }

    private static DagGraph.StepDef note(long id, String name) {
        return new DagGraph.StepDef(id, name, true);
    }

    @Test
    void 线性图_roots与入度正确() {
        DagGraph graph = DagGraph.of(
                List.of(def(1, "拉取数据"), def(2, "清洗"), def(3, "入库")),
                List.of(new DagGraph.Edge(1L, 2L), new DagGraph.Edge(2L, 3L)));

        assertThat(graph.isRoot(1L)).isTrue();
        assertThat(graph.isRoot(2L)).isFalse();
        assertThat(graph.inDegree(2L)).isEqualTo(1);
        assertThat(graph.inDegree(3L)).isEqualTo(1);
        assertThat(graph.successors(1L)).containsExactly(2L);
    }

    @Test
    void 菱形图_汇聚节点入度为上游数() {
        DagGraph graph = DagGraph.of(
                List.of(def(1, "A"), def(2, "B"), def(3, "C"), def(4, "D")),
                List.of(new DagGraph.Edge(1L, 2L), new DagGraph.Edge(1L, 3L),
                        new DagGraph.Edge(2L, 4L), new DagGraph.Edge(3L, 4L)));

        assertThat(graph.inDegree(4L)).isEqualTo(2);
        assertThat(graph.allUpstreamSuccess(4L)).isFalse();   // 任一上游未成功即 false
    }

    @Test
    void 环被拒绝_且报出环成员() {
        // A → B → C → A（docs/06 §16：含环应拒绝；对应 DAG 校验规则 5）
        assertThatThrownBy(() -> DagGraph.of(
                List.of(def(1, "A"), def(2, "B"), def(3, "C")),
                List.of(new DagGraph.Edge(1L, 2L), new DagGraph.Edge(2L, 3L), new DagGraph.Edge(3L, 1L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("循环依赖");
    }

    @Test
    void 自环被拒绝() {
        assertThatThrownBy(() -> DagGraph.of(
                List.of(def(1, "A")),
                List.of(new DagGraph.Edge(1L, 1L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自环");
    }

    @Test
    void NOTE步骤不进图_引用它的连线被拒绝() {
        DagGraph graph = DagGraph.of(
                List.of(def(1, "A"), note(9L, "备注：此处等待审批")),
                List.of());

        assertThat(graph.executableStepIds()).containsExactly(1L);
        assertThat(graph.pendingSteps()).containsExactly(1L);   // NOTE 不参与执行与判定

        assertThatThrownBy(() -> DagGraph.of(
                List.of(def(1, "A"), note(9L, "备注")),
                List.of(new DagGraph.Edge(1L, 9L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOTE");
    }

    @Test
    void 连线引用不存在的步骤被拒绝() {
        assertThatThrownBy(() -> DagGraph.of(
                List.of(def(1, "A")),
                List.of(new DagGraph.Edge(1L, 99L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在的可执行步骤");
    }

    @Test
    void consumed标记_入度递减恰好一次() {
        DagGraph graph = DagGraph.of(
                List.of(def(1, "A"), def(2, "B")),
                List.of(new DagGraph.Edge(1L, 2L)));

        graph.setStatus(1L, StepStatus.SUCCESS);
        assertThat(graph.consumeIfNotYet(1L)).containsExactly(2L);
        assertThat(graph.inDegree(2L)).isZero();

        // 重复消费（tick 重跑场景）不再递减 —— §7.2 consumed 标记的必要性
        assertThat(graph.consumeIfNotYet(1L)).isEmpty();
        assertThat(graph.inDegree(2L)).isZero();
    }
}
