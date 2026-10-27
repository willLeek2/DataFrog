-- 014-data-05 保留策略、留存核验与导出限额的库表增量（方案 D6/D7/D8）。
-- 纪律与 01/02 一致：可重复执行、不使用迁移框架、泳道部署进程启动前按版本号顺序执行
-- （01 的建表在 v1、05 的增量在 v3，两文件互不冲突；可重复执行保证先后执行都幂等）。
-- 启动时 RetentionSchemaGuard 检查本文件的列与表都在，缺失即启动失败。
-- 04 的建列在自己的 v2 文件里（04 未合入 v1.4 基线），与本文件无重叠。

-- 对象行加保留策略两列（014-data-05 方案 D6）：缺省 NULL = 永久保留 / 全保留，
-- 即维持 01 软删先行的现状；空列不改变现状，物理删除从 frog 对保留策略点头那刻起
-- 才可能发生。软删时间戳一并列补上——「软删之后多少天物理清理」的到期计算需要它，
-- 01 的 markDeleted 之前没记这个事实。
ALTER TABLE datafrog_data_object ADD COLUMN IF NOT EXISTS retention_days INTEGER;
ALTER TABLE datafrog_data_object ADD COLUMN IF NOT EXISTS version_keep_last INTEGER;
ALTER TABLE datafrog_data_object ADD COLUMN IF NOT EXISTS deleted_at BIGINT;

-- 版本行加物理清理标记（014-data-05 方案 D6）：物理清理只删内容文件不删行，
-- 行上的标记就是「内容已清」的档案事实。
ALTER TABLE datafrog_object_version ADD COLUMN IF NOT EXISTS content_purged_at BIGINT;

-- 留存与清理的执行核验记录（014-data-05 方案 D7）：到点之后该留的还在不在、该删的
-- 删没删，逐条留痕；verdict=VIOLATION 的记录可独立过滤查询，人工只在异常时介入。
CREATE TABLE IF NOT EXISTS datafrog_retention_check_record (
    id          BIGSERIAL PRIMARY KEY,
    object_id   TEXT NOT NULL,
    check_type  TEXT NOT NULL,                    -- RETAIN（该留的还在不在） | PURGE（该删的删没删）
    expected    TEXT NOT NULL,                    -- 期望事实的短描述
    actual      TEXT NOT NULL,                    -- 实测事实的短描述
    verdict     TEXT NOT NULL,                    -- OK | VIOLATION
    detail      TEXT NOT NULL DEFAULT '',         -- 异常时的补充说明（如具体缺的文件）
    checked_at  BIGINT NOT NULL                   -- 13 位毫秒
);

CREATE INDEX IF NOT EXISTS idx_df_retention_check_object
    ON datafrog_retention_check_record (object_id);
CREATE INDEX IF NOT EXISTS idx_df_retention_check_verdict
    ON datafrog_retention_check_record (verdict);

-- 导出触发限额的计数流水（014-data-05 方案 D8）：每用户每日次数与同对象冷却两个
-- 判断都查这张表；触发即记一行（导出随后成败都算一次触发，防滥用的语义如此）。
-- 两个上限值是热配置起始值（默认每日 50 次、同对象 60 秒），不写进规范篇当永久口径。
CREATE TABLE IF NOT EXISTS datafrog_export_rate_ledger (
    id           BIGSERIAL PRIMARY KEY,
    user_id      TEXT NOT NULL,
    object_id    TEXT NOT NULL,
    exported_at  BIGINT NOT NULL                  -- 13 位毫秒
);

CREATE INDEX IF NOT EXISTS idx_df_export_rate_user_object_time
    ON datafrog_export_rate_ledger (user_id, object_id, exported_at);
