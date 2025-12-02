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
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工业级安全套接字实现 (Java 21 虚拟线程完全兼容版)
 * <p>
 * 修复与增强：
 * 1. 【完整性】补全了 shutdownInput/shutdownOutput 方法。
 * 2. 【无 Pinning】全链路使用 ReentrantLock 替代 synchronized。
 * 3. 【防 OOM】强制限制最大包体大小。
 * 4. 【流一致性】精准处理部分读取超时，防止数据错乱。
 */
public class SecureSocket implements Closeable {
    public static final String STOP_STRING = "\u0004";
    public static final byte[] STOP_BYTE = new byte[]{0x04};

    // 内部 IO 缓冲区大小 (32KB)
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final String KEY_EXCHANGE_ALGO = "X25519";

    // ThreadLocal 缓存优化，避免高频创建开销
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

    // 默认最大包大小 64MB，防止内存溢出攻击
    private static volatile int MAX_ALLOWED_PACKET_SIZE = 64 * 1024 * 1024;

    private final AtomicBoolean connectionClosed = new AtomicBoolean(false);
    private final AtomicBoolean connectionBroken = new AtomicBoolean(false);

    // 【核心修复】使用 ReentrantLock 替代 synchronized，避免 Virtual Thread Pinning
    private final ReentrantLock handshakeLock = new ReentrantLock();
    // 写锁：保证多线程发送不乱序
    private final ReentrantLock writeLock = new ReentrantLock();
    // 读锁：保证多线程接收时数据包完整性
    private final ReentrantLock readLock = new ReentrantLock();

    private Socket socket;
    private BufferedInputStream inputStream;
    private BufferedOutputStream outputStream;
    private AESUtil aesUtil;

    private volatile boolean handshakeCompleted = false;
    private boolean isServerMode = false;

    // ==================== 构造函数 ====================

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

    public SecureSocket(Socket socket) throws IOException {
        this.socket = socket;
        // 强制优化底层参数
        try {
            if (!socket.getKeepAlive()) socket.setKeepAlive(true);
            if (!socket.getTcpNoDelay()) socket.setTcpNoDelay(true);
        } catch (SocketException ignored) {
        }

        initStreams();
        this.handshakeCompleted = false;
    }

    public SecureSocket() throws IOException {
        this.socket = new Socket();
        initStreams();
        this.handshakeCompleted = false;
    }

    public static void setMaxAllowedPacketSize(int size) {
        if (size <= 0) throw new IllegalArgumentException("Size must be positive");
        MAX_ALLOWED_PACKET_SIZE = size;
    }

    protected void initServerMode() {
        this.isServerMode = true;
        this.handshakeCompleted = false;
    }

