package world.willfrog.datafrog.server.objectdata.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.datafrog.server.objectdata.service.ObjectStoreExceptions.ConventionViolationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 内容存储：CSV 进、Parquet part 落盘、两级校验和（D5）、第 10 节 CSV 导出。
 *
 * 存储形态：数据根（datafrog.objectstore.data-root，默认 data/objectstore）下按
 * <object_id>/v<version>/part-<六位序号>.parquet 分 part 存放；part 文件一旦写入永不改写，
 * 追加只新增 part（不可变快照的文件层落地）。全部列按 VARCHAR 存（v1 简化）：
 * 规范篇第 6 节缺失三态（NA/NULL/PENDING 占位字符串）因此可以原样穿透存储，
 * 类型化与数值校验留给生产侧（014-data-03）。
 *
 * 校验和口径（D5 + 第 11 节示例三）：每个 part 落盘时算自己的 sha256（写入后不变）；
 * 版本行 content_checksum = 当前全部 part 按文件名升序拼接的 sha256（随追加重算）；
 * 导出 aggregate_checksum = 导出分片逐片解压、按文件名升序依序拼接的 sha256
 * （与版本行 checksum 公式相同、文件集不同，不必相等）。
 *
 * 导出（第 10 节）：UTF-8、RFC 4180、首行表头、LF、无 BOM；日期样列（YYYY-MM-DD）自动附
 * YYYYMMDD 伴随列；单文件超上限则分片，每片独立带表头；声明 zip 时逐片打 zip；
 * 另附字段说明附表。单文件上限与反滥用限额联动（规范篇第 10 节【已定】条目），
 * 反滥用配置归 014-data-06，接线前先取 512MB 代码常量。
 *
 * v1 边界（已向 reviewer 报备）：导出时整个 CSV 在内存中分片，泳道验收对象（小体量）适用；
 * 中文字段名与单位的登记归 014-data-03，附表先留空列并注明去向。
 */
@Service
public class ContentStorageService {

    static final long EXPORT_SINGLE_FILE_MAX_BYTES = 512L * 1024 * 1024;

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** 数据根目录（相对工作目录或绝对路径，由部署环境决定）。 */
    private final String dataRoot;

    public ContentStorageService(@Value("${datafrog.objectstore.data-root:data/objectstore}") String dataRoot) {
        this.dataRoot = dataRoot;
    }

    /** 单个 part 的落盘事实。 */
    public record StoredPart(String fileName, String sha256, long rowCount) {
    }

    /** 导出结果：分片按文件名升序；aggregateChecksum 为逐片解压后拼接的 sha256。 */
    public record ExportShard(String fileName, long byteSize, String sha256) {
    }

    public record ExportResult(String exportDir, List<ExportShard> shards, String aggregateChecksum,
                               String fieldNotesFile) {
    }

    /** 写入一个新 part（目录内现有 part 序号最大者加一）；不动旧 part。 */
    public StoredPart writePart(String contentPath, byte[] csvContent) {
        Path versionDir = versionDir(contentPath);
        try {
            Files.createDirectories(versionDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建版本目录失败: " + versionDir, e);
        }
        int nextSeq = listParquetFiles(versionDir).size() + 1;
        String fileName = String.format("part-%06d.parquet", nextSeq);
        Path partFile = versionDir.resolve(fileName);
        Path csvTemp = writeTempCsv(csvContent);
        try (Connection conn = duckdb()) {
            // all_varchar：占位符字符串原样穿透，不做类型推断（见类注释）
            execUpdate(conn, "COPY (SELECT * FROM " + readCsv(csvTemp) + ") TO " + sqlString(partFile)
                    + " (FORMAT PARQUET)");
            long rows = queryLong(conn, "SELECT count(*) FROM read_parquet(" + sqlString(partFile) + ")");
            String sha = sha256Of(partFile);
            return new StoredPart(fileName, sha, rows);
        } catch (SQLException e) {
            throw new ConventionViolationException("CSV 内容无法解析落盘: " + e.getMessage());
        } finally {
            deleteQuietly(csvTemp);
        }
    }

    /** 汇总校验和：目录内全部 part 按文件名升序拼接的 sha256（流式，不整读单文件）。 */
    public String aggregateChecksum(Path versionDir) {
        MessageDigest digest = sha256Digest();
        for (Path p : listParquetFiles(versionDir)) {
            digestPart(digest, p);
        }
        return toHex(digest.digest());
    }

    /** 汇总校验和（按 contentPath 口径）。 */
    public String contentChecksum(String contentPath) {
        return aggregateChecksum(versionDir(contentPath));
    }

    /** 元数据落库失败时的尽力清理：删除整个版本目录（登记/升版的文件先于数据库写入）。 */
    public void deleteVersionDirQuietly(String contentPath) {
        Path dir = versionDir(contentPath);
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            // 清理失败不掩盖原始异常
        }
    }

