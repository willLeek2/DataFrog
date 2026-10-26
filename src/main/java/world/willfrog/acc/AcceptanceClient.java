package world.willfrog.acc;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import world.willfrog.datafrog.idl.CreateGenerationTaskRequest;
import world.willfrog.datafrog.idl.CreateGenerationTaskResponse;
import world.willfrog.datafrog.idl.GenerationTaskService;
import world.willfrog.datafrog.idl.GetGenerationTaskRequest;
import world.willfrog.datafrog.idl.GetGenerationTaskResponse;

/**
 * 014-data-02 泳道验收消费端：从开发机直连 Beta 泳道 datafrog-server 的 tri 端口，
 * 逐个调用 GenerationTaskService 的两个 RPC 并打印完整响应。
 *
 * 连接形态：Dubbo 编程式 API + ReferenceConfig 直连 tri://host:port，不用 Spring、不用注册中心。
 * 每次运行只打一个 RPC，打印完整响应后退出。
 *
 * 退出码：0=响应无 error_code（成功）；2=响应带 error_code（业务失败）；1=用法/参数错误。
 */
public final class AcceptanceClient {

    /** 泳道验收期服务端鉴权为直通形态（datafrog.auth.passthrough-user-id 非空时任何非空 token 通过），
     *  故默认发送一个非空占位 token；如需指定真实凭证，用各子命令的可选尾参覆盖。 */
    private static final String DEFAULT_SESSION_TOKEN = "acc";

    private static final int RPC_TIMEOUT_MS = 15_000;

