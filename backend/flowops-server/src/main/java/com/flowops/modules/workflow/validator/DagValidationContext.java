package com.flowops.modules.workflow.validator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAG 校验的输入（纯数据，不含任何 Mapper）。
 *
 * <p><b>为什么把"取数"和"判定"分开</b>：10 条规则里有 4 条需要跨域数据
 * （算子参数模板、算子版本发布状态、集群资源上限）。若校验器自己注入 Mapper，
 * 单测就得为每条规则堆一整套 mock，而 mock 的形状一旦和真实查询不一致，
 * 测试就变成"验证我没理解错自己的 mock"。这里改为调用方（Service）把数据装配成
 * 本记录，校验器只做纯函数判定 —— 于是"逐条规则造错误用例"变成真正的单元测试。</p>
 *
 * <p><b>边用下标而不是 id</b>：{@code EdgeLink} 里的 {@code sourceIndex} /
 * {@code targetIndex} 指向 {@code steps} 的下标。这样校验器不必知道节点在库里
 * 叫 {@code step_id} 还是别的什么，也就不会因为"用业务编号还是内部主键"而改变行为。</p>
 *
 * @param steps          步骤（顺序即下标）
 * @param edges          连线
 * @param concurrency    并发配置（DAG 规则 8）
 * @param operatorSpecs  {@code operatorVersionId → 规格}；缺失即视为"版本不存在/不可用"
 * @param clusterSpecs   {@code clusterId → 集群规格}；缺失表示该集群无上限数据（跳过规则 6 的比对，
 *                       而不是当作 0 —— 当作 0 会把所有步骤都判成超限）
 */
public record DagValidationContext(
        List<StepNode> steps,
        List<EdgeLink> edges,
        Concurrency concurrency,
        Map<Long, OperatorSpec> operatorSpecs,
        Map<Long, ClusterSpec> clusterSpecs) {

    /**
     * 一个步骤节点。
     *
     * @param retryCount       步骤级重试次数（规则 9）
     * @param targetClusterId  目标集群（规则 6 按它聚合）
     * @param operatorVersionId 绑定的算子版本（规则 2/3/7）
     */
    public record StepNode(
            String stepName,
            String stepType,
            Long operatorId,
            Long operatorVersionId,
            Map<String, Object> params,
            Map<String, Object> customParams,
            Long targetClusterId,
            BigDecimal cpu,
            BigDecimal gpu,
            Long memory,
            Long disk,
            Integer retryCount) {

        /** 备注节点不参与可达性、算子绑定、必填参数与资源校验（docs/07 §9.2 规则 1 的括号）。 */
        public boolean isNote() {
            return "NOTE".equals(stepType);
        }
    }

    /** 一条有向边，两端是 {@code steps} 的下标。 */
    public record EdgeLink(int sourceIndex, int targetIndex) {
    }

    /** 并发配置（工作流级，PRD §12.3）。 */
    public record Concurrency(String policy, Integer maxParallelRuns) {
    }

    /**
     * 算子版本的校验规格。
     *
     * @param publishStatus   发布状态；只有 PUBLISHED 可被引用（规则 7）
     * @param requiredParams  参数模板里 {@code required=true} 的 paramKey（规则 3）
     * @param declaredOutputs 输出声明里的 varName 集合（规则 4 的"变量存在"判定）。
     *                        <b>空集表示该算子版本没有声明输出</b>，此时跳过"变量是否存在"的判定
     *                        —— 拿一份没有声明的规格去断言"变量不存在"会把合法工作流全部拦下
     */
    public record OperatorSpec(String publishStatus, Set<String> requiredParams, Set<String> declaredOutputs) {
    }

    /**
     * 目标集群的校验规格。
     *
     * @param clusterId   集群业务编号（CL-xxxx）。错误信息里只出业务编号不出内部主键（D-27），
     *                    而内部主键只在上层 Map 的 key 里做关联
     * @param clusterName 集群名（提示可读性）
     * @param limit       资源上限
     */
    public record ClusterSpec(String clusterId, String clusterName, ResourceLimit limit) {
    }

    /**
     * 资源上限（docs/05 的 {@code cluster.cpu_total / gpu_total / memory_total / disk_total}）。
     *
     * <p>只有集群有资源上限列；{@code queue} 表只有 {@code max_concurrent_tasks} /
     * {@code max_waiting_tasks}（并发口径，不是资源口径）。故规则 6 只按集群聚合，
     * 队列维度的一期不校验 —— 见 README-M3 的偏离登记。</p>
     */
    public record ResourceLimit(BigDecimal cpu, BigDecimal gpu, Long memory, Long disk) {
    }
}