    private void initStreams() throws IOException {
        this.inputStream = new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE);
        this.outputStream = new SilentBufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE, connectionBroken);
    }

    // ==================== 握手逻辑 ====================

    private void ensureHandshake() throws IOException {
        if (handshakeCompleted) return;

        handshakeLock.lock(); // 使用 Lock 替代 synchronized
        try {
            if (handshakeCompleted) return;
            if (connectionBroken.get() || connectionClosed.get()) throw new IOException("Connection closed");

            if (isServerMode) {
                try {
                    performServerHandshake();
                    // 握手成功后，清除僵尸防御超时，恢复为无限等待或用户默认设置
                    socket.setSoTimeout(0);
                } catch (Exception e) {
                    close(); // 握手失败直接关闭
                    if (e instanceof IOException) throw (IOException) e;
                    throw new IOException("Handshake failed", e);
                }
            } else {
                // 客户端模式防御性检查
                if (aesUtil == null) {
                    performClientHandshake();
                }
            }
            handshakeCompleted = true;
        } finally {
            handshakeLock.unlock();
        }
    }

    private void performServerHandshake() throws Exception {
        try {
            KeyPair keyPair = KEY_PAIR_GEN_TL.get().generateKeyPair();
            byte[] pubKey = keyPair.getPublic().getEncoded();
            writeRawPacketInternal(pubKey);

            byte[] otherKey = readRawPacketInternal();
            KeyFactory keyFactory = KEY_FACTORY_TL.get();
            PublicKey otherPublicKey = keyFactory.generatePublic(new X509EncodedKeySpec(otherKey));

            generateSharedSecret(keyPair, otherPublicKey);
        } catch (SocketTimeoutException e) {
            throw new IOException("Handshake timeout - client unresponsive (Zombie Connection blocked)", e);
        }
    }

    private void performClientHandshake() throws IOException {
        handshakeLock.lock();
        try {
            if (handshakeCompleted) return;

            byte[] serverKey = readRawPacketInternal();
            KeyFactory keyFactory = KEY_FACTORY_TL.get();
            PublicKey serverPublicKey = keyFactory.generatePublic(new X509EncodedKeySpec(serverKey));

            KeyPair keyPair = KEY_PAIR_GEN_TL.get().generateKeyPair();
            writeRawPacketInternal(keyPair.getPublic().getEncoded());

            generateSharedSecret(keyPair, serverPublicKey);
            handshakeCompleted = true;
        } catch (Exception e) {
            close();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Client handshake failed", e);
        } finally {
            handshakeLock.unlock();
        }
    }

    private void generateSharedSecret(KeyPair keyPair, PublicKey otherKey) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance(KEY_EXCHANGE_ALGO);
        ka.init(keyPair.getPrivate());
        ka.doPhase(otherKey, true);
        byte[] secret = ka.generateSecret();
        // 使用 SHA-256 派生密钥，确保均匀性
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] aesKey = sha.digest(secret);
        this.aesUtil = new AESUtil(new SecretKeySpec(aesKey, "AES"));
    }

    // ==================== 底层读写 (核心稳定区) ====================

    private void writeRawPacketInternal(byte[] data) throws IOException {
        if (connectionBroken.get()) throw new IOException("Connection broken");
        int len = data.length;
        byte[] header = {(byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len};

        writeLock.lock();
        try {
            outputStream.write(header);
            outputStream.write(data);
            outputStream.flush();
        } finally {
            writeLock.unlock();
        }
    }

    private byte[] readRawPacketInternal() throws IOException {
        if (connectionBroken.get()) throw new IOException("Connection broken");

        // 1. 读取长度头 (允许超时重试，不标记连接损坏)
        byte[] lenBytes = new byte[4];
        int readTotal = 0;

        try {
            // 循环读取直到满 4 字节
            while (readTotal < 4) {
                int c = inputStream.read(lenBytes, readTotal, 4 - readTotal);
                if (c < 0) throw new EOFException("Connection closed by peer");
                readTotal += c;
            }
        } catch (SocketTimeoutException e) {
            // 在读取数据头之前超时，流是干净的，可以抛出给上层处理
            throw e;
        }

        int len = ((lenBytes[0] & 0xFF) << 24) | ((lenBytes[1] & 0xFF) << 16) | ((lenBytes[2] & 0xFF) << 8) | (lenBytes[3] & 0xFF);

        if (len < 0) throw new IOException("Negative packet length: " + len);
        if (len > MAX_ALLOWED_PACKET_SIZE) {
            markConnectionBroken();
            throw new IOException("Packet too large: " + len + " (Max: " + MAX_ALLOWED_PACKET_SIZE + ")");
        }
        if (len == 0) return new byte[0];

        // 2. 读取包体 (Java 21 readNBytes 优化)
        // 关键逻辑：一旦开始读取包体，如果超时，说明数据流不完整，必须熔断连接
        try {
            byte[] data = inputStream.readNBytes(len);
            if (data.length < len) {
                markConnectionBroken();
                throw new EOFException("Expected " + len + " bytes, but connection closed");
            }
            return data;
        } catch (SocketTimeoutException e) {
            markConnectionBroken(); // 此时流已损坏
            throw new IOException("Read timed out during packet body - Connection corrupt", e);
        } catch (IOException e) {
            markConnectionBroken();
            throw e;
        }
    }

    // ==================== Public API ====================

    public int sendStr(String message) throws IOException {
        ensureHandshake();
        if (connectionBroken.get()) return -1;
        byte[] data = (message != null ? message : STOP_STRING).getBytes(StandardCharsets.UTF_8);
        return sendEncrypted(data);
    }

    public String receiveStr() throws IOException {
        return receiveStr(0);
    }

    public String receiveStr(int timeoutMillis) throws IOException {
        ensureHandshake();
        byte[] decrypted = receiveDecrypted(timeoutMillis);
        if (decrypted == null) return null;
        String str = new String(decrypted, StandardCharsets.UTF_8);
        return str.equals(STOP_STRING) ? null : str;
    }

    public int sendRaw(byte[] data) throws IOException {
        ensureHandshake();
        try {
            writeRawPacketInternal(data);
            return 4 + data.length;
        } catch (IOException e) {
            handleIOException(e);
            throw e;
        }
    }

    public byte[] receiveRaw() throws IOException {
        return receiveRaw(0);
    }

    public byte[] receiveRaw(int timeoutMillis) throws IOException {
        readLock.lock();
        try {
            ensureHandshake();
            return receiveRawInternal(timeoutMillis);
        } finally {
            readLock.unlock();
        }
    }

    private byte[] receiveRawInternal(int timeoutMillis) throws IOException {
        if (connectionBroken.get()) return new byte[0];

        int original = socket.getSoTimeout();
        boolean timeoutChanged = false;
        try {
            if (timeoutMillis > 0 && original != timeoutMillis) {
                socket.setSoTimeout(timeoutMillis);
                timeoutChanged = true;
            }
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
            if (timeoutChanged) {
                try {
                    socket.setSoTimeout(original);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public int sendByte(byte[] data) throws IOException {
        ensureHandshake();
        return sendEncrypted(data == null ? STOP_BYTE : data);
    }

    public int sendByte(byte[] data, int offset, int length) throws IOException {
        ensureHandshake();
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

    private int sendEncrypted(byte[] plainData) throws IOException {
        try {
            byte[] encrypted = aesUtil.encrypt(plainData);
            writeRawPacketInternal(encrypted);
            return 4 + encrypted.length;
        } catch (IOException e) {
            handleIOException(e);
            return -1;
        }
    }

    private byte[] receiveDecrypted(int timeoutMillis) throws IOException {
        readLock.lock(); // 确保读取操作串行化
        try {
            if (connectionBroken.get()) return null;

            // 复用 receiveRawInternal 处理超时和IO
            byte[] encrypted = receiveRawInternal(timeoutMillis);
            if (encrypted.length == 0) return null; // 空包或错误

            return aesUtil.decrypt(encrypted);
        } finally {
            readLock.unlock();
        }
    }

    private void handleIOException(IOException e) throws IOException {
        if (isBrokenPipeException(e)) {
            markConnectionBroken();
        }
        throw e;
    }

    private boolean isBrokenPipeException(IOException e) {
        if (e instanceof SocketException || e instanceof EOFException) {
            String message = e.getMessage();
            if (message == null) return true; // EOF usually implies closure
            message = message.toLowerCase();
            return message.contains("broken pipe") ||
                    message.contains("connection reset") ||
                    message.contains("socket closed") ||
                    message.contains("connection closed") ||
                    message.contains("software caused connection abort");
        }
        return false;
    }

    private void markConnectionBroken() {
        if (connectionBroken.compareAndSet(false, true)) {
            try {
                close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void close() throws IOException {
        // 原子操作，防止多次关闭
        if (connectionClosed.getAndSet(true)) return;

        connectionBroken.set(true);

        // 依次关闭资源，互不影响
        try {
            if (inputStream != null) inputStream.close();
        } catch (Throwable ignored) {
        }

        try {
            if (outputStream != null) outputStream.close();
        } catch (Throwable ignored) {
        }

        try {
            if (socket != null) socket.close();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 代理方法 ====================

    public void shutdownInput() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.shutdownInput();
        }
    }

    public void shutdownOutput() throws IOException {
        if (socket != null && !socket.isClosed()) {
            // 注意：不建议在 shutdownOutput 前强制 flush 加密流，因为这可能导致并发问题。
            // 如果业务需要确保数据发出，应在调用此方法前在业务层确保发送完毕。
            socket.shutdownOutput();
        }
    }

    public boolean isInputShutdown() {
        return socket.isInputShutdown();
    }

    public boolean isOutputShutdown() {
        return socket.isOutputShutdown();
    }

    public boolean isClosed() {
        return connectionClosed.get() || connectionBroken.get();
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

    // 内部类：静默输出流，防止 write 时抛出异常影响流程控制（由上层 handleIOException 处理）
    private static class SilentBufferedOutputStream extends BufferedOutputStream {
        private final AtomicBoolean connectionBroken;

        public SilentBufferedOutputStream(OutputStream out, int size, AtomicBoolean connectionBroken) {
            super(out, size);
            this.connectionBroken = connectionBroken;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (connectionBroken.get()) throw new IOException("Connection broken");
            super.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            if (connectionBroken.get()) return;
            super.flush();
        }
    }
}