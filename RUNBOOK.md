# 014-data-02 节点 d 验收执行手册（泳道 lane-014data02）

泳道：lane-014data02，提交 34ebe09（基点 v1.4=658d2cd），端口以每次 lane start 输出为准（当前 54002，host 39.96.85.79）。
身份：DATAFROG_AUTH_PASSTHROUGH_USER_ID=5（stress_beta_3），直通期任何非空 session token 放行，消费端缺省发 "acc"。
落库核验：贝克开的 afobserve_ro 只读通道查 datafrog 库 datafrog_generation_task 表。

## 调用形态

A. 本机 Java 消费端（直连，需通道）：tmp/014-data-02-acceptance/，见 README。
   create <host> <port> <objectName> <reqId> <instruction> <timeoutSec> [outputFormat] [token]
   get <host> <port> <taskId> [token]
B. grpcurl/grpcio（转发或内侧形态）：服务 world.willfrog.datafrog.idl.GenerationTaskService，
   方法 CreateGenerationTask / GetGenerationTask，proto 在 datafrog-api。
   CreateGenerationTaskRequest 字段：session_token, target_object_name, spec_json(Struct), client_request_id。
   spec_json 三键：instruction（非空）、output_format（选）、timeout_seconds（60..86400）。

## 七条路径

| # | 路径 | 操作 | 预期 |
|---|------|------|------|
| 1 | 正常拉起 | create obj=acc-obj-a req=r1 instr=最小指令 timeout=3600 | task_id 非空、status=RUNNING、dedup=false；表行 run_id 非空、charged=25 |
| 2 | 重复提交去重 | 同 5 号用户重发 r1 | 返回同 task_id、dedup=true；表仍一行 |
| 3 | 同对象互斥 | r1 运行中 create obj=acc-obj-a req=r2 | GENERATION_IN_PROGRESS |
| 4 | 规格校验 | 空 instruction / timeout 缺省 / timeout=30 / timeout=90000 | 各返 INVALID_SPEC |
| 5 | 状态推进 | get r1 的 task_id（运行中→终态） | 非终态透传运行状态摘要；run 终态后惰性推进为 SUCCEEDED/FAILED，settled=实耗+5 |
| 6 | 超时置终 | create obj=acc-obj-b req=r3 timeout=60，等 61s+ 后 get | FAILED、error_code=TASK_TIMEOUT、先 cancelRun 再置终（看 run 侧是否收到取消） |
| 7 | 未拉起回补 | 客户端不可直接诱发；以 3/6 中惰性推进的 charged→settled 落库佐证回补语义，覆盖边界如实写报告 | 见报告 |

余额不足（第 1 条文档口径里的 CREDIT_INSUFFICIENT）：用五键 preset 加 --set-env datafrog:DATAFROG_AUTH_PASSTHROUGH_USER_ID=2 同名重拉（端口会重分配）→ create 应返 CREDIT_INSUFFICIENT → 换回 5 继续。注意 --set-env 不能与 02 preset 同键并用（贝克定的用法）。

## 断言与证据

- 每条路径留：请求参数、响应全字段、退出码。
- 表态核验 SQL（afobserve_ro）：SELECT id,target_object_name,status,run_id,error_code,credit_charged,credit_settled FROM datafrog_generation_task ORDER BY created_at;
- 消耗归属：全部记 5 号账户；跑完汇总 run 实耗（getStatus 的 total_credits_consumed）。
