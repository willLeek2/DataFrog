/**
 * HTTP REST 对外入口（014-data-06）。
 *
 * 本包是适配层：只做鉴权、参数翻译、错误映射，不产生新的状态规则。
 * 业务规则全部在下层 bean 里：数据对象走 rpc.DataObjectServiceImpl 的
 * ResultInfo（ok + 可机读错误码 + message 的信封）契约，生成任务走
 * task.GenerationTaskServiceImpl 的 error_code 字符串契约；本包进程内直调
 * 这些 bean，不经 Dubbo。
 *
 * 有意不建在此包的端点：POST /generation-tasks/{id}/review（审核业务归 014-data-03，
 * 03 落地前不应有占位入口）；014-data-04 的新鲜度与断言；014-data-05 的语义查询三端点。
 * 登录态过滤器对 /api/data-platform/v1/** 前缀统一生效，这些端点将来落在同一前缀下
 * 即自动被覆盖，无需改过滤器。
 */
package world.willfrog.datafrog.server.httpapi;
