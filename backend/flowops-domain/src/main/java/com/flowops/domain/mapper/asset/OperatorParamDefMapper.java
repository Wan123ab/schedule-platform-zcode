package com.flowops.domain.mapper.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.asset.OperatorParamDef;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 算子参数定义 Mapper（docs/05 §3.3 operator_param_def，R9）。
 *
 * <p>本表随版本整体生命周期（新增/替换/删除），故只有"按版本删除 + 按版本列出"两个
 * 自定义动作，没有软删与 CAS —— 与 DDL 不带 {@code version}/{@code deleted} 一致。</p>
 */
@Mapper
public interface OperatorParamDefMapper extends BaseMapper<OperatorParamDef> {

    /** 替换式更新：先删后插（草稿版本编辑时用），保证 seq/key 唯一性不会中途冲突。 */
    int deleteByVersionId(@Param("operatorVersionId") Long operatorVersionId);

    /** 详情页与变量校验都要按 seq 稳定排序。 */
    List<OperatorParamDef> listByVersionId(@Param("operatorVersionId") Long operatorVersionId);

    /**
     * 批量取多个版本的参数定义（DAG 校验装配必填参数集合时用）。
     *
     * <p><b>为什么必须批量</b>：一个 100 节点的画布就是 100 次 {@code listByVersionId}，
     * 而"保存草稿"是编辑器里最高频的写操作。这里按 {@code operator_version_id IN (...)}
     * 一次取回后在内存分组，节点数与 SQL 次数解耦。</p>
     */
    List<OperatorParamDef> listByVersionIds(@Param("operatorVersionIds") Collection<Long> operatorVersionIds);

    /**
     * 改写某参数的默认值（试运行「将本次参数另存为默认值」）。
     *
     * <p><b>为什么可以做单列 UPDATE，而不违反"版本发布后冻结"（D-11）</b>：
     * 冻结保护的是<b>文件与结构</b>（参数 key/类型/必填/是否敏感、输出声明），
     * 因为工作流步骤按 {@code operator_version_id} 绑定、历史任务要靠它追溯
     * "当时执行的是哪份代码"。而默认值只是<b>新建步骤时的预填值</b>：
     * 它既不参与任何已保存步骤的参数快照，也不改变命令模板，
     * 因此更新它不会让任何一条历史记录的含义发生变化（PRD §10.6 明确要求这个动作，
     * 原型也把"另存为默认值"放在已发布版本的详情页上）。</p>
     *
     * <p>参数 key 用 {@code paramKey} 而不是子表主键：调用方的入参本来就是它，
     * 且 {@code uk_param_def_key} 已保证同版本内唯一，不必让前端持有内部主键。</p>
     *
     * @return 受影响行数（0 = 该版本下无此 key，调用方应视为参数错误）
     */
    int updateDefaultValue(@Param("operatorVersionId") Long operatorVersionId,
                           @Param("paramKey") String paramKey,
                           @Param("defaultValue") String defaultValue);
}
