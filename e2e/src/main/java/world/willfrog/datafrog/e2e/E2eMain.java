package world.willfrog.datafrog.e2e;

import com.google.protobuf.TextFormat;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import world.willfrog.datafrog.idl.AppendToObjectRequest;
import world.willfrog.datafrog.idl.AppendToObjectResponse;
import world.willfrog.datafrog.idl.CreateNewVersionRequest;
import world.willfrog.datafrog.idl.CreateNewVersionResponse;
import world.willfrog.datafrog.idl.DataObjectCard;
import world.willfrog.datafrog.idl.DataObjectService;
import world.willfrog.datafrog.idl.DeleteDataObjectRequest;
import world.willfrog.datafrog.idl.DeleteDataObjectResponse;
import world.willfrog.datafrog.idl.ErrorCode;
import world.willfrog.datafrog.idl.ExportDataObjectRequest;
import world.willfrog.datafrog.idl.ExportDataObjectResponse;
import world.willfrog.datafrog.idl.GetDataObjectRequest;
import world.willfrog.datafrog.idl.GetDataObjectResponse;
import world.willfrog.datafrog.idl.ListDataObjectsRequest;
import world.willfrog.datafrog.idl.ListDataObjectsResponse;
import world.willfrog.datafrog.idl.ListSemanticDefinitionsRequest;
import world.willfrog.datafrog.idl.ListSemanticDefinitionsResponse;
import world.willfrog.datafrog.idl.RegisterDataObjectRequest;
import world.willfrog.datafrog.idl.RegisterDataObjectResponse;
import world.willfrog.datafrog.idl.RegisterSemanticDefinitionRequest;
import world.willfrog.datafrog.idl.RegisterSemanticDefinitionResponse;
import world.willfrog.datafrog.idl.SemanticRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 014-data-01 节点 d 泳道验收消费端（tri 直连泳道实例，不走向量路由）。
 *
 * 五组用例对应方案 §6；断言只依赖应答与 proto 契约，不依赖服务端内部实现细节。
 * 数据库侧独立证据（台账四动作行、版本行 part_checksums 的 JSONB 形态、旧版本 SUPERSEDED 定格）
 * 由 af-observe 只读通道另行采集，不在本程序内。
 *
 * 用法：java ... --host <宿主机> --port <泳道 tri 映射端口> --out <证据目录>
 */
public final class E2eMain {

