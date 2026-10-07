-- =====================================================================
-- FlowOps V7 · 给 workflow 补 description 列（CONTRACT §6.1 要求，V1 漏建）
-- =====================================================================
-- 背景（M3 工作流域建模时发现的契约缺失）
--
--   prd/CONTRACT-API.md §6.1 明确：
--     POST /workflows      请求体含 `workflowName`, `projectId`, `description`
--     PUT  /workflows/{id} 请求体含 `workflowName` 等
--   而 docs/05 §3.4 的 workflow DDL **没有 description 列**
--   （同节的 workflow_step 有 description text，operator/credential 也都有
--     —— 只有 workflow 这一张漏了，属 DDL 漏写而非有意为之）。
--
-- 为什么不是"接口收下但不落库"：
--   用户填了备注却被静默丢弃，比报错更糟 —— 前端会显示"保存成功"，
--   而下次打开详情页备注消失，且没有任何可排查的线索。契约与 DDL 冲突时，
--   以契约（prd/ 优先于 docs/，docs/00 §2 的仲裁规则）为准，补齐列。
--
-- 迁移而非改 V1：Flyway 已应用脚本不可改（checksum），追加是唯一正确姿势（同 V6）。
--
-- 影响面：纯新增可空列，存量行 description IS NULL；无索引、无约束、无需回填。
--         工作流列表页的关键字检索仍只按 workflow_name（保持既有行为，
--         不做 ILIKE 备注，避免一期引入无索引的模糊全表扫）。
-- =====================================================================

ALTER TABLE workflow ADD COLUMN IF NOT EXISTS description text;

COMMENT ON COLUMN workflow.description IS '工作流备注（CONTRACT §6.1 POST /workflows 的 description）';
