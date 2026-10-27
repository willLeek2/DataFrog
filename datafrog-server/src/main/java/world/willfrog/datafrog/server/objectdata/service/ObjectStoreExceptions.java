package world.willfrog.datafrog.server.objectdata.service;

import java.util.List;

/**
 * 本模块业务失败统一用异常向上抛，rpc 层（DataObjectServiceImpl）负责转成 ResultInfo 信封
 * （ok=false + 可机读错误码 + message），业务失败不走 Java 异常栈协议层。
 * 这里只放模块内自己产生的失败；数据库连接等基础设施故障按 RuntimeException 原样上抛，rpc 层兜 INTERNAL。
 */
public final class ObjectStoreExceptions {

    private ObjectStoreExceptions() {
    }

    /** 挂靠的语义定义不存在（规范篇第 2 节：生成与修改数据对象必须挂靠已有定义）。 */
    public static final class SemanticRefNotFoundException extends RuntimeException {
        public final List<String> missingRefs;

        public SemanticRefNotFoundException(List<String> missingRefs) {
            super("挂靠的语义定义不存在: " + String.join(", ", missingRefs));
            this.missingRefs = missingRefs;
        }
    }

    /** 语义定义 name+version 重复注册。 */
    public static final class DuplicateDefinitionException extends RuntimeException {
        public DuplicateDefinitionException(String name, int version) {
            super("语义定义已存在: sd_" + name + "_v" + version);
        }
    }

    /** 登记内容或声明违反《数据规范与约定》某条已定条目。 */
    public static final class ConventionViolationException extends RuntimeException {
        public ConventionViolationException(String message) {
            super(message);
        }
    }

    /** 数据对象不存在（含软删后不可见）。 */
    public static final class ObjectNotFoundException extends RuntimeException {
        public ObjectNotFoundException(String objectId) {
            super("数据对象不存在: " + objectId);
        }
    }

    /** 对象存在但没有 ACTIVE 版本行（正常流程不会出现，防御性兜底）。 */
    public static final class NoActiveVersionException extends RuntimeException {
        public NoActiveVersionException(String objectId) {
            super("数据对象没有当前生效版本: " + objectId);
        }
    }

    /** 目标版本号已存在（升版必须用全新递增版本号）。 */
    public static final class DuplicateVersionException extends RuntimeException {
        public DuplicateVersionException(String objectId, int version) {
            super("版本已存在: " + objectId + " v" + version);
        }
    }

    /** 元数据表未就位（建表脚本 db/datafrog-objectstore-v1.sql 未在进程启动前执行）。 */
    public static final class TablesNotReadyException extends RuntimeException {
        public TablesNotReadyException(String detail) {
            super("元数据表未就位（先执行 db/datafrog-objectstore-v1.sql）: " + detail);
        }
    }

    /** 请求参数本身不合法（枚举值非法、必填缺失等），与约定违规区分开。 */
    public static final class InvalidArgumentException extends RuntimeException {
        public InvalidArgumentException(String message) {
            super(message);
        }
    }
}
