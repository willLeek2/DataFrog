package world.willfrog.datafrog.server.rpc;

import com.google.protobuf.ByteString;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import world.willfrog.datafrog.idl.Adjustment;
import world.willfrog.datafrog.idl.AppendToObjectRequest;
import world.willfrog.datafrog.idl.AppendToObjectResponse;
import world.willfrog.datafrog.idl.AssertionDetail;
import world.willfrog.datafrog.idl.AssertionSpec;
import world.willfrog.datafrog.idl.AssertionStatus;
import world.willfrog.datafrog.idl.Ccy;
import world.willfrog.datafrog.idl.CreateNewVersionRequest;
import world.willfrog.datafrog.idl.CreateNewVersionResponse;
import world.willfrog.datafrog.idl.DataObjectCard;
import world.willfrog.datafrog.idl.DubboDataObjectServiceTriple;
import world.willfrog.datafrog.idl.DefinitionStatus;
import world.willfrog.datafrog.idl.DeleteDataObjectRequest;
import world.willfrog.datafrog.idl.DeleteDataObjectResponse;
import world.willfrog.datafrog.idl.ErrorCode;
import world.willfrog.datafrog.idl.ExportDataObjectRequest;
import world.willfrog.datafrog.idl.ExportDataObjectResponse;
import world.willfrog.datafrog.idl.ExportShard;
import world.willfrog.datafrog.idl.Frequency;
import world.willfrog.datafrog.idl.GenMethod;
import world.willfrog.datafrog.idl.GetAvailabilityAssertionsRequest;
import world.willfrog.datafrog.idl.GetAvailabilityAssertionsResponse;
import world.willfrog.datafrog.idl.GetDataObjectFreshnessRequest;
import world.willfrog.datafrog.idl.GetDataObjectFreshnessResponse;
import world.willfrog.datafrog.idl.GetDataObjectRequest;
import world.willfrog.datafrog.idl.GetDataObjectResponse;
import world.willfrog.datafrog.idl.GetSemanticDefinitionRequest;
import world.willfrog.datafrog.idl.GetSemanticDefinitionResponse;
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
import world.willfrog.datafrog.idl.ResultInfo;
import world.willfrog.datafrog.idl.SemanticDefinitionCard;
import world.willfrog.datafrog.idl.SemanticRef;
import world.willfrog.datafrog.idl.SourceType;
import world.willfrog.datafrog.idl.Visibility;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectMapper;
import world.willfrog.datafrog.server.objectdata.repo.SemanticDefinitionRow;
import world.willfrog.datafrog.server.objectdata.service.ContentStorageService;
import world.willfrog.datafrog.server.objectdata.service.DataObjectRegistryService;
import world.willfrog.datafrog.server.objectdata.service.ObjectStoreExceptions;
import world.willfrog.datafrog.server.objectdata.service.SemanticDefinitionService;

import java.util.List;
import java.util.Locale;

/**
 * 数据对象存储模块对外 Dubbo 入口（014-data-01）。
 * 业务失败不在异常栈上走协议：每个应答带 ResultInfo（ok=false 时给可机读错误码与 message）。
 * 枚举换算约定：proto 枚举名小写即领域值（system、single_period、owner_visible、qfq…），
 * 唯独币种词表取值大写（CNY/HKD/USD），单独处理；proto 侧 *_UNKNOWN 统一映射为「未填」。
 */
@DubboService
@Service
public class DataObjectServiceImpl extends DubboDataObjectServiceTriple.DataObjectServiceImplBase {

    /** 缺省页大小（httpapi 引用的单一来源）。 */
    public static final int DEFAULT_PAGE_SIZE = 20;
    /** 页大小上限（httpapi 引用的单一来源）。 */
    public static final int MAX_PAGE_SIZE = 200;

    private final DataObjectRegistryService registry;
    private final SemanticDefinitionService definitions;

