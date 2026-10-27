-- 014-data-04 数据对象存储模块增量脚本（在 datafrog-objectstore-v1.sql 之后执行）。
-- 纪律延续 v1：可重复执行（IF NOT EXISTS / IF NOT COLUMN 语义，存在即跳过）；
-- 泳道部署在进程启动前按文件名序执行 v1、v2；缺表缺列时启动失败、不导入一半（fail-closed）。
-- 口径（bob 审定）：
--   assertions 存对象行：登记时声明的检查条件（JSONB 数组，AssertionSpec 结构），结果不落库、查询时现算；
--   continuous_complete_date / has_gap_after_watermark 存版本行：建列与查询归 04，写入归 03
--   （审核批准完成登记或追加之后、生产库表映射同一登记路径写入；两列没写上时批准不能报成功）。

ALTER TABLE datafrog_data_object ADD COLUMN IF NOT EXISTS assertions JSONB NOT NULL DEFAULT '[]';

ALTER TABLE datafrog_object_version ADD COLUMN IF NOT EXISTS continuous_complete_date TEXT;

ALTER TABLE datafrog_object_version ADD COLUMN IF NOT EXISTS has_gap_after_watermark BOOLEAN;
