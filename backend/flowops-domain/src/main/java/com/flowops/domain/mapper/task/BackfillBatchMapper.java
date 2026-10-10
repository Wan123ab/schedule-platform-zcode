package com.flowops.domain.mapper.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.task.BackfillBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 回填批次 Mapper（docs/05 §3.5 backfill_batch；自定义 SQL 见 XML）。 */
@Mapper
public interface BackfillBatchMapper extends BaseMapper<BackfillBatch> {

    /** 暂停：RUNNING → PAUSED。0 行 = 状态已被他方改变（42238）。 */
    int pauseBatch(@Param("id") Long id);

    /** 继续：PAUSED → RUNNING。0 行 = 42238。 */
    int resumeBatch(@Param("id") Long id);

    /** 重跑失败后从 DONE 复活：DONE → RUNNING。0 行 = 42238。 */
    int reopenBatch(@Param("id") Long id);

    /** 投放引擎刷新进度（幂等全量覆盖）；DONE 判定由调用方传 waiting/inflight。 */
    int refreshProgress(@Param("id") Long id,
                        @Param("success") int success,
                        @Param("failed") int failed,
                        @Param("waiting") int waiting,
                        @Param("inflight") int inflight);
}