    public DataObjectServiceImpl(DataObjectRegistryService registry, SemanticDefinitionService definitions) {
        this.registry = registry;
        this.definitions = definitions;
    }

    @Override
    public RegisterDataObjectResponse registerDataObject(RegisterDataObjectRequest request) {
        try {
            var cmd = new DataObjectRegistryService.RegisterCommand(
                    request.getName(),
                    request.getDescription(),
                    srcValue(request.getSourceType()),
                    request.getOwner(),
                    visValue(request.getVisibility()),
                    request.getSemanticRefsList().stream()
                            .map(r -> new world.willfrog.datafrog.server.objectdata.domain.SemanticRef(
                                    r.getName(), r.getVersion()))
                            .toList(),
                    request.getCoverageScope(),
                    freqValue(request.getFrequency()),
                    genValue(request.getGenMethod()),
                    request.getGenTask(),
                    request.getInputsList(),
                    request.getPrimaryKeyList(),
                    adjValue(request.getAdjustment()),
                    ccyValue(request.getCcy()),
                    request.getExportFormat(),
                    request.getCompression(),
                    request.getCoverageStart(),
                    request.getCoverageEnd(),
                    request.getCsvContent().toByteArray(),
                    request.getAssertionsList().stream()
                            .map(a -> new world.willfrog.datafrog.server.objectdata.domain.AssertionSpec(
                                    a.getType(), a.getDateValue(), a.getCountValue(), a.getDependsOnList()))
                            .toList());
            return RegisterDataObjectResponse.newBuilder()
                    .setResult(ok())
                    .setCard(toCard(registry.register(cmd)))
                    .build();
        } catch (Exception e) {
            return RegisterDataObjectResponse.newBuilder().setResult(error(e)).build();
        }
    }

    @Override
    public AppendToObjectResponse appendToObject(AppendToObjectRequest request) {
        try {
            return AppendToObjectResponse.newBuilder()
                    .setResult(ok())
                    .setCard(toCard(registry.append(request.getObjectId(),
                            request.getCsvContent().toByteArray(), request.getNote())))
                    .build();
        } catch (Exception e) {
            return AppendToObjectResponse.newBuilder().setResult(error(e)).build();
        }
    }

    @Override
    public CreateNewVersionResponse createNewVersion(CreateNewVersionRequest request) {
        try {
            return CreateNewVersionResponse.newBuilder()
                    .setResult(ok())
                    .setCard(toCard(registry.newVersion(request.getObjectId(), request.getVersionNote(),
                            request.getCoverageStart(), request.getCoverageEnd(),
                            request.getCsvContent().toByteArray())))
                    .build();
        } catch (Exception e) {
            return CreateNewVersionResponse.newBuilder().setResult(error(e)).build();
        }
    }

    @Override
    public DeleteDataObjectResponse deleteDataObject(DeleteDataObjectRequest request) {
        try {
            registry.delete(request.getObjectId());
            return DeleteDataObjectResponse.newBuilder().setResult(ok()).build();
        } catch (Exception e) {
            return DeleteDataObjectResponse.newBuilder().setResult(error(e)).build();
        }
    }

    @Override
    public GetDataObjectResponse getDataObject(GetDataObjectRequest request) {
        try {
            return GetDataObjectResponse.newBuilder()
                    .setResult(ok())
                    .setCard(toCard(registry.get(request.getObjectId())))
                    .build();
        } catch (Exception e) {
            return GetDataObjectResponse.newBuilder().setResult(error(e)).build();
        }
    }