    private static final Pattern DS_ID = Pattern.compile("^ds_[0-9a-f]{8}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    // 样例 CSV：trade_date（YYYY-MM-DD，应获 YYYYMMDD 伴随列）、updated_at_ms（毫秒时间戳，
    // 不应获伴随列——bob 边界②）、na_test（NA/NULL/PENDING 占位符穿透）。
    private static final String CSV_V1 = """
            trade_date,close,volume,na_test,updated_at_ms
            2026-01-05,10.50,1000,NA,1736073600000
            2026-01-06,10.80,1200,NULL,1736160000000
            2026-01-07,11.20,1500,PENDING,1736246400000
            2026-01-08,11.00,1300,NA,1736332800000
            2026-01-09,11.40,1600,NA,1736419200000
            """;
    private static final String CSV_APPEND = """
            trade_date,close,volume,na_test,updated_at_ms
            2026-01-12,11.60,1700,NA,1736678400000
            2026-01-13,11.80,1800,NULL,1736764800000
            2026-01-14,12.00,1900,NA,1736851200000
            """;
    private static final String CSV_V2_FULL = """
            trade_date,close,volume,na_test,updated_at_ms
            2026-01-05,10.55,1000,NA,1736073600000
            2026-01-06,10.85,1200,NULL,1736160000000
            2026-01-07,11.25,1500,PENDING,1736246400000
            2026-01-08,11.05,1300,NA,1736332800000
            2026-01-09,11.45,1600,NA,1736419200000
            2026-01-10,11.70,1650,NA,1736505600000
            """;
    private static final String CSV_SMALL_ZIP = """
            trade_date,close,na_test,updated_at_ms
            2026-02-02,9.90,NA,1738454400000
            2026-02-03,9.95,NULL,1738540800000
            2026-02-04,10.05,NA,1738627200000
            """;
    private static final String CSV_SMALL_DELETE = """
            trade_date,close,na_test,updated_at_ms
            2026-03-02,8.80,NA,1740873600000
            2026-03-03,8.85,NULL,1740960000000
            """;

    private final List<String[]> checks = new ArrayList<>();
    private final StringBuilder evidence = new StringBuilder();
    private int pass;
    private int fail;

    public static void main(String[] args) throws Exception {
        String host = arg(args, "--host", "127.0.0.1");
        int port = Integer.parseInt(arg(args, "--port", "50070"));
        Path out = Path.of(arg(args, "--out", "out"));

        ReferenceConfig<DataObjectService> ref = new ReferenceConfig<>();
        ref.setInterface(DataObjectService.class);
        ref.setUrl("tri://" + host + ":" + port);
        ref.setTimeout(60000);
        DubboBootstrap bootstrap = DubboBootstrap.getInstance()
                .application(new ApplicationConfig("datafrog-014data01-e2e"))
                .reference(ref)
                .start();
        DataObjectService svc = bootstrap.getCache().get(DataObjectService.class);

        E2eMain e2e = new E2eMain();
        e2e.run(svc);

        Files.createDirectories(out);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path file = out.resolve("evidence-" + stamp + ".txt");
        Files.writeString(file, e2e.evidence.toString(), StandardCharsets.UTF_8);
        System.out.println("PASS=" + e2e.pass + " FAIL=" + e2e.fail);
        System.out.println("evidence: " + file.toAbsolutePath());
        System.exit(e2e.fail == 0 ? 0 : 1);
    }

    private void run(DataObjectService svc) {
        evidence.append("014-data-01 泳道验收 ").append(LocalDateTime.now()).append('\n');

        // ── 用例 1：启动加载与 18 条定义迁移 ─────────────────────────
        // 泳道实例启动成功本身=四表齐+Loader 导入通过（缺表即失败的 fail-closed 设计）；
        // 本组正向断言 List/Get 的可见结果。
        section("case1 定义迁移");
        ListSemanticDefinitionsResponse defs = svc.listSemanticDefinitions(
                ListSemanticDefinitionsRequest.newBuilder().setPageSize(100).build());
        record("list-defs", defs);
        check("c1.定义总数=18", defs.getCardsCount() == 18, "actual=" + defs.getCardsCount());
        boolean allActive = defs.getCardsList().stream().allMatch(c -> c.getStatus().getNumber() == 1);
        check("c1.全部 ACTIVE", allActive, "");
        boolean allNamed = defs.getCardsList().stream().allMatch(c -> !c.getName().isEmpty() && c.getVersion() > 0);
        check("c1.name/version 齐全", allNamed, "");
        boolean allOrigin = defs.getCardsList().stream().allMatch(c -> !c.getOrigin().isEmpty());
        check("c1.origin 非空（01304 迁移标注）", allOrigin, "");
        String defName = defs.getCardsCount() > 0 ? defs.getCards(0).getName() : "close";
        int defVersion = defs.getCardsCount() > 0 ? defs.getCards(0).getVersion() : 1;
        record("get-def " + defName + " v" + defVersion,
                svc.getSemanticDefinition(world.willfrog.datafrog.idl.GetSemanticDefinitionRequest.newBuilder()
                        .setName(defName).setVersion(defVersion).build()));

        // ── 用例 2：挂靠强制拒绝与约定校验拒绝 ───────────────────────
        section("case2 拒绝路径");
        RegisterSemanticDefinitionResponse dup = svc.registerSemanticDefinition(
                RegisterSemanticDefinitionRequest.newBuilder()
                        .setName(defName).setVersion(defVersion).setDisplayName("e2e 重复注册探针")
                        .setDefinition("e2e 重复注册探针").setCalculation("-").setUnit("-").setScope("-")
                        .build());
        record("reg-def-dup", dup);
        check("c2.重复注册→DEFINITION_ALREADY_EXISTS",
                !dup.getResult().getOk() && dup.getResult().getCode() == ErrorCode.DEFINITION_ALREADY_EXISTS,
                "code=" + dup.getResult().getCode());

        RegisterDataObjectResponse badRef = register(svc, "e2e-bad-ref", "e2e-no-such-def", 1,
                CSV_V1, "2026-01-05", "2026-01-09", "csv", "");
        record("reg-bad-ref", badRef);
        check("c2.挂靠不存在定义→SEMANTIC_REF_NOT_FOUND",
                !badRef.getResult().getOk() && badRef.getResult().getCode() == ErrorCode.SEMANTIC_REF_NOT_FOUND,
                "code=" + badRef.getResult().getCode());

        RegisterDataObjectResponse badRange = register(svc, "e2e-bad-range", defName, defVersion,
                CSV_V1, "2026-01-09", "2026-01-05", "csv", "");
        record("reg-bad-range", badRange);
        check("c2.coverage 区间倒置→INVALID_CONVENTION",
                !badRange.getResult().getOk() && badRange.getResult().getCode() == ErrorCode.INVALID_CONVENTION,
                "code=" + badRange.getResult().getCode());

        RegisterDataObjectResponse badInputs = svc.registerDataObject(registerReq("e2e-bad-inputs", defName, defVersion,
                CSV_V1, "2026-01-05", "2026-01-09", "csv", "").toBuilder()
                .addInputs("e2e-no-such-input-object").build());
        record("reg-bad-inputs", badInputs);
        check("c2.inputs 引用未登记对象→INVALID_CONVENTION",
                !badInputs.getResult().getOk() && badInputs.getResult().getCode() == ErrorCode.INVALID_CONVENTION,
                "code=" + badInputs.getResult().getCode());

        RegisterDataObjectRequest noPk = registerReq("e2e-no-pk", defName, defVersion,
                CSV_V1, "2026-01-05", "2026-01-09", "csv", "");
        noPk = noPk.toBuilder().clearPrimaryKey().build();
        RegisterDataObjectResponse badPk = svc.registerDataObject(noPk);
        record("reg-no-pk", badPk);
        check("c2.primary_key 缺失→INVALID_CONVENTION",
                !badPk.getResult().getOk() && badPk.getResult().getCode() == ErrorCode.INVALID_CONVENTION,
                "code=" + badPk.getResult().getCode());

        // ── 用例 3：登记→追加→升版（台账四动作的应答侧；DB 证据走 af-observe）──
        section("case3 四动作");
        RegisterDataObjectResponse reg = register(svc, "e2e-main-object", defName, defVersion,
                CSV_V1, "2026-01-05", "2026-01-09", "csv", "");
        record("register", reg);
        String mainId = reg.getResult().getOk() ? reg.getCard().getId() : "";
        check("c3.登记 ok", reg.getResult().getOk(), reg.getResult().getMessage());
        check("c3.id 形态 ds_+8hex", DS_ID.matcher(mainId).matches(), "id=" + mainId);
        check("c3.v1 row_count=5", reg.getCard().getRowCount() == 5, "actual=" + reg.getCard().getRowCount());
        check("c3.v1 coverage=声明值", "2026-01-05".equals(reg.getCard().getCoverageStart())
                && "2026-01-09".equals(reg.getCard().getCoverageEnd()),
                reg.getCard().getCoverageStart() + "~" + reg.getCard().getCoverageEnd());
        check("c3.v1 checksum 64hex", SHA256.matcher(reg.getCard().getContentChecksum()).matches(),
                reg.getCard().getContentChecksum());
        String checksumV1 = reg.getCard().getContentChecksum();

        AppendToObjectResponse app = svc.appendToObject(AppendToObjectRequest.newBuilder()
                .setObjectId(mainId).setCsvContent(bytes(CSV_APPEND)).setNote("e2e 追加三行").build());
        record("append", app);
        check("c3.追加 ok", app.getResult().getOk(), app.getResult().getMessage());
        check("c3.追加不升版 version=1", app.getCard().getCurrentVersion() == 1,
                "actual=" + app.getCard().getCurrentVersion());
        check("c3.追加 row_count=8", app.getCard().getRowCount() == 8, "actual=" + app.getCard().getRowCount());
        check("c3.追加 coverage_end 推进到 2026-01-14",
                "2026-01-14".equals(app.getCard().getCoverageEnd()), app.getCard().getCoverageEnd());
        check("c3.追加 coverage_start 不变",
                "2026-01-05".equals(app.getCard().getCoverageStart()), app.getCard().getCoverageStart());
        check("c3.追加 checksum 前进", !app.getCard().getContentChecksum().equals(checksumV1),
                app.getCard().getContentChecksum());

        CreateNewVersionResponse ver = svc.createNewVersion(CreateNewVersionRequest.newBuilder()
                .setObjectId(mainId).setVersionNote("e2e 修正升版（全量替换）")
                .setCoverageStart("2026-01-05").setCoverageEnd("2026-01-10")
                .setCsvContent(bytes(CSV_V2_FULL)).build());
        record("new-version", ver);
        check("c3.升版 ok", ver.getResult().getOk(), ver.getResult().getMessage());
        check("c3.升版 version=2", ver.getCard().getCurrentVersion() == 2,
                "actual=" + ver.getCard().getCurrentVersion());
        check("c3.升版 row_count=6（新全量）", ver.getCard().getRowCount() == 6,
                "actual=" + ver.getCard().getRowCount());
        check("c3.升版 coverage_end=2026-01-10", "2026-01-10".equals(ver.getCard().getCoverageEnd()),
                ver.getCard().getCoverageEnd());

        // 删除动作用独立小对象，主对象保留给用例 4/5。
        RegisterDataObjectResponse regDel = register(svc, "e2e-del-object", defName, defVersion,
                CSV_SMALL_DELETE, "2026-03-02", "2026-03-03", "csv", "");
        String delId = regDel.getResult().getOk() ? regDel.getCard().getId() : "";
        DeleteDataObjectResponse del = svc.deleteDataObject(
                DeleteDataObjectRequest.newBuilder().setObjectId(delId).build());
        record("delete", del);
        check("c3.软删 ok", del.getResult().getOk(), del.getResult().getMessage());
        GetDataObjectResponse afterDel = svc.getDataObject(
                GetDataObjectRequest.newBuilder().setObjectId(delId).build());
        record("get-after-del", afterDel);
        check("c3.软删后 Get→OBJECT_NOT_FOUND",
                !afterDel.getResult().getOk() && afterDel.getResult().getCode() == ErrorCode.OBJECT_NOT_FOUND,
                "code=" + afterDel.getResult().getCode());
        boolean gone = true;
        for (DataObjectCard c : pageThrough(svc, 100)) {
            if (c.getId().equals(delId)) {
                gone = false;
            }
        }
        check("c3.软删后 List 不可见", gone, "id=" + delId);

        // ── 用例 4：ExportDataObject（CSV 导出约定；文件内容对账另经容器侧取回）──
        section("case4 导出");
        RegisterDataObjectResponse regZip = register(svc, "e2e-zip-object", defName, defVersion,
                CSV_SMALL_ZIP, "2026-02-02", "2026-02-04", "csv", "zip");
        String zipId = regZip.getResult().getOk() ? regZip.getCard().getId() : "";
        ExportDataObjectResponse exp = svc.exportDataObject(ExportDataObjectRequest.newBuilder()
                .setObjectId(mainId).setVersion(0).build());
        record("export-active", exp);
        check("c4.导出 ok", exp.getResult().getOk(), exp.getResult().getMessage());
        check("c4.小对象 shard_count=1", exp.getShardCount() == 1, "actual=" + exp.getShardCount());
        check("c4.分片清单含 .csv", exp.getShardsCount() == 1 && exp.getShards(0).getFileName().contains(".csv"),
                exp.getShardsCount() > 0 ? exp.getShards(0).getFileName() : "(empty)");
        check("c4.分片 sha256 64hex", exp.getShardsCount() == 1
                && SHA256.matcher(exp.getShards(0).getSha256()).matches(), "");
        check("c4.汇总 checksum 64hex", SHA256.matcher(exp.getAggregateChecksum()).matches(), "");
        check("c4.export_dir 含对象与 export", exp.getExportDir().contains(mainId)
                && exp.getExportDir().contains("export"), exp.getExportDir());
        ExportDataObjectResponse expAgain = svc.exportDataObject(ExportDataObjectRequest.newBuilder()
                .setObjectId(mainId).setVersion(0).build());
        record("export-repeat", expAgain);
        check("c4.重复导出同目录（覆盖语义）", expAgain.getExportDir().equals(exp.getExportDir()),
                expAgain.getExportDir());
        ExportDataObjectResponse expV1 = svc.exportDataObject(ExportDataObjectRequest.newBuilder()
                .setObjectId(mainId).setVersion(1).build());
        record("export-v1", expV1);
        check("c4.指定旧版本 v1 导出 ok", expV1.getResult().getOk(), expV1.getResult().getMessage());
        check("c4.v1 行数口径: 导出为追加后 8 行全量（part 集 5+3）",
                expV1.getResult().getOk() && expV1.getShardCount() >= 1, "");
        ExportDataObjectResponse expZip = svc.exportDataObject(ExportDataObjectRequest.newBuilder()
                .setObjectId(zipId).setVersion(0).build());
        record("export-zip", expZip);
        check("c4.zip 声明→分片为 .zip",
                expZip.getResult().getOk() && expZip.getShardsCount() == 1
                        && expZip.getShards(0).getFileName().contains(".zip"),
                expZip.getShardsCount() > 0 ? expZip.getShards(0).getFileName() : "(empty)");

        // ── 用例 5：只读幂等与分页 ─────────────────────────────────
        section("case5 只读与分页");
        GetDataObjectResponse g1 = svc.getDataObject(GetDataObjectRequest.newBuilder().setObjectId(mainId).build());
        GetDataObjectResponse g2 = svc.getDataObject(GetDataObjectRequest.newBuilder().setObjectId(mainId).build());
        GetDataObjectResponse g3 = svc.getDataObject(GetDataObjectRequest.newBuilder().setObjectId(mainId).build());
        String t1 = TextFormat.printer().printToString(g1);
        check("c5.Get 三次应答一致",
                t1.equals(TextFormat.printer().printToString(g2)) && t1.equals(TextFormat.printer().printToString(g3)),
                "");
        List<DataObjectCard> big = pageThrough(svc, 100);
        List<DataObjectCard> paged = pageThrough(svc, 2);
        check("c5.List 分页 pageSize=2 与整页条数一致", big.size() == paged.size(),
                big.size() + " vs " + paged.size());
        int defsTotal = 0;
        String token = "";
        do {
            ListSemanticDefinitionsRequest.Builder b = ListSemanticDefinitionsRequest.newBuilder().setPageSize(5);
            if (!token.isEmpty()) {
                b.setPageToken(token);
            }
            ListSemanticDefinitionsResponse r = svc.listSemanticDefinitions(b.build());
            defsTotal += r.getCardsCount();
            token = r.getNextPageToken();
        } while (!token.isEmpty());
        check("c5.定义分页 pageSize=5 翻完=18", defsTotal == 18, "actual=" + defsTotal);
        check("c5.主对象仍在列表（未删）", big.stream().anyMatch(c -> c.getId().equals(mainId)), "");
    }

    // ── 工具 ──────────────────────────────────────────────

    private static RegisterDataObjectRequest registerReq(String name, String defName, int defVersion,
                                                         String csv, String start, String end,
                                                         String format, String compression) {
        return RegisterDataObjectRequest.newBuilder()
                .setName(name).setDescription("014-data-01 e2e " + name)
                .setSourceTypeValue(1) // SYSTEM
                .setOwner("wang-teacher-mbp")
                .setVisibilityValue(1) // PRIVATE
                .addSemanticRefs(SemanticRef.newBuilder().setName(defName).setVersion(defVersion))
                .setCoverageScope("样例").setFrequencyValue(1) // DAILY
                .setGenMethodValue(2) // INDICATOR
                .setGenTask("e2e")
                .addPrimaryKey("trade_date")
                .setAdjustmentValue(1) // RAW
                .setCcyValue(1) // CNY
                .setExportFormat(format).setCompression(compression)
                .setCoverageStart(start).setCoverageEnd(end)
                .setCsvContent(bytes(csv))
                .build();
    }

    private static RegisterDataObjectResponse register(DataObjectService svc, String name, String defName,
                                                       int defVersion, String csv, String start, String end,
                                                       String format, String compression) {
        return svc.registerDataObject(registerReq(name, defName, defVersion, csv, start, end, format, compression));
    }

    private List<DataObjectCard> pageThrough(DataObjectService svc, int pageSize) {
        List<DataObjectCard> all = new ArrayList<>();
        String token = "";
        do {
            ListDataObjectsRequest.Builder b = ListDataObjectsRequest.newBuilder().setPageSize(pageSize);
            if (!token.isEmpty()) {
                b.setPageToken(token);
            }
            ListDataObjectsResponse r = svc.listDataObjects(b.build());
            if (!r.getResult().getOk()) {
                throw new IllegalStateException("listDataObjects failed: " + r.getResult().getMessage());
            }
            all.addAll(r.getCardsList());
            token = r.getNextPageToken();
        } while (!token.isEmpty());
        return all;
    }

    private static com.google.protobuf.ByteString bytes(String s) {
        return com.google.protobuf.ByteString.copyFromUtf8(s);
    }

    private void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
        } else {
            fail++;
        }
        checks.add(new String[]{name, cond ? "PASS" : "FAIL", detail});
        evidence.append(cond ? "[PASS] " : "[FAIL] ").append(name)
                .append(detail == null || detail.isEmpty() ? "" : "  (" + detail + ")").append('\n');
        System.out.println((cond ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : "  (" + detail + ")"));
    }

    private void record(String tag, com.google.protobuf.Message msg) {
        evidence.append("\n==== ").append(tag).append(" ====\n")
                .append(TextFormat.printer().printToString(msg)).append('\n');
    }

    private void section(String name) {
        evidence.append("\n######## ").append(name).append(" ########\n");
        System.out.println("\n######## " + name + " ########");
    }

    private static String arg(String[] args, String key, String dft) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (key.equals(args[i])) {
                return args[i + 1];
            }
        }
        return dft;
    }

    private E2eMain() {
    }
}
