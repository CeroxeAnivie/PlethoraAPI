package plethora.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class SecureServerSocket implements Closeable {
    public static final int DEFAULT_TIMEOUT_MS = 1000;
    private final ServerSocket serverSocket;

    // *** 优化点 1: 使用 ConcurrentHashSet 实现 O(1) 复杂度的 IP 查找 ***
    // 即使黑名单有 10 万个 IP，accept 时的判断速度也不会下降
    private final Set<String> ignoreIPs = ConcurrentHashMap.newKeySet();

    public SecureServerSocket(int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
    }

    public void addIgnoreIP(String ip) {
        ignoreIPs.add(ip);
    }

    public boolean removeIgnoreIP(String ip) {
        return ignoreIPs.remove(ip);
    }

    /**
     * 保持 API 签名不变，返回类型仍为 CopyOnWriteArrayList。
     * 注意：此处返回的是当前黑名单的“快照”。
     */
    public CopyOnWriteArrayList<String> getIgnoreIPs() {
        return new CopyOnWriteArrayList<>(ignoreIPs);
    }

    public SecureSocket accept() throws IOException {
        Socket socket = serverSocket.accept();
        boolean success = false;
        try {
            // 设置 socket 超时
            socket.setSoTimeout(DEFAULT_TIMEOUT_MS);

            String ip = socket.getInetAddress().getHostAddress();

            // *** 优化点: O(1) 快速查找 ***
            if (ignoreIPs.contains(ip)) {
                socket.close();
                return null;
            }

            SecureSocket secureSocket = new SecureSocket(socket);
            try {
                secureSocket.performServerHandshake();
                success = true;
                return secureSocket;
            } catch (Exception e) {
                secureSocket.close();
                throw new IOException("Handshake failed from " + ip, e);
            }
        } finally {
            if (!success && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ============== ServerSocket 兼容方法 (保持不变) ==============

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