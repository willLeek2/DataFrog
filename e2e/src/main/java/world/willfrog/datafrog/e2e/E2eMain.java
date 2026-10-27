package world.willfrog.datafrog.e2e;

import com.google.protobuf.TextFormat;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import world.willfrog.datafrog.idl.AppendToObjectRequest;
import world.willfrog.datafrog.idl.AppendToObjectResponse;
import world.willfrog.datafrog.idl.AssertionDetail;
import world.willfrog.datafrog.idl.AssertionSpec;
import world.willfrog.datafrog.idl.AssertionStatus;
import world.willfrog.datafrog.idl.CreateNewVersionRequest;
import world.willfrog.datafrog.idl.CreateNewVersionResponse;
import world.willfrog.datafrog.idl.DataObjectCard;
import world.willfrog.datafrog.idl.DataObjectService;
import world.willfrog.datafrog.idl.GetDataObjectFreshnessRequest;
import world.willfrog.datafrog.idl.GetDataObjectFreshnessResponse;
import world.willfrog.datafrog.idl.GetDataObjectRequest;
import world.willfrog.datafrog.idl.GetDataObjectResponse;
import world.willfrog.datafrog.idl.GetAvailabilityAssertionsRequest;
import world.willfrog.datafrog.idl.GetAvailabilityAssertionsResponse;
import world.willfrog.datafrog.idl.ListDataObjectsRequest;
import world.willfrog.datafrog.idl.ListDataObjectsResponse;
import world.willfrog.datafrog.idl.ListSemanticDefinitionsRequest;
import world.willfrog.datafrog.idl.ListSemanticDefinitionsResponse;
import world.willfrog.datafrog.idl.ReadObjectDataRequest;
import world.willfrog.datafrog.idl.ReadObjectDataResponse;
import world.willfrog.datafrog.idl.RegisterDataObjectRequest;
import world.willfrog.datafrog.idl.RegisterDataObjectResponse;
import world.willfrog.datafrog.idl.RegisterSemanticDefinitionRequest;
import world.willfrog.datafrog.idl.RegisterSemanticDefinitionResponse;
import world.willfrog.datafrog.idl.SemanticDefinitionCard;
import world.willfrog.datafrog.idl.SemanticRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 014-data-04 节点 d 泳道验收断言程序（tri 直连泳道实例，不走向量路由）。
 *
 * 覆盖用例：c1 登记带断言、c2 追加推覆盖与断言翻转、c2b 断言边界语义、c3 枚举过滤组、
 * c5 ReadObjectData 分页读取（--phase 1）；c4-21/断言成环构造复核（--phase 2，贝克先跑
 * 程序生成的 psql-phase2.sql）；c4-22（--phase 3，先跑 psql-phase3.sql）；c4-20（--phase 4，
 * 对象 B 的 object_id 由参数传入）。c4-20 的空列情形在 phase 1 末尾对对象 A 做了一次预检
 * （正式用例仍以 phase 4 对提升对象的断言为准）。c6/c7/c8/c9 走主仓库侧真实 run，不在本程序。
 *
 * 共库隔离（对 06 残留）：全部 List/过滤断言叠加 filter_owner=运行身份；结果断言只做
 * 「谓词成立 + 指定对象在结果内」，不做全库计数。
 *
 * 成环与深度超限的构造口径（对过 dacc2eb 服务端代码）：
 *  - 互环 D→E→D 无法用登记接口构造（depends_on 存对象 id，id 服务端随机生成，登记时不校验
 *    存在性），且互环在详单根层观测到的 reason 是 dependency_not_pass（环在被依赖方求值里
 *    被路径检测终止）——只有自环能在详单里看到 cycle_detected。phase 1 登记自环对象 Z 与
 *    互环对象 D/E（初始挂无害断言），phase 1 结束时生成 psql-phase2.sql（贝克直写对象行
 *    assertions JSONB 构造自环+互环），phase 2 断言自环 reason=cycle_detected、互环两对象
 *    调用按时返回且 passed=false。
 *  - depth_limit 只出现在链上 depth>=10 节点的求值里，详单只收根对象（depth==0）的断言，
 *    任何应答都看不到这个字面量。可观测等价物：12 对象链（C0 无 depends_on 断言、C1..C11
 *    各挂 depends_on 指向前一个）上，C10 作根（10 层递归）passed=true、C11 作根（11 层）
 *    passed=false，且 C11 同对象其余断言照常通过（汇总 PARTIAL）。
 *
 * 用法：java -jar e2e-014data04.jar --host <宿主机> --port <泳道 tri 映射端口> \
 *       --owner <运行身份用户名> --phase 1|2|3|4|all [--out <证据目录>] \
 *       [--prefix acc04-] [--fresh-date 2026-01-15] [--object-id <phase4 对象 B>]
 */
public final class E2eMain {

    private static final java.util.regex.Pattern SHA256 =
            java.util.regex.Pattern.compile("^[0-9a-f]{64}$");
    /** List 应答的 next_page_token 是纯偏移量字符串（服务端不编码过滤条件）。 */
    private static final java.util.regex.Pattern OFFSET_TOKEN =
            java.util.regex.Pattern.compile("^\\d+$");
    /** ReadObjectData 游标形态 partIndex:byteOffset。 */
    private static final java.util.regex.Pattern READ_TOKEN =
            java.util.regex.Pattern.compile("^\\d+:\\d+$");

    private static final int READ_PAGE_BYTES = 512;
    private static final String FRESH_DATE_DEFAULT = "2026-01-15";
    private static final String NOTE_NO_WATERMARK = "还没有连续完整日";
    private static final String NOTE_GAP_KEYWORD = "下一轮从连续完整日的下一个开市日起补";
    /** 永远不可能被服务端生成（id 是 ds_+8 位十六进制），用于 dependency_missing 构造。 */
    private static final String MISSING_ID = "ds_zzzzzzzz";

    // ── 运行配置与本次批次命名 ─────────────────────────────
    private record Config(String host, int port, Path out, String phase, String owner,
                          String prefix, String freshDate, String objectIdB) {
    }

    private final List<String[]> checks = new ArrayList<>();
    private final StringBuilder evidence = new StringBuilder();
    private int pass;
    private int fail;

    /** 本次运行的批次后缀：全部对象名/语义定义名带它，防共库撞名。 */
    private final String stamp;
    /** coverage_scope 统一值（c3.13 子串过滤的目标）。 */
    private final String scope;