    @Override
    public ListDataObjectsResponse listDataObjects(ListDataObjectsRequest request) {
        try {
            int offset = offsetOf(request.getPageToken());
            int size = pageSizeOf(request.getPageSize());
            // 过滤组全留空=01 纯分页行为（直查 listActive）；有任一条件走过滤面。
            // 断言状态过滤在 service 层现算（结论不落库），token 仍是偏移量、不编码过滤条件。
            DataObjectMapper.ListFilters filters = new DataObjectMapper.ListFilters(
                    request.getFilterSemanticName(),
                    request.getFilterFieldName(),
                    request.getFilterCoverageScopeSubstring(),
                    request.getFilterCoverageStartFrom(),
                    request.getFilterCoverageEndTo(),
                    request.getFilterFreshnessSince(),
                    request.getFilterOwner());
            world.willfrog.datafrog.server.objectdata.domain.AssertionStatus statusFilter =
                    statusFilterOf(request.getFilterAssertionStatus());
            DataObjectRegistryService.ListPage page = filters.isEmpty() && statusFilter == null
                    ? registry.list(size, offset)
                    : registry.list(filters, statusFilter, size, offset);
            ListDataObjectsResponse.Builder b = ListDataObjectsResponse.newBuilder().setResult(ok());
            for (DataObjectRegistryService.CardView view : page.cards()) {
                b.addCards(toCard(view));
            }
            if (offset + page.cards().size() < page.total()) {
                b.setNextPageToken(String.valueOf(offset + page.cards().size()));
            }
            return b.build();
        } catch (Exception e) {
            return ListDataObjectsResponse.newBuilder().setResult(error(e)).build();
        }
    }

    @Override
    public RegisterSemanticDefinitionResponse registerSemanticDefinition(RegisterSemanticDefinitionRequest request) {
        try {
            SemanticDefinitionRow row = definitions.register(request.getName(), request.getVersion(),
                    request.getDisplayName(), request.getDefinition(), request.getCalculation(),
                    request.getUnit(), request.getScope(), "manual");
            return RegisterSemanticDefinitionResponse.newBuilder()
                    .setResult(ok())
                    .setCard(toDefinitionCard(row))
                    .build();
        } catch (Exception e) {
            return RegisterSemanticDefinitionResponse.newBuilder().setResult(error(e)).build();
        }
    }

    @Override
    public GetSemanticDefinitionResponse getSemanticDefinition(GetSemanticDefinitionRequest request) {
        try {
            SemanticDefinitionRow row = definitions.get(request.getName(), request.getVersion())
                    .orElseThrow(() -> new ObjectStoreExceptions.ObjectNotFoundException(
                            "sd_" + request.getName() + "_v" + request.getVersion()));
            return GetSemanticDefinitionResponse.newBuilder()
                    .setResult(ok())
                    .setCard(toDefinitionCard(row))
                    .build();
        } catch (Exception e) {
            return GetSemanticDefinitionResponse.newBuilder().setResult(error(e)).build();
        }
    }

    @Override
    public ListSemanticDefinitionsResponse listSemanticDefinitions(ListSemanticDefinitionsRequest request) {
        try {
            int offset = offsetOf(request.getPageToken());
            int size = pageSizeOf(request.getPageSize());
            List<SemanticDefinitionRow> rows = definitions.list(size, offset);
            ListSemanticDefinitionsResponse.Builder b = ListSemanticDefinitionsResponse.newBuilder().setResult(ok());
            for (SemanticDefinitionRow row : rows) {
                b.addCards(toDefinitionCard(row));
            }
            long total = definitions.count();
            if (offset + rows.size() < total) {
                b.setNextPageToken(String.valueOf(offset + rows.size()));
            }
            return b.build();
        } catch (Exception e) {
            return ListSemanticDefinitionsResponse.newBuilder().setResult(error(e)).build();
        }
    }

    @Override
    public ExportDataObjectResponse exportDataObject(ExportDataObjectRequest request) {
        try {
            DataObjectRegistryService.ExportView view = registry.export(request.getObjectId(), request.getVersion());
            ContentStorageService.ExportResult result = view.result();
            ExportDataObjectResponse.Builder b = ExportDataObjectResponse.newBuilder()
                    .setResult(ok())
                    .setShardCount(result.shards().size())
                    .setAggregateChecksum(result.aggregateChecksum())
                    .setExportDir(result.exportDir());
            for (ContentStorageService.ExportShard shard : result.shards()) {
                b.addShards(ExportShard.newBuilder()
                        .setFileName(shard.fileName())
                        .setByteSize(shard.byteSize())
                        .setSha256(shard.sha256()));
            }
            return b.build();
        } catch (Exception e) {
            return ExportDataObjectResponse.newBuilder().setResult(error(e)).build();
        }
    }

