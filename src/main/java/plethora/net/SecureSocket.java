package plethora.net;

import plethora.security.encryption.AESUtil;

import javax.crypto.KeyAgreement;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.atomic.AtomicBoolean;

public class SecureSocket implements Closeable {
    private final Socket socket;
    private final BufferedInputStream inputStream;
    private final SilentBufferedOutputStream outputStream;
    private AESUtil aesUtil;
    public static final String STOP_STRING = "\u0004";
    public static final byte[] STOP_BYTE = new byte[]{0x04};

    // 连接状态标志
    private final AtomicBoolean connectionClosed = new AtomicBoolean(false);
    private final AtomicBoolean connectionBroken = new AtomicBoolean(false);

    // 缓冲区大小优化
    private static final int BUFFER_SIZE = 8192;

    // 直接连接构造方法
    public SecureSocket(String host, int port) throws IOException {
        this(new Socket(host, port));
        try {
            performHandshake();
        } catch (Exception e) {
            close();
            throw new IOException("Handshake failed", e);
        }
    }

    // 代理连接构造方法
    public SecureSocket(Proxy proxy, String host, int port) throws IOException {
        this(new Socket(proxy));
        try {
            connect(host, port);
            performHandshake();
        } catch (Exception e) {
            close();
            throw new IOException("Handshake failed", e);
        }
    }

