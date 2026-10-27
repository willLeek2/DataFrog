-- 014-data-05 修改任务受理：任务表与约束。
-- 结构镜像 014-data-02 的 datafrog-generation-task-v1.sql（受理管线同型，差异见各列注释），
-- DDL 纪律一致：可重复执行、不使用迁移框架、泳道部署进程启动前执行；
-- 服务启动时检查本表存在，缺失即启动失败（见 ModificationTaskSchemaGuard）。
-- 文件位置与 01/02 对齐：统一放 datafrog-server/src/main/resources/db/，随 jar 一起走。

CREATE TABLE IF NOT EXISTS datafrog_modification_task (
    id                TEXT PRIMARY KEY,              -- 任务编号（UUID）
    user_id           TEXT NOT NULL,                 -- 平台第一跳校验后得到的用户标识
    base_object_id    TEXT NOT NULL,                 -- 修改目标：已有对象的 id（ds_ 前缀），互斥键
    base_version      INTEGER NOT NULL,              -- 受理时落定的基准版本号（缺省解析为对象当时
                                                    -- 的当前版本；登记时只认这个号，见方案 D1 守卫）
    version_note      TEXT NOT NULL,                 -- 升版事由（CreateNewVersion 契约硬性要求非空，
                                                    -- 与 instruction 语义不同：一个是修改需求本身，
                                                    -- 一个是登记口径的升版事由）
    spec_json         TEXT NOT NULL,                 -- 任务规格快照：02 约定键 + 上述三键
    client_request_id TEXT NOT NULL,                 -- 调用方幂等键
    status            TEXT NOT NULL,                 -- ACCEPTED / RUNNING / SUCCEEDED / FAILED / FAILED_INCOMPLETE
    run_id            TEXT,                          -- 平台拉起的 Agent 运行编号
    incomplete        BOOLEAN NOT NULL DEFAULT FALSE, -- 登记段失败置 FAILED_INCOMPLETE 时写 TRUE
    error_code        TEXT,
    error_message     TEXT,
    credit_charged    INTEGER NOT NULL DEFAULT 0,    -- 受理时预扣（与 02 同值）
    credit_settled    INTEGER NOT NULL DEFAULT 0,    -- 终态实结；回补金额 = charged - settled
    registered_version INTEGER,                      -- 登记段成功升到的版本号；NULL=尚未登记成功
    columns_confirmed BOOLEAN NOT NULL DEFAULT FALSE, -- 两列（连续完整数据日）确认标记：第二波接线
                                                    -- 后由重算调用点写 TRUE（方案 D1 方法契约）
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ
);

-- 重复提交去重：同一用户内同一调用方幂等键只建一条任务（用户维度隔离，跨用户撞键互不可见）。
CREATE UNIQUE INDEX IF NOT EXISTS datafrog_modification_task_client_request_id_uq
    ON datafrog_modification_task (user_id, client_request_id);

-- 并发互斥：同一基准对象只允许一条非终态任务（部分唯一索引）。与 02 的互斥键不同：
-- 生成任务按目标对象名互斥，修改任务按基准对象 id 互斥（方案 D1 选择镜像建表的差异之一）。
-- 与生成任务之间的互斥不靠跨表索引：由登记段的基准版本守卫拦截（基准版本被生成任务的
-- 追加/升版顶掉时，修改任务登记失败标 FAILED_INCOMPLETE，不升版）。
CREATE UNIQUE INDEX IF NOT EXISTS datafrog_modification_task_active_base_uq
    ON datafrog_modification_task (base_object_id)
    WHERE status IN ('ACCEPTED', 'RUNNING');
