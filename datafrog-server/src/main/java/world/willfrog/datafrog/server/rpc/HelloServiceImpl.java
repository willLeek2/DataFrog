package world.willfrog.datafrog.server.rpc;

import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import world.willfrog.datafrog.idl.DubboHelloServiceTriple.HelloServiceImplBase;
import world.willfrog.datafrog.idl.HelloReply;
import world.willfrog.datafrog.idl.HelloRequest;

/**
 * 骨架示例服务，验证 DataFrog 对外 RPC 链路（proto 契约生成、Dubbo 注册、容器化部署）端到端可用。
 * 首批真实数据服务接入后，由 frog 决定本服务去留。
 */
@DubboService
@Service
public class HelloServiceImpl extends HelloServiceImplBase {

    @Override
    public HelloReply sayHello(HelloRequest request) {
        String name = request.getName();
        String message = (name == null || name.isBlank()) ? "Hello World" : "Hello World, " + name;
        return HelloReply.newBuilder().setMessage(message).build();
    }
}
