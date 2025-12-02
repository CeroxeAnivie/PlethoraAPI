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
    private static final String KEY_EXCHANGE_ALGO = "X25519";
    // ThreadLocal 缓存优化
    private static final ThreadLocal<KeyPairGenerator> KEY_PAIR_GEN_TL = ThreadLocal.withInitial(() -> {
        try {
            return KeyPairGenerator.getInstance(KEY_EXCHANGE_ALGO);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    });
    private static final ThreadLocal<KeyFactory> KEY_FACTORY_TL = ThreadLocal.withInitial(() -> {
        try {
            return KeyFactory.getInstance(KEY_EXCHANGE_ALGO);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    });
    private static volatile int MAX_ALLOWED_PACKET_SIZE = Integer.MAX_VALUE;
    private final AtomicBoolean connectionClosed = new AtomicBoolean(false);
    private final AtomicBoolean connectionBroken = new AtomicBoolean(false);
    private final Object handshakeLock = new Object();

    // 【核心修复 1】新增写入锁对象，用于保证多线程发送时的原子性
    private final Object writeLock = new Object();

    private Socket socket;
    private BufferedInputStream inputStream;
    private SilentBufferedOutputStream outputStream;
    private AESUtil aesUtil;
    // *** 懒加载握手控制变量 ***
    // 默认为 true (兼容客户端主动连接模式)，如果是服务端模式则由 initServerMode 置为 false
    private volatile boolean handshakeCompleted = true;
    private boolean isServerMode = false;

    // ==================== 构造函数 ====================

    // 1. 客户端构造函数 (保持不变，客户端通常需要同步建立连接)
    public SecureSocket(String host, int port) throws IOException {
        this(new Socket(host, port));
        performClientHandshake();
    }

    public SecureSocket(Proxy proxy, String host, int port) throws IOException {
        this.socket = new Socket(proxy);
        initStreams();
        this.socket.connect(new InetSocketAddress(host, port));
        performClientHandshake();
    }

    // 2. 包装构造函数 (被 Accept 调用)
    public SecureSocket(Socket socket) throws IOException {
        this.socket = socket;
        initStreams();
        // 这里不执行握手，极速返回
    }

    public SecureSocket() throws IOException {
        this.socket = new Socket();
        initStreams();
    }

    public static int getMaxAllowedPacketSize() {
        return MAX_ALLOWED_PACKET_SIZE;
    }

    public static void setMaxAllowedPacketSize(int size) {
        if (size < 0) throw new IllegalArgumentException("Negative size");
        MAX_ALLOWED_PACKET_SIZE = size;
    }

    // ==================== 核心：自动握手检查 (零副作用关键) ====================

    // *** 关键方法：由 SecureServerSocket 内部调用 ***
    protected void initServerMode() {
        this.isServerMode = true;
        this.handshakeCompleted = false; // 标记为未握手，等待首次 IO 触发
    }

    // ==================== 握手实现 (内部私有) ====================

    private void initStreams() throws IOException {
        this.inputStream = new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE);
        this.outputStream = new SilentBufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE, connectionBroken);
    }

    /**
     * 在每次 IO 操作前自动调用。
     * 只有第一次调用会触发握手，后续调用直接通过。
     */
    private void ensureHandshake() throws IOException {
        if (handshakeCompleted) return; // 快速检查，绝大多数调用直接返回，性能无损

        synchronized (handshakeLock) {
            if (handshakeCompleted) return; // 双重检查

            if (isServerMode) {
                try {
                    // 执行服务端握手
                    // 此时 socket 带有 2000ms 超时 (由 ServerSocket 设置)
                    // 如果对方是僵尸连接，这里会抛出 SocketTimeoutException
                    performServerHandshake();

                    // 握手成功！
                    // *** 关键步骤：重置超时 ***
                    // 握手通过说明不是僵尸，现在恢复为 0 (无限等待) 或原有配置
                    socket.setSoTimeout(0);

                } catch (Exception e) {
                    close(); // 握手失败必须关闭连接
                    if (e instanceof IOException) throw (IOException) e;
                    throw new IOException("Handshake failed during lazy initialization", e);
                }
            }
            handshakeCompleted = true;
        }
    }

    private void performServerHandshake() throws Exception {
        try {
            KeyPair keyPair = KEY_PAIR_GEN_TL.get().generateKeyPair();

            // 使用 internal 方法发送，避免递归调用 public sendRaw -> ensureHandshake -> 死循环
            byte[] pubKey = keyPair.getPublic().getEncoded();
            writeRawPacketInternal(pubKey);

            // 使用 internal 方法接收
            byte[] otherKey = readRawPacketInternal();

            KeyFactory keyFactory = KEY_FACTORY_TL.get();
            PublicKey otherPublicKey = keyFactory.generatePublic(new X509EncodedKeySpec(otherKey));

            generateSharedSecret(keyPair, otherPublicKey);
        } catch (SocketTimeoutException e) {
            throw new IOException("Handshake timeout - client unresponsive (Zombie Connection blocked)", e);
        }
    }

    // ==================== 底层读写 (不触发握手，供握手内部使用) ====================

    private void performClientHandshake() throws IOException {
        try {
            byte[] serverKey = readRawPacketInternal();

            KeyFactory keyFactory = KEY_FACTORY_TL.get();
            PublicKey serverPublicKey = keyFactory.generatePublic(new X509EncodedKeySpec(serverKey));

            KeyPair keyPair = KEY_PAIR_GEN_TL.get().generateKeyPair();
            writeRawPacketInternal(keyPair.getPublic().getEncoded());

            generateSharedSecret(keyPair, serverPublicKey);
            handshakeCompleted = true;
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Client handshake failed", e);
        }
    }

    private void generateSharedSecret(KeyPair keyPair, PublicKey otherKey) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance(KEY_EXCHANGE_ALGO);
        ka.init(keyPair.getPrivate());
        ka.doPhase(otherKey, true);
        byte[] secret = ka.generateSecret();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        this.aesUtil = new AESUtil(new SecretKeySpec(sha.digest(secret), "AES"));
    }

    // ==================== 公开 API (全自动触发握手) ====================

    // 【核心修复 2】加上 synchronized (writeLock)，确保多线程并发写入时不会乱序
    private void writeRawPacketInternal(byte[] data) throws IOException {
        if (connectionBroken.get()) throw new IOException("Connection broken");
        int len = data.length;
        byte[] header = {(byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len};

        synchronized (writeLock) {
            outputStream.write(header);
            outputStream.write(data);
            outputStream.flush();
        }
    }

    private byte[] readRawPacketInternal() throws IOException {
        if (connectionBroken.get()) throw new IOException("Connection broken");
        byte[] lenBytes = inputStream.readNBytes(4);
        if (lenBytes.length < 4) throw new EOFException("Connection closed");
        int len = ((lenBytes[0] & 0xFF) << 24) | ((lenBytes[1] & 0xFF) << 16) | ((lenBytes[2] & 0xFF) << 8) | (lenBytes[3] & 0xFF);

        if (len < 0) throw new IOException("Negative length");
        if (len > MAX_ALLOWED_PACKET_SIZE) throw new IOException("Packet too large: " + len);
        if (len == 0) return new byte[0];

        byte[] data = inputStream.readNBytes(len);
        if (data.length < len) throw new EOFException("Expected " + len + " bytes");
        return data;
    }

    // 1. 发送字符串
    public int sendStr(String message) throws IOException {
        ensureHandshake(); // <--- 自动触发握手
        if (connectionBroken.get()) return -1;
        byte[] data = (message != null ? message : STOP_STRING).getBytes(StandardCharsets.UTF_8);
        return sendEncrypted(data);
    }

    // 2. 接收字符串
    public String receiveStr() throws IOException {
        return receiveStr(0);
    }

    public String receiveStr(int timeoutMillis) throws IOException {
        ensureHandshake(); // <--- 自动触发握手
        byte[] decrypted = receiveDecrypted(timeoutMillis);
        if (decrypted == null) return null;
        String str = new String(decrypted, StandardCharsets.UTF_8);
        return str.equals(STOP_STRING) ? null : str;
    }

    // 3. 发送 Raw 数据 (业务层接口)
    public int sendRaw(byte[] data) throws IOException {
        ensureHandshake(); // <--- 自动触发握手
        try {
            writeRawPacketInternal(data);
            return 4 + data.length;
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1;
            }
            throw e;
        }
    }

    // 4. 接收 Raw 数据 (业务层接口)
    public byte[] receiveRaw() throws IOException {
        ensureHandshake(); // <--- 自动触发握手
        return receiveRaw(0);
    }

    public byte[] receiveRaw(int timeoutMillis) throws IOException {
        ensureHandshake(); // <--- 自动触发握手
        if (connectionBroken.get()) return new byte[0];
        int original = socket.getSoTimeout();
        try {
            if (timeoutMillis > 0) socket.setSoTimeout(timeoutMillis);
            return readRawPacketInternal();
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return new byte[0];
            }
            throw e;
        } finally {
            if (timeoutMillis > 0) {
                try {
                    socket.setSoTimeout(original);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // 5. 其他基础数据类型
    public int sendByte(byte[] data) throws IOException {
        ensureHandshake();
        return sendEncrypted(data == null ? STOP_BYTE : data);
    }

    public int sendByte(byte[] data, int offset, int length) throws IOException {
        ensureHandshake();
        if (connectionBroken.get()) return -1;
        byte[] toSend;
        if (data == null) {
            toSend = STOP_BYTE;
        } else {
            toSend = new byte[length];
            System.arraycopy(data, offset, toSend, 0, length);
        }
        return sendEncrypted(toSend);
    }

    public byte[] receiveByte() throws IOException {
        return receiveByte(0);
    }

    public byte[] receiveByte(int timeoutMillis) throws IOException {
        ensureHandshake();
        byte[] decrypted = receiveDecrypted(timeoutMillis);
        if (decrypted != null && decrypted.length == 1 && decrypted[0] == STOP_BYTE[0]) {
            return null;
        }
        return decrypted;
    }

    public int sendInt(int value) throws IOException {
        ensureHandshake();
        byte[] intBytes = new byte[]{
                (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value
        };
        return sendEncrypted(intBytes);
    }

    // ==================== 加密/解密 辅助 ====================

    public int receiveInt() throws IOException {
        return receiveInt(0);
    }

    public int receiveInt(int timeoutMillis) throws IOException {
        ensureHandshake();
        byte[] decrypted = receiveDecrypted(timeoutMillis);
        if (decrypted == null || decrypted.length != 4)
            throw new IOException("Invalid int data received");
        return ((decrypted[0] & 0xFF) << 24) | ((decrypted[1] & 0xFF) << 16) |
                ((decrypted[2] & 0xFF) << 8) | (decrypted[3] & 0xFF);
    }

    // ==================== Getter/Setter & Utils ====================

    private int sendEncrypted(byte[] plainData) throws IOException {
        try {
            byte[] encrypted = aesUtil.encrypt(plainData);
            writeRawPacketInternal(encrypted);
            return 4 + encrypted.length;
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return -1;
            }
            throw e;
        }
    }

    private byte[] receiveDecrypted(int timeoutMillis) throws IOException {
        if (connectionBroken.get()) return null;
        int original = socket.getSoTimeout();
        try {
            if (timeoutMillis > 0) socket.setSoTimeout(timeoutMillis);
            byte[] encrypted = readRawPacketInternal();
            return aesUtil.decrypt(encrypted);
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            if (isBrokenPipeException(e)) {
                markConnectionBroken();
                return null;
            }
            throw e;
        } finally {
            if (timeoutMillis > 0) {
                try {
                    socket.setSoTimeout(original);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean isBrokenPipeException(IOException e) {
        if (e instanceof SocketException) {
            String message = e.getMessage();
            return message != null && (message.contains("Broken pipe") ||
                    message.contains("Connection reset") ||
                    message.contains("写入已结束") ||
                    message.contains("Socket closed"));
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

    @Override
    public void close() throws IOException {
        if (connectionClosed.getAndSet(true)) return;
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {
        }
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    public boolean isClosed() {
        return (socket != null && socket.isClosed()) || connectionClosed.get() || connectionBroken.get();
    }

    public boolean isConnected() {
        return socket.isConnected() && !isClosed();
    }

    public boolean isConnectionBroken() {
        return connectionBroken.get();
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

    public int getSoTimeout() throws SocketException {
        return socket.getSoTimeout();
    }

    public void setSoTimeout(int timeout) throws SocketException {
        socket.setSoTimeout(timeout);
    }

    public void shutdownInput() throws IOException {
        socket.shutdownInput();
    }

    public void shutdownOutput() throws IOException {
        socket.shutdownOutput();
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
            } catch (IOException e) {
                handleEx(e);
            }
        }

        @Override
        public void flush() throws IOException {
            if (connectionBroken.get()) return;
            try {
                super.flush();
            } catch (IOException e) {
                handleEx(e);
            }
        }

        private void handleEx(IOException e) throws IOException {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset"))) {
                connectionBroken.set(true);
            } else {
                throw e;
            }
        }
    }
}