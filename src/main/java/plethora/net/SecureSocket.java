package plethora.net;

import plethora.security.encryption.AESUtil;
import plethora.security.encryption.RSAUtil;
import plethora.utils.Sleeper;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PublicKey;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public class SecureSocket {
    private final Socket socket;
    private final ObjectInputStream objectInputStream;
    private final ObjectOutputStream objectOutputStream;
    public static final int RSA_LENGTH = 2048;
    private final RSAUtil rsaUtil;
    private AESUtil aesUtil;
    private static final String HEARTBREAK_MESSAGE = "0";
    private CopyOnWriteArrayList<Object> messageBox;

    public SecureSocket(String host, int port) throws IOException {
        rsaUtil = new RSAUtil(RSA_LENGTH);

        socket = new Socket(host, port);
//        socket.setSoTimeout(TIMEOUT_CYCLE);
        objectInputStream = new ObjectInputStream(socket.getInputStream());
        objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
        this.exchangeKeys();
        this.startCheckAliveThread();
    }

    protected SecureSocket(Socket socket, ObjectInputStream objectInputStream, ObjectOutputStream objectOutputStream, RSAUtil rsaUtil, AESUtil aesUtil) {//server side
        this.socket = socket;
        this.objectOutputStream = objectOutputStream;
        this.objectInputStream = objectInputStream;
        this.rsaUtil = rsaUtil;
        this.aesUtil = aesUtil;
        messageBox = new CopyOnWriteArrayList<>();
        this.initCheckAliveThreadServer();
    }

    public void exchangeKeys() throws IOException {
        // 发送公钥
        objectOutputStream.writeObject(rsaUtil.getPublicKey());
        objectOutputStream.flush();

        // 接收对方RSA公钥
        PublicKey publicKey = null;
        try {
            publicKey = (PublicKey) objectInputStream.readObject();
        } catch (ClassNotFoundException ignore) {
        }//impossible

        //替换自己rsaUtil里的公钥为对方的
        rsaUtil.setPublicKey(publicKey);

        // 获取AES密钥并解密
        try {
            SecretKey secretKey = new SecretKeySpec(Objects.requireNonNull(rsaUtil.decrypt((byte[]) objectInputStream.readObject())), "AES");
            aesUtil = new AESUtil(secretKey);
        } catch (ClassNotFoundException ignore) {
        }//impossible
    }

    public void sendStr(String str) throws IOException {
        objectOutputStream.writeObject(aesUtil.encrypt(str.getBytes(StandardCharsets.UTF_8)));
        objectOutputStream.flush();
    }

    public void sendFile(File file) throws IOException {
        objectOutputStream.writeObject(new DataPacket(file.length(), aesUtil.encrypt(Files.readAllBytes(file.toPath()))));
        objectOutputStream.flush();
    }

    public String receiveStr() throws IOException {
        if (this.messageBox != null) {//server
            Object o = this.getFromBox();
            String str = new String(aesUtil.decrypt((byte[]) o), StandardCharsets.UTF_8);
            return str;
        } else {//client
            String str = null;
            try {
                str = new String(aesUtil.decrypt((byte[]) objectInputStream.readObject()), StandardCharsets.UTF_8);
            } catch (ClassNotFoundException e) {
            }//impossible
            return str;
        }

    }

    public byte[] receiveFileBytes() throws IOException {
        if (this.messageBox != null) {//server
            DataPacket dataPacket = (DataPacket) this.getFromBox();
            return aesUtil.decrypt(dataPacket.enData);
        } else {//client

            DataPacket dataPacket = null;
            try {
                dataPacket = (DataPacket) objectInputStream.readObject();
            } catch (ClassNotFoundException ignore) {
            }//ignore
            return aesUtil.decrypt(dataPacket.enData);
        }
    }

    private Object getFromBox() throws IOException {
        while (true) {
            if (socket.isClosed()) {
                throw new IOException();
            }
            if (messageBox.isEmpty()) {
                Sleeper.sleep(100);
            } else {
                Object o = messageBox.remove(0);
                if (o instanceof byte[] || o instanceof DataPacket) {
                    return o;
                }
            }
        }
    }

    public void close() {
        try {
            socket.close();
        } catch (Exception ignore) {
        }//impossible
        System.gc();
    }

    private void startCheckAliveThread() {
        new Thread(() -> {
            try {
                objectOutputStream.writeObject(HEARTBREAK_MESSAGE);
                objectOutputStream.flush();
                Sleeper.sleep(5000);
            } catch (IOException e) {
                this.close();
            }
        }).start();
    }

    private void initCheckAliveThreadServer() {
        new Thread(() -> {
            while (true) {
                try {
                    messageBox.add(objectInputStream.readObject());
                } catch (Exception e) {//closed;
                    this.close();
                    break;
                }

            }
        }).start();
    }

    public void setSoTimeout(int timeout) throws SocketException {
        this.socket.setSoTimeout(timeout);
    }
}
