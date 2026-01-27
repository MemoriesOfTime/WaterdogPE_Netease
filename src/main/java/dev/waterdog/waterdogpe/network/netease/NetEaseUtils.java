package dev.waterdog.waterdogpe.network.netease;

import lombok.extern.log4j.Log4j2;
import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig;

import java.util.Arrays;
import java.util.List;

/**
 * Netease协议处理工具类
 */
@Log4j2
public class NetEaseUtils {

    private final static List<Integer> SUPPORTED_PROTOCOL_VERSIONS = Arrays.asList(
            ProtocolVersion.MINECRAFT_PE_1_21_2.getProtocol(),
            ProtocolVersion.MINECRAFT_PE_1_21_50.getProtocol()
    );
    
    /**
     * 检查给定的协议版本和数据是否表示Netease客户端
     */
    public static boolean isNetEaseClient(int raknetProtocol, int protocolVersion) {
        // config.yml中，需开启配置
        ProxyConfig config = ProxyServer.getInstance().getConfiguration();
        if (!config.netEaseClient()) {
            return false;
        }

        // 识别条件：raknetProtocol == 8 且 protocol 是网易支持的版本
        if (raknetProtocol == 8 && SUPPORTED_PROTOCOL_VERSIONS.contains(protocolVersion)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查给定的协议版本和数据是否表示Netease客户端
     */
    public static boolean isNetEaseClient(int raknetProtocol) {
        // 简化版本，不使用协议进行判断
        return isNetEaseClient(raknetProtocol, ProtocolVersion.MINECRAFT_PE_1_21_2.getProtocol());
    }

}