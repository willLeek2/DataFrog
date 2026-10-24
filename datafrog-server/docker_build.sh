USE_PROXY=${USE_PROXY:-1}

if [ "$USE_PROXY" = "1" ] || [ "$USE_PROXY" = "true" ]; then
  export https_proxy=http://127.0.0.1:7890 http_proxy=http://127.0.0.1:7890 all_proxy=socks5://127.0.0.1:7890
  PROXY_ARGS="--build-arg http_proxy=$http_proxy --build-arg https_proxy=$https_proxy"
else
  unset https_proxy http_proxy all_proxy
  PROXY_ARGS=""
fi

# IMAGE_TAG 或第一个参数可覆盖产物标签，未设置时保持 :latest（与 alphafrog 各模块同一约定，
# Beta 接待命令按 IMAGE_TAG 区分主环境与泳道构建产物）。
IMAGE_TAG="${1:-${IMAGE_TAG:-datafrog-server:latest}}"
# 在仓库根目录执行本脚本，构建上下文为模块目录。
docker build $PROXY_ARGS -t "$IMAGE_TAG" ./datafrog-server
