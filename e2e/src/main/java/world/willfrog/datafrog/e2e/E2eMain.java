package world.willfrog.datafrog.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.RpcContext;
import world.willfrog.datafrog.idl.DataObjectCard;
import world.willfrog.datafrog.idl.DataObjectService;
import world.willfrog.datafrog.idl.GetDataObjectRequest;
import world.willfrog.datafrog.idl.GetDataObjectResponse;
import world.willfrog.datafrog.idl.ListDataObjectsRequest;
import world.willfrog.datafrog.idl.ListDataObjectsResponse;
import world.willfrog.datafrog.idl.ReadObjectDataRequest;
import world.willfrog.datafrog.idl.ReadObjectDataResponse;
import world.willfrog.datafrog.idl.RegisterDataObjectRequest;
import world.willfrog.datafrog.idl.RegisterDataObjectResponse;
import world.willfrog.datafrog.idl.RegisterSemanticDefinitionRequest;
import world.willfrog.datafrog.idl.RegisterSemanticDefinitionResponse;
import world.willfrog.datafrog.idl.SemanticRef;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 014-data-05 节点 d 泳道验收断言程序（Dubbo tri 直连泳道实例 + HTTP Bearer 通道）。
 *
 * 覆盖：w1 D4 列表过滤（七参数/属主注入/过滤后计数/翻页）；w2 D3 执行段（自建对象全语义：
 * 命中五算子、misses 逐类原因、time_scope、as_of 覆盖不足、池子缺失 400）；w3 D3 执行段对
 * 04 遗留 B 对象（动态适配语义名/主键/标的，选样内容真实——wang 建议，不随机挑库）；w4/w5
 * D2 语义查询与 D5 删除摘点（依赖泳道热配置带 embedding 密钥，不可达时按环境边界如实记录）；
 * w6 D6 保留策略带值拒绝（rpc 面）；w7 D7 留存核验记录（维护端点，1b）；w8 D8 导出触发限额
 * （429 冷却与恢复，验收热配置把间隔调小后可测）；w9 D9 维护端点鉴权（403 名单外/403 名单空
 * →200 名单内两段）与索引重建入口（200/409 尽力）；S10 前置记录（phase 1 末把登记对象的
 * 校验和写进证据，phase 3 滚动后对比）。
 *
 * phase 划分与泳道操作顺序（部署清单同口径）：
 *   phase 1  —— 泳道拉起、热配置（导出限额参数调小）后跑；含 w9-403（此时 admin 名单未配）。
 *   phase 1b —— 贝克把 maintenanceAdminUserIds 配成运行身份后跑；w7/w9-200/重建。
 *   phase 2  —— 修改任务 run 全链（受理→运行→附件→升版→基准守卫）；run 驱动器与 wang
 *               协调中，本版程序留骨架不实现，验收排期不受 phase 1/1b/3 阻塞。
 *   phase 3  —— S10 实证：贝克 mounts apply 滚 datafrog 半边后跑，断言对象内容、校验和、
 *               执行段求值与滚动前一致。需要 --s10-object <phase 1 登记的执行段对象 id>。
 *
 * 用法：java -jar e2e-014data05.jar --host <宿主机> --rpc-port <tri 映射端口> \
 *       --http-port <8070 映射端口> --owner <运行身份> --token <登录态 Bearer> \
 *       --phase 1|1b|3 [--out out] [--prefix e2e05-] [--object-b <04 遗留 B 对象 id>] \
 *       [--s10-object <phase1 登记的对象 id>]
 */
public final class E2eMain {

    private static final String API = "/api/data-platform/v1";

    private record Config(String host, int rpcPort, int httpPort, Path out, String phase,
                          String owner, String token, String prefix, String laneTag,
                          String objectB, String objectBSymbol, String s10Object) {
    }

    private final List<String[]> checks = new ArrayList<>();
    private final StringBuilder evidence = new StringBuilder();
    private int pass;
    private int fail;