    // 014-data-04：freshness 查询（三种情形的 note 文案见 service 注释）。
    @Override
    public GetDataObjectFreshnessResponse getDataObjectFreshness(GetDataObjectFreshnessRequest request) {
        try {
            DataObjectRegistryService.FreshnessView view = registry.freshness(request.getObjectId());
            return GetDataObjectFreshnessResponse.newBuilder()
                    .setResult(ok())
                    .setCoverageEnd(view.coverageEnd() == null ? "" : view.coverageEnd())
                    .setContinuousCompleteDate(view.continuousCompleteDate() == null ? "" : view.continuousCompleteDate())
                    .setHasGapAfterWatermark(view.hasGapAfterWatermark())
                    .setNote(view.note() == null ? "" : view.note())
                    .setAssertionStatus(statusValue(view.assertionStatus()))
                    .build();
        } catch (Exception e) {
            return GetDataObjectFreshnessResponse.newBuilder().setResult(error(e)).build();
        }
    }

    // 014-data-04：可用性断言逐条现算详单（结果不落库）。
    @Override
    public GetAvailabilityAssertionsResponse getAvailabilityAssertions(GetAvailabilityAssertionsRequest request) {
        try {
            DataObjectRegistryService.AssertionsView view = registry.assertionsOf(request.getObjectId());
            GetAvailabilityAssertionsResponse.Builder b = GetAvailabilityAssertionsResponse.newBuilder()
                    .setResult(ok())
                    .setAssertionStatus(statusValue(view.status()));
            for (DataObjectRegistryService.AssertionOutcome outcome : view.details()) {
                b.addDetails(toAssertionDetail(outcome));
            }
            return b.build();
        } catch (Exception e) {
            return GetAvailabilityAssertionsResponse.newBuilder().setResult(error(e)).build();
        }
    }

    // 014-data-04：按版本读已入库内容的一段字节（对象数据进沙箱的主通道；只读不写）。
    @Override
    public ReadObjectDataResponse readObjectData(ReadObjectDataRequest request) {
        try {
            DataObjectRegistryService.DataPageView page = registry.readObjectData(
                    request.getObjectId(), request.getVersion(), request.getPageToken(), request.getPageSize());
            return ReadObjectDataResponse.newBuilder()
                    .setResult(ok())
                    .setData(ByteString.copyFrom(page.data()))
                    .setNextPageToken(page.nextPageToken() == null ? "" : page.nextPageToken())
                    .setPartIndex(page.partIndex())
                    .setPartBoundaryEnd(page.partBoundaryEnd())
                    .build();
        } catch (Exception e) {
            return ReadObjectDataResponse.newBuilder().setResult(error(e)).build();
        }
    }

    // ---- 枚举与信封换算 ----

    private static ResultInfo ok() {
        return ResultInfo.newBuilder().setOk(true).setCode(ErrorCode.OK).build();
    }

