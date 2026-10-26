-- 014-data-02 生成任务受理：任务表与约束。
-- DDL 纪律与 014-data-01 一致：可重复执行、不使用迁移框架、泳道部署进程启动前执行；
-- 服务启动时检查本表存在，缺失即启动失败（见 GenerationTaskSchemaGuard）。

CREATE TABLE IF NOT EXISTS datafrog_generation_task (
    id                TEXT PRIMARY KEY,              -- 任务编号（UUID）
    user_id           TEXT NOT NULL,                 -- 平台第一跳校验后得到的用户标识
    target_object_name TEXT NOT NULL,                -- 任务规格声明的目标对象名（非 ds_ 前缀对象 id）
    spec_json         TEXT NOT NULL,                 -- 任务规格快照：行为边界、输出格式、时限
    client_request_id TEXT NOT NULL,                 -- 调用方幂等键
    status            TEXT NOT NULL,                 -- ACCEPTED / RUNNING / SUCCEEDED / FAILED / FAILED_INCOMPLETE
    run_id            TEXT,                          -- 平台拉起的 Agent 运行编号
    incomplete        BOOLEAN NOT NULL DEFAULT FALSE, -- 是否有部分成功产物（014-data-03 写入）
    error_code        TEXT,
    error_message     TEXT,
    credit_charged    INTEGER NOT NULL DEFAULT 0,    -- 受理时预扣
    credit_settled    INTEGER NOT NULL DEFAULT 0,    -- 终态实结；回补金额 = charged - settled
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ
);

-- 重复提交去重：同一调用方幂等键只建一条任务。
CREATE UNIQUE INDEX IF NOT EXISTS datafrog_generation_task_client_request_id_uq
    ON datafrog_generation_task (client_request_id);

-- 并发互斥：同一目标对象名只允许一条非终态任务（部分唯一索引）。
CREATE UNIQUE INDEX IF NOT EXISTS datafrog_generation_task_active_target_uq
    ON datafrog_generation_task (target_object_name)
    WHERE status IN ('ACCEPTED', 'RUNNING');