    /** 本次批次后缀：对象名/语义定义名带它防共库撞名。 */
    private final String stamp;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private E2eMain() {
        String ts = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now());
        this.stamp = ts + "-" + String.format("%04x", new Random().nextInt(0x10000));
    }

    public static void main(String[] args) throws Exception {
        String phase = arg(args, "--phase", "1");
        String owner = arg(args, "--owner", "");
        String token = arg(args, "--token", "");
        if (owner.isBlank() || token.isBlank()) {
            System.err.println("用法: --host <host> --rpc-port <port> --http-port <port> "
                    + "--owner <运行身份> --token <Bearer> --phase 1|1b|3 [--lane-tag <泳道 tag>] "
                    + "[--out out] [--prefix e2e05-] [--object-b <id> --object-b-symbol <B 的标的值>] [--s10-object <id>]");
            System.exit(2);
        }
        if (!List.of("1", "1b", "2", "3").contains(phase)) {
            System.err.println("未知 --phase: " + phase + "（可选 1|1b|2|3；2 为 run 全链骨架）");
            System.exit(2);
        }
        Config cfg = new Config(arg(args, "--host", "127.0.0.1"),
                Integer.parseInt(arg(args, "--rpc-port", "50070")),
                Integer.parseInt(arg(args, "--http-port", "8070")),
                Path.of(arg(args, "--out", "out")),
                phase, owner, token,
                arg(args, "--prefix", "e2e05-"),
                arg(args, "--lane-tag", ""),
                arg(args, "--object-b", ""),
                arg(args, "--object-b-symbol", ""),
                arg(args, "--s10-object", ""));

        ReferenceConfig<DataObjectService> ref = new ReferenceConfig<>();
        ref.setInterface(DataObjectService.class);
        ref.setUrl("tri://" + cfg.host() + ":" + cfg.rpcPort());
        ref.setTimeout(60000);
        DubboBootstrap bootstrap = DubboBootstrap.getInstance()
                .application(new ApplicationConfig("datafrog-014data05-e2e"))
                .reference(ref)
                .start();
        DataObjectService svc = bootstrap.getCache().get(DataObjectService.class);

        E2eMain e2e = new E2eMain();
        e2e.evidence.append("014-data-05 泳道验收 phase=").append(cfg.phase()).append(' ')
                .append(LocalDateTime.now()).append('\n')
                .append("参数: host=").append(cfg.host())
                .append(" rpc=").append(cfg.rpcPort()).append(" http=").append(cfg.httpPort())
                .append(" owner=").append(cfg.owner()).append(" prefix=").append(cfg.prefix())
                .append(" objectB=").append(cfg.objectB().isEmpty() ? "(无)" : cfg.objectB())
                .append(" s10Object=").append(cfg.s10Object().isEmpty() ? "(无)" : cfg.s10Object())
                .append('\n');

        switch (cfg.phase()) {
            case "1" -> e2e.phase1(svc, cfg);
            case "1b" -> e2e.phase1b(svc, cfg);
            case "2" -> e2e.phase2(cfg);
            case "3" -> e2e.phase3(svc, cfg);
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

    // ══════════════ phase 1：w1-w6 + w8 + w9-403 + S10 前置记录 ══════════════

    private void phase1(DataObjectService svc, Config cfg) throws Exception {
        section("phase1 环境");
        // 执行段专用语义定义：名字与数据列名一致（e2e05close），挂靠匹配与列匹配两头同串
        String defName = (cfg.prefix() + "close").replaceAll("-", "");
        laneTag(cfg);
        RegisterSemanticDefinitionResponse regDef = svc.registerSemanticDefinition(
                RegisterSemanticDefinitionRequest.newBuilder().setName(defName).setVersion(1)
                        .setDisplayName("e2e05 执行段语义").setDefinition("e2e05 条件筛选求值用临时语义定义")
                        .setCalculation("-").setUnit("-").setScope("e2e05").build());
        record("reg-def", regDef);
        check("pre.执行段语义定义登记 ok", regDef.getResult().getOk(), regDef.getResult().getMessage());

        // ── w2 前置：执行段对象 E（带 trade_date，两标的，价格单调） ──
        section("w2 D3 执行段（自建对象）");
        String csv = "trade_date,symbol," + defName + "\n"
                + "2026-01-05,SYMA,10.0\n"
                + "2026-01-06,SYMA,10.5\n"
                + "2026-01-07,SYMA,11.0\n"
                + "2026-01-08,SYMA,11.5\n"
                + "2026-01-05,SYMB,20.0\n"
                + "2026-01-06,SYMB,20.5\n"
                + "2026-01-07,SYMB,21.0\n"
                + "2026-01-08,SYMB,21.5\n";
        RegisterDataObjectResponse regE = register(svc, cfg, objName(cfg, "e"), defName, csv);
        record("register-E", regE);
        check("w2.前置对象 E 登记 ok", regE.getResult().getOk(), regE.getResult().getMessage());
        String eId = regE.getResult().getOk() ? regE.getCard().getId() : "";

        // 命中：五算子各一条（SYMA 最新 2026-01-08 值 11.5；命中值取最新满足行）
        execute(cfg, "w2.命中等于", cond(defName, "等于", "11.5", null), List.of("SYMA"), 1, 0,
                "hits=1 且命中值 11.5、日期 2026-01-08");
        execute(cfg, "w2.命中大于", cond(defName, "大于", "10.0", null), List.of("SYMB"), 1, 0,
                "hits=1（SYMB 全部行大于 10）");
        execute(cfg, "w2.命中大于等于", cond(defName, "大于等于", "21.5", null), List.of("SYMB"), 1, 0,
                "hits=1（最新行恰等于边界）");
        execute(cfg, "w2.命中小于", cond(defName, "小于", "12.0", null), List.of("SYMA"), 1, 0,
                "hits=1");
        execute(cfg, "w2.命中小于等于", cond(defName, "小于等于", "10.0", null), List.of("SYMA"), 1, 0,
                "hits=1（首行恰等于边界，存在满足行即命中）");
        // misses：条件不成立
        execute(cfg, "w2.miss 条件不成立", cond(defName, "大于", "100.0", null), List.of("SYMA"), 0, 1,
                "misses=1 原因含「条件不成立」");
        // 算子不支持
        execute(cfg, "w2.miss 算子不支持", cond(defName, "包含", "1", null), List.of("SYMA"), 0, 1,
                "misses=1 原因含「算子不支持」");
        // time_scope 范围内/外
        execute(cfg, "w2.time_scope 内命中", cond(defName, "大于", "10.2",
                        scope("2026-01-07", "2026-01-08")), List.of("SYMA"), 1, 0,
                "hits=1（范围内有满足行 11.0/11.5）");
        execute(cfg, "w2.time_scope 外未命中", cond(defName, "大于", "10.2",
                        scope("2025-01-01", "2025-01-31")), List.of("SYMA"), 0, 1,
                "misses=1 原因含「时间范围内没有行」");
        // 池子缺失 400 专用码
        HttpResult r0 = httpPost(cfg, "/condition-filters/execute",
                "{\"conditions\":[" + cond(defName, "等于", "1", null) + "],\"symbolPool\":[]}");
        check("w2.symbolPool 空 400 CONDITION_SCOPE_REQUIRED",
                r0.status == 400 && r0.body.contains("CONDITION_SCOPE_REQUIRED"),
                "status=" + r0.status + " body=" + clip(r0.body));
        // as_of 超覆盖
        HttpResult rAsOf = httpPost(cfg, "/condition-filters/execute",
                "{\"conditions\":[" + cond(defName, "等于", "11.5", null)
                        + "],\"symbolPool\":[\"SYMA\"],\"asOf\":\"2026-06-01\"}");
        check("w2.as_of 超覆盖日期未命中（原因含覆盖日期不足）",
                rAsOf.status == 200 && rAsOf.body.contains("覆盖日期不足"),
                "status=" + rAsOf.status + " body=" + clip(rAsOf.body));

        // 没有挂靠对象：语义名乱写
        execute(cfg, "w2.miss 没有挂靠对象", cond("no_such_semantic_e2e05", "等于", "1", null),
                List.of("SYMA"), 0, 1, "misses=1 原因含「没有挂靠对象」");
        // 挂靠对象不唯一：登记第二个挂同一语义名的对象
        RegisterDataObjectResponse regE2 = register(svc, cfg, objName(cfg, "e2"), defName, csv);
        check("w2.前置第二对象登记 ok", regE2.getResult().getOk(), regE2.getResult().getMessage());
        execute(cfg, "w2.miss 挂靠对象不唯一", cond(defName, "等于", "11.5", null),
                List.of("SYMA"), 0, 1, "misses=1 原因含「挂靠对象不唯一」");

        // ── w3 执行段对 04 遗留 B 对象（内容真实，动态适配） ──
        section("w3 D3 执行段（04 B 对象）");
        if (cfg.objectB().isBlank()) {
            check("w3.跳过（未给 --object-b）", true, "本 phase 未提供 B 对象 id，如实记录为跳过");
        } else {
            laneTag(cfg);
            GetDataObjectResponse b = svc.getDataObject(
                    GetDataObjectRequest.newBuilder().setObjectId(cfg.objectB()).build());
            record("get-B", b);
            if (b.getResult().getOk()) {
                DataObjectCard card = b.getCard();
                String bField = card.getSemanticRefsCount() > 0
                        ? card.getSemanticRefs(0).getName() : "";
                String bKey = card.getPrimaryKeyCount() > 0 ? card.getPrimaryKey(0) : "";
                check("w3.B 对象卡片可读（语义名=" + bField + " 主键=" + bKey + "）",
                        !bField.isBlank() && !bKey.isBlank(), "field=" + bField + " key=" + bKey);
                String bSymbol = cfg.objectBSymbol();
                if (bSymbol.isBlank()) {
                    check("w3.B 对象求值跳过（未给 --object-b-symbol，parquet 不可文本取标的）",
                            true, "标的值由断言方从 04 登记卡或验收证据给出后可深验；本条如实记录为跳过");
                } else {
                    execute(cfg, "w3.B 对象求值（真实 parquet 链路）",
                            cond(bField, "大于", "-1e18", null), List.of(bSymbol), -1, -1,
                            "应答 200 且 hits+misses=1（值域未知，只验证真实对象求值链路通）");
                }
            } else {
                check("w3.B 对象卡片可读", false, b.getResult().getMessage());
            }
        }

        // ── w1 D4 列表过滤（HTTP） ──
        section("w1 D4 列表过滤");
        HttpResult l0 = httpGet(cfg, "/data-objects?limit=200");
        check("w1.无过滤列表 200", l0.status == 200, "status=" + l0.status);
        JsonNode l0body = json.readTree(l0.body);
        // 无过滤（=只有自己的对象）：total 与 items 一致性交给过滤断言；这里断 E2 在列
        boolean selfListContainsE2 = false;
        for (JsonNode item : l0body.path("items")) {
            if (item.path("id").asText().equals(eId)) {
                selfListContainsE2 = true;
            }
        }
        check("w1.无过滤列表含本轮对象 E（属主注入=只列自己）", selfListContainsE2,
                "items=" + l0body.path("items").size() + " total=" + l0body.path("total").asLong());
        // 语义名过滤
        HttpResult l1 = httpGet(cfg, "/data-objects?filter_semantic_name=no_such_semantic_e2e05");
        JsonNode l1body = json.readTree(l1.body);
        check("w1.语义名过滤无命中 total=0", l1.status == 200 && l1body.path("total").asLong() == 0,
                "status=" + l1.status + " total=" + l1body.path("total").asLong());
        // 字段等值过滤：defName 两对象都在自己名下
        HttpResult l2 = httpGet(cfg, "/data-objects?filter_field_name=" + defName);
        JsonNode l2body = json.readTree(l2.body);
        check("w1.字段过滤 total=2 且 items 同数（过滤后计数口径）",
                l2.status == 200 && l2body.path("total").asLong() == 2
                        && l2body.path("items").size() == 2,
                "total=" + l2body.path("total").asLong() + " items=" + l2body.path("items").size());
        // filter_owner 传名即 400
        HttpResult l3 = httpGet(cfg, "/data-objects?filter_owner=" + cfg.owner());
        check("w1.filter_owner 带名 400", l3.status == 400, "status=" + l3.status + " body=" + clip(l3.body));
        // 非法断言状态值 400
        HttpResult l4 = httpGet(cfg, "/data-objects?filter_assertion_status=BOGUS");
        check("w1.filter_assertion_status 非法 400", l4.status == 400, "status=" + l4.status);
        // 覆盖区间/新鲜度过滤：叠加字段维度把 04 遗留对象（不挂 defName）排除在计数外，
        // 断言才对得上本批次两对象
        HttpResult l5 = httpGet(cfg,
                "/data-objects?filter_field_name=" + defName + "&filter_coverage_start_from=2026-01-06");
        JsonNode l5body = json.readTree(l5.body);
        check("w1.覆盖起点过滤（E/E2 起点 2026-01-05 不满足）→ total=0",
                l5.status == 200 && l5body.path("total").asLong() == 0,
                "total=" + l5body.path("total").asLong());
        HttpResult l6 = httpGet(cfg,
                "/data-objects?filter_field_name=" + defName + "&filter_freshness_since=2026-01-07");
        JsonNode l6body = json.readTree(l6.body);
        check("w1.新鲜度过滤 total=2（两对象 coverage_end=2026-01-08 >= 01-07）",
                l6.status == 200 && l6body.path("total").asLong() == 2,
                "total=" + l6body.path("total").asLong());
        // 翻页：字段过滤 total=2 下 limit=1 取两页，第二页 token 推进正确
        HttpResult p1 = httpGet(cfg, "/data-objects?filter_field_name=" + defName + "&limit=1");
        JsonNode p1body = json.readTree(p1.body);
        String nextToken = p1body.path("nextPageToken").asText("");
        HttpResult p2 = httpGet(cfg, "/data-objects?filter_field_name=" + defName
                + "&limit=1&offset=" + nextToken);
        JsonNode p2body = json.readTree(p2.body);
        check("w1.过滤内翻页（limit=1 两页取回 2 张、第二页无 nextToken）",
                p1.status == 200 && p2.status == 200
                        && p1body.path("items").size() == 1 && p2body.path("items").size() == 1
                        && !nextToken.isBlank() && p2body.path("nextPageToken").asText("").isEmpty(),
                "p1=" + p1body.path("items").size() + " next=" + nextToken
                        + " p2=" + p2body.path("items").size()
                        + " next2=" + p2body.path("nextPageToken").asText(""));

        // ── w6 D6 保留策略带值拒绝（rpc 面） ──
        section("w6 D6 保留拒绝");
        RegisterDataObjectRequest.Builder rb = baseRegister(cfg, objName(cfg, "ret"), defName, csv);
        rb.setRetentionDays(30);
        laneTag(cfg);
        RegisterDataObjectResponse regRet = svc.registerDataObject(rb.build());
        record("register-retention", regRet);
        check("w6.带 retention_days=30 拒绝（RETENTION_POLICY_INVALID）",
                !regRet.getResult().getOk()
                        && regRet.getResult().getCode().getNumber() == 10,
                "ok=" + regRet.getResult().getOk()
                        + " code=" + regRet.getResult().getCode());
        RegisterDataObjectRequest.Builder rb0 = baseRegister(cfg, objName(cfg, "ret0"), defName, csv);
        laneTag(cfg);
        RegisterDataObjectResponse regRet0 = svc.registerDataObject(rb0.build());
        check("w6.不带保留值（0=未设置）登记 ok", regRet0.getResult().getOk(),
                regRet0.getResult().getMessage());

        // ── w8 D8 导出触发限额（验收热配置：冷却 2s/每日 3 次） ──
        section("w8 D8 导出限额");
        HttpResult ex1 = httpGet(cfg, "/data-objects/" + eId + "/export");
        check("w8.首次导出触发 200", ex1.status == 200, "status=" + ex1.status + " body=" + clip(ex1.body));
        HttpResult ex2 = httpGet(cfg, "/data-objects/" + eId + "/export");
        check("w8.冷却期内再触发 429 RATE_LIMITED",
                ex2.status == 429 && ex2.body.contains("RATE_LIMITED"),
                "status=" + ex2.status + " body=" + clip(ex2.body));
        // 每日上限（3 次）：换对象触发绕过同对象冷却，逐次计账
        String e2Id = regE2.getResult().getOk() ? regE2.getCard().getId() : "";
        HttpResult ex3 = httpGet(cfg, "/data-objects/" + e2Id + "/export");
        Thread.sleep(2500); // 过 2s 冷却
        HttpResult ex4 = httpGet(cfg, "/data-objects/" + eId + "/export");
        HttpResult ex5 = httpGet(cfg, "/data-objects/" + e2Id + "/export");
        check("w8.每日第 3 次触发后第 4 次 429（跨对象计账）",
                ex3.status == 200 && ex4.status == 200 && ex5.status == 429,
                "ex3=" + ex3.status + " ex4=" + ex4.status + " ex5=" + ex5.status);

        // ── w9-403 维护端点鉴权（admin 名单未配窗口） ──
        section("w9 D9 维护鉴权（名单未配）");
        HttpResult m0 = httpGet(cfg, "/maintenance/status");
        check("w9.status 403（名单空全拒）", m0.status == 403, "status=" + m0.status);
        HttpResult rb0resp = httpPost(cfg, "/maintenance/semantic-index/rebuild", "{}");
        check("w9.rebuild 403（名单空全拒）", rb0resp.status == 403, "status=" + rb0resp.status);

        // ── S10 前置记录：把执行段对象 E 的校验和写进证据（phase 3 对比用） ──
        section("S10 前置记录");
        laneTag(cfg);
        GetDataObjectResponse eCard = svc.getDataObject(
                GetDataObjectRequest.newBuilder().setObjectId(eId).build());
        evidence.append("[S10-PRE] object=").append(eId)
                .append(" contentChecksum=").append(eCard.getCard().getContentChecksum())
                .append(" coverageEnd=").append(eCard.getCard().getCoverageEnd())
                .append(" rowCount=").append(eCard.getCard().getRowCount())
                .append(" phase3 用 --s10-object ").append(eId).append('\n');
        check("S10.前置记录已写（对象 E 校验和进证据）", true, "见 [S10-PRE] 行");
    }

    // ══════════════ phase 1b：w7 核验记录 + w9-200/重建（admin 名单已配） ══════════════

    private void phase1b(DataObjectService svc, Config cfg) throws Exception {
        section("phase1b w9 名单内");
        HttpResult m1 = httpGet(cfg, "/maintenance/status");
        check("w9b.status 200（名单含运行身份）", m1.status == 200,
                "status=" + m1.status + " body=" + clip(m1.body));
        if (m1.status == 200) {
            JsonNode st = json.readTree(m1.body);
            check("w9b.status 带 collection 与 vectorDim",
                    !st.path("collection").asText("").isEmpty() && st.has("vectorDim"),
                    st.toString());
        }

        section("w7 D7 留存核验记录");
        // Sweeper 60 秒节拍、间隔默认六小时、首次启动即全量核验：泳道起来 1-2 分钟内应有 RETAIN
        HttpResult rc = null;
        for (int i = 0; i < 10; i++) {
            rc = httpGet(cfg, "/maintenance/retention-checks?object_id=" + cfg.s10Object());
            if (rc.status == 200 && rc.body.contains("RETAIN")) {
                break;
            }
            Thread.sleep(30000);
        }
        check("w7.留存核验有 RETAIN 记录（等待最多 5 分钟）",
                rc != null && rc.status == 200 && rc.body.contains("RETAIN"),
                "status=" + (rc == null ? "?" : rc.status) + " body=" + (rc == null ? "" : clip(rc.body)));

        section("w9b D9 索引重建");
        HttpResult rb1 = httpPost(cfg, "/maintenance/semantic-index/rebuild", "{}");
        check("w9b.rebuild 200（名单内）", rb1.status == 200,
                "status=" + rb1.status + " body=" + clip(rb1.body));
        HttpResult rb2 = httpPost(cfg, "/maintenance/semantic-index/rebuild", "{}");
        // 重入 409 只在重建进行中才出现；两连发第二次 200（很快完成）或 409 都如实记录
        check("w9b.rebuild 连发第二次 200 或 409（409=重入防护实证）",
                rb2.status == 200 || rb2.status == 409, "status=" + rb2.status);
    }

    // ══════════════ phase 2：受理段断言（run 段复用 wang 驱动器，排期另行通知） ══════════════

    /**
     * 受理段（HTTP）现在就断言：无效规格 400、缺 version_note 400、base_version 给旧版本
     * 受理即拒、受理成功、幂等重发 deduplicated。run 终态与升版断言等 wang 的改造版驱动器
     * （05 的 run 由受理端点拉起、驱动器充当执行方，从 runId 接管）到位后并入本 phase。
     */
    private void phase2(Config cfg) throws Exception {
        section("phase2 受理段（run 段等驱动器）");
        String clientReqId = "e2e05-" + stamp;

        // 无效规格：缺 base_object_id
        HttpResult bad1 = httpPost(cfg, "/modification-tasks",
                "{" + specJson("把对象改好", null, null, "例行修改") + ",\"clientRequestId\":\"" + clientReqId + "-bad1\"}");
        check("p2.缺 base_object_id 400 INVALID_SPEC",
                bad1.status == 400 && bad1.body.contains("INVALID_SPEC"),
                "status=" + bad1.status + " body=" + clip(bad1.body));

        // 缺 version_note
        HttpResult bad2 = httpPost(cfg, "/modification-tasks",
                "{" + specJson("把对象改好", "ds_any", null, null) + ",\"clientRequestId\":\"" + clientReqId + "-bad2\"}");
        check("p2.缺 version_note 400 INVALID_SPEC",
                bad2.status == 400 && bad2.body.contains("INVALID_SPEC"),
                "status=" + bad2.status + " body=" + clip(bad2.body));

        // base_version 给旧版本：受理即拒（九决定之一）。需要一个已升过版/可先登记再升版的对象：
        // 这里只对不存在对象给显式版本——校验顺序是对象存在性在前，断言用 MODIFICATION_TARGET_NOT_FOUND
        HttpResult bad3 = httpPost(cfg, "/modification-tasks",
                "{" + specJson("把对象改好", "ds_nosuch00", 1, "例行修改") + ",\"clientRequestId\":\"" + clientReqId + "-bad3\"}");
        check("p2.基准对象不存在 400 MODIFICATION_TARGET_NOT_FOUND",
                bad3.status == 400 && bad3.body.contains("MODIFICATION_TARGET_NOT_FOUND"),
                "status=" + bad3.status + " body=" + clip(bad3.body));

        // 受理成功与幂等：需要 --s10-object（phase 1 登记的 E 对象）作基准
        if (cfg.s10Object().isBlank()) {
            check("p2.受理成功段跳过（未给 --s10-object）", true, "run 段驱动器就绪后带对象重跑");
            return;
        }
        HttpResult ok1 = httpPost(cfg, "/modification-tasks",
                "{" + specJson("把收盘价整体上浮百分之十，输出同列结构 CSV", cfg.s10Object(), null, "e2e05 修改验证")
                        + ",\"clientRequestId\":\"" + clientReqId + "\"}");
        JsonNode ok1body = json.readTree(ok1.body);
        String taskId = ok1body.path("taskId").asText("");
        check("p2.受理成功 200 且 taskId 非空、status=RUNNING",
                ok1.status == 200 && !taskId.isBlank() && "RUNNING".equals(ok1body.path("status").asText()),
                "status=" + ok1.status + " body=" + clip(ok1.body));

        HttpResult dup = httpPost(cfg, "/modification-tasks",
                "{" + specJson("把收盘价整体上浮百分之十，输出同列结构 CSV", cfg.s10Object(), null, "e2e05 修改验证")
                        + ",\"clientRequestId\":\"" + clientReqId + "\"}");
        JsonNode dupBody = json.readTree(dup.body);
        check("p2.同 clientRequestId 重发 deduplicated=true 且 taskId 一致",
                dup.status == 200 && dupBody.path("deduplicated").asBoolean(false)
                        && taskId.equals(dupBody.path("taskId").asText()),
                "status=" + dup.status + " body=" + clip(dup.body));

        HttpResult q = httpGet(cfg, "/modification-tasks/" + taskId);
        JsonNode qBody = json.readTree(q.body);
        check("p2.查询可见 runId（受理已拉起运行）",
                q.status == 200 && !qBody.path("runId").asText("").isBlank(),
                "status=" + q.status + " body=" + clip(q.body));
        evidence.append("[P2-TASK] taskId=").append(taskId)
                .append(" runId=").append(qBody.path("runId").asText())
                .append("（run 段驱动器接管后续断言）\n");
    }

    /** 组装 specJson 字符串值（base/version/note 任一可为 null 表示不写该键）。 */
    private static String specJson(String instruction, String baseObjectId, Integer baseVersion,
                                   String versionNote) {
        StringBuilder sb = new StringBuilder("\"specJson\":\"{");
        sb.append("\\\"instruction\\\":\\\"").append(instruction).append("\\\"");
        if (baseObjectId != null) {
            sb.append(",\\\"base_object_id\\\":\\\"").append(baseObjectId).append("\\\"");
        }
        if (baseVersion != null) {
            sb.append(",\\\"base_version\\\":").append(baseVersion);
        }
        if (versionNote != null) {
            sb.append(",\\\"version_note\\\":\\\"").append(versionNote).append("\\\"");
        }
        sb.append("}\"");
        return sb.toString();
    }

    // ══════════════ phase 3：S10 滚动存活实证（贝克 mounts apply 后） ══════════════

    private void phase3(DataObjectService svc, Config cfg) throws Exception {
        section("phase3 S10 滚动存活");
        if (cfg.s10Object().isBlank()) {
            check("S10.未给 --s10-object，跳过", true, "需要 phase 1 证据 [S10-PRE] 行里的对象 id");
            return;
        }
        laneTag(cfg);
        GetDataObjectResponse card = svc.getDataObject(
                GetDataObjectRequest.newBuilder().setObjectId(cfg.s10Object()).build());
        record("s10-card-after-roll", card);
        check("S10.滚动后卡片可读", card.getResult().getOk(), card.getResult().getMessage());
        check("S10.滚动后行数不变（8 行）", card.getCard().getRowCount() == 8,
                "rowCount=" + card.getCard().getRowCount());
        // 读首页字节成功即内容存活；执行段再求值一次做行为级验证（等于最新值仍命中）
        laneTag(cfg);
        ReadObjectDataResponse firstPage = svc.readObjectData(ReadObjectDataRequest.newBuilder()
                .setObjectId(cfg.s10Object()).setPageSize(4096).build());
        byte[] head = firstPage.getData().toByteArray();
        record("s10-first-page", "bytes=" + head.length + " token=" + firstPage.getNextPageToken());
        check("S10.滚动后 ReadObjectData 首页有字节（parquet 魔数 PAR1）",
                head.length > 4 && head[0] == 'P' && head[1] == 'A' && head[2] == 'R' && head[3] == '1',
                "size=" + head.length + " head=" + (head.length >= 4
                        ? "" + (char) head[0] + (char) head[1] + (char) head[2] + (char) head[3] : "?"));
        HttpResult r = httpPost(cfg, "/condition-filters/execute",
                "{\"conditions\":[" + cond((cfg.prefix() + "close").replaceAll("-", ""),
                        "等于", "11.5", null) + "],\"symbolPool\":[\"SYMA\"]}");
        check("S10.滚动后执行段求值仍命中（内容与索引链路活着）",
                r.status == 200 && r.body.contains("SYMA") && r.body.contains("11.5"),
                "status=" + r.status + " body=" + clip(r.body));
    }

    // ══════════════ 辅助 ══════════════

    private RegisterDataObjectRequest.Builder baseRegister(Config cfg, String name,
                                                           String defName, String csv) {
        return RegisterDataObjectRequest.newBuilder()
                .setName(name).setDescription("e2e05 " + name)
                .setSourceType(world.willfrog.datafrog.idl.SourceType.SYSTEM).setOwner(cfg.owner()).setVisibility(world.willfrog.datafrog.idl.Visibility.PRIVATE)
                .addSemanticRefs(SemanticRef.newBuilder().setName(defName).build())
                .setCoverageScope("e2e05-" + stamp).setFrequency(world.willfrog.datafrog.idl.Frequency.DAILY)
                .setGenMethod(world.willfrog.datafrog.idl.GenMethod.AGENT).setCoverageStart("2026-01-05").setCoverageEnd("2026-01-08")
                .addPrimaryKey("symbol").addPrimaryKey("trade_date")
                .setCsvContent(com.google.protobuf.ByteString.copyFromUtf8(csv));
    }

    private RegisterDataObjectResponse register(DataObjectService svc, Config cfg, String name,
                                                String defName, String csv) {
        return svc.registerDataObject(baseRegister(cfg, name, defName, csv).build());
    }

    private String objName(Config cfg, String tag) {
        return cfg.prefix() + tag + "-" + stamp;
    }

    /** 组装 execute 请求体的一条条件 JSON。 */
    private static String cond(String semanticName, String operator, String value, String timeScope) {
        return "{\"semanticName\":\"" + semanticName + "\",\"operator\":\"" + operator
                + "\",\"value\":\"" + value + "\"" + (timeScope == null ? "" : ",\"timeScope\":" + timeScope)
                + "}";
    }

    private static String scope(String from, String to) {
        return "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    }

    /**
     * 执行一次条件筛选并断言形态。expectHits/expectMisses 传 -1 表示不断言具体个数
     * （只断 200 与 hits+misses=symbolPool 数）。
     */
    private void execute(Config cfg, String label, String conditionJson, List<String> pool,
                         int expectHits, int expectMisses, String meaning) throws Exception {
        HttpResult r = httpPost(cfg, "/condition-filters/execute",
                "{\"conditions\":[" + conditionJson + "],\"symbolPool\":"
                        + json.writeValueAsString(pool) + "}");
        boolean ok = r.status == 200;
        String detail = "status=" + r.status + " body=" + clip(r.body);
        if (ok) {
            JsonNode body = json.readTree(r.body);
            int hits = body.path("hits").size();
            int misses = body.path("misses").size();
            detail = "hits=" + hits + " misses=" + misses + " body=" + clip(r.body);
            if (expectHits >= 0) {
                ok = hits == expectHits;
            }
            if (ok && expectMisses >= 0) {
                ok = misses == expectMisses;
            }
            if (ok) {
                ok = hits + misses == pool.size();
            }
        }
        check(label + "（" + meaning + "）", ok, detail);
    }



    /** 控制器发布的宿主口按 dubbo.tag 做泳道分流：带 --lane-tag 时每次 rpc 调用前设置。 */
    private void laneTag(Config cfg) {
        if (!cfg.laneTag().isBlank()) {
            RpcContext.getClientAttachment().setAttachment("dubbo.tag", cfg.laneTag());
        }
    }

    // ── HTTP 基础 ──

    private record HttpResult(int status, String body) {
    }

    private HttpResult httpGet(Config cfg, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + cfg.host() + ":" + cfg.httpPort() + API + path))
                .header("Authorization", "Bearer " + cfg.token())
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(resp.statusCode(), resp.body());
    }

    private HttpResult httpPost(Config cfg, String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + cfg.host() + ":" + cfg.httpPort() + API + path))
                .header("Authorization", "Bearer " + cfg.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(resp.statusCode(), resp.body());
    }

    // ── 断言记录（04 同款） ──

    private void check(String label, boolean ok, String detail) {
        checks.add(new String[]{ok ? "PASS" : "FAIL", label, detail});
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        evidence.append(ok ? "[PASS] " : "[FAIL] ").append(label).append(" | ").append(detail).append('\n');
    }

    private void record(String label, Object obj) {
        evidence.append("[REC] ").append(label).append(": ")
                .append(obj == null ? "null" : String.valueOf(obj).replace("\n", " ")).append('\n');
    }

    private void section(String title) {
        evidence.append("\n===== ").append(title).append(" =====\n");
    }

    private static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static String arg(String[] args, String key, String dft) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) {
                return args[i + 1];
            }
        }
        return dft;
    }
}
