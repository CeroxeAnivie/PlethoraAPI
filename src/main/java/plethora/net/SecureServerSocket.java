package plethora.net;

import plethora.security.encryption.AESUtil;
import plethora.security.encryption.RSAUtil;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.PublicKey;

public class SecureServerSocket {
    private final ServerSocket serverSocket;
    public static final int RSA_LENGTH = 2048;
    public static final int AES_LENGTH = 128;
    private int socketSoTimeout = -1;

    public SecureServerSocket(int port) throws IOException {
        serverSocket = new ServerSocket(port);
    }

    public SecureSocket accept() throws IOException {//改变原本的握手机制为服务端的
        Socket socket = serverSocket.accept();
        if (socketSoTimeout != -1) {
            socket.setSoTimeout(socketSoTimeout);
        }

        ObjectOutputStream objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
        ObjectInputStream objectInputStream = new ObjectInputStream(socket.getInputStream());

        RSAUtil rsaUtil = new RSAUtil(RSA_LENGTH);
        // 接收对方RSA公钥
        PublicKey publicKey = null;
        try {
            publicKey = (PublicKey) objectInputStream.readObject();
        } catch (ClassNotFoundException ignore) {
        }//impossible

        // 发送公钥
        objectOutputStream.writeObject(rsaUtil.getPublicKey());
        objectOutputStream.flush();

        //替换自己rsaUtil里的公钥为对方的
        rsaUtil.setPublicKey(publicKey);

        // 发送加密的AES密钥
        AESUtil aesUtil;
        aesUtil = new AESUtil(AES_LENGTH);
        objectOutputStream.writeObject(rsaUtil.encrypt(aesUtil.getKeyBytes()));
        return new SecureSocket(socket, objectInputStream, objectOutputStream, rsaUtil, aesUtil);
    }

    public void setSocketSoTimeout(int timeout) {
        this.socketSoTimeout = timeout;

    }

    public void close() {
        try {
            serverSocket.close();
        } catch (Exception ignore) {
        }//impossible
        System.gc();
    }
}