    private AcceptanceClient() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(1);
        }
        // 关掉 Dubbo QoS 内嵌端口，避免验收脚本连跑时 22222 端口冲突
        System.setProperty("qos.enable", "false");
        try {
            switch (args[0]) {
                case "create":
                    System.exit(runCreate(args));
                    break;
                case "get":
                    System.exit(runGet(args));
                    break;
                case "help":
                case "--help":
                case "-h":
                    usage();
                    System.exit(0);
                    break;
                default:
                    System.err.println("unknown subcommand: " + args[0]);
                    usage();
                    System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("RPC invocation failed: " + e);
            System.exit(2);
        }
    }

    /**
     * create &lt;host&gt; &lt;port&gt; &lt;targetObjectName&gt; &lt;clientRequestId&gt; &lt;instruction&gt;
     * &lt;timeoutSeconds&gt; [outputFormat] [sessionToken] [model]
     */
    private static int runCreate(String[] args) throws Exception {
        // args[0]="create"，其后 6 个必填 + 3 个可选
        if (args.length < 7 || args.length > 10) {
            System.err.println("create: expected 6 required and up to 3 optional args, got " + (args.length - 1));
            usage();
            return 1;
        }
        String host = args[1];
        int port = parsePort(args[2]);
        String targetObjectName = args[3];
        String clientRequestId = args[4];
        String instruction = args[5];
        // timeoutSeconds 传 "omit" 时不写该键，用于规格校验路径的「timeout_seconds 缺省」用例
        boolean omitTimeout = "omit".equals(args[6]);
        long timeoutSeconds = omitTimeout ? 0 : parseTimeout(args[6]);
        String outputFormat = args.length >= 8 ? args[7] : null;
        // 可选位传空串时按缺省处理，方便脚本在只想传尾部 model 时用 "" 占位
        String sessionToken = args.length >= 9 && !args[8].isBlank() ? args[8] : DEFAULT_SESSION_TOKEN;
        String model = args.length >= 10 && !args[9].isBlank() ? args[9] : null;

        // 按服务端约定组 spec_json：instruction（必填非空）、output_format（选填，缺省不写入该键）、
        // timeout_seconds（必填，整数，服务端限制 60~86400）、model（选填，非空时服务端按阶段覆盖默认模型）。
        // 用 protobuf Struct + JsonFormat 打印保证转义正确。
        Struct.Builder spec = Struct.newBuilder();
        spec.putFields("instruction", Value.newBuilder().setStringValue(instruction).build());
        if (outputFormat != null && !outputFormat.isBlank()) {
            spec.putFields("output_format", Value.newBuilder().setStringValue(outputFormat).build());
        }
        if (!omitTimeout) {
            spec.putFields("timeout_seconds", Value.newBuilder().setNumberValue(timeoutSeconds).build());
        }
        if (model != null) {
            spec.putFields("model", Value.newBuilder().setStringValue(model).build());
        }
        String specJson = JsonFormat.printer().print(spec.build());

        CreateGenerationTaskRequest request = CreateGenerationTaskRequest.newBuilder()
                .setSessionToken(sessionToken)
                .setTargetObjectName(targetObjectName)
                .setSpecJson(specJson)
                .setClientRequestId(clientRequestId)
                .build();

        GenerationTaskService service = refer(host, port);
        CreateGenerationTaskResponse response = service.createGenerationTask(request);

        System.out.println("=== CreateGenerationTask response ===");
        System.out.println("task_id        = " + response.getTaskId());
        System.out.println("status         = " + response.getStatus().name() + " (" + response.getStatusValue() + ")");
        System.out.println("deduplicated   = " + response.getDeduplicated());
        System.out.println("error_code     = " + quote(response.getErrorCode()));
        System.out.println("error_message  = " + quote(response.getErrorMessage()));
        System.out.println("spec_json sent = " + specJson);
        System.out.println("model sent     = " + (model == null ? "<absent>" : model));

        return response.getErrorCode().isEmpty() ? 0 : 2;
    }

    /**
     * get &lt;host&gt; &lt;port&gt; &lt;taskId&gt; [sessionToken]
     */
    private static int runGet(String[] args) throws Exception {
        // args[0]="get"，其后 3 个必填 + 1 个可选
        if (args.length < 4 || args.length > 5) {
            System.err.println("get: expected 3 required and up to 1 optional arg, got " + (args.length - 1));
            usage();
            return 1;
        }
        String host = args[1];
        int port = parsePort(args[2]);
        String taskId = args[3];
        String sessionToken = args.length >= 5 ? args[4] : DEFAULT_SESSION_TOKEN;

        GetGenerationTaskRequest request = GetGenerationTaskRequest.newBuilder()
                .setSessionToken(sessionToken)
                .setTaskId(taskId)
                .build();

        GenerationTaskService service = refer(host, port);
        GetGenerationTaskResponse response = service.getGenerationTask(request);

        System.out.println("=== GetGenerationTask response ===");
        System.out.println("task_id             = " + response.getTaskId());
        System.out.println("status              = " + response.getStatus().name() + " (" + response.getStatusValue() + ")");
        System.out.println("run_id              = " + quote(response.getRunId()));
        System.out.println("incomplete          = " + response.getIncomplete());
        System.out.println("error_code          = " + quote(response.getErrorCode()));
        System.out.println("error_message       = " + quote(response.getErrorMessage()));
        System.out.println("run_status_json     = " + quote(response.getRunStatusJson()));
        System.out.println("credit_charged      = " + response.getCreditCharged());
        System.out.println("credit_settled      = " + response.getCreditSettled());
        System.out.println("created_at_epoch    = " + response.getCreatedAtEpoch());
        System.out.println("finished_at_epoch   = " + response.getFinishedAtEpoch());
        System.out.println("query_error_code    = " + quote(response.getQueryErrorCode()));
        System.out.println("query_error_message = " + quote(response.getQueryErrorMessage()));

        boolean failed = !response.getErrorCode().isEmpty() || !response.getQueryErrorCode().isEmpty();
        return failed ? 2 : 0;
    }

    /** 编程式 API 直连：tri://host:port，无注册中心。用生成的顶层接口 GenerationTaskService
     *  （Dubbo 3.3 codegen 形态：extends DubboStub，含阻塞与异步方法）作 ReferenceConfig 的 interface。 */
    private static GenerationTaskService refer(String host, int port) {
        ReferenceConfig<GenerationTaskService> reference = new ReferenceConfig<>();
        reference.setInterface(GenerationTaskService.class);
        reference.setUrl("tri://" + host + ":" + port);
        reference.setTimeout(RPC_TIMEOUT_MS);
        reference.setRetries(0);
        DubboBootstrap.getInstance()
                .application(new ApplicationConfig("datafrog-acc-client"))
                .reference(reference)
                .start();
        return reference.get();
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + raw);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port is not a number: " + raw, e);
        }
    }

    private static long parseTimeout(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("timeoutSeconds is not an integer: " + raw, e);
        }
    }

    private static String quote(String s) {
        return s == null || s.isEmpty() ? "<empty>" : s;
    }

    private static void usage() {
        System.err.println();
        System.err.println("Usage:");
        System.err.println("  java -cp <classpath> " + AcceptanceClient.class.getName()
                + " create <host> <port> <targetObjectName> <clientRequestId> <instruction> <timeoutSeconds> [outputFormat] [sessionToken] [model]");
        System.err.println("  java -cp <classpath> " + AcceptanceClient.class.getName()
                + " get <host> <port> <taskId> [sessionToken]");
        System.err.println();
        System.err.println("Exit code: 0 = response has no error_code; 2 = response carries error_code"
                + " (get counts error_code or query_error_code); 1 = bad usage.");
        System.err.println("Defaults: sessionToken = \"" + DEFAULT_SESSION_TOKEN
                + "\" (lane acceptance auth passthrough accepts any non-empty token).");
        System.err.println("Optional positional args given as \"\" count as absent"
                + " (lets you pass a trailing model without pinning outputFormat/sessionToken).");
    }
}
