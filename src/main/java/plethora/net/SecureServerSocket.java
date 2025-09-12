package plethora.net;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class SecureServerSocket {
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

    public void close() throws IOException {
        serverSocket.close();
    }

    public boolean isClosed() {
        return serverSocket.isClosed();
    }

    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }
}