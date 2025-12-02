package plethora.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工业级安全服务端 Socket (Java 21 虚拟线程增强版)
 * <p>
 * 特性：
 * 1. 彻底移除 synchronized，适配虚拟线程调度。
 * 2. 默认开启 TCP KeepAlive 和 NoDelay，增强长连接稳定性。
 * 3. 内置僵尸连接熔断与黑名单 O(1) 拦截。
 */
public class SecureServerSocket implements Closeable {
    // 增大接收缓冲区以应对高并发突发流量 (128KB)
    private static final int RECEIVE_BUFFER_SIZE = 128 * 1024;
    // 握手防御超时：如果客户端连接后 1秒 内不完成握手，将被视为僵尸连接并断开
    private static volatile int ZOMBIE_DEFENSE_TIMEOUT = 1000;
    private final ServerSocket serverSocket;
    // 优化：使用 ConcurrentKeySet 实现 O(1) IP 查找
    private final Set<String> ignoreIPs = ConcurrentHashMap.newKeySet();

    public SecureServerSocket(int port) throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true); // 允许快速重启复用端口
        this.serverSocket.setReceiveBufferSize(RECEIVE_BUFFER_SIZE);
        this.serverSocket.bind(new InetSocketAddress(port));
    }

    public static int getZombieDefenseTimeout() {
        return ZOMBIE_DEFENSE_TIMEOUT;
    }

    public static boolean setZombieDefenseTimeout(int zombieDefenseTimeout) {
        if (zombieDefenseTimeout >= 0) {
            ZOMBIE_DEFENSE_TIMEOUT = zombieDefenseTimeout;
            return true;
        }
        return false;
    }

    public void addIgnoreIP(String ip) {
        ignoreIPs.add(ip);
    }

    public boolean removeIgnoreIP(String ip) {
        return ignoreIPs.remove(ip);
    }

    public CopyOnWriteArrayList<String> getIgnoreIPs() {
        return new CopyOnWriteArrayList<>(ignoreIPs);
    }

    /**
     * 接收客户端连接。
     * 该方法是阻塞的，直到建立一个非黑名单的有效连接。
     * 建议在虚拟线程中运行此方法。
     *
     * @return 一个已经连接但尚未握手的 SecureSocket 实例
     * @throws IOException 如果 ServerSocket 被关闭或发生严重 IO 错误
     */
    public SecureSocket accept() throws IOException {
        while (!serverSocket.isClosed()) {
            Socket socket = null;
            try {
                // 1. 阻塞等待 TCP 连接建立
                // 在 Java 21 虚拟线程中，这里的阻塞会自动卸载，不会占用平台线程
                socket = serverSocket.accept();

                InetAddress inetAddress = socket.getInetAddress();
                // 极罕见情况防御
                if (inetAddress == null) {
                    closeSocketQuietly(socket);
                    continue;
                }

                String ip = inetAddress.getHostAddress();

                // 2. 黑名单极速拦截
                if (ignoreIPs.contains(ip)) {
                    // 默默关闭，不通知上层，直接进行下一次循环
                    closeSocketQuietly(socket);
                    continue;
                }

                // 3. 配置 Socket 底层参数 (增强长连接稳定性)
                configureSocket(socket);

                // 4. 实例化对象，但不执行握手 (懒加载模式)
                SecureSocket secureSocket = new SecureSocket(socket);
                secureSocket.initServerMode();

                // 5. 返回有效连接
                return secureSocket;

            } catch (SocketException e) {
                // 如果是 ServerSocket 关闭导致的异常，抛出给上层退出循环
                if (serverSocket.isClosed()) {
                    throw e;
                }
                // 其他 Socket 异常（如连接重置），关闭当前 socket 并重试
                closeSocketQuietly(socket);
            } catch (IOException e) {
                // 其他 IO 异常，关闭当前 socket 并重试
                closeSocketQuietly(socket);
            }
        }
        throw new SocketException("ServerSocket is closed");
    }

    /**
     * 配置底层 Socket 参数以适应长连接和高性能场景
     */
    private void configureSocket(Socket socket) throws SocketException {
        // *** 核心优化 1: 立即设置超时，防御僵尸连接 ***
        // 握手必须在 ZOMBIE_DEFENSE_TIMEOUT 内完成
        socket.setSoTimeout(ZOMBIE_DEFENSE_TIMEOUT);

        // *** 核心优化 2: 开启 KeepAlive ***
        // 这对于长时间没有数据传输的连接至关重要，防止中间网络设备断开连接
        socket.setKeepAlive(true);

        // *** 核心优化 3: 禁用 Nagle 算法 ***
        // 减少小包延迟，对 RPC 类调用非常重要，提升实时性
        socket.setTcpNoDelay(true);
    }

    private void closeSocketQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ============== ServerSocket 兼容方法 ==============

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }

    public boolean isClosed() {
        return serverSocket.isClosed();
    }

    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    public InetAddress getInetAddress() {
        return serverSocket.getInetAddress();
    }
}