package world.willfrog.datafrog.server.httpapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 导出下载的文件定位与响应内容类型。
 *
 * 目录公式必须与 ContentStorageService.exportCsv 保持一致（那边没暴露查询方法，
 * 公式在本类平行维护，改动需两处同步）：数据根/&lt;objectId&gt;/export/v&lt;version&gt;。
 * 同一对象同一版本重复导出覆盖同一目录，所以「文件已存在就不必再触发导出」。
 */
@Component
public class ExportFileSupport {

    /** 下载文件名的字符白名单：防路径穿越（借 ../ 这类片段逃出目标目录的注入手法）。 */
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final String dataRoot;

    public ExportFileSupport(@Value("${datafrog.objectstore.data-root:data/objectstore}") String dataRoot) {
        this.dataRoot = dataRoot;
    }

    /**
     * 定位某对象某版本导出目录里的一个文件。fileName 先过字符白名单，normalize 后再校验
     * 落点的父目录必须恰好是导出目录——".." 这类名字字符全在白名单内也能逃出目录，必须二次拦住。
     */
    public Path resolveShardFile(String objectId, int version, String fileName) {
        requireSafeFileName(fileName);
        Path exportDir = exportDirOf(objectId, version);
        Path file = exportDir.resolve(fileName).normalize();
        if (!file.getParent().equals(exportDir)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "fileName 非法: " + fileName);
        }
        return file;
    }

    /** 与 ContentStorageService 的导出目录公式保持一致（见类注释）。 */
    public Path exportDirOf(String objectId, int version) {
        return Path.of(dataRoot, objectId, "export", "v" + version).normalize();
    }

    /** 不符字符白名单即 400 INVALID_ARGUMENT（方案口径，先于一切文件操作执行）。 */
    public void requireSafeFileName(String fileName) {
        if (fileName == null || !SAFE_FILE_NAME.matcher(fileName).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                    "fileName 只允许字母、数字、点、下划线、连字符: " + fileName);
        }
    }

    /** 内容类型按扩展名：.csv 是 text/csv，.zip 是 application/zip，其余按二进制流。 */
    public MediaType contentTypeFor(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return MediaType.parseMediaType("text/csv");
        }
        if (lower.endsWith(".zip")) {
            // Spring 的 MediaType 常量里没有 application/zip，用解析方法表达
            return MediaType.parseMediaType("application/zip");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
