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
    public static final String STOP_STRING = "\u0004";
    public static final byte[] STOP_BYTE = new byte[]{0x04};
    private static final int BUFFER_SIZE = 8192;
    private static volatile int MAX_ALLOWED_PACKET_SIZE = Integer.MAX_VALUE;
    private final Socket socket;
    private final BufferedInputStream inputStream;
    private final SilentBufferedOutputStream outputStream;
    private final AtomicBoolean connectionClosed = new AtomicBoolean(false);
    private final AtomicBoolean connectionBroken = new AtomicBoolean(false);
    private AESUtil aesUtil;

    // ==================== 构造函数 ====================

    // 直接连接构造方法 (客户端)
    public SecureSocket(String host, int port) throws IOException {
        this(new Socket(host, port));
        try {
            // *** 修改点: 客户端执行客户端握手 ***
            performClientHandshake();
        } catch (Exception e) {
            close();
            throw new IOException("Handshake failed", e);
        }
    }

    // 代理连接构造方法 (客户端)
    public SecureSocket(Proxy proxy, String host, int port) throws IOException {
        this(new Socket(proxy));
        try {
            connect(host, port);
            // *** 修改点: 客户端执行客户端握手 ***
            performClientHandshake();
        } catch (Exception e) {
            close();
            throw new IOException("Handshake failed", e);
        }
    }

    // 基于现有Socket的构造方法 (服务器端使用)
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

    // ==================== 连接方法 ====================

    public static int getMaxAllowedPacketSize() {
        return MAX_ALLOWED_PACKET_SIZE;
    }

    public static void setMaxAllowedPacketSize(int size) {
        if (size < 0) throw new IllegalArgumentException("Max allowed packet size cannot be negative: " + size);
        MAX_ALLOWED_PACKET_SIZE = size;
    }

    public void connect(String host, int port) throws IOException {
        socket.connect(new InetSocketAddress(host, port));
    }

    // ==================== 握手协议 (核心修改) ====================

    public void connect(String host, int port, int timeout) throws IOException {
        socket.connect(new InetSocketAddress(host, port), timeout);
    }

    public void connect(Proxy proxy, String host, int port) throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        try {
            java.lang.reflect.Field socketField = this.getClass().getDeclaredField("socket");
            socketField.setAccessible(true);
            Socket newSocket = new Socket(proxy);
            socketField.set(this, newSocket);

            java.lang.reflect.Field inputStreamField = this.getClass().getDeclaredField("inputStream");
            inputStreamField.setAccessible(true);
            inputStreamField.set(this, new BufferedInputStream(newSocket.getInputStream(), BUFFER_SIZE));

            java.lang.reflect.Field outputStreamField = this.getClass().getDeclaredField("outputStream");
            outputStreamField.setAccessible(true);
            outputStreamField.set(this, new SilentBufferedOutputStream(newSocket.getOutputStream(), BUFFER_SIZE, connectionBroken));

            newSocket.connect(new InetSocketAddress(host, port));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IOException("Failed to set proxy connection", e);
        }
    }

    /**
     * *** 新增方法: 执行服务器端的握手流程 ***
     * 流程：生成密钥 -> 发送公钥 -> 接收客户端公钥 -> 生成共享密钥
     */
    protected void performServerHandshake() throws Exception {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("DH");
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();

            // 1. 发送服务器公钥
            byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
            sendRaw(publicKeyBytes);

            // 2. 接收客户端公钥
            byte[] otherPublicKeyBytes = receiveRaw();
            KeyFactory keyFactory = KeyFactory.getInstance("DH");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(otherPublicKeyBytes);
            PublicKey otherPublicKey = keyFactory.generatePublic(keySpec);

            // 3. 生成共享密钥
            generateSharedSecret(keyPair, otherPublicKey);
        } catch (SocketTimeoutException e) {
            throw new IOException("Handshake timeout - client did not respond in time", e);
        } catch (EOFException e) {
            throw new IOException("Handshake failed - client disconnected unexpectedly", e);
        } catch (Exception e) {
            throw new IOException("Handshake failed due to error: " + e.getMessage(), e);
        }
    }

    /**
     * *** 新增方法: 执行客户端的握手流程 ***
     * 流程：接收服务器公钥 -> 生成密钥 -> 发送客户端公钥 -> 生成共享密钥
     */
    protected void performClientHandshake() throws Exception {
        // 1. 接收服务器公钥
        byte[] serverPublicKeyBytes = receiveRaw();
        KeyFactory keyFactory = KeyFactory.getInstance("DH");
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(serverPublicKeyBytes);
        PublicKey serverPublicKey = keyFactory.generatePublic(keySpec);

        // 2. 生成DH参数和密钥对
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("DH");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        // 3. 发送客户端公钥
        byte[] clientPublicKeyBytes = keyPair.getPublic().getEncoded();
        sendRaw(clientPublicKeyBytes);

        // 4. 生成共享密钥
        generateSharedSecret(keyPair, serverPublicKey);
    }

    // ==================== 数据收发方法 ====================
    // (以下方法保持不变，因为它们依赖于已经建立好的 aesUtil)

    /**
     * *** 新增方法: 从密钥对和对方公钥生成共享密钥和AES工具 ***
     */
    private void generateSharedSecret(KeyPair keyPair, PublicKey otherPublicKey) throws Exception {
        KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
        keyAgreement.init(keyPair.getPrivate());
        keyAgreement.doPhase(otherPublicKey, true);

        byte[] sharedSecret = keyAgreement.generateSecret();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] sessionKey = digest.digest(sharedSecret);

        SecretKeySpec secretKey = new SecretKeySpec(sessionKey, "AES");
        this.aesUtil = new AESUtil(secretKey);
    }

    /**
     * *** 已废弃: 原始的对称握手方法，会导致死锁 ***
     * 保留仅为兼容性，默认行为为服务器端握手。
     */
    @Deprecated
    protected void performHandshake() throws Exception {
        performServerHandshake();
    }

    public int sendStr(String message) throws IOException {
        if (connectionBroken.get()) return -1;
        try {
            byte[] data = (message != null ? message : STOP_STRING).getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = aesUtil.encrypt(data);
            return sendRaw(encrypted);
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1;
            }
            throw e;
        }
    }

    public String receiveStr() throws IOException {
        if (connectionBroken.get()) return null;
        try {
            byte[] encrypted = receiveRaw();
            byte[] decrypted = aesUtil.decrypt(encrypted);
            String str = new String(decrypted, StandardCharsets.UTF_8);
            return str.equals(STOP_STRING) ? null : str;
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return null;
            }
            throw e;
        }
    }

    public String receiveStr(int timeoutMillis) throws IOException {
        if (connectionBroken.get()) return null;
        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(timeoutMillis);
            byte[] encrypted = receiveRaw();
            byte[] decrypted = aesUtil.decrypt(encrypted);
            String str = new String(decrypted, StandardCharsets.UTF_8);
            return str.equals(STOP_STRING) ? null : str;
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return null;
            }
            throw e;
        } finally {
            try {
                socket.setSoTimeout(originalTimeout);
            } catch (SocketException ignored) {
            }
        }
    }

    public int sendByte(byte[] data) throws IOException {
        if (connectionBroken.get()) return -1;
        try {
            byte[] toSend = (data == null) ? STOP_BYTE : data;
            byte[] encrypted = aesUtil.encrypt(toSend);
            return sendRaw(encrypted);
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1;
            }
            throw e;
        }
    }

    public int sendByte(byte[] data, int offset, int length) throws IOException {
        if (connectionBroken.get()) return -1;
        try {
            byte[] toSend = (data == null) ? STOP_BYTE : new byte[0];
            if (data != null) {
                toSend = new byte[length];
                System.arraycopy(data, offset, toSend, 0, length);
            }
            byte[] encrypted = aesUtil.encrypt(toSend);
            return sendRaw(encrypted);
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1;
            }
            throw e;
        }
    }

    public byte[] receiveByte() throws IOException {
        if (connectionBroken.get()) return null;
        try {
            byte[] raw = receiveRaw();
            if (raw.length == 1 && raw[0] == STOP_BYTE[0]) return null;
            return aesUtil.decrypt(raw);
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return null;
            }
            throw e;
        }
    }

    public byte[] receiveByte(int timeoutMillis) throws IOException {
        if (connectionBroken.get()) return null;
        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(timeoutMillis);
            byte[] raw = receiveRaw();
            if (raw.length == 1 && raw[0] == STOP_BYTE[0]) return null;
            return aesUtil.decrypt(raw);
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return null;
            }
            throw e;
        } finally {
            try {
                socket.setSoTimeout(originalTimeout);
            } catch (SocketException ignored) {
            }
        }
    }

    public int sendInt(int value) throws IOException {
        if (connectionBroken.get()) return -1;
        try {
            byte[] intBytes = new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
            byte[] encrypted = aesUtil.encrypt(intBytes);
            return sendRaw(encrypted);
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1;
            }
            throw e;
        }
    }

    public int receiveInt() throws IOException {
        if (connectionBroken.get()) return -1;
        try {
            byte[] encrypted = receiveRaw();
            byte[] decrypted = aesUtil.decrypt(encrypted);
            if (decrypted.length != 4)
                throw new IOException("Invalid int data received: expected 4 bytes, got " + decrypted.length);
            return ((decrypted[0] & 0xFF) << 24) | ((decrypted[1] & 0xFF) << 16) | ((decrypted[2] & 0xFF) << 8) | (decrypted[3] & 0xFF);
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1;
            }
            throw e;
        }
    }

    public int receiveInt(int timeoutMillis) throws IOException {
        if (connectionBroken.get()) return -1;
        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(timeoutMillis);
            byte[] encrypted = receiveRaw();
            byte[] decrypted = aesUtil.decrypt(encrypted);
            if (decrypted.length != 4)
                throw new IOException("Invalid int data received: expected 4 bytes, got " + decrypted.length);
            return ((decrypted[0] & 0xFF) << 24) | ((decrypted[1] & 0xFF) << 16) | ((decrypted[2] & 0xFF) << 8) | (decrypted[3] & 0xFF);
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1;
            }
            throw e;
        } finally {
            try {
                socket.setSoTimeout(originalTimeout);
            } catch (SocketException ignored) {
            }
        }
    }

    public int sendRaw(byte[] data) throws IOException {
        if (connectionBroken.get()) return -1;
        try {
            byte[] lengthBytes = intToBytes(data.length);
            outputStream.write(lengthBytes);
            outputStream.write(data);
            outputStream.flush();
            return 4 + data.length;
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1;
            }
            throw e;
        }
    }

    public byte[] receiveRaw() throws IOException {
        if (connectionBroken.get()) return new byte[0];
        try {
            byte[] lengthBytes = new byte[4];
            int bytesRead = 0;
            while (bytesRead < 4) {
                int result = inputStream.read(lengthBytes, bytesRead, 4 - bytesRead);
                if (result == -1) throw new EOFException("Connection closed while reading length");
                bytesRead += result;
            }
            int length = bytesToInt(lengthBytes);

            if (length < 0) throw new IOException("Invalid data length: " + length + " (negative value)");
            int currentMaxSize = getMaxAllowedPacketSize();
            if (currentMaxSize >= 0 && length > currentMaxSize) {
                throw new IOException("Invalid data length: " + length + " (exceeds maximum allowed size of " + currentMaxSize + ")");
            }
            if (length == 0) return new byte[0];

            ByteArrayOutputStream buffer = new ByteArrayOutputStream(length);
            byte[] chunk = new byte[Math.min(BUFFER_SIZE, length)];
            int totalRead = 0;
            while (totalRead < length) {
                int toRead = Math.min(chunk.length, length - totalRead);
                int result = inputStream.read(chunk, 0, toRead);
                if (result == -1)
                    throw new EOFException("Connection closed while reading data, expected " + length + " bytes, got " + totalRead);
                buffer.write(chunk, 0, result);
                totalRead += result;
            }
            return buffer.toByteArray();
        } catch (SocketTimeoutException e) {
            throw e; // 重新抛出超时异常，让上层处理
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return new byte[0];
            }
            throw e;
        }
    }

    public byte[] receiveRaw(int timeoutMillis) throws IOException {
        if (connectionBroken.get()) return new byte[0];
        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(timeoutMillis);
            return receiveRaw(); // 委托给无超时版本，因为socket已设置超时
        } catch (SocketTimeoutException e) {
            throw e;
        } finally {
            try {
                socket.setSoTimeout(originalTimeout);
            } catch (SocketException ignored) {
            }
        }
    }

    // ==================== 辅助方法和Socket兼容方法 ====================
    // (以下方法保持不变)

    private byte[] intToBytes(int value) {
        return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
    }

    private boolean isBrokenPipeException(IOException e) {
        if (e instanceof SocketException) {
            String message = e.getMessage();
            return message != null && (message.contains("Broken pipe") || message.contains("Connection reset") || message.contains("写入已结束"));
        }
        return false;
    }

    private void markConnectionBroken() {
        connectionBroken.set(true);
        try {
            close();
        } catch (IOException ignored) {
        }
    }

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
        if (connectionClosed.getAndSet(true)) return;
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
        try {
            outputStream.close();
        } catch (IOException ignored) {
        }
        try {
            socket.close();
        } catch (IOException ignored) {
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
            if (!isBrokenPipeException(e)) throw e;
            markConnectionBroken();
        }
    }

    public void shutdownOutput() throws IOException {
        try {
            socket.shutdownOutput();
        } catch (IOException e) {
            if (!isBrokenPipeException(e)) throw e;
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
            if (connectionBroken.get()) return;
            try {
                super.write(b, off, len);
            } catch (SocketException e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset"))) {
                    connectionBroken.set(true);
                } else {
                    throw e;
                }
            }
        }

        @Override
        public void flush() throws IOException {
            if (connectionBroken.get()) return;
            try {
                super.flush();
            } catch (SocketException e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset"))) {
                    connectionBroken.set(true);
                } else {
                    throw e;
                }
            }
        }
    }
}