package world.willfrog.datafrog.server.maintenance;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import world.willfrog.datafrog.server.config.DatafrogRuntimeConfig;
import world.willfrog.datafrog.server.config.DatafrogRuntimeConfigLoader;
import world.willfrog.datafrog.server.httpapi.ApiException;

/**
 * 维护端点的名单鉴权（014-data-05 方案 D10）：平台没有管理员角色，维护端点
 * （核验查询、索引重建、索引状态）用热配置名单 datafrog.maintenance.admin-user-ids
 * （逗号分隔的用户 id 清单）控制。默认空名单 = 全部关闭（403 MAINTENANCE_FORBIDDEN），
 * 验收与生产要用时经热配置组下发名单（bob 裁定此形态）。
 *
 * 名单是热配置：改名单不用重启进程（加载器 10 秒轮询 + 事件即时重读）。
 */
@Component
public class MaintenanceAccessGuard {

    private final DatafrogRuntimeConfigLoader configLoader;

    public MaintenanceAccessGuard(DatafrogRuntimeConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    /** 当前用户不在名单即拒绝；名单为空（未配置）时全部拒绝。 */
    public void requireAdmin(String userId) {
        DatafrogRuntimeConfig cfg = configLoader.current().orElse(null);
        String list = cfg == null ? null : cfg.getMaintenanceAdminUserIds();
        boolean allowed = false;
        if (list != null && !list.isBlank()) {
            for (String item : list.split(",")) {
                if (userId.equals(item.trim())) {
                    allowed = true;
                    break;
                }
            }
        }
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MAINTENANCE_FORBIDDEN",
                    "当前用户不在维护端点名单（datafrog.maintenance.admin-user-ids）");
        }
    }
}
