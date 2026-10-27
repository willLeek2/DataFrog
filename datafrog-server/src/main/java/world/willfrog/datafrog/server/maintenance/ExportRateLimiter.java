package world.willfrog.datafrog.server.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import world.willfrog.datafrog.server.config.DatafrogRuntimeConfig;
import world.willfrog.datafrog.server.config.DatafrogRuntimeConfigLoader;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 导出触发限额（014-data-05 方案 D8 差量一）：每用户每日导出触发次数上限与同一对象
 * 重复导出的最小间隔，两值热配置可调（datafrog.export.daily-limit-per-user 默认 50、
 * datafrog.export.reexport-cooldown-seconds 默认 60）。两个默认值是热配置起始值
 * （bob 裁定），不写进规范篇当永久口径。
 *
 * 判定与计数都基于 datafrog_export_rate_ledger 流水表：触发前查表判断，通过即记一行
 * ——导出随后成败都算一次触发，防滥用的语义如此。限额拒绝抛
 * {@link ExportRateLimitedException}，HTTP 适配层映射 429 RATE_LIMITED（06 预留码由 05 产出）。
 *
 * 「每日」的口径：按东八区自然日计（平台使用者与交易日历都在东八区），不引入新配置键。
 */
@Component
public class ExportRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ExportRateLimiter.class);

    /** 热配置起始值（bob 裁定）：每用户每日导出触发次数上限。 */
    static final int DEFAULT_DAILY_LIMIT_PER_USER = 50;
    /** 热配置起始值（bob 裁定）：同一对象重复导出的最小间隔秒数。 */
    static final int DEFAULT_REEXPORT_COOLDOWN_SECONDS = 60;

    private static final ZoneId TRADING_ZONE = ZoneId.of("Asia/Shanghai");

    private final ExportRateLedgerMapper ledgerMapper;
    private final DatafrogRuntimeConfigLoader configLoader;

    public ExportRateLimiter(ExportRateLedgerMapper ledgerMapper, DatafrogRuntimeConfigLoader configLoader) {
        this.ledgerMapper = ledgerMapper;
        this.configLoader = configLoader;
    }

    /**
     * 判断本次导出触发是否放行；放行即记账，拒绝抛 {@link ExportRateLimitedException}。
     * 冷却判断在前（同一对象 60 秒内重复导出是最常见的误操作），额度判断在后。
     */
    public void tryAcquire(String userId, String objectId) {
        long now = System.currentTimeMillis();
        long last = ledgerMapper.lastExportedAt(userId, objectId);
        int cooldownSeconds = cooldownSeconds();
        if (last != null && now - last < cooldownSeconds * 1000L) {
            long waitSeconds = (cooldownSeconds * 1000L - (now - last) + 999) / 1000;
            throw new ExportRateLimitedException(
                    "该对象冷却中：距上次导出不足 " + cooldownSeconds + " 秒，约 " + waitSeconds + " 秒后可再导出");
        }
        long dayStartMs = LocalDate.now(TRADING_ZONE).atStartOfDay(TRADING_ZONE).toInstant().toEpochMilli();
        long used = ledgerMapper.countSince(userId, dayStartMs);
        int dailyLimit = dailyLimit();
        if (used >= dailyLimit) {
            throw new ExportRateLimitedException(
                    "今日导出额度已用完：" + used + "/" + dailyLimit + " 次（按东八区自然日计），明日重置");
        }
        ledgerMapper.insert(userId, objectId, now);
        log.info("export trigger recorded: user={} object={} used={}/{}", userId, objectId, used + 1, dailyLimit);
    }

    private int dailyLimit() {
        DatafrogRuntimeConfig cfg = configLoader.current().orElse(null);
        Integer hot = cfg == null ? null : cfg.getExportDailyLimitPerUser();
        return hot != null && hot > 0 ? hot : DEFAULT_DAILY_LIMIT_PER_USER;
    }

    private int cooldownSeconds() {
        DatafrogRuntimeConfig cfg = configLoader.current().orElse(null);
        Integer hot = cfg == null ? null : cfg.getExportReexportCooldownSeconds();
        return hot != null && hot >= 0 ? hot : DEFAULT_REEXPORT_COOLDOWN_SECONDS;
    }
}
