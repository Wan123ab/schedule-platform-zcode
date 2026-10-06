package com.flowops.common.api;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 分页包装（docs/07 §3.3）：{total, page, page_size, records[]}。
 * total > 10000 时 totalCapped=true，前端展示「10000+」（docs/07 §7.4）。
 */
@Getter
@AllArgsConstructor
public class PageResult<T> {

    private final long total;
    private final long page;
    private final long pageSize;
    private final List<T> records;
    private final boolean totalCapped;

    public static <T> PageResult<T> of(long total, long page, long pageSize, List<T> records) {
        boolean capped = total > 10_000;
        return new PageResult<>(capped ? 10_000 : total, page, pageSize, records, capped);
    }
}
