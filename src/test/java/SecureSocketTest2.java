import plethora.net.SecureServerSocket;
import plethora.net.SecureSocket;

import java.io.IOException;

public class SecureSocketTest2 {
    static void main() {
        new Thread(() -> {
            try {
                SecureServerSocket serverSocket = new SecureServerSocket(44556);
                serverSocket.addIgnoreIP("127.0.0.1");
                SecureSocket socket = serverSocket.accept();
                System.out.println(socket);
                System.out.println("getIgnoreIPs = " + serverSocket.getIgnoreIPs());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();
        new Thread(() -> {
            try {
                SecureSocket socket = new SecureSocket("127.0.0.1", 44556);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();
    }
}
