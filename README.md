# DataFrog

DataFrog 是 AlphaFrog 的数据平台，一个独立仓库的单体 Spring 应用。当前处于骨架阶段：应用只暴露一个对外 RPC 端点，调用后返回 Hello World。骨架的意义是把数据平台后续所有对外连接的接入位置在代码里提前留好，并且让构建、部署、调试的形态与 alphafrog-micro 仓库里的既有微服务保持一致，使 Beta 环境与生产环境的既有工具链可以原样作用于本服务。

## 仓库布局

仓库是一个 Maven 聚合工程，含两个模块。

`datafrog-api` 是 Dubbo 契约模块，只放 proto 文件，构建时由 dubbo-maven-plugin 生成消息类与 triple 调用 stub。任何需要调用 DataFrog 的服务（包括 alphafrog 侧的服务）只依赖这个模块的 jar，不依赖应用本体。

`datafrog-server` 是单体应用模块，DataFrog 的全部 Dubbo 提供方都跑在这一个进程里。它依赖 `datafrog-api`，实现契约接口并以 `@DubboService` 暴露。

把契约拆成独立模块是沿用 alphafrog 的既有形态。alphafrog 的每个微服务都由一个 Api 契约模块和一个 Service 实现模块组成，DataFrog 沿用同样的模块划分，调试与构建工具链不需要为本服务发明新路径。

## 构建

本机执行 Maven 前必须把 JDK 固定为 17：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.1.jdk/Contents/Home
mvn clean install
```

本机的 mvn 不带 JAVA_HOME 时会跑在 openjdk 25 上，而锁定版本的构建依赖不认 Java 25 的类文件，alphafrog 仓库有同样的纪律，原因与处置记录在 alphafrog 仓库的 AGENTS.md 通用工作规则第 1 条。

构建产物：`datafrog-api-0.1.0-SNAPSHOT.jar`（契约）与 `datafrog-server-0.1.0-SNAPSHOT.jar`（可执行应用，spring-boot repackage）。

单元测试是纯逻辑测试，不启动 Spring 上下文，因此不需要 Nacos 或任何中间件。上下文级测试刻意不写：`@EnableDubbo` 的上下文启动时会连接注册中心，本机开发环境没有常驻 Nacos，这类验证留给有编排环境的阶段。

## 本地运行

应用启动需要一个可达的 Nacos 注册中心，地址由环境变量 `NACOS_ADDRESS` 给出，默认 `127.0.0.1:8848`。本机没有常驻 Nacos 时，可以复用 alphafrog 仓库 docker-compose 编排里的 Nacos 容器。

## 对外连接的骨架位置

数据平台后续每类对外连接的接入位置如下，新增连接时不需要再动工程结构。

第一类，对外提供 RPC（别的服务调 DataFrog）。已有一个活的示例：`datafrog-api/src/main/proto/hello.proto` 定义的 `HelloService`，由 `datafrog-server` 的 `world.willfrog.datafrog.server.rpc.HelloServiceImpl` 实现并暴露。新增真实服务时，在 `datafrog-api` 加一个 proto 文件，在 `server.rpc` 包加一个实现类，其余什么都不用动。

第二类，调用 alphafrog 既有微服务（DataFrog 调别人）。消费代码统一放在 `world.willfrog.datafrog.server.client` 包，该包的 package-info 写了接入步骤。`datafrog-server/pom.xml` 里留有注释掉的 alphafrog Api 模块依赖示例，取消注释前需要把目标 Api 模块 `mvn install` 到本机 .m2。`dubbo.consumer.check=false` 已在配置里全局生效，启动时不要求提供方在线。

第三类，PostgreSQL 数据源。`application.yml` 顶部与 `datafrog-server/pom.xml` 里各有一块注释掉的配套配置，形态与 alphafrog 各业务服务一致，连接信息由部署环境以 `AF_DB_MAIN_*` 环境变量注入。

第四类，Redis。同样在 `application.yml` 顶部与 pom 里有配套注释块，环境变量为 `AF_REDIS_*`。

第五类，热配置。alphafrog 的热配置走 common 模块的自研 NacosConfigBridge，按环境与泳道从 Nacos 组拉取并写本地缓存文件。DataFrog 接入热配置时从 alphafrog 的 common 模块复制该桥或抽共享库，配置组名在运维工具改造时一并登记，`application.yml` 尾部留有说明。

第六类，HTTP 端点与登录鉴权。alphafrog 的 HTTP 入口集中在 frontend 模块，鉴权是 HTTP 加 JWT 令牌，由 JwtConfig、JwtAuthFilter、SecurityConfig 三个类组成校验链。DataFrog 复用 alphafrog 的登录态时按该形态适配，骨架阶段不引入 web 依赖，业务流量全部走 Dubbo。

第七类，部署与泳道。`datafrog-server/Dockerfile` 与 `docker_build.sh` 沿用 alphafrog 各模块的形态，镜像基于 eclipse-temurin:17-jdk-noble，构建脚本认 `IMAGE_TAG` 环境变量，Beta 的构建流程靠这个变量区分主环境与泳道产物。泳道本身对应用零代码：泳道身份由 Beta 部署控制器经环境变量注入（`AF_LANE_TRAFFIC_SCOPE_ID`，以及 Dubbo 注册参数里的 `dubbo.tag` 键），本服务与 alphafrog 既有服务同形态，部署进泳道后自动参与标签路由。本机禁止构建与运行 Docker 镜像，镜像构建发生在 Beta 机或生产机上。

## 登记值

应用名与 Dubbo 应用名都是 `datafrog-server`。tri 协议端口登记为 50070，alphafrog 既有服务已占用 50052 至 50062 与 20882。groupId 为 `world.willfrog`，Java 包根为 `world.willfrog.datafrog`，proto 包为 `world.willfrog.datafrog.idl`。版本 `0.1.0-SNAPSHOT` 表示骨架阶段，构件只进本机 .m2；正式的版本化发布约定与 alphafrog Api 模块的发布约定是同一个议题，等 frog 拍板。
