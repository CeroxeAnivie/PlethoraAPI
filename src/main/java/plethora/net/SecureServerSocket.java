package plethora.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class SecureServerSocket implements Closeable {
    // 握手防御超时 1000ms 如果客户端连接后 1秒 内不完成握手，将被视为僵尸连接断开
    private static int ZOMBIE_DEFENSE_TIMEOUT = 1000;
    private final ServerSocket serverSocket;
    // 优化：使用 ConcurrentKeySet 实现 O(1) IP 查找
    private final Set<String> ignoreIPs = ConcurrentHashMap.newKeySet();

    public SecureServerSocket(int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
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
     * 修改后的 accept 方法：
     * 1. 立即返回，不阻塞主线程进行握手。
     * 2. 设置初始超时，防御僵尸连接。
     * 3. 标记 Socket 为服务端模式，启用“懒加载握手”。
     */
    public SecureSocket accept() throws IOException {
        // 1. 阻塞等待 TCP 连接建立（正常行为）
        Socket socket = serverSocket.accept();
        boolean success = false;
        try {
            String ip = socket.getInetAddress().getHostAddress();

            // 2. 黑名单极速拦截
            if (ignoreIPs.contains(ip)) {
                socket.close();
                return null;
            }

            // *** 核心优化 1: 立即设置超时，防御僵尸连接 ***
            socket.setSoTimeout(ZOMBIE_DEFENSE_TIMEOUT);

            // 3. 实例化对象，但不执行握手
            SecureSocket secureSocket = new SecureSocket(socket);

            // *** 核心优化 2: 告诉 Socket 它是服务端，需要后续自动触发握手 ***
            secureSocket.initServerMode();

            // 4. 立即返回，释放主线程
            success = true;
            return secureSocket;

        } finally {
            if (!success && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ============== ServerSocket 兼容方法 ==============

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

    public int getSoTimeout() throws IOException {
        return serverSocket.getSoTimeout();
    }

    public void setSoTimeout(int timeout) throws SocketException {
        serverSocket.setSoTimeout(timeout);
    }

    public SocketAddress getLocalSocketAddress() {
        return serverSocket.getLocalSocketAddress();
    }

    public boolean getReuseAddress() throws SocketException {
        return serverSocket.getReuseAddress();
    }

    public void setReuseAddress(boolean on) throws SocketException {
        serverSocket.setReuseAddress(on);
    }

    public int getReceiveBufferSize() throws SocketException {
        return serverSocket.getReceiveBufferSize();
    }

    public void setReceiveBufferSize(int size) throws SocketException {
        serverSocket.setReceiveBufferSize(size);
    }
}