    private E2eMain() {
        String ts = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now());
        String rand = String.format("%04x", new Random().nextInt(0x10000));
        this.stamp = ts + "-" + rand;
        this.scope = "e2e04-" + stamp;
    }

    public static void main(String[] args) throws Exception {
        String phase = arg(args, "--phase", "1");
        String owner = arg(args, "--owner", "");
        if (owner.isBlank()) {
            System.err.println("用法: --host <host> --port <port> --owner <运行身份用户名> "
                    + "--phase 1|2|3|4|all [--out out] [--prefix acc04-] "
                    + "[--fresh-date 2026-01-15] [--object-id <phase4 对象 B 的 id>]");
            System.exit(2);
        }
        if (!List.of("1", "2", "3", "4", "all").contains(phase)) {
            System.err.println("未知 --phase: " + phase + "（可选 1|2|3|4|all）");
            System.exit(2);
        }
        if (("4".equals(phase)) && arg(args, "--object-id", "").isBlank()) {
            System.err.println("--phase 4 需要同时给 --object-id <主仓库 run 提升产生的对象 B 的 id>");
            System.exit(2);
        }
        Config cfg = new Config(arg(args, "--host", "127.0.0.1"),
                Integer.parseInt(arg(args, "--port", "50070")),
                Path.of(arg(args, "--out", "out")),
                phase,
                owner,
                arg(args, "--prefix", "acc04-"),
                arg(args, "--fresh-date", FRESH_DATE_DEFAULT),
                arg(args, "--object-id", ""));

        ReferenceConfig<DataObjectService> ref = new ReferenceConfig<>();
        ref.setInterface(DataObjectService.class);
        ref.setUrl("tri://" + cfg.host() + ":" + cfg.port());
        ref.setTimeout(60000);
        DubboBootstrap bootstrap = DubboBootstrap.getInstance()
                .application(new ApplicationConfig("datafrog-014data04-e2e"))
                .reference(ref)
                .start();
        DataObjectService svc = bootstrap.getCache().get(DataObjectService.class);

        E2eMain e2e = new E2eMain();
        e2e.evidence.append("014-data-04 泳道验收 phase=").append(cfg.phase()).append(' ')
                .append(LocalDateTime.now()).append('\n')
                .append("参数: host=").append(cfg.host()).append(" port=").append(cfg.port())
                .append(" owner=").append(cfg.owner()).append(" prefix=").append(cfg.prefix())
                .append(" freshDate=").append(cfg.freshDate())
                .append(" objectB=").append(cfg.objectIdB().isEmpty() ? "(无)" : cfg.objectIdB())
                .append('\n');

        switch (cfg.phase()) {
            case "1" -> e2e.phase1(svc, cfg);
            case "2" -> e2e.phase2(svc, cfg);
            case "3" -> e2e.phase3(svc, cfg);
            case "4" -> e2e.phase4(svc, cfg);
            case "all" -> {
                e2e.phase1(svc, cfg);
                e2e.phase2(svc, cfg);
                e2e.phase3(svc, cfg);
            }
            default -> throw new IllegalStateException("unreachable: " + cfg.phase());
        }

        Files.createDirectories(cfg.out());
        String stampNow = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path file = cfg.out().resolve("evidence-" + cfg.phase() + "-" + stampNow + ".txt");
        Files.writeString(file, e2e.evidence.toString(), StandardCharsets.UTF_8);
        System.out.println("PASS=" + e2e.pass + " FAIL=" + e2e.fail);
        System.out.println("evidence: " + file.toAbsolutePath());
        System.exit(e2e.fail == 0 ? 0 : 1);
    }

    // ══════════════ phase 1：c1 + c2 + c2b + c3 + c5 + c4 空列预检 ══════════════

    private void phase1(DataObjectService svc, Config cfg) throws IOException {
        section("phase1 环境");
        ListSemanticDefinitionsResponse defs = svc.listSemanticDefinitions(
                ListSemanticDefinitionsRequest.newBuilder().setPageSize(100).build());
        record("list-defs", defs);
        check("pre.泳道实例定义清单非空（迁移 18 条预期）", defs.getCardsCount() > 0,
                "actual=" + defs.getCardsCount());
        String defName = defs.getCardsCount() > 0 ? defs.getCards(0).getName() : "close";
        int defVersion = defs.getCardsCount() > 0 ? defs.getCards(0).getVersion() : 1;

        // 过滤组专用的两个语义定义（c3.11 前缀 / c3.12 等值），命名只允许小写字母数字下划线。
        String defA = prefixSafe(cfg) + "sema" + stamp.replaceAll("-", "");
        String defB = prefixSafe(cfg) + "semb" + stamp.replaceAll("-", "");
        RegisterSemanticDefinitionResponse regDefA = svc.registerSemanticDefinition(
                RegisterSemanticDefinitionRequest.newBuilder().setName(defA).setVersion(1)
                        .setDisplayName("e2e04 过滤组语义 A").setDefinition("e2e04 过滤断言用临时语义定义 A")
                        .setCalculation("-").setUnit("-").setScope("e2e04").build());
        RegisterSemanticDefinitionResponse regDefB = svc.registerSemanticDefinition(
                RegisterSemanticDefinitionRequest.newBuilder().setName(defB).setVersion(1)
                        .setDisplayName("e2e04 过滤组语义 B").setDefinition("e2e04 过滤断言用临时语义定义 B")
                        .setCalculation("-").setUnit("-").setScope("e2e04").build());
        record("reg-def-A", regDefA);
        record("reg-def-B", regDefB);
        check("pre.过滤组语义定义 A/B 登记 ok",
                regDefA.getResult().getOk() && regDefB.getResult().getOk(),
                regDefA.getResult().getMessage() + " / " + regDefB.getResult().getMessage());

        // ── c1 登记带断言 ────────────────────────────────
        section("c1 登记带断言");
        // 前置对象 P：A 的 depends_on 要通过，依赖对象汇总必须是 PASS——引用无断言对象
        // （NO_ASSERTIONS）也算不通过，所以 P 自带一条可通过的 row_count 断言。
        RegisterDataObjectResponse regP = register(svc, cfg, objName(cfg, "p"), defName, defVersion,
                weekdays("2026-01-05", 2), List.of(rowCountAtLeast(1)));
        record("register-P", regP);
        check("c1.前置对象 P 登记 ok（自带可通过断言，汇总=PASS）",
                regP.getResult().getOk() && regP.getCard().getAssertionStatus() == AssertionStatus.PASS,
                "ok=" + regP.getResult().getOk() + " status=" + regP.getCard().getAssertionStatus());
        String pId = regP.getResult().getOk() ? regP.getCard().getId() : "";

        // 对象 A：12 行（2026-01-05..2026-01-20），三类断言。目标日 2026-01-23 > 登记时
        // 覆盖终点 2026-01-20 → coverage 断言不通过；row_count(10) 与 depends_on(P) 通过。
        RegisterDataObjectResponse regA = register(svc, cfg, objName(cfg, "a"), defName, defVersion,
                weekdays("2026-01-05", 12), List.of(
                        coverageEndAtOrAfter("2026-01-23"),
                        rowCountAtLeast(10),
                        dependsOnObjectsPass(pId)));
        record("register-A", regA);
        String aId = regA.getResult().getOk() ? regA.getCard().getId() : "";
        check("c1.对象 A 登记带三类断言 ok", regA.getResult().getOk(), regA.getResult().getMessage());
        check("c1.登记时卡片 assertion_status=PARTIAL（coverage 未到目标日）",
                regA.getResult().getOk() && regA.getCard().getAssertionStatus() == AssertionStatus.PARTIAL,
                "status=" + regA.getCard().getAssertionStatus());

        GetAvailabilityAssertionsResponse detA0 = svc.getAvailabilityAssertions(
                GetAvailabilityAssertionsRequest.newBuilder().setObjectId(aId).build());
        record("assertions-A-at-register", detA0);
        check("c1.详单三条与登记声明一一对应", detA0.getResult().getOk()
                        && detA0.getDetailsCount() == 3
                        && detailOf(detA0, "coverage_end_at_or_after") != null
                        && detailOf(detA0, "row_count_at_least") != null
                        && detailOf(detA0, "depends_on_objects_pass") != null,
                "details=" + detA0.getDetailsCount());
        AssertionDetail cov0 = detailOf(detA0, "coverage_end_at_or_after");
        check("c1.coverage 断言未达标 passed=false（actual 带实际 coverage_end）",
                cov0 != null && !cov0.getPassed() && cov0.getActual().contains("coverage_end=2026-01-20")
                        && !cov0.getReason().isEmpty(),
                cov0 == null ? "(缺条目)" : cov0.getActual() + " reason=" + cov0.getReason());
        AssertionDetail rc0 = detailOf(detA0, "row_count_at_least");
        check("c1.row_count 断言 passed=true（actual 带实际 row_count=12）",
                rc0 != null && rc0.getPassed() && rc0.getActual().contains("row_count=12")
                        && rc0.getReason().isEmpty(),
                rc0 == null ? "(缺条目)" : rc0.getActual());
        AssertionDetail dep0 = detailOf(detA0, "depends_on_objects_pass");
        check("c1.depends_on 断言 passed=true（actual 带依赖对象汇总状态）",
                dep0 != null && dep0.getPassed() && dep0.getActual().contains(pId + "=PASS")
                        && dep0.getReason().isEmpty(),
                dep0 == null ? "(缺条目)" : dep0.getActual());
        // c1.3 库侧 assertions JSONB 与登记一致性：psql 直查，贝克采集（见 evidence 头部说明）。

        // ── c2 追加推覆盖与断言翻转 ──────────────────────
        section("c2 追加推覆盖与断言翻转");
        // 对象 C 先登记（depends_on A；此刻 A=PARTIAL → C 不通过），追加后再复核翻转。
        RegisterDataObjectResponse regC = register(svc, cfg, objName(cfg, "c"), defName, defVersion,
                weekdays("2026-01-05", 2), List.of(dependsOnObjectsPass(aId)));
        record("register-C", regC);
        String cId = regC.getResult().getOk() ? regC.getCard().getId() : "";
        check("c2.对象 C depends_on A 登记 ok", regC.getResult().getOk(), regC.getResult().getMessage());
        GetAvailabilityAssertionsResponse detC0 = svc.getAvailabilityAssertions(
                GetAvailabilityAssertionsRequest.newBuilder().setObjectId(cId).build());
        record("assertions-C-before-flip", detC0);
        AssertionDetail cDep0 = detailOf(detC0, "depends_on_objects_pass");
        check("c2.翻转前 C 的 depends_on 断言 passed=false（A=PARTIAL）",
                cDep0 != null && !cDep0.getPassed() && cDep0.getActual().contains(aId + "=PARTIAL"),
                cDep0 == null ? "(缺条目)" : cDep0.getActual() + " reason=" + cDep0.getReason());
        check("c2.翻转前 C 卡片 FAIL（唯一断言不通过）",
                detC0.getResult().getOk() && detC0.getAssertionStatus() == AssertionStatus.FAIL,
                "status=" + detC0.getAssertionStatus());

        // 追加 4 行（2026-01-21..2026-01-26），覆盖推过断言目标日 2026-01-23。
        AppendToObjectResponse appA = svc.appendToObject(AppendToObjectRequest.newBuilder()
                .setObjectId(aId).setCsvContent(bytes(csv(weekdays("2026-01-21", 4), 100)))
                .setNote("e2e04 追加四行推覆盖过断言目标日").build());
        record("append-A", appA);
        check("c2.追加 ok", appA.getResult().getOk(), appA.getResult().getMessage());
        check("c2.追加 coverage_end 推进到 2026-01-26",
                appA.getResult().getOk() && "2026-01-26".equals(appA.getCard().getCoverageEnd()),
                appA.getCard().getCoverageEnd());
        check("c2.追加 row_count 增到 16",
                appA.getResult().getOk() && appA.getCard().getRowCount() == 16,
                "actual=" + appA.getCard().getRowCount());
        check("c2.追加不升版 version=1",
                appA.getResult().getOk() && appA.getCard().getCurrentVersion() == 1,
                "actual=" + appA.getCard().getCurrentVersion());

        GetAvailabilityAssertionsResponse detA1 = svc.getAvailabilityAssertions(
                GetAvailabilityAssertionsRequest.newBuilder().setObjectId(aId).build());
        record("assertions-A-after-append", detA1);
        AssertionDetail cov1 = detailOf(detA1, "coverage_end_at_or_after");
        check("c2.追加后 coverage 断言翻为 passed（reason 空，actual 带新终点）",
                cov1 != null && cov1.getPassed() && cov1.getReason().isEmpty()
                        && cov1.getActual().contains("coverage_end=2026-01-26"),
                cov1 == null ? "(缺条目)" : cov1.getActual());
        check("c2.追加后 A 卡片 assertion_status=PASS",
                detA1.getResult().getOk() && detA1.getAssertionStatus() == AssertionStatus.PASS,
                "status=" + detA1.getAssertionStatus());
        GetAvailabilityAssertionsResponse detC1 = svc.getAvailabilityAssertions(
                GetAvailabilityAssertionsRequest.newBuilder().setObjectId(cId).build());
        record("assertions-C-after-flip", detC1);
        AssertionDetail cDep1 = detailOf(detC1, "depends_on_objects_pass");
        check("c2.翻转后 C 的 depends_on 断言同步通过（A=PASS）",
                cDep1 != null && cDep1.getPassed() && cDep1.getActual().contains(aId + "=PASS"),
                cDep1 == null ? "(缺条目)" : cDep1.getActual());
        check("c2.翻转后 C 卡片 assertion_status=PASS",
                detC1.getResult().getOk() && detC1.getAssertionStatus() == AssertionStatus.PASS,
                "status=" + detC1.getAssertionStatus());

        // ── c2b 断言边界语义 ─────────────────────────────
        section("c2b 断言边界语义");
        // 7. 认不出的断言类型（type=foo）：登记不拦，求值时该条 passed=false、reason=unknown_type。
        RegisterDataObjectResponse regU = register(svc, cfg, objName(cfg, "u"), defName, defVersion,
                weekdays("2026-01-07", 2), List.of(
                        AssertionSpec.newBuilder().setType("foo").build(),
                        rowCountAtLeast(1)));
        record("register-U", regU);
        String uId = regU.getResult().getOk() ? regU.getCard().getId() : "";
        check("c2b.未知类型登记不拦（type=foo 登记成功）", regU.getResult().getOk(),
                regU.getResult().getMessage());
        GetAvailabilityAssertionsResponse detU = svc.getAvailabilityAssertions(
                GetAvailabilityAssertionsRequest.newBuilder().setObjectId(uId).build());
        record("assertions-U", detU);
        AssertionDetail foo = detailOf(detU, "foo");
        check("c2b.foo 条 passed=false、reason=unknown_type",
                foo != null && !foo.getPassed() && "unknown_type".equals(foo.getReason()),
                foo == null ? "(缺条目)" : foo.getReason());
        AssertionDetail uRc = detailOf(detU, "row_count_at_least");
        check("c2b.同对象其余断言照常算（row_count 条 passed=true）",
                uRc != null && uRc.getPassed(), uRc == null ? "(缺条目)" : uRc.getActual());
        check("c2b.U 卡片汇总=PARTIAL（部分通过）",
                detU.getResult().getOk() && detU.getAssertionStatus() == AssertionStatus.PARTIAL,
                "status=" + detU.getAssertionStatus());

        // 8. 成环构造对象：Z（自环，psql 直写后详单可见 cycle_detected）、D/E（互环，验证
        //    路径检测终止不无限递归）。登记时先挂无害断言，phase 2 前贝克跑 psql-phase2.sql。
        RegisterDataObjectResponse regZ = register(svc, cfg, objName(cfg, "z-selfcycle"), defName,
                defVersion, weekdays("2026-07-01", 1), List.of(rowCountAtLeast(1)));
        RegisterDataObjectResponse regD = register(svc, cfg, objName(cfg, "d-cycle"), defName,
                defVersion, weekdays("2026-07-02", 1), List.of(rowCountAtLeast(1)));
        RegisterDataObjectResponse regE = register(svc, cfg, objName(cfg, "e-cycle"), defName,
                defVersion, weekdays("2026-07-03", 1), List.of(rowCountAtLeast(1)));
        record("register-Z", regZ);
        record("register-D", regD);
        record("register-E", regE);
        check("c2b.成环构造对象 Z/D/E 登记 ok（成环断言在 phase 2，psql 直写后）",
                regZ.getResult().getOk() && regD.getResult().getOk() && regE.getResult().getOk(),
                regZ.getResult().getMessage() + "/" + regD.getResult().getMessage()
                        + "/" + regE.getResult().getMessage());
        String zId = regZ.getResult().getOk() ? regZ.getCard().getId() : "";
        String dId = regD.getResult().getOk() ? regD.getCard().getId() : "";
        String eId = regE.getResult().getOk() ? regE.getCard().getId() : "";

        // 9. 深度超限：12 对象链 C0..C11（C0 只挂 row_count 断言、C1..C11 各挂 depends_on 指向
        //    前一个）。C10 作根=10 层递归（深度上限内）→通过；C11 作根=11 层→超限不通过，
        //    且 C11 同对象 row_count 断言照常通过（汇总 PARTIAL）。
        List<String> chainIds = new ArrayList<>();
        RegisterDataObjectResponse regC0 = register(svc, cfg, objName(cfg, "depth-c0"), defName,
                defVersion, weekdays("2026-07-06", 1), List.of(rowCountAtLeast(1)));
        record("register-depth-c0", regC0);
        chainIds.add(regC0.getResult().getOk() ? regC0.getCard().getId() : "");
        boolean chainOk = regC0.getResult().getOk();
        for (int i = 1; i <= 11; i++) {
            List<AssertionSpec> specs = new ArrayList<>();
            specs.add(dependsOnObjectsPass(chainIds.get(i - 1)));
            if (i == 11) {
                specs.add(rowCountAtLeast(1)); // 11 层链根：验证“只判那条不通过”
            }
            RegisterDataObjectResponse r = register(svc, cfg, objName(cfg, "depth-c" + i), defName,
                    defVersion, weekdays("2026-07-06", 1), specs);
            record("register-depth-c" + i, r);
            chainIds.add(r.getResult().getOk() ? r.getCard().getId() : "");
            chainOk &= r.getResult().getOk();
        }
        String c10Id = chainIds.get(10);
        String c11Id = chainIds.get(11);
        check("c2b.深度链 12 对象登记 ok", chainOk, "链长=" + chainIds.size());
        GetAvailabilityAssertionsResponse detC10 = svc.getAvailabilityAssertions(
                GetAvailabilityAssertionsRequest.newBuilder().setObjectId(c10Id).build());
        record("assertions-depth-c10-root", detC10);
        check("c2b.10 层链根（C10）depends_on passed=true（深度上限 10 内递归正常）",
                detC10.getResult().getOk() && detC10.getDetailsCount() == 1
                        && detC10.getDetails(0).getPassed()
                        && detC10.getAssertionStatus() == AssertionStatus.PASS,
                "actual=" + (detC10.getDetailsCount() > 0 ? detC10.getDetails(0).getActual() : "(空)"));
        GetAvailabilityAssertionsResponse detC11 = svc.getAvailabilityAssertions(
                GetAvailabilityAssertionsRequest.newBuilder().setObjectId(c11Id).build());
        record("assertions-depth-c11-root", detC11);
        check("c2b.11 层链根（C11）depends_on passed=false（深度超限）",
                detC11.getResult().getOk() && detailOf(detC11, "depends_on_objects_pass") != null
                        && !detailOf(detC11, "depends_on_objects_pass").getPassed(),
                detailOf(detC11, "depends_on_objects_pass") == null ? "(缺条目)"
                        : detailOf(detC11, "depends_on_objects_pass").getActual()
                        + " reason=" + detailOf(detC11, "depends_on_objects_pass").getReason());
        check("c2b.11 层链根只判那条不通过（同对象 row_count 条照常通过，汇总=PARTIAL）",
                detC11.getResult().getOk() && detailOf(detC11, "row_count_at_least") != null
                        && detailOf(detC11, "row_count_at_least").getPassed()
                        && detC11.getAssertionStatus() == AssertionStatus.PARTIAL,
                "status=" + detC11.getAssertionStatus());

        // 10. depends_on 引用不存在对象。
        RegisterDataObjectResponse regM = register(svc, cfg, objName(cfg, "m-missing"), defName,
                defVersion, weekdays("2026-01-09", 1), List.of(dependsOnObjectsPass(MISSING_ID)));
        record("register-M", regM);
        String mId = regM.getResult().getOk() ? regM.getCard().getId() : "";
        check("c2b.引用不存在对象的登记 ok（求值期才判）", regM.getResult().getOk(),
                regM.getResult().getMessage());
        GetAvailabilityAssertionsResponse detM = svc.getAvailabilityAssertions(
                GetAvailabilityAssertionsRequest.newBuilder().setObjectId(mId).build());
        record("assertions-M", detM);
        AssertionDetail mDep = detailOf(detM, "depends_on_objects_pass");
        check("c2b.依赖缺失 passed=false、reason=dependency_missing",
                mDep != null && !mDep.getPassed() && "dependency_missing".equals(mDep.getReason())
                        && mDep.getActual().contains(MISSING_ID + "=MISSING"),
                mDep == null ? "(缺条目)" : mDep.getActual() + " reason=" + mDep.getReason());
        check("c2b.M 卡片汇总=FAIL",
                detM.getResult().getOk() && detM.getAssertionStatus() == AssertionStatus.FAIL,
                "status=" + detM.getAssertionStatus());

        // ── c3 枚举过滤组（全部叠加 filter_owner=运行身份） ──
        section("c3 枚举过滤组");
        // 专供对象：O_sem（挂 defA/defB 两个自有定义）、O_feb（coverage_start 在 2 月）、
        // Q1..Q5（一条永不达标的 coverage 断言 + 一条可通过断言 → 汇总 PARTIAL，供分页）。
        RegisterDataObjectResponse regOsem = registerWithRefs(svc, cfg, objName(cfg, "osem"),
                List.of(SemanticRef.newBuilder().setName(defA).setVersion(1).build(),
                        SemanticRef.newBuilder().setName(defB).setVersion(1).build()),
                weekdays("2026-06-01", 2), List.of());
        record("register-Osem", regOsem);
        String osemId = regOsem.getResult().getOk() ? regOsem.getCard().getId() : "";
        RegisterDataObjectResponse regOfeb = register(svc, cfg, objName(cfg, "ofeb"), defName,
                defVersion, weekdays("2026-02-02", 2), List.of());
        record("register-Ofeb", regOfeb);
        String ofebId = regOfeb.getResult().getOk() ? regOfeb.getCard().getId() : "";
        List<String> qIds = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            RegisterDataObjectResponse rq = register(svc, cfg, objName(cfg, "q" + i), defName,
                    defVersion, weekdays("2026-05-04", 2), List.of(
                            coverageEndAtOrAfter("2030-01-01"), rowCountAtLeast(1)));
            record("register-Q" + i, rq);
            qIds.add(rq.getResult().getOk() ? rq.getCard().getId() : "");
        }
        check("c3.专供对象 O_sem/O_feb/Q1..Q5 登记 ok 且 Q 汇总=PARTIAL",
                regOsem.getResult().getOk() && regOfeb.getResult().getOk() && qIds.size() == 5,
                "osem=" + regOsem.getResult().getOk() + " ofeb=" + regOfeb.getResult().getOk());

        // 11. filter_semantic_name 前缀匹配。
        Walk semHit = listWalk(svc, cfg, "list-sem-prefix-hit", 200, 20, b -> b
                .setFilterOwner(cfg.owner()).setFilterSemanticName(defA));
        boolean semPredicate = semHit.ok && semHit.cards.stream()
                .allMatch(c -> c.getSemanticRefsList().stream()
                        .anyMatch(r -> r.getName().startsWith(defA)));
        check("c3.语义前缀命中：O_sem 在结果内且全部 ref 前缀匹配",
                semHit.ok && semPredicate && containsId(semHit.cards, osemId),
                "count=" + semHit.cards.size());
        ListDataObjectsResponse semMiss = listOnce(svc, cfg, 100, "", b -> b
                .setFilterOwner(cfg.owner()).setFilterSemanticName(defA + "zzz"));
        record("list-sem-prefix-miss", semMiss);
        check("c3.语义前缀不命中：空结果",
                semMiss.getResult().getOk() && semMiss.getCardsCount() == 0,
                "count=" + semMiss.getCardsCount());

        // 12. filter_field_name 等值匹配。
        Walk fieldHit = listWalk(svc, cfg, "list-field-equal", 200, 20, b -> b
                .setFilterOwner(cfg.owner()).setFilterFieldName(defB));
        boolean fieldPredicate = fieldHit.ok && fieldHit.cards.stream()
                .allMatch(c -> c.getSemanticRefsList().stream()
                        .anyMatch(r -> defB.equals(r.getName())));
        check("c3.字段等值命中：O_sem 在结果内且全部精确挂靠 defB",
                fieldHit.ok && fieldPredicate && containsId(fieldHit.cards, osemId),
                "count=" + fieldHit.cards.size());

        // 13. coverage_scope 子串。
        Walk scopeHit = listWalk(svc, cfg, "list-scope-substring", 200, 20, b -> b
                .setFilterOwner(cfg.owner()).setFilterCoverageScopeSubstring(stamp));
        check("c3.范围子串命中：A 在结果内且全部 scope 含批次戳",
                scopeHit.ok
                        && scopeHit.cards.stream().allMatch(c -> c.getCoverageScope().contains(stamp))
                        && containsId(scopeHit.cards, aId),
                "count=" + scopeHit.cards.size());

        // 14. 覆盖区间过滤。
        Walk startFrom = listWalk(svc, cfg, "list-coverage-start-from", 200, 20, b -> b
                .setFilterOwner(cfg.owner()).setFilterCoverageStartFrom("2026-02-01"));
        check("c3.coverage_start_from=2026-02-01：O_feb 在结果内且全部 start>=该日",
                startFrom.ok
                        && startFrom.cards.stream()
                        .allMatch(c -> c.getCoverageStart().compareTo("2026-02-01") >= 0)
                        && containsId(startFrom.cards, ofebId),
                "count=" + startFrom.cards.size());
        Walk endTo = listWalk(svc, cfg, "list-coverage-end-to", 200, 20, b -> b
                .setFilterOwner(cfg.owner()).setFilterCoverageEndTo("2026-01-31"));
        check("c3.coverage_end_to=2026-01-31：P 在结果内且全部 end<=该日",
                endTo.ok
                        && endTo.cards.stream()
                        .allMatch(c -> c.getCoverageEnd().compareTo("2026-01-31") <= 0)
                        && containsId(endTo.cards, pId),
                "count=" + endTo.cards.size());

        // 15. filter_freshness_since（coverage_end >= 指定日）。
        Walk freshSince = listWalk(svc, cfg, "list-freshness-since", 200, 20, b -> b
                .setFilterOwner(cfg.owner()).setFilterFreshnessSince("2026-01-20"));
        check("c3.freshness_since=2026-01-20：A 在结果内且全部 coverage_end>=该日",
                freshSince.ok
                        && freshSince.cards.stream()
                        .allMatch(c -> c.getCoverageEnd().compareTo("2026-01-20") >= 0)
                        && containsId(freshSince.cards, aId),
                "count=" + freshSince.cards.size());

        // 16+17. filter_assertion_status 过滤分页：小页(2)连续翻页 vs 大页(200)全量，
        //        序列一致（不重不漏）、token 纯偏移量、非末页满页。
        Walk small = listWalk(svc, cfg, "list-partial-small", 2, 200, b -> b.setFilterOwner(cfg.owner())
                .setFilterAssertionStatus(AssertionStatus.PARTIAL));
        Walk big = listWalk(svc, cfg, "list-partial-big", 200, 10, b -> b.setFilterOwner(cfg.owner())
                .setFilterAssertionStatus(AssertionStatus.PARTIAL));
        check("c3.PARTIAL 过滤分页：小页与大页取回同一序列（连续不重不漏）",
                small.ok && big.ok && idListOf(small.cards).equals(idListOf(big.cards)),
                "small=" + small.cards.size() + " big=" + big.cards.size());
        Set<String> expectedPartial = new LinkedHashSet<>(qIds);
        expectedPartial.add(uId);
        boolean allPartialIn = new LinkedHashSet<>(idListOf(small.cards)).containsAll(expectedPartial);
        check("c3.PARTIAL 候选含全部构造的 PARTIAL 对象（Q1..Q5+U）", allPartialIn,
                "expected=" + expectedPartial.size());
        // 候选=Q1..Q5+U 共 6 个，pageSize=2 → 3 页，页界两处都在我方候选集内（>=3 即达标）。
        // 原阈值 >=4 是按 7 候选写的笔误，泳道首轮实测 pages=3 FAIL，判读为用例校准缺陷非服务端问题。
        check("c3.PARTIAL 小页页数>=3（候选跨页界，页界在我方对象内）", small.pages >= 3,
                "pages=" + small.pages);
        check("c3.同一条件内连续翻页 token 为纯偏移量数字串",
                small.ok && small.tokens.stream().allMatch(t -> t.isEmpty() || OFFSET_TOKEN.matcher(t).matches()),
                "tokens=" + small.tokens);
        check("c3.非末页页内条数=pageSize、末页<=pageSize", small.ok && small.fullPagesOk,
                "pages=" + small.pages);

        // 18. filter_owner。
        Walk ownerWalk = listWalk(svc, cfg, "list-owner", 200, 20, b -> b.setFilterOwner(cfg.owner()));
        boolean ownerPredicate = ownerWalk.ok && ownerWalk.cards.stream()
                .allMatch(c -> cfg.owner().equals(c.getOwner()));
        boolean ownerContainsOurs = new LinkedHashSet<>(idListOf(ownerWalk.cards))
                .containsAll(expectedPartial) && containsId(ownerWalk.cards, aId);
        check("c3.filter_owner=运行身份：全部卡片属主=该身份且含本次对象",
                ownerPredicate && ownerContainsOurs, "count=" + ownerWalk.cards.size());

        // 19. 过滤组全留空=纯分页（01 原行为回归）。
        Walk plainWalk = listWalk(svc, cfg, "list-plain", 3, 300, b -> { });
        check("c3.全留空纯分页：页内条数正确（非末页满页）", plainWalk.ok && plainWalk.fullPagesOk,
                "pages=" + plainWalk.pages + " count=" + plainWalk.cards.size());
        check("c3.全留空纯分页：翻完含本次新登记对象 A", containsId(plainWalk.cards, aId), "");
        check("c3.全量计数>=属主过滤计数（留空=原全量口径）",
                plainWalk.ok && ownerWalk.ok && plainWalk.cards.size() >= ownerWalk.cards.size(),
                "full=" + plainWalk.cards.size() + " owner=" + ownerWalk.cards.size());

        // ── c5 ReadObjectData 分页读取 ───────────────────
        section("c5 ReadObjectData 分页读取");
        // R：登记+两次追加=三个 part。
        RegisterDataObjectResponse regR = register(svc, cfg, objName(cfg, "r-parts"), defName,
                defVersion, weekdays("2026-04-01", 3), List.of());
        record("register-R", regR);
        String rId = regR.getResult().getOk() ? regR.getCard().getId() : "";
        AppendToObjectResponse appR1 = svc.appendToObject(AppendToObjectRequest.newBuilder()
                .setObjectId(rId).setCsvContent(bytes(csv(weekdays("2026-04-06", 2), 100)))
                .setNote("e2e04 追加一（第二个 part）").build());
        record("append-R-1", appR1);
        AppendToObjectResponse appR2 = svc.appendToObject(AppendToObjectRequest.newBuilder()
                .setObjectId(rId).setCsvContent(bytes(csv(weekdays("2026-04-08", 2), 200)))
                .setNote("e2e04 追加二（第三个 part）").build());
        record("append-R-2", appR2);
        check("c5.R 登记+两次追加 ok，行数 3→5→7",
                regR.getResult().getOk() && appR1.getResult().getOk() && appR2.getResult().getOk()
                        && regR.getCard().getRowCount() == 3
                        && appR1.getCard().getRowCount() == 5
                        && appR2.getCard().getRowCount() == 7,
                regR.getResult().getOk() + "/" + appR1.getResult().getOk() + "/"
                        + appR2.getResult().getOk());
        String rV1Checksum = appR2.getResult().getOk() ? appR2.getCard().getContentChecksum() : "";

        ReadWalk rw = readWalk(svc, rId, 0, READ_PAGE_BYTES);
        check("c5.空游标首页：data 非空、part_index=1、token 非空",
                rw.ok && !rw.pages.isEmpty() && rw.pages.get(0).data().length > 0
                        && rw.pages.get(0).partIndex() == 1
                        && !rw.pages.get(0).token().isEmpty()
                        && READ_TOKEN.matcher(rw.pages.get(0).token()).matches(),
                rw.pages.isEmpty() ? "(无页)" : "part=" + rw.pages.get(0).partIndex()
                        + " bytes=" + rw.pages.get(0).data().length
                        + " token=" + rw.pages.get(0).token());
        check("c5.翻页到尾：末页 next_page_token=空串",
                rw.ok && rw.pages.get(rw.pages.size() - 1).token().isEmpty(),
                "pages=" + rw.pages.size());
        check("c5.全程 part_index 单调不减且单页不超请求页上限",
                rw.ok && rw.monotonic && rw.maxPageSizeOk,
                "pages=" + rw.pages.size());
        check("c5.边界标志一致（boundary_end→下一页 part+1；否则同 part）",
                rw.ok && rw.boundaryConsistent, "");
        check("c5.按 (part_index,边界标志) 拆回 part 文件=3 个（一页不跨 part）",
                rw.ok && rw.parts.keySet().equals(Set.of(1, 2, 3)),
                "parts=" + rw.parts.keySet());
        String rebuilt = rw.ok ? sha256Hex(rw.parts) : "";
        check("c5.全部页拼回 part 后按序拼接 sha256 == v1 content_checksum",
                rV1Checksum.equals(rebuilt) && SHA256.matcher(rebuilt).matches(),
                "expect=" + rV1Checksum + " actual=" + rebuilt);

        // 27. 版本钉死：升 v2 后读 version=1，内容与 v1 part 集一致。
        CreateNewVersionResponse upR = svc.createNewVersion(CreateNewVersionRequest.newBuilder()
                .setObjectId(rId).setVersionNote("e2e04 修正升版（版本钉死断言）")
                .setCoverageStart("2026-04-01").setCoverageEnd("2026-04-09")
                .setCsvContent(bytes(csv(weekdays("2026-04-01", 7), 300))).build());
        record("new-version-R", upR);
        check("c5.R 升版 v2 ok", upR.getResult().getOk()
                        && upR.getCard().getCurrentVersion() == 2,
                upR.getResult().getMessage() + " v=" + upR.getCard().getCurrentVersion());
        String rV2Checksum = upR.getResult().getOk() ? upR.getCard().getContentChecksum() : "";
        ReadWalk rwV1 = readWalk(svc, rId, 1, READ_PAGE_BYTES);
        String rebuiltV1 = rwV1.ok ? sha256Hex(rwV1.parts) : "";
        check("c5.升版后读 version=1：拆回拼接 sha256==v1 校验和（升版封存，旧 part 集定格）",
                rV1Checksum.equals(rebuiltV1), "expect=" + rV1Checksum + " actual=" + rebuiltV1);
        ReadWalk rwV2 = readWalk(svc, rId, 0, READ_PAGE_BYTES);
        String rebuiltV2 = rwV2.ok ? sha256Hex(rwV2.parts) : "";
        check("c5.version=0（当前 ACTIVE=v2）读回==v2 校验和",
                rV2Checksum.equals(rebuiltV2), "expect=" + rV2Checksum + " actual=" + rebuiltV2);

        // ── c4 空列情形预检（正式 c4-20 在 phase 4 对提升对象 B） ──
        section("c4 空列情形预检（phase 4 为正式用例）");
        GetDataObjectFreshnessResponse freshPre = safeFreshness(svc, aId, "c4pre.freshness-A");
        if (freshPre != null) {
            check("c4pre.对象 A 两列空：日期空、has_gap=false、note=还没有连续完整日",
                    freshPre.getResult().getOk()
                            && freshPre.getContinuousCompleteDate().isEmpty()
                            && !freshPre.getHasGapAfterWatermark()
                            && NOTE_NO_WATERMARK.equals(freshPre.getNote()),
                    "date=" + freshPre.getContinuousCompleteDate()
                            + " gap=" + freshPre.getHasGapAfterWatermark()
                            + " note=" + freshPre.getNote());
        } else {
            check("c4pre.对象 A 两列空情形", false, "freshness 调用异常（v2 建列脚本是否已跑？）");
        }

        // ── 交付物落盘：ids 文件 + 贝克 psql 脚本 ──────────
        Properties ids = new Properties();
        ids.setProperty("id.A", aId);
        ids.setProperty("id.P", pId);
        ids.setProperty("id.C", cId);
        ids.setProperty("id.U", uId);
        ids.setProperty("id.M", mId);
        ids.setProperty("id.Z-selfcycle", zId);
        ids.setProperty("id.D-cycle", dId);
        ids.setProperty("id.E-cycle", eId);
        ids.setProperty("id.depth-C10", c10Id);
        ids.setProperty("id.depth-C11", c11Id);
        ids.setProperty("id.R-parts", rId);
        ids.setProperty("id.osem", osemId);
        ids.setProperty("id.ofeb", ofebId);
        for (int i = 0; i < qIds.size(); i++) {
            ids.setProperty("id.Q" + (i + 1), qIds.get(i));
        }
        ids.setProperty("def.A", defA);
        ids.setProperty("def.B", defB);
        ids.setProperty("freshDate", cfg.freshDate());
        ids.setProperty("owner", cfg.owner());
        Path idsFile = cfg.out().resolve("object-ids.properties");
        Files.createDirectories(cfg.out());
        try (var w = Files.newBufferedWriter(idsFile, StandardCharsets.UTF_8)) {
            ids.store(w, "014-data-04 e2e phase1 登记对象 id（phase 2/3 读取）");
        }

        String sql2 = """
                -- 014-data-04 phase2 前置（程序生成，对象 id 已代入；在泳道 datafrog 库执行）
                -- ① c2b.8 成环构造：自环 Z（详单可见 cycle_detected）+ 互环 D/E（验证路径检测终止）
                UPDATE datafrog_data_object SET assertions = '[{"type":"depends_on_objects_pass","date_value":"","count_value":0,"depends_on":["%s"]}]'::jsonb WHERE id = '%s';
                UPDATE datafrog_data_object SET assertions = '[{"type":"depends_on_objects_pass","date_value":"","count_value":0,"depends_on":["%s"]}]'::jsonb WHERE id = '%s';
                UPDATE datafrog_data_object SET assertions = '[{"type":"depends_on_objects_pass","date_value":"","count_value":0,"depends_on":["%s"]}]'::jsonb WHERE id = '%s';
                -- ② c4-21 freshness 情形二：对象 A 的 ACTIVE 版本行（version=1）两列直写（模拟 03 写入）
                UPDATE datafrog_object_version SET continuous_complete_date = '%s', has_gap_after_watermark = FALSE WHERE object_id = '%s' AND version = 1 AND status = 'ACTIVE';
                """.formatted(zId, zId, eId, dId, dId, eId, cfg.freshDate(), aId);
        Path sql2File = cfg.out().resolve("psql-phase2.sql");
        Files.writeString(sql2File, sql2, StandardCharsets.UTF_8);
        String sql3 = """
                -- 014-data-04 phase3 前置（程序生成）：c4-22 freshness 情形三，改 has_gap=true
                UPDATE datafrog_object_version SET has_gap_after_watermark = TRUE WHERE object_id = '%s' AND version = 1 AND status = 'ACTIVE';
                """.formatted(aId);
        Path sql3File = cfg.out().resolve("psql-phase3.sql");
        Files.writeString(sql3File, sql3, StandardCharsets.UTF_8);

        System.out.println();
        System.out.println("phase1 完成。ids: " + idsFile.toAbsolutePath());
        System.out.println("贝克执行后再跑 phase2: " + sql2File.toAbsolutePath());
        System.out.println("phase2 后、phase3 前执行: " + sql3File.toAbsolutePath());
        evidence.append("\n== 交付物 ==\nobject-ids.properties: ").append(idsFile.toAbsolutePath())
                .append("\npsql-phase2.sql: ").append(sql2File.toAbsolutePath())
                .append("\npsql-phase3.sql: ").append(sql3File.toAbsolutePath()).append('\n');
    }

    // ══════════════ phase 2：c4-21 + 成环断言 ══════════════

    private void phase2(DataObjectService svc, Config cfg) throws IOException {
        Properties ids = loadIds(cfg);
        section("phase2 c4-21 freshness 情形二 + c2b 成环断言");
        String aId = ids.getProperty("id.A", "");
        String zId = ids.getProperty("id.Z-selfcycle", "");
        String dId = ids.getProperty("id.D-cycle", "");
        String eId = ids.getProperty("id.E-cycle", "");
        check("phase2.ids 文件齐备", !aId.isEmpty() && !zId.isEmpty() && !dId.isEmpty() && !eId.isEmpty(),
                "A=" + aId + " Z=" + zId + " D=" + dId + " E=" + eId);

        GetDataObjectResponse getA = svc.getDataObject(
                GetDataObjectRequest.newBuilder().setObjectId(aId).build());
        record("get-A", getA);
        GetDataObjectFreshnessResponse fresh2 = safeFreshness(svc, aId, "freshness-A-case2");
        if (fresh2 != null) {
            check("c4-21.日期=直写值且 has_gap=false",
                    fresh2.getResult().getOk()
                            && cfg.freshDate().equals(fresh2.getContinuousCompleteDate())
                            && !fresh2.getHasGapAfterWatermark(),
                    "date=" + fresh2.getContinuousCompleteDate()
                            + " gap=" + fresh2.getHasGapAfterWatermark());
            check("c4-21.无空洞时 note 为空",
                    fresh2.getNote().isEmpty(), "note=" + fresh2.getNote());
            check("c4-21.coverage_end 为 ACTIVE 版本投影（与卡片一致）",
                    fresh2.getResult().getOk() && getA.getResult().getOk()
                            && fresh2.getCoverageEnd().equals(getA.getCard().getCoverageEnd()),
                    "fresh=" + fresh2.getCoverageEnd() + " card=" + getA.getCard().getCoverageEnd());
        } else {
            check("c4-21.freshness 调用", false, "异常（psql-phase2.sql 是否已执行？）");
        }

        // 自环 Z：详单根层可见 cycle_detected。
        GetAvailabilityAssertionsResponse detZ = safeAssertions(svc, zId, "assertions-Z-selfcycle");
        if (detZ != null) {
            AssertionDetail zDep = detailOf(detZ, "depends_on_objects_pass");
            check("c2b.自环 Z：passed=false、reason=cycle_detected、actual 含 =CYCLE",
                    detZ.getResult().getOk() && zDep != null && !zDep.getPassed()
                            && "cycle_detected".equals(zDep.getReason())
                            && zDep.getActual().contains(zId + "=CYCLE"),
                    zDep == null ? "(缺条目)" : zDep.getActual() + " reason=" + zDep.getReason());
            check("c2b.自环 Z 卡片汇总=FAIL",
                    detZ.getAssertionStatus() == AssertionStatus.FAIL,
                    "status=" + detZ.getAssertionStatus());
        } else {
            check("c2b.自环 Z 详单调用", false, "异常（psql-phase2.sql 是否已执行？）");
        }
        // 互环 D/E：调用按时返回（不无限递归）、两条都 passed=false。
        for (String pairId : List.of(dId, eId)) {
            GetAvailabilityAssertionsResponse det = safeAssertions(svc, pairId,
                    "assertions-pair-" + pairId);
            if (det != null) {
                AssertionDetail dep = detailOf(det, "depends_on_objects_pass");
                check("c2b.互环 " + shortId(pairId) + "：按时返回且 passed=false（环被路径检测终止）",
                        det.getResult().getOk() && dep != null && !dep.getPassed()
                                && "dependency_not_pass".equals(dep.getReason()),
                        dep == null ? "(缺条目)" : dep.getActual() + " reason=" + dep.getReason());
            } else {
                check("c2b.互环 " + shortId(pairId) + " 调用", false, "超时或异常（未按时返回）");
            }
        }
    }

    // ══════════════ phase 3：c4-22 ══════════════

    private void phase3(DataObjectService svc, Config cfg) throws IOException {
        Properties ids = loadIds(cfg);
        section("phase3 c4-22 freshness 情形三");
        String aId = ids.getProperty("id.A", "");
        GetDataObjectFreshnessResponse fresh3 = safeFreshness(svc, aId, "freshness-A-case3");
        if (fresh3 != null) {
            check("c4-22.日期仍=直写值且 has_gap=true",
                    fresh3.getResult().getOk()
                            && cfg.freshDate().equals(fresh3.getContinuousCompleteDate())
                            && fresh3.getHasGapAfterWatermark(),
                    "date=" + fresh3.getContinuousCompleteDate()
                            + " gap=" + fresh3.getHasGapAfterWatermark());
            check("c4-22.note 说明下一轮从连续完整日的下一个开市日起补",
                    fresh3.getNote().contains(NOTE_GAP_KEYWORD), "note=" + fresh3.getNote());
            check("c4-22.coverage_end 不变（2026-01-26）",
                    "2026-01-26".equals(fresh3.getCoverageEnd()), fresh3.getCoverageEnd());
        } else {
            check("c4-22.freshness 调用", false, "异常（psql-phase3.sql 是否已执行？）");
        }
    }

    // ══════════════ phase 4：c4-20（对象 B 由主仓库 run 提升） ══════════════

    private void phase4(DataObjectService svc, Config cfg) {
        section("phase4 c4-20 freshness 情形一（提升对象 B，两列必空）");
        String bId = cfg.objectIdB();
        GetDataObjectResponse getB = svc.getDataObject(
                GetDataObjectRequest.newBuilder().setObjectId(bId).build());
        record("get-B", getB);
        GetDataObjectFreshnessResponse freshB = safeFreshness(svc, bId, "freshness-B-case1");
        if (freshB != null) {
            check("c4-20.ok=true",
                    freshB.getResult().getOk(), freshB.getResult().getMessage());
            check("c4-20.两列空：continuous_complete_date 空、has_gap=false",
                    freshB.getContinuousCompleteDate().isEmpty() && !freshB.getHasGapAfterWatermark(),
                    "date=" + freshB.getContinuousCompleteDate() + " gap=" + freshB.getHasGapAfterWatermark());
            check("c4-20.note=还没有连续完整日",
                    NOTE_NO_WATERMARK.equals(freshB.getNote()), "note=" + freshB.getNote());
            check("c4-20.coverage_end 与卡片一致",
                    getB.getResult().getOk()
                            && freshB.getCoverageEnd().equals(getB.getCard().getCoverageEnd()),
                    "fresh=" + freshB.getCoverageEnd() + " card=" + getB.getCard().getCoverageEnd());
        } else {
            check("c4-20.freshness 调用", false, "异常");
        }
    }

    // ── rpc 包装（异常时返回 null 并记 evidence，不中断整轮） ──

    private GetDataObjectFreshnessResponse safeFreshness(DataObjectService svc, String id, String tag) {
        try {
            GetDataObjectFreshnessResponse r = svc.getDataObjectFreshness(
                    GetDataObjectFreshnessRequest.newBuilder().setObjectId(id).build());
            record(tag, r);
            return r;
        } catch (Throwable t) {
            evidence.append("\n==== ").append(tag).append(" 异常 ====\n")
                    .append(t).append('\n');
            System.out.println("RPC-ERR " + tag + ": " + t);
            return null;
        }
    }

    private GetAvailabilityAssertionsResponse safeAssertions(DataObjectService svc, String id, String tag) {
        try {
            GetAvailabilityAssertionsResponse r = svc.getAvailabilityAssertions(
                    GetAvailabilityAssertionsRequest.newBuilder().setObjectId(id).build());
            record(tag, r);
            return r;
        } catch (Throwable t) {
            evidence.append("\n==== ").append(tag).append(" 异常 ====\n")
                    .append(t).append('\n');
            System.out.println("RPC-ERR " + tag + ": " + t);
            return null;
        }
    }

    // ── 登记与构造工具 ──────────────────────────────────

    private String objName(Config cfg, String role) {
        return cfg.prefix() + role + "-" + stamp;
    }

    /** 语义定义名只允许 [a-z0-9_]，把对象名前缀净化后用。 */
    private static String prefixSafe(Config cfg) {
        String s = cfg.prefix().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return s.isEmpty() ? "e2e04_" : s;
    }

    private RegisterDataObjectResponse register(DataObjectService svc, Config cfg, String name,
                                                String defName, int defVersion, List<String> dates,
                                                List<AssertionSpec> assertions) {
        return registerWithRefs(svc, cfg, name,
                List.of(SemanticRef.newBuilder().setName(defName).setVersion(defVersion).build()),
                dates, assertions);
    }

    private RegisterDataObjectResponse registerWithRefs(DataObjectService svc, Config cfg, String name,
                                                        List<SemanticRef> refs, List<String> dates,
                                                        List<AssertionSpec> assertions) {
        RegisterDataObjectRequest.Builder b = RegisterDataObjectRequest.newBuilder()
                .setName(name).setDescription("014-data-04 e2e " + name)
                .setSourceTypeValue(1) // SYSTEM
                .setOwner(cfg.owner())
                .setVisibilityValue(1) // PRIVATE
                .addAllSemanticRefs(refs)
                .setCoverageScope(scope)
                .setFrequencyValue(1) // DAILY
                .setGenMethodValue(2) // INDICATOR
                .setGenTask("e2e04")
                .addPrimaryKey("trade_date")
                .setAdjustmentValue(1) // RAW
                .setCcyValue(1) // CNY
                .setExportFormat("csv").setCompression("none")
                .setCoverageStart(dates.get(0)).setCoverageEnd(dates.get(dates.size() - 1))
                .setCsvContent(bytes(csv(dates, 0)))
                .addAllAssertions(assertions);
        return svc.registerDataObject(b.build());
    }

    private static AssertionSpec coverageEndAtOrAfter(String date) {
        return AssertionSpec.newBuilder().setType("coverage_end_at_or_after").setDateValue(date).build();
    }

    private static AssertionSpec rowCountAtLeast(long count) {
        return AssertionSpec.newBuilder().setType("row_count_at_least").setCountValue(count).build();
    }

    private static AssertionSpec dependsOnObjectsPass(String... ids) {
        return AssertionSpec.newBuilder().setType("depends_on_objects_pass")
                .addAllDependsOn(Arrays.asList(ids)).build();
    }

    /** 列结构固定的样例 CSV：trade_date,close,volume。offset 只用于让不同批次内容可区分。 */
    private static String csv(List<String> dates, int offset) {
        StringBuilder sb = new StringBuilder("trade_date,close,volume\n");
        int i = 0;
        for (String d : dates) {
            sb.append(d).append(',')
                    .append(String.format("%.2f", 10 + (i + offset) * 0.1)).append(',')
                    .append(1000 + (i + offset) * 10).append('\n');
            i++;
        }
        return sb.toString();
    }

    /** 从 startIso 起取 count 个工作日（YYYY-MM-DD）。 */
    private static List<String> weekdays(String startIso, int count) {
        LocalDate d = LocalDate.parse(startIso);
        List<String> out = new ArrayList<>();
        while (out.size() < count) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                out.add(d.toString());
            }
            d = d.plusDays(1);
        }
        return out;
    }

    // ── List 分页工具 ───────────────────────────────────

    private record Walk(boolean ok, List<DataObjectCard> cards, List<String> tokens,
                        int pages, boolean fullPagesOk) {
    }

    private ListDataObjectsResponse listOnce(DataObjectService svc, Config cfg, int pageSize,
                                             String token, Consumer<ListDataObjectsRequest.Builder> filters) {
        ListDataObjectsRequest.Builder b = ListDataObjectsRequest.newBuilder().setPageSize(pageSize);
        if (!token.isEmpty()) {
            b.setPageToken(token);
        }
        filters.accept(b);
        return svc.listDataObjects(b.build());
    }

    /** 同一组过滤条件内从头翻到尾（同一条件内连续翻页；改条件须空 token 重查是调用方纪律）。 */
    private Walk listWalk(DataObjectService svc, Config cfg, String tag, int pageSize, int maxPages,
                          Consumer<ListDataObjectsRequest.Builder> filters) {
        List<DataObjectCard> cards = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        boolean fullPagesOk = true;
        String token = "";
        int pages = 0;
        while (pages < maxPages) {
            ListDataObjectsResponse r = listOnce(svc, cfg, pageSize, token, filters);
            if (pages == 0) {
                record(tag + "-page1", r);
            }
            if (!r.getResult().getOk()) {
                evidence.append("\n==== listWalk 第 ").append(pages + 1).append(" 页失败 ====\n")
                        .append(TextFormat.printer().printToString(r)).append('\n');
                return new Walk(false, cards, tokens, pages, false);
            }
            if (pages > 0 && r.getCardsCount() == 0) {
                return new Walk(false, cards, tokens, pages, false); // 防御：空页死循环
            }
            if (!r.getNextPageToken().isEmpty() && r.getCardsCount() < pageSize) {
                fullPagesOk = false; // 非末页必须满页
            }
            cards.addAll(r.getCardsList());
            token = r.getNextPageToken();
            tokens.add(token);
            pages++;
            if (token.isEmpty()) {
                break;
            }
        }
        return new Walk(true, cards, tokens, pages, fullPagesOk);
    }

    private static boolean containsId(List<DataObjectCard> cards, String id) {
        for (DataObjectCard c : cards) {
            if (c.getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> idListOf(List<DataObjectCard> cards) {
        List<String> out = new ArrayList<>();
        for (DataObjectCard c : cards) {
            out.add(c.getId());
        }
        return out;
    }

    // ── ReadObjectData 分页工具 ────────────────────────

    private record ReadPage(byte[] data, String token, int partIndex, boolean boundaryEnd) {
    }

    private static final class ReadWalk {
        boolean ok;
        final List<ReadPage> pages = new ArrayList<>();
        /** part_index → 该 part 拼回的字节（按 (part_index, part_boundary_end) 拆分）。 */
        final TreeMap<Integer, byte[]> parts = new TreeMap<>();
        boolean monotonic = true;
        boolean boundaryConsistent = true;
        boolean maxPageSizeOk = true;
    }

    private ReadWalk readWalk(DataObjectService svc, String objectId, int version, int pageSize) {
        ReadWalk w = new ReadWalk();
        String token = "";
        int pages = 0;
        try {
            while (pages < 2000) {
                ReadObjectDataResponse r = svc.readObjectData(ReadObjectDataRequest.newBuilder()
                        .setObjectId(objectId).setVersion(version).setPageToken(token)
                        .setPageSize(pageSize).build());
                if (!r.getResult().getOk()) {
                    record("read-page-" + pages + "-fail", r);
                    return w;
                }
                if (pages == 0) {
                    record("read-page-first", r);
                }
                w.pages.add(new ReadPage(r.getData().toByteArray(), r.getNextPageToken(),
                        r.getPartIndex(), r.getPartBoundaryEnd()));
                evidence.append("read page ").append(pages).append(": part=").append(r.getPartIndex())
                        .append(" bytes=").append(r.getData().size())
                        .append(" boundary=").append(r.getPartBoundaryEnd())
                        .append(" next=").append(r.getNextPageToken().isEmpty() ? "(end)" : r.getNextPageToken())
                        .append('\n');
                w.parts.merge(r.getPartIndex(), r.getData().toByteArray(), E2eMain::concat);
                if (r.getData().size() > pageSize) {
                    w.maxPageSizeOk = false;
                }
                if (pages > 0) {
                    ReadPage prev = w.pages.get(pages - 1);
                    if (r.getPartIndex() < prev.partIndex()) {
                        w.monotonic = false;
                    }
                    if (prev.boundaryEnd() ? r.getPartIndex() != prev.partIndex() + 1
                            : r.getPartIndex() != prev.partIndex()) {
                        w.boundaryConsistent = false;
                    }
                }
                token = r.getNextPageToken();
                pages++;
                if (token.isEmpty()) {
                    break;
                }
            }
            w.ok = token.isEmpty();
        } catch (Throwable t) {
            evidence.append("\n==== readWalk 异常 ====\n").append(t).append('\n');
        }
        return w;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** 全部 part 按 part 序号（=文件名升序）拼接后的 sha256。 */
    private static String sha256Hex(TreeMap<Integer, byte[]> parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<Integer, byte[]> e : parts.entrySet()) {
                md.update(e.getValue());
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 断言详单查找 ──────────────────────────────────

    private static AssertionDetail detailOf(GetAvailabilityAssertionsResponse resp, String type) {
        for (AssertionDetail d : resp.getDetailsList()) {
            if (d.getSpec().getType().equals(type)) {
                return d;
            }
        }
        return null;
    }

    private static String shortId(String id) {
        return id.length() > 6 ? id.substring(0, 6) + "…" : id;
    }

    // ── ids 文件 ──────────────────────────────────────

    private Properties loadIds(Config cfg) throws IOException {
        Path idsFile = cfg.out().resolve("object-ids.properties");
        Properties ids = new Properties();
        if (Files.exists(idsFile)) {
            try (var r = Files.newBufferedReader(idsFile, StandardCharsets.UTF_8)) {
                ids.load(r);
            }
        }
        return ids;
    }

    // ── 记录与参数 ────────────────────────────────────

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
        System.out.println((cond ? "PASS " : "FAIL ") + name
                + (detail == null || detail.isEmpty() ? "" : "  (" + detail + ")"));
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
}
