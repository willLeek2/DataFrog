/**
 * 调用 alphafrog 既有微服务的 Dubbo 消费端统一放在本包。
 *
 * 接入步骤：在 datafrog-server/pom.xml 取消目标 alphafrog Api 模块的依赖注释
 * （前提是该 Api 模块已 mvn install 到本机 .m2，或已按版本号发布），
 * 在本包新建消费类并以 @DubboReference 注入契约接口。
 * dubbo.consumer.check=false 已在 application.yml 全局配置，启动时不要求提供方在线。
 */
package world.willfrog.datafrog.server.client;
