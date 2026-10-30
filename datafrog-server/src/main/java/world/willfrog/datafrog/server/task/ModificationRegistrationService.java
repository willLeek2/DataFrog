package world.willfrog.datafrog.server.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;
import world.willfrog.datafrog.server.client.AgentRunClient;
import world.willfrog.datafrog.server.objectdata.service.ContentStorageService;
import world.willfrog.datafrog.server.objectdata.service.DataObjectRegistryService;
import world.willfrog.datafrog.server.objectdata.service.ObjectStoreExceptions;

import java.util.ArrayList;
import java.util.List;

/**
 * 修改任务的产物登记段（014-data-05 方案 D1）。改前没有：02 的生成任务产物登记归 03，
 * 且 03 的通道绑死生成任务；05 对修改任务自建这一段最小实现。
 *
 * 任务置 SUCCEEDED（运行成功、已结算）之后执行：经 06 交付的 Agent 运行产物读取面
 * （ListArtifacts + DownloadArtifact）取回运行产出的完整 CSV——CreateNewVersion 契约
 * 要求完整内容、不是增量——做三样最小校验（能按 CSV 解析、含日期样列、能算出覆盖起止），
 * 然后过基准版本守卫，再经 01 契约的 newVersion 登记为已有对象的新版本。
 *
 * 基准版本守卫取代跨表互斥（bob 必写句）：生成任务的追加与另一次修改可能抢在本次修改
 * 前面把对象推到新版本。登记时校验任务表里落定的基准版本号必须仍是该对象的当前有效
 * 版本；已经不是，任务改判 FAILED_INCOMPLETE（错误信息写明基准版本已被替代），不升版。
 * 守卫的原子形态（bob 2026-10-27 00c8998c 裁定）：核对在升版那一次事务里做
 * （newVersionFromBase），不在事务外面先读一次就算核对过了——预读到落库之间被人升版
 * 的窗口、以及并发双登记的第二笔升版，都由事务内核对加版本行主键唯一约束拦住。
 * 对象在修改任务在途期间被删除的场景同样在升版入口拦截：requireVisible 取不到
 * ACTIVE 对象，登记失败，语义自洽。这样不需要生成任务表与修改任务表之间的跨表互斥。
 *
 * 登记失败任务标记 FAILED_INCOMPLETE，与 02 的产物入库失败口径一致；结算不动（运行
 * 真实消耗的积分已实结，登记失败不是运行的过错）。终态不回改、如实留痕。
 *
 * 修改产物不经人工审核直接登记：需求原文对修改任务未提审核，03 的审核明确只受理生成
 * 任务，两个依据同向（方案 D1）。
 */
