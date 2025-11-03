package plethora.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class SecureServerSocket implements Closeable {
    private final ServerSocket serverSocket;
    private final CopyOnWriteArrayList<String> ignoreIPs = new CopyOnWriteArrayList<>();
    public static final int DEFAULT_TIMEOUT_MS=1000;

    public SecureServerSocket(int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
    }

    public void addIgnoreIP(String ip) {
        ignoreIPs.add(ip);
    }

    public boolean removeIgnoreIP(String ip) {
        return ignoreIPs.remove(ip);
    }

    public CopyOnWriteArrayList<String> getIgnoreIPs() {
        return ignoreIPs;
    }

    /**
     * *** 修改点: accept方法中调用服务器端专用的握手方法 ***
     */
    public SecureSocket accept() throws IOException {
        Socket socket = serverSocket.accept();
        try {
            // 设置socket超时，防止无限期阻塞
            socket.setSoTimeout(DEFAULT_TIMEOUT_MS); // 1秒超时

            String ip = socket.getInetAddress().getHostAddress();
            if (ignoreIPs.contains(ip)) {
                socket.close();
                return null;
            }

            SecureSocket secureSocket = new SecureSocket(socket);
            try {
                secureSocket.performServerHandshake();
                return secureSocket;
            } catch (Exception e) {
                secureSocket.close();
                throw new IOException("Handshake failed from " + ip, e);
            }
        } catch (IOException e) {
            // 确保在异常情况下关闭socket
            try {
                socket.close();
            } catch (IOException ignored) {}
            throw e;
        }
    }

    // ============== ServerSocket兼容方法 ==============
    // (以下方法保持不变)

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