    /** 元数据落库失败时的尽力清理：只删本次追加写入的 part。 */
    public void deletePartQuietly(String contentPath, String partFile) {
        deleteQuietly(versionDir(contentPath).resolve(partFile));
    }

    /** 追加前校验：新 CSV 的列结构必须与版本首个 part 一致（追加不升版，结构变了必须升版）。 */
    public void assertSameColumns(String contentPath, byte[] csvContent) {
        List<Path> parts = listParquetFiles(versionDir(contentPath));
        if (parts.isEmpty()) {
            return;
        }
        Path csvTemp = writeTempCsv(csvContent);
        try (Connection conn = duckdb()) {
            List<String> existing = columnNames(conn, "SELECT * FROM read_parquet(" + sqlString(parts.get(0)) + ")");
            List<String> incoming = columnNames(conn, "SELECT * FROM " + readCsv(csvTemp));
            if (!existing.equals(incoming)) {
                throw new ConventionViolationException(
                        "追加的列结构与登记时不一致（结构变更须升版）: 登记=" + existing + " 追加=" + incoming);
            }
        } catch (SQLException e) {
            throw new ConventionViolationException("CSV 内容无法解析: " + e.getMessage());
        } finally {
            deleteQuietly(csvTemp);
        }
    }

    /** part 内出现的最大日期（YYYY-MM-DD），用于追加推进数据覆盖结束日期；无日期样列返回 null。 */
    public String maxDateInPart(String contentPath, String partFile) {
        Path part = versionDir(contentPath).resolve(partFile);
        try (Connection conn = duckdb()) {
            String max = null;
            for (String col : dateLikeColumns(conn, "read_parquet(" + sqlString(part) + ")")) {
                String v = queryString(conn, "SELECT max(try_cast(" + ident(col) + " AS DATE))::VARCHAR FROM read_parquet("
                        + sqlString(part) + ")");
                if (v != null && (max == null || v.compareTo(max) > 0)) {
                    max = v;
                }
            }
            return max;
        } catch (SQLException e) {
            throw new IllegalStateException("读取 part 日期失败: " + part, e);
        }
    }

    /** 版本目录内全部 part 文件名，按文件名升序（与 content_checksum 的拼接定义同序）。 */
    public List<String> listPartFileNames(String contentPath) {
        List<String> names = new ArrayList<>();
        for (Path p : listParquetFiles(versionDir(contentPath))) {
            names.add(p.getFileName().toString());
        }
        return names;
    }

    /** 单个 part 的字节长度。 */
    public long partSize(String contentPath, String partFile) {
        try {
            return Files.size(versionDir(contentPath).resolve(partFile));
        } catch (IOException e) {
            throw new IllegalStateException("读取 part 长度失败: " + partFile, e);
        }
    }

    /** 读单个 part 自 offset 起的至多 length 字节（ReadObjectData 用；只读不写）。 */
    public byte[] readPartRange(String contentPath, String partFile, long offset, int length) {
        Path part = versionDir(contentPath).resolve(partFile);
        try (InputStream in = Files.newInputStream(part)) {
            in.skipNBytes(offset);
            return in.readNBytes(length);
        } catch (IOException e) {
            throw new IllegalStateException("读 part 数据失败: " + part, e);
        }
    }

