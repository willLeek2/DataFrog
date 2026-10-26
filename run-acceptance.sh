#!/usr/bin/env bash
# 014-data-02 泳道验收驱动（Beta 机内侧执行；泳道端口不公网开放，必须在 Beta 机上跑）。
# 用法: ACC_MODEL=<模型名> bash run-acceptance.sh <host> <port> [evidenceDir]
# 前置: JDK 17 与 mvn 在 PATH；本地 .m2 已有含 GenerationTask 类的 datafrog-api:0.1.0-SNAPSHOT
#       （没有就先对任务分支跑 ./mvnw -pl datafrog-api install -DskipTests）。
# ACC_MODEL 非空时所有 create 在 spec_json 里带 model 键（frog 2026-10-26：测试用便宜模型，
# 最后一轮用 deepseek/deepseek-v4.1-flash）；留空则不写该键，运行用服务端默认模型。
# 产物: evidenceDir 下每步一个输出文件 + exit-codes.txt 汇总 + SUMMARY.txt。
set -uo pipefail

HOST=${1:?usage: run-acceptance.sh <host> <port> [evidenceDir]}
PORT=${2:?missing port}
EV=${3:-evidence-$(date +%Y%m%d-%H%M%S)}
TAG=${RUN_TAG:-$(date +%H%M%S)}
MODEL=${ACC_MODEL:-}
mkdir -p "$EV"

mvn -q package || { echo "mvn package FAILED" | tee "$EV/BUILD-FAILED.txt"; exit 1; }
mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath -Dmdep.outputFile=target/cp.txt
CP="target/classes:$(cat target/cp.txt)"

# run <step-name> <client args...>：退出码与完整输出都留证
# -Dfile.encoding=UTF-8：服务端错误文案是中文，缺省编码下 JVM 输出打成 ?
run() {
  local name=$1; shift
  java -Dfile.encoding=UTF-8 -cp "$CP" world.willfrog.acc.AcceptanceClient "$@" > "$EV/$name.txt" 2>&1
  local code=$?
  echo "$code $name" | tee -a "$EV/exit-codes.txt"
}

OBJ_A="acc-obj-a-$TAG"
OBJ_B="acc-obj-b-$TAG"
OBJ_C="acc-obj-c-$TAG"

# 路径 1：正常拉起（指令最小化控制消耗；泳道鉴权直通，token 用缺省 acc）
# 输出格式与 token 两位用 "" 占位（客户端把空串当缺省），把尾参位置让给 MODEL
run 01-create-normal create "$HOST" "$PORT" "$OBJ_A" "r1-$TAG" "写一句问候语" 3600 "" "" "$MODEL"
TID=$(awk -F'= ' '/^task_id/{print $2}' "$EV/01-create-normal.txt")

# 路径 2：重复提交去重（同用户同 client_request_id）
run 02-create-dedupe create "$HOST" "$PORT" "$OBJ_A" "r1-$TAG" "写一句问候语" 3600 "" "" "$MODEL"

# 路径 3：同对象互斥（路径 1 的运行仍在进行，紧跟一发；预期 GENERATION_IN_PROGRESS，
# 若路径 1 的运行已抢先终态则走惰性推进放行，此时该路径按未覆盖如实记录）
run 03-create-mutex create "$HOST" "$PORT" "$OBJ_A" "r2-$TAG" "再写一句" 3600 "" "" "$MODEL"

# 路径 4：规格校验四条（空 instruction / timeout 缺省 / 30 秒低于下限 / 90000 秒高于上限）
run 04a-spec-empty-instruction create "$HOST" "$PORT" "$OBJ_C" "r4a-$TAG" "" 3600 "" "" "$MODEL"
run 04b-spec-timeout-omit    create "$HOST" "$PORT" "$OBJ_C" "r4b-$TAG" "写一句" omit "" "" "$MODEL"
run 04c-spec-timeout-low     create "$HOST" "$PORT" "$OBJ_C" "r4c-$TAG" "写一句" 30 "" "" "$MODEL"
run 04d-spec-timeout-high    create "$HOST" "$PORT" "$OBJ_C" "r4d-$TAG" "写一句" 90000 "" "" "$MODEL"

# 路径 5：状态推进（轮询路径 1 的任务到终态，上限 15 分钟）
if [ -n "${TID:-}" ]; then
  for i in $(seq 1 60); do
    run "05-get-poll-$i" get "$HOST" "$PORT" "$TID"
    if grep -qE '^status *= *(SUCCEEDED|FAILED|FAILED_INCOMPLETE)' "$EV/05-get-poll-$i.txt"; then
      break
    fi
    sleep 15
  done
fi

# 路径 6：超时置终（60 秒下限创建，等 75 秒后查询触发惰性超时判定）。
# 指令故意加重（取数+长文），让运行尽量活过 60 秒 TTL：若运行仍提前终态，
# 该路径转为成功/失败档结算的证据点，TASK_TIMEOUT 按未覆盖如实记录。
run 06-create-timeout create "$HOST" "$PORT" "$OBJ_B" "r3-$TAG" "调用市场数据工具查询沪深300指数最近一个月的日线行情，然后写一份不少于1200字的走势观察报告，要求分小节、有数据引用" 60 "" "" "$MODEL"
sleep 75
TID3=$(awk -F'= ' '/^task_id/{print $2}' "$EV/06-create-timeout.txt")
if [ -n "${TID3:-}" ]; then
  run 06-get-after-timeout get "$HOST" "$PORT" "$TID3"
fi

{
  echo "evidence dir: $EV"
  echo "tag: $TAG  host: $HOST  port: $PORT  model: ${MODEL:-<absent>}"
  echo "--- exit codes ---"
  cat "$EV/exit-codes.txt"
} > "$EV/SUMMARY.txt"

echo "DONE. evidence: $EV"
