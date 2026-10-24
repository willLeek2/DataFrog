package world.willfrog.datafrog.server.rpc;

import org.junit.jupiter.api.Test;
import world.willfrog.datafrog.idl.HelloReply;
import world.willfrog.datafrog.idl.HelloRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 纯单元测试，不启动 Spring 上下文，因此不需要 Nacos 或任何中间件。
 * 不起上下文的原因：@EnableDubbo 的上下文在启动时会尝试连接注册中心，
 * 本机开发环境没有常驻 Nacos，上下文级测试应留给有编排环境的阶段。
 */
class HelloServiceImplTest {

    private final HelloServiceImpl service = new HelloServiceImpl();

    @Test
    void blankNameReturnsHelloWorld() {
        HelloReply reply = service.sayHello(HelloRequest.getDefaultInstance());
        assertEquals("Hello World", reply.getMessage());
    }

    @Test
    void providedNameIsAppended() {
        HelloReply reply = service.sayHello(HelloRequest.newBuilder().setName("datafrog").build());
        assertEquals("Hello World, datafrog", reply.getMessage());
    }
}