    /**
     * 第 10 节导出。同一对象同一版本重复导出覆盖同一目录（先清空再写）。
     */
    public ExportResult exportCsv(String objectId, int version, String contentPath,
                                  String exportFormat, String compression) {
        if (!"csv".equals(exportFormat)) {
            throw new ConventionViolationException("仅支持 csv 导出格式: " + exportFormat);
        }
        boolean zip = "zip".equals(compression);
        Path versionDir = versionDir(contentPath);
        List<Path> parts = listParquetFiles(versionDir);
        if (parts.isEmpty()) {
            throw new ConventionViolationException("该版本没有内容 part，无法导出");
        }
        Path exportDir = Path.of(dataRoot, objectId, "export", "v" + version);
        try {
            prepareCleanDir(exportDir);
        } catch (IOException e) {
            throw new IllegalStateException("准备导出目录失败: " + exportDir, e);
        }

        Path fullCsv = null;
        try (Connection conn = duckdb()) {
            fullCsv = Files.createTempFile("df-export-", ".csv");
            String parquetList = parquetList(parts);
            execUpdate(conn, "COPY (" + exportSelect(conn, parquetList) + ") TO " + sqlString(fullCsv)
                    + " (FORMAT CSV, HEADER)");
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("导出 CSV 失败: " + e.getMessage(), e);
        }

        try {
            byte[] full = Files.readAllBytes(fullCsv);
            List<byte[]> shardPayloads = splitIntoShards(full);
            List<ExportShard> shards = new ArrayList<>();
            MessageDigest aggregate = sha256Digest();
            for (int i = 0; i < shardPayloads.size(); i++) {
                String csvName = String.format("data-%06d.csv", i + 1);
                byte[] payload = shardPayloads.get(i);
                aggregate.update(payload);
                Path csvFile = exportDir.resolve(csvName);
                Files.write(csvFile, payload);
                String deliveredName = csvName;
                if (zip) {
                    String zipName = String.format("data-%06d.zip", i + 1);
                    zipSingle(csvFile, exportDir.resolve(zipName));
                    Files.delete(csvFile);
                    deliveredName = zipName;
                }
                Path delivered = exportDir.resolve(deliveredName);
                shards.add(new ExportShard(deliveredName, Files.size(delivered), sha256Of(delivered)));
            }
            String fieldNotes = writeFieldNotes(exportDir, parts);
            return new ExportResult(exportDir.toString(), shards, toHex(aggregate.digest()), fieldNotes);
        } catch (IOException e) {
            throw new IllegalStateException("写导出文件失败: " + exportDir, e);
        } finally {
            deleteQuietly(fullCsv);
        }
    }

