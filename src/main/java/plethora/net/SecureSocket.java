package plethora.net;

import plethora.security.encryption.AESUtil;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.spec.*;

public class SecureSocket {
    private Socket socket;
    private BufferedInputStream inputStream;
    private BufferedOutputStream outputStream;
    private AESUtil aesUtil;
    private boolean handshakeCompleted = false;

    // 缓冲区大小优化
    private static final int BUFFER_SIZE = 8192;

    public SecureSocket(String host, int port) throws IOException {
        this(new Socket(host, port));
        try {
            performHandshake();
        } catch (Exception e) {
            close();
            throw new IOException("Handshake failed", e);
        }
    }

    public SecureSocket(Socket socket) throws IOException {
        this.socket = socket;
        this.inputStream = new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE);
        this.outputStream = new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE);
    }

    // 执行Diffie-Hellman密钥交换
    void performHandshake() throws Exception {
        // 生成DH参数和密钥对
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("DH");
        keyGen.initialize(2048); // 使用2048位DH参数提高安全性
        KeyPair keyPair = keyGen.generateKeyPair();

        // 发送公钥
        byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
        sendRaw(publicKeyBytes);

        // 接收对方公钥
        byte[] otherPublicKeyBytes = receiveRaw();
        KeyFactory keyFactory = KeyFactory.getInstance("DH");
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(otherPublicKeyBytes);
        PublicKey otherPublicKey = keyFactory.generatePublic(keySpec);

        // 生成共享密钥
        KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
        keyAgreement.init(keyPair.getPrivate());
        keyAgreement.doPhase(otherPublicKey, true);

        byte[] sharedSecret = keyAgreement.generateSecret();

        // 使用SHA-256派生会话密钥
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] sessionKey = digest.digest(sharedSecret);

        // 创建AES工具实例
        SecretKeySpec secretKey = new SecretKeySpec(sessionKey, "AES");
        this.aesUtil = new AESUtil(secretKey);

        handshakeCompleted = true;
    }

    public int sendStr(String message) throws Exception {
        if (!handshakeCompleted) {
            throw new IllegalStateException("Handshake not completed");
        }

        byte[] data = message.getBytes("UTF-8");
        byte[] encrypted = aesUtil.encrypt(data);
        return sendRaw(encrypted);
    }

    public String receiveStr() throws Exception {
        if (!handshakeCompleted) {
            throw new IllegalStateException("Handshake not completed");
        }

        byte[] encrypted = receiveRaw();
        byte[] decrypted = aesUtil.decrypt(encrypted);
        return new String(decrypted, "UTF-8");
    }

    public int sendByte(byte[] data) throws Exception {
        if (!handshakeCompleted) {
            throw new IllegalStateException("Handshake not completed");
        }

        byte[] encrypted = aesUtil.encrypt(data);
        return sendRaw(encrypted);
    }

    public int sendByte(byte[] data, int offset, int length) throws Exception {
        if (!handshakeCompleted) {
            throw new IllegalStateException("Handshake not completed");
        }

        byte[] encrypted = aesUtil.encrypt(data, offset, length);
        return sendRaw(encrypted);
    }

    public int sendInt(int value) throws Exception {
        if (!handshakeCompleted) {
            throw new IllegalStateException("Handshake not completed");
        }

        // 将int转换为4字节数组
        byte[] intBytes = new byte[4];
        intBytes[0] = (byte) (value >> 24);
        intBytes[1] = (byte) (value >> 16);
        intBytes[2] = (byte) (value >> 8);
        intBytes[3] = (byte) value;

        byte[] encrypted = aesUtil.encrypt(intBytes);
        return sendRaw(encrypted);
    }

    public int receiveInt() throws Exception {
        if (!handshakeCompleted) {
            throw new IllegalStateException("Handshake not completed");
        }

        byte[] encrypted = receiveRaw();
        byte[] decrypted = aesUtil.decrypt(encrypted);

        // 将4字节数组转换回int
        if (decrypted.length != 4) {
            throw new IOException("Invalid int data received: expected 4 bytes, got " + decrypted.length);
        }

        return ((decrypted[0] & 0xFF) << 24) |
                ((decrypted[1] & 0xFF) << 16) |
                ((decrypted[2] & 0xFF) << 8) |
                (decrypted[3] & 0xFF);
    }

    public byte[] receiveByte() throws Exception {
        if (!handshakeCompleted) {
            throw new IllegalStateException("Handshake not completed");
        }

        byte[] encrypted = receiveRaw();
        return aesUtil.decrypt(encrypted);
    }

    private int sendRaw(byte[] data) throws IOException {
        // 先发送数据长度
        byte[] lengthBytes = intToBytes(data.length);
        outputStream.write(lengthBytes);
        outputStream.write(data);
        outputStream.flush();

        // 返回实际发送的字节数：4字节长度 + 数据长度
        return 4 + data.length;
    }

    private byte[] receiveRaw() throws IOException {
        // 先读取数据长度
        byte[] lengthBytes = new byte[4];
        int bytesRead = 0;
        while (bytesRead < 4) {
            int result = inputStream.read(lengthBytes, bytesRead, 4 - bytesRead);
            if (result == -1) {
                throw new EOFException("Connection closed while reading length");
            }
            bytesRead += result;
        }

        int length = bytesToInt(lengthBytes);
        if (length <= 0) {
            throw new IOException("Invalid data length: " + length);
        }

        // 使用直接缓冲区读取数据，减少内存分配
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(length);
        byte[] chunk = new byte[Math.min(BUFFER_SIZE, length)];

        int totalRead = 0;
        while (totalRead < length) {
            int toRead = Math.min(chunk.length, length - totalRead);
            int result = inputStream.read(chunk, 0, toRead);
            if (result == -1) {
                throw new EOFException("Connection closed while reading data");
            }
            buffer.write(chunk, 0, result);
            totalRead += result;
        }

        return buffer.toByteArray();
    }

    private byte[] intToBytes(int value) {
        return new byte[] {
                (byte)(value >> 24),
                (byte)(value >> 16),
                (byte)(value >> 8),
                (byte)value
        };
    }

    private int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }

    public void close() throws IOException {
        try {
            inputStream.close();
        } catch (IOException e) {
            // 忽略关闭异常
        }
        try {
            outputStream.close();
        } catch (IOException e) {
            // 忽略关闭异常
        }
        try {
            socket.close();
        } catch (IOException e) {
            // 忽略关闭异常
        }
    }

    public boolean isHandshakeCompleted() {
        return handshakeCompleted;
    }

    public boolean isConnected() {
        return socket.isConnected() && !socket.isClosed();
    }

    public boolean isClosed() {
        return socket.isClosed();
    }
}