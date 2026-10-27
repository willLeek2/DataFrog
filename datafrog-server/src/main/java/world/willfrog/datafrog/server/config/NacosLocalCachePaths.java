package world.willfrog.datafrog.server.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 Nacos 本地缓存文件按流量范围错开。
 *
 * <p>Beta 主环境和泳道如果挂同一份宿主目录，两边会轮流覆盖同一个本地缓存文件。
 * 有 {@code AF_LANE_TRAFFIC_SCOPE_ID}（泳道身份，Beta 部署控制器注入，与 alphafrog
 * 服务用同一个环境变量键）时，在同一目录下把文件名改成 {@code {范围}.{原文件名}}，
 * 主环境继续用原来的路径。</p>
 *
 * <p>014-data-06 自 alphafrog common 模块同名类复制（多项目约定：先复制、不急着公共化），
 * 仅改包名与注释措辞。</p>
 */
public final class NacosLocalCachePaths {

    private NacosLocalCachePaths() {
    }

    public static String isolate(String targetFile, String trafficScopeId) {
        if (targetFile == null || targetFile.isBlank()) {
            return targetFile;
        }
        if (trafficScopeId == null || trafficScopeId.isBlank()) {
            return targetFile;
        }
        String scope = trafficScopeId.trim();
        Path path = Paths.get(targetFile);
        String name = path.getFileName().toString();
        String prefix = scope + ".";
        if (name.startsWith(prefix)) {
            return path.toString();
        }
        Path parent = path.getParent();
        Path isolated = parent == null ? Paths.get(prefix + name) : parent.resolve(prefix + name);
        return isolated.toString();
    }
}