    /** 导出 SELECT：原列 + 日期样列的 YYYYMMDD 伴随列（第 10 节导出约定）。 */
    private String exportSelect(Connection conn, String parquetList) throws SQLException {
        List<String> columns = columnNames(conn, "SELECT * FROM read_parquet(" + parquetList + ")");
        Set<String> dateCols = new HashSet<>(dateLikeColumns(conn, "read_parquet(" + parquetList + ")"));
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            String col = columns.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ident(col));
            if (dateCols.contains(col)) {
                sb.append(", strftime(try_cast(").append(ident(col)).append(" AS DATE), '%Y%m%d') AS ")
                        .append(ident(col + "_yyyymmdd"));
            }
        }
        return sb.append(" FROM read_parquet(").append(parquetList).append(")").toString();
    }

    /** 字段说明附表（第 10 节「字段名→中文→单位」）：中文名与单位待 014-data-03 登记后补齐。 */
    private String writeFieldNotes(Path exportDir, List<Path> parts) throws IOException {
        List<String> columns;
        try (Connection conn = duckdb()) {
            columns = columnNames(conn, "SELECT * FROM read_parquet(" + sqlString(parts.get(0)) + ")");
        } catch (SQLException e) {
            throw new IllegalStateException("读取列名失败", e);
        }
        StringBuilder sb = new StringBuilder("field_name,chinese_name,unit,note\n");
        for (String col : columns) {
            sb.append(csvField(col)).append(',')
              .append(csvField("")).append(',')
              .append(csvField("")).append(',')
              .append(csvField("中文名与单位由生产侧在 014-data-03 登记"))
              .append('\n');
        }
        String fileName = "field_notes.csv";
        Files.write(exportDir.resolve(fileName), sb.toString().getBytes(StandardCharsets.UTF_8));
        return fileName;
    }

    /**
     * 分片：按 RFC 4180 引号感知的 LF 行边界贪心装包，单文件不超过 {@link #EXPORT_SINGLE_FILE_MAX_BYTES}
     * （单行本身超上限时独占一片）；每片重复表头，保证片片是可独立解析的完整 CSV。
     */
    private List<byte[]> splitIntoShards(byte[] full) {
        List<int[]> lines = lineBoundaries(full);
        int[] header = lines.get(0);
        int headerLen = header[1] - header[0];
        List<byte[]> shards = new ArrayList<>();
        ByteArrayOutputStream cur = new ByteArrayOutputStream();
        appendBytes(cur, full, header[0], headerLen);
        for (int i = 1; i < lines.size(); i++) {
            int[] line = lines.get(i);
            int lineLen = line[1] - line[0];
            if (cur.size() > headerLen && cur.size() + lineLen > EXPORT_SINGLE_FILE_MAX_BYTES) {
                shards.add(cur.toByteArray());
                cur = new ByteArrayOutputStream();
                appendBytes(cur, full, header[0], headerLen);
            }
            appendBytes(cur, full, line[0], lineLen);
        }
        shards.add(cur.toByteArray());
        return shards;
    }

    private static void appendBytes(ByteArrayOutputStream out, byte[] data, int off, int len) {
        out.write(data, off, len);
    }

    /** LF 行边界扫描；引号内的 LF 不算行尾（RFC 4180 双引号转义按连续两个引号处理）。 */
    private static List<int[]> lineBoundaries(byte[] full) {
        List<int[]> lines = new ArrayList<>();
        boolean inQuotes = false;
        int start = 0;
        for (int i = 0; i < full.length; i++) {
            byte b = full[i];
            if (b == '"') {
                inQuotes = !inQuotes;
            } else if (b == '\n' && !inQuotes) {
                lines.add(new int[]{start, i + 1});
                start = i + 1;
            }
        }
        if (start < full.length) {
            lines.add(new int[]{start, full.length});
        }
        return lines;
    }

    private static void zipSingle(Path source, Path target) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(target))) {
            // 固定条目时间戳：同内容重复导出产出字节一致的 zip，便于对账
            ZipEntry entry = new ZipEntry(source.getFileName().toString());
            entry.setTime(0);
            zos.putNextEntry(entry);
            Files.copy(source, zos);
            zos.closeEntry();
        }
    }

    private Path versionDir(String contentPath) {
        return Path.of(dataRoot, contentPath);
    }

    private List<Path> listParquetFiles(Path versionDir) {
        if (!Files.isDirectory(versionDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(versionDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".parquet"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("列出版本目录 part 失败: " + versionDir, e);
        }
    }

    private static Connection duckdb() throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:");
    }

    private static String readCsv(Path csvTemp) {
        return "read_csv(" + sqlString(csvTemp) + ", header=true, all_varchar=true)";
    }

    private static String parquetList(List<Path> parts) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(sqlString(parts.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String sqlString(Path p) {
        return "'" + p.toString().replace("'", "''") + "'";
    }

    private static String ident(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String csvField(String v) {
        String escaped = v.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private static void execUpdate(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static long queryLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String queryString(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<String> columnNames(Connection conn, String fromSql) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("DESCRIBE " + fromSql)) {
            while (rs.next()) {
                names.add(rs.getString("column_name"));
            }
        }
        return names;
    }

    /** 日期样列：抽样 100 行，非空值全部形如 YYYY-MM-DD 且至少一个非空的列。parquetSource 是 read_parquet(...) 表达式。 */
    private List<String> dateLikeColumns(Connection conn, String parquetSource) throws SQLException {
        String from = parquetSource;
        List<String> columns = columnNames(conn, "SELECT * FROM " + from);
        List<List<String>> sample = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            sample.add(new ArrayList<>());
        }
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT * FROM " + from + " LIMIT 100")) {
            while (rs.next()) {
                for (int i = 0; i < columns.size(); i++) {
                    sample.get(i).add(rs.getString(i + 1));
                }
            }
        }
        List<String> dateCols = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            boolean anyNonNull = false;
            boolean allMatch = true;
            for (String v : sample.get(i)) {
                if (v == null) {
                    continue;
                }
                anyNonNull = true;
                if (!DATE_PATTERN.matcher(v).matches()) {
                    allMatch = false;
                    break;
                }
            }
            if (anyNonNull && allMatch) {
                dateCols.add(columns.get(i));
            }
        }
        return dateCols;
    }

    private static Path writeTempCsv(byte[] content) {
        try {
            Path tmp = Files.createTempFile("df-csv-in-", ".csv");
            Files.write(tmp, content);
            return tmp;
        } catch (IOException e) {
            throw new IllegalStateException("写临时 CSV 失败", e);
        }
    }

    private static void prepareCleanDir(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        Files.createDirectories(dir);
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程
            }
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void digestPart(MessageDigest digest, Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读 part 计算校验和失败: " + file, e);
        }
    }

    private static String sha256Of(Path file) {
        MessageDigest digest = sha256Digest();
        digestPart(digest, file);
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
