# datafrog-acc-client — 014-data-02 泳道验收消费端

这是一个独立的 Maven 命令行项目，不属于任何 git 仓库。作用是在验收 014-data-02 泳道时，从开发机直连 Beta 泳道里 datafrog-server 实例的 triple 协议端口，逐个调用 `GenerationTaskService` 的两个 RPC（`CreateGenerationTask` / `GetGenerationTask`），把响应字段全部打印出来。每次运行只打一个 RPC，打印完就退出。

连接方式是 Dubbo 编程式 API（`ReferenceConfig` + `tri://host:port` 直连），不用 Spring，也不走注册中心，所以泳道不挂 Nacos 也能验。

## 前置条件

构建和运行都要钉 JDK 17（本机默认 JDK 25 与依赖基线不兼容）：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.1.jdk/Contents/Home
```

契约构件 `world.willfrog:datafrog-api:0.1.0-SNAPSHOT` 必须存在于本地 .m2，且要包含 GenerationTask 相关生成类。它是从 014-data-02 worktree 安装出来的。若 .m2 里的构件是旧的（jar 里没有 `GenerationTaskService` 等类），重装一次：

```bash
cd /Users/frog_wch/.slock/agents/022be858-9780-450c-855b-33593ea237a3/worktrees/014-data-02
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.1.jdk/Contents/Home ./mvnw -pl datafrog-api install -DskipTests
```

## 构建

```bash
cd /Users/frog_wch/.slock/agents/022be858-9780-450c-855b-33593ea237a3/tmp/014-data-02-acceptance
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.1.jdk/Contents/Home mvn -q package
```

再生成一次运行时 classpath 文件（依赖不变就不用重跑）：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.1.jdk/Contents/Home \
  mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath \
  -Dmdep.outputFile=target/cp.txt
```

## 运行

主类是 `world.willfrog.acc.AcceptanceClient`，第一个参数是子命令名。运行模板：

```bash
CP="target/classes:$(cat target/cp.txt)"

# 创建生成任务
java -cp "$CP" world.willfrog.acc.AcceptanceClient \
  create <host> <port> <targetObjectName> <clientRequestId> <instruction> <timeoutSeconds> [outputFormat] [sessionToken]

# 查询生成任务
java -cp "$CP" world.willfrog.acc.AcceptanceClient \
  get <host> <port> <taskId> [sessionToken]
```

泳道里 datafrog-server 的 triple 端口登记为 50070，所以 port 一般填 50070。host 是泳道实例所在机器对开发机可达的地址。

### create 参数

| 位置 | 参数 | 说明 |
| --- | --- | --- |
| 1 | host | 泳道实例地址 |
| 2 | port | triple 端口，泳道固定 50070 |
| 3 | targetObjectName | 任务规格声明的目标对象名，不接受 ds_ 前缀 id |
| 4 | clientRequestId | 调用方幂等键，同用户内重复提交会去重命中 |
| 5 | instruction | 行为边界文本，组进 spec_json 的 instruction 键 |
| 6 | timeoutSeconds | 时限秒数，服务端要求 60 到 86400 的整数 |
| 7 | outputFormat | 可选，组进 spec_json 的 output_format 键，不传则不写这个键 |
| 8 | sessionToken | 可选，默认 `acc` |

spec_json 由客户端按服务端约定组装，固定三个键：instruction（必填非空）、output_format（选填）、timeout_seconds（必填整数）。用 protobuf Struct 序列化，特殊字符转义无手工拼串风险。

### get 参数

| 位置 | 参数 | 说明 |
| --- | --- | --- |
| 1 | host | 泳道实例地址 |
| 2 | port | triple 端口，泳道固定 50070 |
| 3 | taskId | create 返回的 task_id |
| 4 | sessionToken | 可选，默认 `acc` |

### 运行示例

```bash
# 受理一个生成任务（不指定输出格式）
java -cp "$CP" world.willfrog.acc.AcceptanceClient \
  create 10.0.0.10 50070 my_target_obj req-20261026-001 \
  "按样例生成最近三十天的行情摘要" 3600

# 受理并指定输出格式
java -cp "$CP" world.willfrog.acc.AcceptanceClient \
  create 10.0.0.10 50070 my_target_obj req-20261026-002 \
  "生成月度持仓报告" 7200 csv

# 查询任务
java -cp "$CP" world.willfrog.acc.AcceptanceClient \
  get 10.0.0.10 50070 3f6c9b2e-8d1a-4c55-9a77-2e4f0b1c6d7a
```

输出把响应的全部字段逐行列出，create 打印 task_id、status、deduplicated、error_code、error_message，get 打印 proto 里全部 13 个字段（含 credit_charged / credit_settled / created_at_epoch 等）。

### 退出码

- 0：响应不带 error_code，视为成功（get 是 error_code 和 query_error_code 都为空）。
- 2：响应带 error_code（业务失败），或 RPC 本身抛异常。
- 1：用法或参数错误。

验收脚本可以拿退出码直接当断言用。

## 泳道验收注意事项

session_token 的处理要留意。014-data-02 过渡期的鉴权是本地直通形态：服务端环境变量 `DATAFROG_AUTH_PASSTHROUGH_USER_ID` 为空时一切凭证判无效，受理第一跳就拦下；泳道验收期会把它配成验收用户标识，此时任何非空 session_token 都视为该用户通过。所以客户端默认发非空占位 token `acc`，不配置就能打通；泳道若要校验特定凭证，把真实 token 传成最后一个参数即可。

幂等去重维度是 (user_id, client_request_id)。验收里反复用同一个 clientRequestId 会命中去重，响应带 deduplicated=true，不新建任务也不重复计费；想每次新建任务就换 clientRequestId。

## 依赖版本对齐

| 依赖 | 版本 | 对齐来源 |
| --- | --- | --- |
| org.apache.dubbo:dubbo | 3.3.2 | DataFrog 父 pom 的 dubbo.version，与 alphafrog-micro 同一基线 |
| world.willfrog:datafrog-api | 0.1.0-SNAPSHOT | 014-data-02 worktree 契约模块，本地 .m2 |
| com.google.protobuf:protobuf-java / -util | 3.25.3 | DataFrog 父 pom 的 protobuf-java.version |

Dubbo 用的生成接口形态说明一下。datafrog-api 由 dubbo-maven-plugin 3.3.2 生成，顶层接口 `world.willfrog.datafrog.idl.GenerationTaskService`（继承 `org.apache.dubbo.rpc.model.DubboStub`，含阻塞与异步两组方法）就是消费端 `ReferenceConfig` 该填的 interface。`DubboGenerationTaskServiceTriple` 只是 newStub 工厂类，嵌套的 Stub / ImplBase 分别是消费端代理基类和服务端实现基类，不是消费端引用形态。

## 验收记录

2026-10-26 交付时编译验证通过（`mvn -q package` 钉 JDK 17 全绿），并做了两条冒烟：无参数运行打印用法并以 1 退出；对 127.0.0.1:1 发 create 在连接被拒后打印 `RPC invocation failed` 并以 2 退出，消费端 URL 形态核对为 `tri://host:port/world.willfrog.datafrog.idl.GenerationTaskService`。真实泳道 RPC 未验，泳道起来后按上面示例跑即可。
