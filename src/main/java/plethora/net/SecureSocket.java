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

    // *** 优化点 2: 升级为 X25519 (Curve25519) ***
    // Java 11+ 支持，Java 21 性能极佳。比 DH 快且更安全，公钥仅 32 字节。
    private static final String KEY_EXCHANGE_ALGO = "X25519";

    // *** 优化点 3: ThreadLocal 缓存算法实例，避免重复 getInstance() ***
    // KeyPairGenerator 不是线程安全的，因此使用 ThreadLocal 而非简单的静态变量
    private static final ThreadLocal<KeyPairGenerator> KEY_PAIR_GEN_TL = ThreadLocal.withInitial(() -> {
        try {
            return KeyPairGenerator.getInstance(KEY_EXCHANGE_ALGO);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("X25519 algorithm not available", e);
        }
    });

    private static final ThreadLocal<KeyFactory> KEY_FACTORY_TL = ThreadLocal.withInitial(() -> {
        try {
            return KeyFactory.getInstance(KEY_EXCHANGE_ALGO);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("X25519 factory not available", e);
        }
    });

    private Socket socket;
    private BufferedInputStream inputStream;
    private SilentBufferedOutputStream outputStream;
    private final AtomicBoolean connectionClosed = new AtomicBoolean(false);
    private final AtomicBoolean connectionBroken = new AtomicBoolean(false);
    private AESUtil aesUtil;

    // ==================== 构造函数 (逻辑保持不变) ====================

    public SecureSocket(String host, int port) throws IOException {
        this(new Socket(host, port));
        try {
            performClientHandshake();
        } catch (Exception e) {
            close();
            throw new IOException("Handshake failed", e);
        }
    }

    public SecureSocket(Proxy proxy, String host, int port) throws IOException {
        this.socket = new Socket(proxy);
        initStreams();
        try {
            connect(host, port);
            performClientHandshake();
        } catch (Exception e) {
            close();
            throw new IOException("Handshake failed", e);
        }
    }

    public SecureSocket(Socket socket) throws IOException {
        this.socket = socket;
        initStreams();
    }

    public SecureSocket() throws IOException {
        this.socket = new Socket();
        initStreams();
    }

    private void initStreams() throws IOException {
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

    public void connect(String host, int port, int timeout) throws IOException {
        socket.connect(new InetSocketAddress(host, port), timeout);
    }

    public void connect(Proxy proxy, String host, int port) throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        this.socket = new Socket(proxy);
        initStreams();
        this.socket.connect(new InetSocketAddress(host, port));
    }

    // ==================== 握手协议 (性能优化核心) ====================

    protected void performServerHandshake() throws Exception {
        try {
            // 1. 使用缓存的生成器生成密钥对 (极快)
            KeyPair keyPair = KEY_PAIR_GEN_TL.get().generateKeyPair();

            // 2. 发送服务器公钥 (X25519 公钥很短，仅约 44 字节 Base64 长度)
            byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
            sendRaw(publicKeyBytes);

            // 3. 接收客户端公钥
            byte[] otherPublicKeyBytes = receiveRaw();

            // 4. 使用缓存的 KeyFactory 还原公钥
            KeyFactory keyFactory = KEY_FACTORY_TL.get();
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(otherPublicKeyBytes);
            PublicKey otherPublicKey = keyFactory.generatePublic(keySpec);

            // 5. 生成共享密钥
            generateSharedSecret(keyPair, otherPublicKey);
        } catch (SocketTimeoutException e) {
            throw new IOException("Handshake timeout - client did not respond in time", e);
        } catch (EOFException e) {
            throw new IOException("Handshake failed - client disconnected unexpectedly", e);
        }
    }

    protected void performClientHandshake() throws Exception {
        // 1. 接收服务器公钥
        byte[] serverPublicKeyBytes = receiveRaw();

        KeyFactory keyFactory = KEY_FACTORY_TL.get();
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(serverPublicKeyBytes);
        PublicKey serverPublicKey = keyFactory.generatePublic(keySpec);

        // 2. 生成密钥对
        KeyPair keyPair = KEY_PAIR_GEN_TL.get().generateKeyPair();

        // 3. 发送客户端公钥
        byte[] clientPublicKeyBytes = keyPair.getPublic().getEncoded();
        sendRaw(clientPublicKeyBytes);

        // 4. 生成共享密钥
        generateSharedSecret(keyPair, serverPublicKey);
    }

    private void generateSharedSecret(KeyPair keyPair, PublicKey otherPublicKey) throws Exception {
        // KeyAgreement 实例化开销很小，且是有状态的，通常不缓存或通过 ThreadLocal 缓存
        KeyAgreement keyAgreement = KeyAgreement.getInstance(KEY_EXCHANGE_ALGO);
        keyAgreement.init(keyPair.getPrivate());
        keyAgreement.doPhase(otherPublicKey, true);

        byte[] sharedSecret = keyAgreement.generateSecret();

        // 保持 SHA-256 哈希以生成最终 AES 密钥
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] sessionKey = digest.digest(sharedSecret);

        this.aesUtil = new AESUtil(new SecretKeySpec(sessionKey, "AES"));
    }

    @Deprecated
    protected void performHandshake() throws Exception {
        performServerHandshake();
    }

    // ==================== 数据收发 (保持上一次优化的状态) ====================

    public int sendStr(String message) throws IOException {
        if (connectionBroken.get()) return -1;
        byte[] data = (message != null ? message : STOP_STRING).getBytes(StandardCharsets.UTF_8);
        return sendEncrypted(data);
    }

    public String receiveStr() throws IOException {
        return receiveStr(0);
    }

    public String receiveStr(int timeoutMillis) throws IOException {
        byte[] decrypted = receiveDecrypted(timeoutMillis);
        if (decrypted == null) return null;
        String str = new String(decrypted, StandardCharsets.UTF_8);
        return str.equals(STOP_STRING) ? null : str;
    }

    public int sendByte(byte[] data) throws IOException {
        return sendEncrypted(data == null ? STOP_BYTE : data);
    }

    public int sendByte(byte[] data, int offset, int length) throws IOException {
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
        byte[] decrypted = receiveDecrypted(timeoutMillis);
        if (decrypted != null && decrypted.length == 1 && decrypted[0] == STOP_BYTE[0]) {
            return null;
        }
        return decrypted;
    }

    public int sendInt(int value) throws IOException {
        byte[] intBytes = new byte[]{
                (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value
        };
        return sendEncrypted(intBytes);
    }

    public int receiveInt() throws IOException {
        return receiveInt(0);
    }

    public int receiveInt(int timeoutMillis) throws IOException {
        byte[] decrypted = receiveDecrypted(timeoutMillis);
        if (decrypted == null || decrypted.length != 4)
            throw new IOException("Invalid int data received");
        return ((decrypted[0] & 0xFF) << 24) | ((decrypted[1] & 0xFF) << 16) |
                ((decrypted[2] & 0xFF) << 8)  | (decrypted[3] & 0xFF);
    }

    private int sendEncrypted(byte[] plainData) throws IOException {
        try {
            byte[] encrypted = aesUtil.encrypt(plainData);
            return sendRaw(encrypted);
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
        int originalTimeout = socket.getSoTimeout();
        try {
            if (timeoutMillis > 0) socket.setSoTimeout(timeoutMillis);
            byte[] encrypted = receiveRaw();
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
                try { socket.setSoTimeout(originalTimeout); } catch (Exception ignored) {}
            }
        }
    }

    public int sendRaw(byte[] data) throws IOException {
        if (connectionBroken.get()) return -1;
        try {
            int len = data.length;
            byte[] header = new byte[]{
                    (byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len
            };
            outputStream.write(header);
            outputStream.write(data);
            outputStream.flush();
            return 4 + len;
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
            byte[] lengthBytes = inputStream.readNBytes(4);
            if (lengthBytes.length < 4) {
                throw new EOFException("Connection closed while reading length");
            }

            int length = ((lengthBytes[0] & 0xFF) << 24) | ((lengthBytes[1] & 0xFF) << 16) |
                    ((lengthBytes[2] & 0xFF) << 8)  | (lengthBytes[3] & 0xFF);

            if (length < 0) throw new IOException("Invalid negative data length: " + length);

            int currentMaxSize = MAX_ALLOWED_PACKET_SIZE;
            if (length > currentMaxSize) {
                throw new IOException("Data length " + length + " exceeds maximum allowed " + currentMaxSize);
            }
            if (length == 0) return new byte[0];

            byte[] data = inputStream.readNBytes(length);
            if (data.length < length) {
                throw new EOFException("Expected " + length + " bytes, but got " + data.length);
            }
            return data;

        } catch (SocketTimeoutException e) {
            throw e;
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
            return receiveRaw();
        } catch (SocketTimeoutException e) {
            throw e;
        } finally {
            try { socket.setSoTimeout(originalTimeout); } catch (Exception ignored) {}
        }
    }

    // ==================== 辅助方法和兼容性 ====================

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
        try { close(); } catch (IOException ignored) {}
    }

    @Override
    public void close() throws IOException {
        if (connectionClosed.getAndSet(true)) return;
        try { if (inputStream != null) inputStream.close(); } catch (IOException ignored) {}
        try { if (outputStream != null) outputStream.close(); } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    public boolean isClosed() {
        return (socket != null && socket.isClosed()) || connectionClosed.get() || connectionBroken.get();
    }

    // (Getter/Setter 保持不变)
    public int getPort() { return socket.getPort(); }
    public int getLocalPort() { return socket.getLocalPort(); }
    public InetAddress getInetAddress() { return socket.getInetAddress(); }
    public InetAddress getLocalAddress() { return socket.getLocalAddress(); }
    public int getSoTimeout() throws SocketException { return socket.getSoTimeout(); }
    public void setSoTimeout(int timeout) throws SocketException { socket.setSoTimeout(timeout); }
    public void shutdownInput() throws IOException { socket.shutdownInput(); }
    public void shutdownOutput() throws IOException { socket.shutdownOutput(); }
    public boolean isInputShutdown() { return socket.isInputShutdown(); }
    public boolean isOutputShutdown() { return socket.isOutputShutdown(); }
    public SocketAddress getRemoteSocketAddress() { return socket.getRemoteSocketAddress(); }
    public SocketAddress getLocalSocketAddress() { return socket.getLocalSocketAddress(); }
    public boolean getKeepAlive() throws SocketException { return socket.getKeepAlive(); }
    public void setKeepAlive(boolean on) throws SocketException { socket.setKeepAlive(on); }
    public boolean getTcpNoDelay() throws SocketException { return socket.getTcpNoDelay(); }
    public void setTcpNoDelay(boolean on) throws SocketException { socket.setTcpNoDelay(on); }
    public int getReceiveBufferSize() throws SocketException { return socket.getReceiveBufferSize(); }
    public void setReceiveBufferSize(int size) throws SocketException { socket.setReceiveBufferSize(size); }
    public int getSendBufferSize() throws SocketException { return socket.getSendBufferSize(); }
    public void setSendBufferSize(int size) throws SocketException { socket.setSendBufferSize(size); }
    public boolean isConnected() { return socket.isConnected() && !isClosed(); }
    public boolean isConnectionBroken() { return connectionBroken.get(); }

    private static class SilentBufferedOutputStream extends BufferedOutputStream {
        private final AtomicBoolean connectionBroken;
        public SilentBufferedOutputStream(OutputStream out, int size, AtomicBoolean connectionBroken) {
            super(out, size);
            this.connectionBroken = connectionBroken;
        }
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (connectionBroken.get()) return;
            try { super.write(b, off, len); } catch (IOException e) { handleEx(e); }
        }
        @Override
        public void flush() throws IOException {
            if (connectionBroken.get()) return;
            try { super.flush(); } catch (IOException e) { handleEx(e); }
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