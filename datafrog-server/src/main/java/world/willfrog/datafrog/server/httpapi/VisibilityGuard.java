package world.willfrog.datafrog.server.httpapi;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 可见性骨架：当前唯一规则是数据对象 owner 与当前 userId 完全相等才可见。
 *
 * 这是骨架而非终态：PRIVATE 与 OWNER_VISIBLE 两档的确切语义差异、
 * source_type=SYSTEM（系统生产）的对象是否全员可见，还在等 frog 拍板；
 * 拍板出处编号 1df57f50。拍板后在本类扩展档位语义即可：列表过滤、详情、导出、
 * 删除、下载都走这里，调用方不用动。
 */
@Component
public class VisibilityGuard {

    /** 骨架期口径：owner 完全相等才算可见；owner 为空的脏数据一律不可见。 */
    public boolean isVisible(String owner, String userId) {
        return owner != null && owner.equals(userId);
    }

    /** 详情/导出/删除/下载前的判定：不可见即 403，对应方案映射表的 FORBIDDEN_VISIBILITY。 */
    public void requireVisible(String owner, String userId) {
        if (!isVisible(owner, userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_VISIBILITY", "数据对象对当前用户不可见");
        }
    }
}