    // 基于现有Socket的构造方法
    public SecureSocket(Socket socket) throws IOException {
        this.socket = socket;
        this.inputStream = new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE);
        this.outputStream = new SilentBufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE, connectionBroken);
    }

    // 无连接构造方法，需要手动调用connect
    public SecureSocket() throws IOException {
        this.socket = new Socket();
        this.inputStream = new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE);
        this.outputStream = new SilentBufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE, connectionBroken);
    }

    // 连接方法（用于无连接构造方法）
    public void connect(String host, int port) throws IOException {
        socket.connect(new InetSocketAddress(host, port));
    }

    // 带超时的连接方法
    public void connect(String host, int port, int timeout) throws IOException {
        socket.connect(new InetSocketAddress(host, port), timeout);
    }

    // 代理连接方法
    public void connect(Proxy proxy, String host, int port) throws IOException {
        // 如果socket已经创建，需要先关闭
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        // 使用反射重新设置socket，因为Socket的代理只能在构造时指定
        try {
            java.lang.reflect.Field socketField = this.getClass().getDeclaredField("socket");
            socketField.setAccessible(true);
            Socket newSocket = new Socket(proxy);
            socketField.set(this, newSocket);

            // 也需要重新设置流
            java.lang.reflect.Field inputStreamField = this.getClass().getDeclaredField("inputStream");
            inputStreamField.setAccessible(true);
            inputStreamField.set(this, new BufferedInputStream(newSocket.getInputStream(), BUFFER_SIZE));

            java.lang.reflect.Field outputStreamField = this.getClass().getDeclaredField("outputStream");
            outputStreamField.setAccessible(true);
            outputStreamField.set(this, new SilentBufferedOutputStream(newSocket.getOutputStream(), BUFFER_SIZE, connectionBroken));

            // 连接
            newSocket.connect(new InetSocketAddress(host, port));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IOException("Failed to set proxy connection", e);
        }
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
    }

    public int sendStr(String message) throws IOException {
        // 检查连接状态
        if (connectionBroken.get()) {
            return -1; // 连接已断开，不执行任何操作
        }

        try {
            if (message != null) {
                byte[] data = message.getBytes(StandardCharsets.UTF_8);
                byte[] encrypted = aesUtil.encrypt(data);
                return sendRaw(encrypted);
            } else {
                byte[] data = STOP_STRING.getBytes(StandardCharsets.UTF_8);
                byte[] encrypted = aesUtil.encrypt(data);
                return sendRaw(encrypted);
            }
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1; // 静默处理，不抛出异常
            }
            throw e; // 其他异常正常抛出
        }
    }

    public String receiveStr() throws IOException {
        // 检查连接状态
        if (connectionBroken.get()) {
            return null; // 连接已断开，不执行任何操作
        }

        try {
            byte[] encrypted = receiveRaw();
            byte[] decrypted = aesUtil.decrypt(encrypted);
            String str = new String(decrypted, StandardCharsets.UTF_8);
            if (str.equals(STOP_STRING)) {
                return null;
            }
            return str;
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return null; // 静默处理，不抛出异常
            }
            throw e; // 其他异常正常抛出
        }
    }

    public int sendByte(byte[] data) throws IOException {
        // 检查连接状态
        if (connectionBroken.get()) {
            return -1; // 连接已断开，不执行任何操作
        }

        try {
            if (data == null) {
                return sendRaw(STOP_BYTE);
            }
            byte[] encrypted = aesUtil.encrypt(data);
            return sendRaw(encrypted);
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1; // 静默处理，不抛出异常
            }
            throw e; // 其他异常正常抛出
        }
    }

    public int sendByte(byte[] data, int offset, int length) throws IOException {
        // 检查连接状态
        if (connectionBroken.get()) {
            return -1; // 连接已断开，不执行任何操作
        }

        try {
            if (data == null) {
                return sendRaw(STOP_BYTE);
            }
            byte[] encrypted = aesUtil.encrypt(data, offset, length);
            return sendRaw(encrypted);
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1; // 静默处理，不抛出异常
            }
            throw e; // 其他异常正常抛出
        }
    }

    public int sendInt(int value) throws IOException {
        // 检查连接状态
        if (connectionBroken.get()) {
            return -1; // 连接已断开，不执行任何操作
        }

        try {
            // 将int转换为4字节数组
            byte[] intBytes = new byte[4];
            intBytes[0] = (byte) (value >> 24);
            intBytes[1] = (byte) (value >> 16);
            intBytes[2] = (byte) (value >> 8);
            intBytes[3] = (byte) value;

            byte[] encrypted = aesUtil.encrypt(intBytes);
            return sendRaw(encrypted);
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1; // 静默处理，不抛出异常
            }
            throw e; // 其他异常正常抛出
        }
    }

    public int receiveInt() throws IOException {
        // 检查连接状态
        if (connectionBroken.get()) {
            return -1; // 连接已断开，不执行任何操作
        }

        try {
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
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1; // 静默处理，不抛出异常
            }
            throw e; // 其他异常正常抛出
        }
    }

    public byte[] receiveByte() throws IOException {
        // 检查连接状态
        if (connectionBroken.get()) {
            return null; // 连接已断开，不执行任何操作
        }

        try {
            byte[] raw = receiveRaw();
            // 检查是否是停止字节（长度为1且内容为0x04）
            if (raw.length == 1 && raw[0] == STOP_BYTE[0]) {
                return null;
            }
            return aesUtil.decrypt(raw);
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return null; // 静默处理，不抛出异常
            }
            throw e; // 其他异常正常抛出
        }
    }

    private int sendRaw(byte[] data) throws IOException {
        // 检查连接状态
        if (connectionBroken.get()) {
            return -1; // 连接已断开，不执行任何操作
        }

        try {
            // 先发送数据长度
            byte[] lengthBytes = intToBytes(data.length);
            outputStream.write(lengthBytes);
            outputStream.write(data);
            outputStream.flush();

            // 返回实际发送的字节数：4字节长度 + 数据长度
            return 4 + data.length;
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1; // 静默处理，不抛出异常
            }
            throw e; // 其他异常正常抛出
        }
    }

    private byte[] receiveRaw() throws IOException {
        // 检查连接状态
        if (connectionBroken.get()) {
            return new byte[0]; // 连接已断开，不执行任何操作
        }

        try {
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
            if (length < 0) {
                throw new IOException("Invalid data length: " + length);
            }

            // 处理长度为0的特殊情况
            if (length == 0) {
                return new byte[0];
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
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return new byte[0]; // 静默处理，不抛出异常
            }
            throw e; // 其他异常正常抛出
        }
    }

    private byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    private int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }

    // 检查是否为Broken pipe异常
    private boolean isBrokenPipeException(IOException e) {
        if (e instanceof SocketException) {
            String message = e.getMessage();
            return message != null &&
                    (message.contains("Broken pipe") ||
                            message.contains("Connection reset") ||
                            message.contains("写入已结束"));
        }
        return false;
    }

    // 标记连接为已断开
    private void markConnectionBroken() {
        connectionBroken.set(true);
        // 静默关闭连接
        try {
            close();
        } catch (IOException ignored) {
            // 忽略关闭时的异常
        }
    }

    // ============== Socket兼容方法 ==============

    public int getPort() {
        return socket.getPort();
    }

    public int getLocalPort() {
        return socket.getLocalPort();
    }

    public InetAddress getInetAddress() {
        return socket.getInetAddress();
    }

    public InetAddress getLocalAddress() {
        return socket.getLocalAddress();
    }

    public boolean isClosed() {
        return socket.isClosed() || connectionClosed.get() || connectionBroken.get();
    }

    public void close() throws IOException {
        if (connectionClosed.getAndSet(true)) {
            return; // 已经关闭过了
        }

        try {
            inputStream.close();
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
        try {
            outputStream.close();
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
    }

    public int getSoTimeout() throws SocketException {
        return socket.getSoTimeout();
    }

    public void setSoTimeout(int timeout) throws SocketException {
        socket.setSoTimeout(timeout);
    }

    public void shutdownInput() throws IOException {
        try {
            socket.shutdownInput();
        } catch (IOException e) {
            if (!isBrokenPipeException(e)) {
                throw e;
            }
            // 如果是Broken pipe，静默处理
            markConnectionBroken();
        }
    }

    public void shutdownOutput() throws IOException {
        try {
            socket.shutdownOutput();
        } catch (IOException e) {
            if (!isBrokenPipeException(e)) {
                throw e;
            }
            // 如果是Broken pipe，静默处理
            markConnectionBroken();
        }
    }

    public boolean isInputShutdown() {
        return socket.isInputShutdown();
    }

    public boolean isOutputShutdown() {
        return socket.isOutputShutdown();
    }

    public SocketAddress getRemoteSocketAddress() {
        return socket.getRemoteSocketAddress();
    }

    public SocketAddress getLocalSocketAddress() {
        return socket.getLocalSocketAddress();
    }

    public boolean getKeepAlive() throws SocketException {
        return socket.getKeepAlive();
    }

    public void setKeepAlive(boolean on) throws SocketException {
        socket.setKeepAlive(on);
    }

    public boolean getTcpNoDelay() throws SocketException {
        return socket.getTcpNoDelay();
    }

    public void setTcpNoDelay(boolean on) throws SocketException {
        socket.setTcpNoDelay(on);
    }

    public int getReceiveBufferSize() throws SocketException {
        return socket.getReceiveBufferSize();
    }

    public void setReceiveBufferSize(int size) throws SocketException {
        socket.setReceiveBufferSize(size);
    }

    public int getSendBufferSize() throws SocketException {
        return socket.getSendBufferSize();
    }

    public void setSendBufferSize(int size) throws SocketException {
        socket.setSendBufferSize(size);
    }

    public boolean isConnected() {
        return socket.isConnected() && !socket.isClosed() && !connectionBroken.get();
    }

    // 检查连接是否已断开（Broken pipe）
    public boolean isConnectionBroken() {
        return connectionBroken.get();
    }

    /**
     * 静默缓冲输出流，完全捕获并处理Broken pipe异常
     */
    private static class SilentBufferedOutputStream extends BufferedOutputStream {
        private final AtomicBoolean connectionBroken;

        public SilentBufferedOutputStream(OutputStream out, int size, AtomicBoolean connectionBroken) {
            super(out, size);
            this.connectionBroken = connectionBroken;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (connectionBroken.get()) {
                return; // 连接已断开，静默返回
            }

            try {
                super.write(b, off, len);
            } catch (SocketException e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset"))) {
                    connectionBroken.set(true); // 标记连接已断开
                    // 静默返回，不抛出异常
                } else {
                    throw e; // 其他SocketException正常抛出
                }
            }
        }

        @Override
        public void flush() throws IOException {
            if (connectionBroken.get()) {
                return; // 连接已断开，静默返回
            }

            try {
                super.flush();
            } catch (SocketException e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset"))) {
                    connectionBroken.set(true); // 标记连接已断开
                    // 静默返回，不抛出异常
                } else {
                    throw e; // 其他SocketException正常抛出
                }
            }
        }
    }
}