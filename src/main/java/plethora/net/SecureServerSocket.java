package plethora.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;

public class SecureServerSocket implements Closeable {
    private final ServerSocket serverSocket;

    public SecureServerSocket(int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
    }

    public SecureSocket accept() throws IOException {
        Socket socket = serverSocket.accept();
        SecureSocket secureSocket = new SecureSocket(socket);
        try {
            secureSocket.performHandshake();
        } catch (Exception e) {
            secureSocket.close();
            throw new IOException("Handshake failed", e);
        }
        return secureSocket;
    }

    // ============== ServerSocket兼容方法 ==============

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