    private static ResultInfo error(Exception e) {
        ErrorCode code;
        if (e instanceof ObjectStoreExceptions.SemanticRefNotFoundException) {
            code = ErrorCode.SEMANTIC_REF_NOT_FOUND;
        } else if (e instanceof ObjectStoreExceptions.DuplicateDefinitionException) {
            code = ErrorCode.DEFINITION_ALREADY_EXISTS;
        } else if (e instanceof ObjectStoreExceptions.ConventionViolationException) {
            code = ErrorCode.INVALID_CONVENTION;
        } else if (e instanceof ObjectStoreExceptions.ObjectNotFoundException) {
            code = ErrorCode.OBJECT_NOT_FOUND;
        } else if (e instanceof ObjectStoreExceptions.NoActiveVersionException) {
            code = ErrorCode.NO_ACTIVE_VERSION;
        } else if (e instanceof ObjectStoreExceptions.DuplicateVersionException) {
            code = ErrorCode.DUPLICATE_VERSION;
        } else if (e instanceof ObjectStoreExceptions.TablesNotReadyException) {
            code = ErrorCode.TABLES_NOT_READY;
        } else if (e instanceof ObjectStoreExceptions.InvalidArgumentException
                || e instanceof IllegalArgumentException) {
            code = ErrorCode.INVALID_ARGUMENT;
        } else {
            code = ErrorCode.INTERNAL;
        }
        return ResultInfo.newBuilder().setOk(false).setCode(code)
                .setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).build();
    }

    private static DataObjectCard toCard(DataObjectRegistryService.CardView view) {
        var o = view.object();
        DataObjectCard.Builder b = DataObjectCard.newBuilder()
                .setId(o.id())
                .setName(o.name())
                .setDescription(o.description())
                .setSourceType(SourceType.valueOf(o.sourceType().name()))
                .setOwner(o.owner())
                .setVisibility(Visibility.valueOf(o.visibility().name()))
                .setCoverageScope(o.coverageScope())
                .setFrequency(Frequency.valueOf(o.frequency().name()))
                .setGenMethod(GenMethod.valueOf(o.genMethod().name()))
                .setGenTask(o.genTask() == null ? "" : o.genTask())
                .addAllInputs(o.inputs())
                .addAllPrimaryKey(o.primaryKey())
                .setCurrentVersion(o.currentVersion())
                .setExportFormat(o.exportFormat())
                .setCompression(o.compression())
                .setCreatedAt(o.createdAt())
                .setUpdatedAt(o.updatedAt())
                .setRowCount(view.rowCount())
                .setCoverageStart(view.coverageStart() == null ? "" : view.coverageStart())
                .setCoverageEnd(view.coverageEnd() == null ? "" : view.coverageEnd())
                .setContentChecksum(view.contentChecksum() == null ? "" : view.contentChecksum())
                .setAssertionStatus(statusValue(view.assertionStatus()));
        for (var ref : o.semanticRefs()) {
            b.addSemanticRefs(SemanticRef.newBuilder().setName(ref.name()).setVersion(ref.version()));
        }
        if (o.adjustment() != null) {
            b.setAdjustment(Adjustment.valueOf(o.adjustment().name()));
        }
        if (o.ccy() != null) {
            b.setCcy(Ccy.valueOf(o.ccy().name()));
        }
        return b.build();
    }

    /** 断言详单：spec 原样带回（登记什么回什么，认不出的类型也原样展示），actual 给实际值对照。 */
    private static AssertionDetail toAssertionDetail(DataObjectRegistryService.AssertionOutcome outcome) {
        world.willfrog.datafrog.server.objectdata.domain.AssertionSpec spec = outcome.spec();
        AssertionDetail.Builder b = AssertionDetail.newBuilder()
                .setSpec(AssertionSpec.newBuilder()
                        .setType(spec.type() == null ? "" : spec.type())
                        .setDateValue(spec.dateValue() == null ? "" : spec.dateValue())
                        .setCountValue(spec.countValue())
                        .addAllDependsOn(spec.dependsOn() == null ? List.of() : spec.dependsOn()))
                .setPassed(outcome.passed())
                .setActual(outcome.actual() == null ? "" : outcome.actual())
                .setReason(outcome.reason() == null ? "" : outcome.reason());
        return b.build();
    }

    /** 断言状态：proto → 领域同名换算；过滤参数的 *_UNKNOWN 表示不加该条件，统一映射为 null。 */
    private static world.willfrog.datafrog.server.objectdata.domain.AssertionStatus statusFilterOf(AssertionStatus v) {
        return v == AssertionStatus.ASSERTION_STATUS_UNKNOWN
                ? null
                : world.willfrog.datafrog.server.objectdata.domain.AssertionStatus.valueOf(v.name());
    }

    /** 断言状态：领域 → proto 同名换算（应答侧始终有值，*_UNKNOWN 不会出现）。 */
    private static AssertionStatus statusValue(world.willfrog.datafrog.server.objectdata.domain.AssertionStatus v) {
        return v == null ? AssertionStatus.ASSERTION_STATUS_UNKNOWN : AssertionStatus.valueOf(v.name());
    }

    private static SemanticDefinitionCard toDefinitionCard(SemanticDefinitionRow row) {
        return SemanticDefinitionCard.newBuilder()
                .setName(row.name())
                .setDisplayName(row.displayName())
                .setDefinition(row.definition())
                .setCalculation(row.calculation() == null ? "" : row.calculation())
                .setUnit(row.unit() == null ? "" : row.unit())
                .setScope(row.scope() == null ? "" : row.scope())
                .setVersion(row.version())
                .setStatus(DefinitionStatus.valueOf(row.status()))
                .setOrigin(row.origin())
                .setCreatedAt(row.createdAt())
                .build();
    }

    // source_type 同为库侧非空列（bob 261028 补漏：与 visibility/frequency/gen_method 同一类，
    // 未声明时同样按约定违例拒绝，词表 system | user；adjustment/ccy 两列允许空、保持未声明为空不动）。
    private static String srcValue(SourceType v) {
        if (v == SourceType.SOURCE_UNKNOWN) {
            throw new ObjectStoreExceptions.ConventionViolationException(
                    "source_type 未声明（词表：system | user，见规范篇第 14 节）");
        }
        return v.name().toLowerCase(Locale.ROOT);
    }

    // 014-data-04 泳道实测修：枚举声明字段（visibility/frequency/gen_method）在库里全部 NOT NULL，
    // 此前未声明（proto3 默认 UNKNOWN）映射成 null 一路进 INSERT，炸成 INTERNAL+SQL 原文——
    // 调用方既拿不到可读错误码，也不知道缺了哪个声明。改成 fail-closed：未声明按约定违例拒绝，
    // 错误信息点名缺的字段与词表（规范篇第 14 节）。
    private static String visValue(Visibility v) {
        if (v == Visibility.VISIBILITY_UNKNOWN) {
            throw new ObjectStoreExceptions.ConventionViolationException(
                    "visibility 未声明（词表：private | owner_visible，见规范篇第 14 节）");
        }
        return v.name().toLowerCase(Locale.ROOT);
    }

    private static String freqValue(Frequency v) {
        if (v == Frequency.FREQUENCY_UNKNOWN) {
            throw new ObjectStoreExceptions.ConventionViolationException(
                    "frequency 未声明（词表：daily | single_period，见规范篇第 14 节）");
        }
        return v.name().toLowerCase(Locale.ROOT);
    }

    private static String genValue(GenMethod v) {
        if (v == GenMethod.GEN_METHOD_UNKNOWN) {
            throw new ObjectStoreExceptions.ConventionViolationException(
                    "gen_method 未声明（词表：fetch | indicator | agent，见规范篇第 14 节）");
        }
        return v.name().toLowerCase(Locale.ROOT);
    }

    private static String adjValue(Adjustment v) {
        return v == Adjustment.ADJUSTMENT_UNKNOWN ? null : v.name().toLowerCase(Locale.ROOT);
    }

    /** 词表口径币种取值大写（CNY/HKD/USD）。 */
    private static String ccyValue(Ccy v) {
        return v == Ccy.CCY_UNKNOWN ? null : v.name();
    }

    private static int offsetOf(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(token.trim());
            if (offset < 0) {
                throw new NumberFormatException(token);
            }
            return offset;
        } catch (NumberFormatException e) {
            throw new ObjectStoreExceptions.InvalidArgumentException("page_token 非法: " + token);
        }
    }

    private static int pageSizeOf(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
