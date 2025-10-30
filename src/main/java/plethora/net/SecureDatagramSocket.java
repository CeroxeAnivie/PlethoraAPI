package plethora.net;

import plethora.security.encryption.AESUtil;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个提供工业级加密和安全性的安全 UDP DatagramSocket 封装。
 * <p>
 * <b>核心安全特性：</b>
 * <ul>
 *   <li>使用 AES/GCM 进行数据加密，保证机密性和完整性。</li>
 *   <li>使用 Diffie-Hellman (DH) 进行密钥交换，实现前向保密。</li>
 *   <li><b>（推荐）</b>使用预共享密钥 (PSK) 对 DH 公钥进行身份验证，有效防御中间人攻击。</li>
 *   <li>使用 HKDF (HMAC-based Extract-and-Expand KDF) 安全地派生会话密钥。</li>
 * </ul>
 * <p>
 * <b>使用方式：</b>
 * <p>
 * 此类设计用于点对点安全通信。握手过程在首次调用 {@code send} 或 {@code receive} 时自动触发。
 * <ul>
 *   <li><b>客户端角色</b>：首次调用 {@code send} 方法时，自动发起握手。</li>
 *   <li><b>服务器角色</b>：首次调用 {@code receive} 方法时，自动等待并响应握手请求。</li>
 * </ul>
 * <p>
 * <b>安全警告：</b> 为了向后兼容，不使用PSK的构造函数将执行一个不安全的握手，容易受到中间人攻击。
 * 强烈建议使用 {@link #SecureDatagramSocket(byte[])} 等接受PSK的构造函数来确保通信安全。
 */
public class SecureDatagramSocket implements Closeable {

    // --- 安全与协议常量 ---
    private static final int DEFAULT_DH_KEY_SIZE = 2048;
    private static final int DEFAULT_AES_KEY_SIZE = 128; // in bits
    private static final int DEFAULT_GCM_TAG_LENGTH = 128; // in bits
    private static final int HMAC_SHA256_LENGTH = 32; // bytes
    private static final int MAX_UDP_PAYLOAD = 65507; // IPv4 theoretical max

    // --- 停止信号常量 (恢复原始功能) ---
    // 假设这些是原始 SecureSocket 类中的定义，用于保持协议兼容性。
    private static final String STOP_STRING = "__STOP__";
    private static final byte[] STOP_BYTE = new byte[]{0}; // 假设停止字节是单个0

    // --- 内部状态 ---
    public enum State {
        INIT, HANDSHAKING, ESTABLISHED, CLOSING, CLOSED
    }

    private final DatagramSocket datagramSocket;
    private final AtomicReference<State> state = new AtomicReference<>(State.INIT);
    private AESUtil aesUtil;
    private final byte[] psk; // 预共享密钥，用于身份验证。可为 null，表示使用不安全模式。

    // --- 握手后记录的对端信息 ---
    private volatile InetAddress peerAddress;
    private volatile int peerPort = -1;
    private volatile Boolean isClientMode = null; // null: 未确定, true: 客户端, false: 服务器

    // --- 配置参数 ---
    private final int dhKeySize;
    private final int aesKeySize;

    //=========================================================================
    //  原始构造函数 (向后兼容，但握手不安全)
    //=========================================================================

    public SecureDatagramSocket() throws SocketException {
        this(new DatagramSocket(), null, DEFAULT_DH_KEY_SIZE, DEFAULT_AES_KEY_SIZE);
    }


    public SecureDatagramSocket(int port) throws SocketException {
        this(new DatagramSocket(port), null, DEFAULT_DH_KEY_SIZE, DEFAULT_AES_KEY_SIZE);
    }

    public SecureDatagramSocket(int port, InetAddress addr) throws SocketException {
        this(new DatagramSocket(port, addr), null, DEFAULT_DH_KEY_SIZE, DEFAULT_AES_KEY_SIZE);
    }

    public SecureDatagramSocket(DatagramSocket datagramSocket) {
        this(datagramSocket, null, DEFAULT_DH_KEY_SIZE, DEFAULT_AES_KEY_SIZE);
    }

    /**
     * 使用预设的 AES 密钥构造 SecureDatagramSocket，跳过握手。
     * 此方法是安全的，因为它不依赖不安全的DH交换。
     *
     * @param key AES 密钥 (例如 SecretKeySpec)。
     * @throws SocketException 如果无法创建底层套接字。
     */
    public SecureDatagramSocket(javax.crypto.SecretKey key) throws SocketException {
        this(new DatagramSocket(), null, DEFAULT_DH_KEY_SIZE, DEFAULT_AES_KEY_SIZE);
        this.aesUtil = new AESUtil(key);
        this.state.set(State.ESTABLISHED);
        this.isClientMode = null;
    }

    /**
     * 使用预设的 AES 密钥字符串构造 SecureDatagramSocket，跳过握手。
     * 此方法是安全的，因为它不依赖不安全的DH交换。
     *
     * @param encodedKeyString Base64 编码的 AES 密钥字符串。
     * @throws SocketException 如果无法创建底层套接字。
     */
    public SecureDatagramSocket(String encodedKeyString) throws SocketException {
        this(new DatagramSocket(), null, DEFAULT_DH_KEY_SIZE, DEFAULT_AES_KEY_SIZE);
        this.aesUtil = new AESUtil(encodedKeyString);
        this.state.set(State.ESTABLISHED);
        this.isClientMode = null;
    }

    //=========================================================================
    //  新的安全构造函数 (推荐使用)
    //=========================================================================

    /**
     * 创建一个绑定到任意可用端口的 SecureDatagramSocket。
     * 必须提供预共享密钥 (PSK) 用于身份验证，以确保握手安全。
     *
     * @param psk 预共享密钥，通信双方必须使用相同的密钥。
     * @throws SocketException 如果无法创建套接字。
     * @throws IllegalArgumentException 如果 psk 为 null 或空。
     */
    public SecureDatagramSocket(byte[] psk) throws SocketException {
        this(new DatagramSocket(), psk, DEFAULT_DH_KEY_SIZE, DEFAULT_AES_KEY_SIZE);
    }

    /**
     * 创建一个绑定到指定本地端口的 SecureDatagramSocket。
     * 必须提供预共享密钥 (PSK) 用于身份验证，以确保握手安全。
     *
     * @param port 本地端口。
     * @param psk  预共享密钥。
     * @throws SocketException 如果无法绑定到指定端口。
     * @throws IllegalArgumentException 如果 psk 为 null 或空。
     */
    public SecureDatagramSocket(int port, byte[] psk) throws SocketException {
        this(new DatagramSocket(port), psk, DEFAULT_DH_KEY_SIZE, DEFAULT_AES_KEY_SIZE);
    }

    /**
     * 使用现有的 DatagramSocket 构造 SecureDatagramSocket。
     * 必须提供预共享密钥 (PSK) 用于身份验证，以确保握手安全。
     *
     * @param datagramSocket 现有的 DatagramSocket 实例。
     * @param psk             预共享密钥。
     * @throws IllegalArgumentException 如果 datagramSocket 或 psk 无效。
     */
    public SecureDatagramSocket(DatagramSocket datagramSocket, byte[] psk) {
        this(datagramSocket, psk, DEFAULT_DH_KEY_SIZE, DEFAULT_AES_KEY_SIZE);
    }

    //=========================================================================
    //  内部实现
    //=========================================================================

    /**
     * 内部主构造函数。
     */
    private SecureDatagramSocket(DatagramSocket datagramSocket, byte[] psk, int dhKeySize, int aesKeySize) {
        if (datagramSocket == null || datagramSocket.isClosed()) {
            throw new IllegalArgumentException("DatagramSocket must not be null or closed.");
        }
        this.datagramSocket = datagramSocket;
        this.psk = (psk != null) ? Arrays.copyOf(psk, psk.length) : null; // Defensive copy
        this.dhKeySize = dhKeySize;
        this.aesKeySize = aesKeySize;
    }

    // --- 握手逻辑 (内部实现，支持安全和不安全两种模式) ---
    private void performHandshake(InetAddress targetAddress, int targetPort) throws IOException {
        if (state.get() == State.ESTABLISHED) return;
        if (state.get() == State.CLOSED || state.get() == State.CLOSING) throw new SocketException("Socket is closed or closing.");

        synchronized (this) {
            if (state.get() == State.ESTABLISHED) return;

            if (isClientMode == null) {
                if (targetAddress != null && targetPort > 0) {
                    isClientMode = true;
                    this.peerAddress = targetAddress;
                    this.peerPort = targetPort;
                } else {
                    isClientMode = false;
                }
            }
            state.set(State.HANDSHAKING);
            try {
                KeyPair keyPair = generateDHKeyPair();
                byte[] publicKeyBytes = keyPair.getPublic().getEncoded();

                if (isClientMode) {
                    performClientHandshake(keyPair, publicKeyBytes);
                } else {
                    performServerHandshake(keyPair, publicKeyBytes);
                }

                state.set(State.ESTABLISHED);
            } catch (GeneralSecurityException e) {
                state.set(State.INIT);
                throw new IOException("Handshake failed: " + e.getMessage(), e);
            } catch (IOException e) {
                state.set(State.INIT);
                throw e;
            }
        }
    }

    private KeyPair generateDHKeyPair() throws GeneralSecurityException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("DH");
        keyGen.initialize(dhKeySize);
        return keyGen.generateKeyPair();
    }

    private void performClientHandshake(KeyPair clientKeyPair, byte[] clientPublicKeyBytes) throws IOException, GeneralSecurityException {
        byte[] payload;
        if (psk != null) {
            payload = createAuthenticatedPayload(clientPublicKeyBytes);
        } else {
            payload = clientPublicKeyBytes;
        }
        sendRaw(payload, peerAddress, peerPort);

        byte[] response = receiveRawForHandshake();
        PublicKey serverPublicKey = (psk != null) ? parseAndVerifyAuthenticatedPayload(response) : parseUnverifiedPayload(response);

        completeKeyAgreement(clientKeyPair.getPrivate(), serverPublicKey);
    }

    private void performServerHandshake(KeyPair serverKeyPair, byte[] serverPublicKeyBytes) throws IOException, GeneralSecurityException {
        byte[] request = receiveRawForHandshake();
        PublicKey clientPublicKey = (psk != null) ? parseAndVerifyAuthenticatedPayload(request) : parseUnverifiedPayload(request);

        byte[] payload;
        if (psk != null) {
            payload = createAuthenticatedPayload(serverPublicKeyBytes);
        } else {
            payload = serverPublicKeyBytes;
        }
        sendRaw(payload, peerAddress, peerPort);

        completeKeyAgreement(serverKeyPair.getPrivate(), clientPublicKey);
    }

    private byte[] receiveRawForHandshake() throws IOException {
        byte[] buffer = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        datagramSocket.receive(packet);
        byte[] receivedData = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), receivedData, 0, receivedData.length);
        this.peerAddress = packet.getAddress();
        this.peerPort = packet.getPort();
        return receivedData;
    }

    // --- 安全握手辅助方法 ---
    private byte[] createAuthenticatedPayload(byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(psk, "HmacSHA256"));
        byte[] hmac = mac.doFinal(data);
        byte[] payload = new byte[HMAC_SHA256_LENGTH + data.length];
        System.arraycopy(hmac, 0, payload, 0, HMAC_SHA256_LENGTH);
        System.arraycopy(data, 0, payload, HMAC_SHA256_LENGTH, data.length);
        return payload;
    }

    private PublicKey parseAndVerifyAuthenticatedPayload(byte[] payload) throws GeneralSecurityException, IOException {
        if (payload.length < HMAC_SHA256_LENGTH) throw new SecurityException("Payload too short for HMAC.");
        byte[] receivedHmac = new byte[HMAC_SHA256_LENGTH];
        byte[] receivedData = new byte[payload.length - HMAC_SHA256_LENGTH];
        System.arraycopy(payload, 0, receivedHmac, 0, HMAC_SHA256_LENGTH);
        System.arraycopy(payload, HMAC_SHA256_LENGTH, receivedData, 0, receivedData.length);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(psk, "HmacSHA256"));
        byte[] computedHmac = mac.doFinal(receivedData);
        if (!MessageDigest.isEqual(receivedHmac, computedHmac)) {
            throw new SecurityException("HMAC verification failed. Authentication failed.");
        }
        return parseUnverifiedPayload(receivedData);
    }

    // --- 不安全握手辅助方法 ---
    private PublicKey parseUnverifiedPayload(byte[] publicKeyBytes) throws GeneralSecurityException {
        KeyFactory keyFactory = KeyFactory.getInstance("DH");
        return keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
    }

    // --- 密钥协商 ---
    private void completeKeyAgreement(PrivateKey privateKey, PublicKey peerPublicKey) throws GeneralSecurityException {
        KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
        keyAgreement.init(privateKey);
        keyAgreement.doPhase(peerPublicKey, true);
        byte[] sharedSecret = keyAgreement.generateSecret();
        byte[] sessionKey = deriveKeyWithHKDF(sharedSecret);
        this.aesUtil = new AESUtil(new SecretKeySpec(sessionKey, "AES"));
    }

    private byte[] deriveKeyWithHKDF(byte[] sharedSecret) throws GeneralSecurityException {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(new byte[HMAC_SHA256_LENGTH], "HmacSHA256"));
        byte[] prk = hmac.doFinal(sharedSecret);
        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] info = "SecureDatagramSocket Session Key".getBytes();
        hmac.update(info);
        hmac.update((byte) 0x01);
        byte[] okm = hmac.doFinal();
        byte[] aesKeyBytes = new byte[aesKeySize / 8];
        System.arraycopy(okm, 0, aesKeyBytes, 0, aesKeyBytes.length);
        return aesKeyBytes;
    }

    //=========================================================================
    //  公共 API (原始签名)
    //=========================================================================

    public DatagramSocket getDatagramSocket() { return datagramSocket; }

    public void setKey(javax.crypto.SecretKey key) {
        this.aesUtil = new AESUtil(key);
        this.state.set(State.ESTABLISHED);
        this.isClientMode = null;
    }

    public void setKey(String encodedKeyString) {
        this.aesUtil = new AESUtil(encodedKeyString);
        this.state.set(State.ESTABLISHED);
        this.isClientMode = null;
    }

    public int sendStr(String message, InetAddress address, int port) throws IOException {
        performHandshake(address, port);
        // *** 修复开始：恢复原始的停止信号逻辑 ***
        byte[] data = (message != null ? message : STOP_STRING).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // *** 修复结束 ***
        byte[] encrypted = aesUtil.encrypt(data);
        return sendRaw(encrypted, address, port);
    }

    public int sendStr(String message) throws IOException {
        if (peerAddress == null || peerPort == -1) throw new IOException("No peer address available.");
        return sendStr(message, peerAddress, peerPort);
    }

    public int sendByte(byte[] data, InetAddress address, int port) throws IOException {
        performHandshake(address, port);
        // *** 修复开始：恢复原始的停止信号逻辑 ***
        byte[] dataToSend = (data != null ? data : STOP_BYTE);
        // *** 修复结束 ***
        byte[] encrypted = aesUtil.encrypt(dataToSend);
        return sendRaw(encrypted, address, port);
    }

    public int sendByte(byte[] data) throws IOException {
        if (peerAddress == null || peerPort == -1) throw new IOException("No peer address available.");
        return sendByte(data, peerAddress, peerPort);
    }

    public int sendRaw(byte[] data, InetAddress address, int port) throws IOException {
        if (state.get() == State.CLOSED || state.get() == State.CLOSING) throw new SocketException("Socket is closed.");
        if (data.length > MAX_UDP_PAYLOAD) throw new IOException("Data size exceeds max UDP payload.");
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
        datagramSocket.send(packet);
        return data.length;
    }

    public String receiveStr() throws IOException {
        performHandshake(null, -1);
        byte[] encryptedRaw = receiveRaw();
        try {
            byte[] decryptedRaw = aesUtil.decrypt(encryptedRaw);
            // *** 修复开始：恢复原始的停止信号逻辑 ***
            if (java.util.Arrays.equals(decryptedRaw, STOP_STRING.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                return null; // 是停止字符串，返回 null
            }
            // *** 修复结束 ***
            return new String(decryptedRaw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (SecurityException e) {
            throw new IOException("Decryption failed", e);
        }
    }

    public byte[] receiveByte() throws IOException {
        performHandshake(null, -1);
        byte[] encryptedRaw = receiveRaw();
        try {
            byte[] decryptedRaw = aesUtil.decrypt(encryptedRaw);
            // *** 修复开始：恢复原始的停止信号逻辑 ***
            if (decryptedRaw.length == 1 && decryptedRaw[0] == STOP_BYTE[0]) {
                return null; // 是停止字节，返回 null
            }
            // *** 修复结束 ***
            return decryptedRaw;
        } catch (SecurityException e) {
            throw new IOException("Decryption failed", e);
        }
    }

    public byte[] receiveRaw() throws IOException {
        if (state.get() == State.CLOSED || state.get() == State.CLOSING) throw new SocketException("Socket is closed.");
        byte[] buffer = new byte[MAX_UDP_PAYLOAD];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        datagramSocket.receive(packet);
        byte[] receivedData = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), receivedData, 0, receivedData.length);
        this.peerAddress = packet.getAddress();
        this.peerPort = packet.getPort();
        return receivedData;
    }

    // --- 状态和属性访问 ---
    public State getState() { return state.get(); }
    public boolean isHandshakeCompleted() { return state.get() == State.ESTABLISHED; }
    public InetAddress getPeerAddress() { return peerAddress; }
    public int getPeerPort() { return peerPort; }
    public int getLocalPort() { return datagramSocket.getLocalPort(); }
    public InetAddress getLocalAddress() { return datagramSocket.getLocalAddress(); }
    public boolean isClosed() { return datagramSocket.isClosed() || state.get() == State.CLOSED; }

    @Override
    public void close() {
        if (state.compareAndSet(State.CLOSED, State.CLOSED)) return;
        if (state.compareAndSet(State.INIT, State.CLOSING) ||
                state.compareAndSet(State.HANDSHAKING, State.CLOSING) ||
                state.compareAndSet(State.ESTABLISHED, State.CLOSING)) {
            datagramSocket.close();
            state.set(State.CLOSED);
        }
    }

    // --- Socket 兼容方法 (未改动) ---
    public int getSoTimeout() throws SocketException { return datagramSocket.getSoTimeout(); }
    public void setSoTimeout(int timeout) throws SocketException { datagramSocket.setSoTimeout(timeout); }
    public boolean isConnected() { return datagramSocket.isConnected() && !isClosed(); }
    public void connect(InetAddress address, int port) { datagramSocket.connect(address, port); }
    public void disconnect() { datagramSocket.disconnect(); }
    public SocketAddress getRemoteSocketAddress() { return datagramSocket.getRemoteSocketAddress(); }
    public SocketAddress getLocalSocketAddress() { return datagramSocket.getLocalSocketAddress(); }
    public void setReuseAddress(boolean on) throws SocketException { datagramSocket.setReuseAddress(on); }
    public boolean getReuseAddress() throws SocketException { return datagramSocket.getReuseAddress(); }
    public void setBroadcast(boolean on) throws SocketException { datagramSocket.setBroadcast(on); }
    public boolean getBroadcast() throws SocketException { return datagramSocket.getBroadcast(); }
    public void setReceiveBufferSize(int size) throws SocketException { datagramSocket.setReceiveBufferSize(size); }
    public int getReceiveBufferSize() throws SocketException { return datagramSocket.getReceiveBufferSize(); }
    public void setSendBufferSize(int size) throws SocketException { datagramSocket.setSendBufferSize(size); }
    public int getSendBufferSize() throws SocketException { return datagramSocket.getSendBufferSize(); }
}