@Service
public class ModificationRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ModificationRegistrationService.class);

    /** 任务体自身的失败码（留在 200 应答体里，不是 HTTP 错误，与 02 终态 error_code 同型）。 */
    static final String ERR_BASE_SUPERSEDED = "MODIFICATION_BASE_SUPERSEDED";
    static final String ERR_PRODUCT_INVALID = "MODIFICATION_PRODUCT_INVALID";
    static final String ERR_REGISTRATION_FAILED = "MODIFICATION_REGISTRATION_FAILED";

    /** 运行交付产物的附件类型（主仓库收割通道 014-new-03）：清单里只有这类条目算运行产出。 */
    static final String ARTIFACT_TYPE_RUN_OUTPUT = "run_output";

    private final ModificationTaskMapper taskMapper;
    private final AgentRunClient agentRunClient;
    private final DataObjectRegistryService registryService;
    private final ContentStorageService contentStore;

    public ModificationRegistrationService(ModificationTaskMapper taskMapper,
                                           AgentRunClient agentRunClient,
                                           DataObjectRegistryService registryService,
                                           ContentStorageService contentStore) {
        this.taskMapper = taskMapper;
        this.agentRunClient = agentRunClient;
        this.registryService = registryService;
        this.contentStore = contentStore;
    }

    /**
     * 登记一个已置 SUCCEEDED 的修改任务的产物。幂等：任务不是 SUCCEEDED、或已记有
     * 登记版本（registered_version 非空）时直接返回，重复触发（查询路径惰性推进后
     * 多次进入）只有一个能真正登记。
     */
    public void registerOutcome(String taskId) {
        ModificationTaskEntity task = taskMapper.findById(taskId);
        if (task == null
                || !ModificationTaskStateMachine.SUCCEEDED.equals(task.getStatus())
                || task.getRegisteredVersion() != null) {
            return;
        }
        try {
            byte[] csv = fetchRunCsv(task);
            ContentStorageService.CsvDateOverview overview = contentStore.inspectCsvDates(csv);
            if (overview.dateColumns().isEmpty() || overview.minDate() == null || overview.maxDate() == null) {
                fail(task, ERR_PRODUCT_INVALID,
                        "产物内容没有日期样列，无法计算覆盖起止（日期样列口径与追加路径同一套抽样逻辑）");
                return;
            }

            // 基准版本守卫（事务内形态）：newVersionFromBase 在升版事务里核对任务落定的
            // 基准版本仍是当前有效版本，不是就不写新版本；对象已删除/不存在在升版入口
            // 一并拦住（requireVisible）。这里不做事务外的预读核对。
            DataObjectRegistryService.CardView updated = registryService.newVersionFromBase(
                    task.getBaseObjectId(), task.getBaseVersion(), task.getVersionNote(),
                    overview.minDate(), overview.maxDate(), csv);
            int registeredVersion = updated.object().currentVersion();
            int written = taskMapper.updateRegisteredVersion(task.getId(), registeredVersion);
            if (written == 0) {
                // 并发重入的另一路已经登记成功，本路让位；版本应一致，不一致是真异常，留日志核对
                ModificationTaskEntity other = taskMapper.findById(task.getId());
                log.warn("concurrent registration detected: task={} mine=v{} other=v{}", task.getId(),
                        registeredVersion, other == null ? null : other.getRegisteredVersion());
                return;
            }
            // TODO 014-data-05 第二波接线：CreateNewVersion 成功后调用对象存储层的两列重算方法
            //  （连续完整数据日 continuous_complete_date / has_gap_after_watermark，单一实现归 03
            //  编写、本体在对象存储服务层），成功后置 columns_confirmed=TRUE；重试粒度是对同一
            //  版本号再调重算方法、不再次升版；查询路径做惰性补列。03 未落地前等待，不自建第二
            //  处实现（方案 D1 方法契约五条）。
        } catch (ObjectStoreExceptions.ObjectNotFoundException e) {
            fail(task, ERR_BASE_SUPERSEDED, "基准对象已不存在或已删除（受理时存在），不升版");
        } catch (ObjectStoreExceptions.ExpectedBaseSupersededException e) {
            // 事务内守卫命中：任务落定的基准版本已不是当前有效版本——只有这一路用
            // 「基准版本已被替代」这个码（bob 2026-10-27 47e8ee5e 纠正口径）
            fail(task, ERR_BASE_SUPERSEDED, e.getMessage());
        } catch (ObjectStoreExceptions.DuplicateVersionException e) {
            // 升版前预查发现目标版本号已存在（对方事务已提交后预查才跑到）：记产物登记失败，
            // 不写成基准版本已被替代。真正在 INSERT 时撞数据库主键的那一笔不会走到本分支——
            // 它以 Spring 的 DuplicateKeyException 形态落进最外层通用异常分支，错误码同为
            // MODIFICATION_REGISTRATION_FAILED、正文是数据库原文加「产物登记失败:」前缀
            // （bob 2026-10-27 9db0e12f 核实的事实，行为两路一致无需再改）
            fail(task, ERR_REGISTRATION_FAILED, "产物登记失败: 升版目标版本号已存在: " + e.getMessage());
        } catch (ObjectStoreExceptions.NoActiveVersionException e) {
            // 正常流程不会走到（升版的封存与插入在同一事务）；走到就是对象状态异常，如实记
            fail(task, ERR_REGISTRATION_FAILED, "产物登记失败: 对象没有当前生效版本: " + e.getMessage());
        } catch (ObjectStoreExceptions.ConventionViolationException e) {
            fail(task, ERR_PRODUCT_INVALID, "产物内容未过校验: " + e.getMessage());
        } catch (Exception e) {
            // 取回产物失败（附件缺失、Dubbo 故障等）与升段内部故障，如实带原因
            fail(task, ERR_REGISTRATION_FAILED, "产物登记失败: " + e.getMessage());
        }
    }

    /**
     * 取回运行产出的完整 CSV。产物文件经 06 交付的附件通道取，不读 GetResult 的 answer
     * 文本（文本通道不保证完整）。清单先按附件类型过滤，只认 run_output（014-new-03 M7）：
     * 挂载进运行环境的数据集文件（dataset_*）与注入的 python 脚本（python_script）也在
     * 同一份清单里，它们不是运行产出，不参与登记对象的挑选。过滤后 CSV 附件恰有一个才
     * 登记：零个说明运行没按规格产出文件，多个说明无法确定哪份是登记对象——都如实失败，不猜。
     */
    private byte[] fetchRunCsv(ModificationTaskEntity task) {
        List<AgentArtifactMessage> csvArtifacts = new ArrayList<>();
        for (AgentArtifactMessage artifact : agentRunClient.listRunArtifacts(task.getUserId(), task.getRunId())) {
            String type = artifact.getType() == null ? "" : artifact.getType();
            if (!ARTIFACT_TYPE_RUN_OUTPUT.equals(type)) {
                continue;
            }
            String contentType = artifact.getContentType() == null ? "" : artifact.getContentType();
            String name = artifact.getName() == null ? "" : artifact.getName();
            if (contentType.toLowerCase().contains("csv") || name.toLowerCase().endsWith(".csv")) {
                csvArtifacts.add(artifact);
            }
        }
        if (csvArtifacts.isEmpty()) {
            throw new IllegalStateException("运行没有产出 CSV 附件: run=" + task.getRunId());
        }
        if (csvArtifacts.size() > 1) {
            throw new IllegalStateException("运行产出 " + csvArtifacts.size()
                    + " 个 CSV 附件，无法确定登记对象（应恰好一个）: run=" + task.getRunId());
        }
        return agentRunClient.downloadArtifact(task.getUserId(), csvArtifacts.get(0).getArtifactId());
    }

    /** 登记失败：SUCCEEDED 改判 FAILED_INCOMPLETE；WHERE 守卫保证只改判一次，终态不回改。 */
    private void fail(ModificationTaskEntity task, String errorCode, String message) {
        int updated = taskMapper.updateRegistrationFailed(task.getId(), errorCode, message);
        if (updated > 0) {
            log.warn("modification task registration failed, re-judged FAILED_INCOMPLETE: task={} base={} code={}: {}",
                    task.getId(), task.getBaseObjectId(), errorCode, message);
        }
    }
}
