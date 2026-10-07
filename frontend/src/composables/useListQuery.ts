import { ref, type Ref } from 'vue'
import type { PageResult } from '@/api/types/common'

/**
 * 分页列表的公共状态机（M2 提取）。
 *
 * 【为什么提取成 composable】
 * 集群列表 / 凭据列表 / 集群详情里的节点与队列 tab —— 四个地方要做完全同一件事：
 * 「页码 + 每页条数 + 关键词 + loading + 拉取首屏」。抄四遍的代价不是四倍代码量，
 * 而是以后改分页口径（比如加 `totalCapped` 的 10000+ 展示）要记得改四处。
 *
 * 【Vue 机制说明】
 * - `composable` 就是一个普通函数，内部用 ref 等响应式 API；调用方在 `setup` 里调用它，
 *   拿到的 ref 自动与模板双向绑定（这就是"组合式 API"相对选项式 API 的核心收益：
 *   逻辑可以像乐高一样拼装与复用）。
 * - 返回的 `refresh` 是被复用的"行为"，而 items/total 是各自独立的"状态"——
 *   每次调用 useListQuery 都创建一份新的 ref，多个页面之间不会串数据。
 * - `ref([]) as Ref<T[]>`：`ref` 对数组/对象的深层解包类型（UnwrapRef）在泛型下会
 *   变成复杂类型，显式断言成 `Ref<T[]>` 是社区通行做法，可读性优于让类型推导绕圈。
 */
export interface ListQuery<T> {
  items: Ref<T[]>
  total: Ref<number>
  page: Ref<number>
  pageSize: Ref<number>
  keyword: Ref<string>
  loading: Ref<boolean>
  refresh: () => Promise<void>
}

export interface ListQueryParams {
  page: number
  pageSize: number
  keyword?: string
}

export function useListQuery<T>(
  fetcher: (params: ListQueryParams) => Promise<PageResult<T>>,
  defaultPageSize = 20,
): ListQuery<T> {
  const items = ref([]) as Ref<T[]>
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(defaultPageSize)
  const keyword = ref('')
  const loading = ref(false)

  async function refresh(): Promise<void> {
    loading.value = true
    try {
      const result = await fetcher({
        page: page.value,
        pageSize: pageSize.value,
        keyword: keyword.value || undefined,
      })
      items.value = result.records
      total.value = result.total
    } finally {
      // finally 而非成功分支：请求失败也要关掉 loading，否则表格会一直转圈
      loading.value = false
    }
  }

  return { items, total, page, pageSize, keyword, loading, refresh }
}
