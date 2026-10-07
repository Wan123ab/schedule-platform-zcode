-- =====================================================================
-- FlowOps V6 · 修正 role.scope_type 的取值口径（以 D-19 命名为准）
-- =====================================================================
-- 背景（M2 实测发现的静默失效链路）
--
--   V1 的 role.scope_type CHECK 沿用历史命名：('ALL','TENANT','PROJECT','CLUSTER','SELF')
--   V4 种子据此写入 OPS='CLUSTER' / BUSINESS='SELF' / OPERATOR_MAINTAINER='SELF'
--   而 DataScope 的唯一命名口径是 D-19（docs/07 §5.3）：
--       ALL / AUTHORIZED_CLUSTER / PROJECT / SELF_CREATED / NONE
--   → DataScopeResolver 比较的是 role.scope_type 与 ScopeType.getCode()，
--     两边永不相同 ⇒ OPS 与所有 SELF 类角色一律解析成 NONE，
--     而 NONE 的语义是"不过滤" ⇒ 越权可见（比"看不到"更危险）。
--
-- 仲裁依据：docs/00 §2「DataScope 枚举三套命名 → 以 07 §5.3 为准
--           （AUTHORIZED_CLUSTER / SELF_CREATED）」。故此处修正数据与约束，
--           而不是把枚举改回旧名（否则与 docs/00 §2、docs/03 §3.2、docs/04 §3.2 同时冲突）。
--
-- 迁移而非改 V1：Flyway 已应用脚本不可改（checksum），追加是唯一正确姿势。
-- =====================================================================

-- ① 摘掉旧约束（内联 CHECK 的默认名 role_scope_type_check）
ALTER TABLE role DROP CONSTRAINT IF EXISTS role_scope_type_check;

-- ② 存量数据改名（一个 UPDATE 一行，便于排障时逐条核对）
UPDATE role SET scope_type = 'AUTHORIZED_CLUSTER' WHERE scope_type = 'CLUSTER';
UPDATE role SET scope_type = 'SELF_CREATED'       WHERE scope_type = 'SELF';
-- TENANT：一期仅 1 个默认租户、无租户级数据范围（M-01），故收敛为 ALL（全平台）
UPDATE role SET scope_type = 'ALL'                WHERE scope_type = 'TENANT';

-- ③ 收口为新口径（TENANT 不再允许：没有租户级 DataScope 的消费方）
ALTER TABLE role ADD CONSTRAINT role_scope_type_check
    CHECK (scope_type IN ('ALL','AUTHORIZED_CLUSTER','PROJECT','SELF_CREATED','NONE'));

-- ④ 把 V4 种子的字面量同步为新口径（种子文件保留原样以维持 checksum，
--    故此处显式重述一遍：新装环境走 V1→V4→V6 后，最终态与老环境一致）
UPDATE role SET scope_type = 'AUTHORIZED_CLUSTER' WHERE role_code = 'OPS'                 AND scope_type <> 'AUTHORIZED_CLUSTER';
UPDATE role SET scope_type = 'SELF_CREATED'       WHERE role_code = 'BUSINESS'            AND scope_type <> 'SELF_CREATED';
UPDATE role SET scope_type = 'SELF_CREATED'       WHERE role_code = 'OPERATOR_MAINTAINER' AND scope_type <> 'SELF_CREATED';

-- 自检：迁移后不应残留旧命名（任何一行命中即说明有遗漏分支）
DO $$
DECLARE stale integer;
BEGIN
    SELECT count(*) INTO stale FROM role WHERE scope_type IN ('CLUSTER','SELF','TENANT');
    IF stale > 0 THEN
        RAISE EXCEPTION 'V6 迁移未收敛：仍有 % 行使用历史 scope_type 命名', stale;
    END IF;
END $$;
