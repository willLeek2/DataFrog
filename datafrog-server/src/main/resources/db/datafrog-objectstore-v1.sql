-- 014-data-01 数据对象存储模块建表脚本。
-- 纪律（bob 审定 D10）：不自建迁移框架，本脚本可重复执行（IF NOT EXISTS，存在即跳过）；
-- 泳道部署在进程启动前执行本脚本；表缺失时 InitialDefinitionLoader 启动即失败、不导入一半。
-- 表前缀 datafrog_ 按运维对接文档约定。字段口径对应《数据规范与约定》：
-- 数据覆盖三件（row_count、coverage_end、content_checksum）只存在版本行，登记卡读取时投影；
-- coverage_start 登记时写入、追加不改、升版后旧行定格；part_checksums 追加只增不改。

CREATE TABLE IF NOT EXISTS datafrog_data_object (
    id              TEXT PRIMARY KEY,                -- ds_ 加 8 位十六进制小写，登记后永不复用
    name            TEXT NOT NULL,                   -- 人类可读名称，不强制唯一，靠 id 定位
    description     TEXT NOT NULL,                   -- 是什么、怎么得到、适用范围
    source_type     TEXT NOT NULL,                   -- system | user
    owner           TEXT NOT NULL,                   -- system=通道名(fetch/indicator)，user=用户名
    visibility      TEXT NOT NULL,                   -- private | owner_visible
    semantic_refs   JSONB NOT NULL,                  -- 挂靠的定义引用数组 [{"name":"...","version":1}]
    coverage_scope  TEXT NOT NULL,                   -- 覆盖范围描述；AH 两地代码各自成行在此声明
    frequency       TEXT NOT NULL,                   -- daily | single_period
    gen_method      TEXT NOT NULL,                   -- fetch | indicator | agent
    gen_task        TEXT,
    inputs          JSONB NOT NULL DEFAULT '[]',     -- 输入对象 id 数组，须已登记
    primary_key     JSONB NOT NULL,                  -- 对外自然主键字段名数组
    adjustment      TEXT,                            -- raw | qfq | hfq；价格类数据必填，一个对象不混装
    ccy             TEXT,                            -- CNY | HKD | USD；跨市场金额字段必声明
    current_version INTEGER NOT NULL,
    export_format   TEXT NOT NULL DEFAULT 'csv',     -- 声明的导出配置
    compression     TEXT NOT NULL DEFAULT 'none',    -- none | zip，声明的导出配置
    status          TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DELETED（软删，保留策略任务前不物理删）
    created_at      BIGINT NOT NULL,                 -- 13 位毫秒
    updated_at      BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS datafrog_semantic_definition (
    name          TEXT NOT NULL,                     -- sd_ 的名称部分（英文下划线小写）
    version       INTEGER NOT NULL,                  -- sd_<name>_v<version>
    display_name  TEXT NOT NULL,                     -- 中文名，与命名层严格一致
    definition    TEXT NOT NULL,                     -- 自然语言定义
    calculation   TEXT NOT NULL DEFAULT '',          -- 计算方式
    unit          TEXT NOT NULL DEFAULT '',          -- 单位
    scope         TEXT NOT NULL DEFAULT '',          -- 适用范围
    status        TEXT NOT NULL DEFAULT 'ACTIVE',
    origin        TEXT NOT NULL,                     -- migrated（01304 方法目录迁移）| manual
    created_at    BIGINT NOT NULL,
    PRIMARY KEY (name, version)
);

CREATE TABLE IF NOT EXISTS datafrog_object_version (
    object_id            TEXT NOT NULL,
    version              INTEGER NOT NULL,           -- 递增整数，1 起
    version_note         TEXT NOT NULL DEFAULT '',   -- 版本三件套之一：升版事由须为修正或重算
    prev_version         INTEGER,                    -- 版本三件套之一：前一版本链接
    coverage_start       TEXT NOT NULL,              -- YYYY-MM-DD；登记时写入，追加不改，升版定格
    content_path         TEXT NOT NULL,              -- 版本目录（相对数据根），追加 part 时不变
    part_count           INTEGER NOT NULL DEFAULT 0, -- 存储事实：Parquet part 个数
    part_checksums       JSONB NOT NULL DEFAULT '[]',-- 各 part 的 sha256 数组，追加只增不改
    row_count            BIGINT NOT NULL DEFAULT 0,  -- 数据覆盖三件之一：随生产事实推进
    coverage_end         TEXT NOT NULL,              -- 数据覆盖三件之一：实际覆盖结束日期
    content_checksum     TEXT NOT NULL,              -- 数据覆盖三件之一：当前全部 part 按文件名升序拼接的 sha256
    status               TEXT NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | SUPERSEDED（升版封存）
    created_at           BIGINT NOT NULL,
    watermark_updated_at BIGINT NOT NULL,
    PRIMARY KEY (object_id, version)
);

-- 当前 ACTIVE 版本行是数据覆盖事实的唯一来源；部分索引支撑 getActive 直查。
CREATE INDEX IF NOT EXISTS idx_df_object_version_active
    ON datafrog_object_version (object_id) WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS datafrog_catalog_record (
    id          BIGSERIAL PRIMARY KEY,
    object_id   TEXT NOT NULL,
    action      TEXT NOT NULL,                       -- REGISTERED | APPENDED | MODIFIED | DELETED
    actor       TEXT NOT NULL,                       -- 执行主体（通道名/用户名/system）
    summary     TEXT NOT NULL DEFAULT '',            -- 变更摘要（如追加行数、升版事由）
    created_at  BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_df_catalog_record_object
    ON datafrog_catalog_record (object_id);
