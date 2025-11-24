package plethora.security.encryption;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

public class AESUtil {
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    // ThreadLocal 缓存 Cipher 实例，避免重复 getInstance 的高昂开销
    private static final ThreadLocal<Cipher> ENCRYPT_CIPHER = ThreadLocal.withInitial(() -> initCipher());
    private static final ThreadLocal<Cipher> DECRYPT_CIPHER = ThreadLocal.withInitial(() -> initCipher());

    private final SecretKey key;
    private final byte[] keyBytes;
    private final SecureRandom secureRandom;

    private static Cipher initCipher() {
        try {
            return Cipher.getInstance(TRANSFORMATION);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new SecurityException("Failed to initialize AES/GCM cipher", e);
        }
    }

    public AESUtil(int keySize) {
        this.secureRandom = new SecureRandom();
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(keySize, this.secureRandom);
            this.key = keyGen.generateKey();
            this.keyBytes = this.key.getEncoded();
        } catch (NoSuchAlgorithmException e) {
            throw new SecurityException("AES algorithm not available", e);
        }
    }

    public AESUtil(SecretKey key) {
        this.secureRandom = new SecureRandom();
        this.key = key;
        this.keyBytes = key.getEncoded();
    }

    public AESUtil(String encodedKeyString) {
        this.secureRandom = new SecureRandom();
        byte[] decodedKey = Base64.getDecoder().decode(encodedKeyString);
        this.key = new SecretKeySpec(decodedKey, "AES");
        this.keyBytes = key.getEncoded();
    }

    private static void validateArrayBounds(byte[] array, int offset, int length) {
        if (array == null) throw new IllegalArgumentException("Input array must not be null");
        if (offset < 0 || length < 0 || offset + length > array.length) {
            throw new IllegalArgumentException("Invalid offset or length");
        }
    }

    public byte[] encrypt(byte[] plaintext) {
        if (plaintext == null) throw new IllegalArgumentException("Plaintext must not be null");
        return encryptInternal(plaintext, 0, plaintext.length);
    }

    public byte[] encrypt(byte[] data, int inputOffset, int inputLen) {
        validateArrayBounds(data, inputOffset, inputLen);
        return encryptInternal(data, inputOffset, inputLen);
    }

    private byte[] encryptInternal(byte[] data, int offset, int length) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv); // 生成随机 IV

            Cipher cipher = ENCRYPT_CIPHER.get();
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            int outputSize = cipher.getOutputSize(length);
            // 优化：直接分配最终大小的数组，避免 ByteBuffer 的额外包装和拷贝
            byte[] output = new byte[GCM_IV_LENGTH + outputSize];

            // 1. 复制 IV 到头部
            System.arraycopy(iv, 0, output, 0, GCM_IV_LENGTH);

            // 2. 执行加密直接写入 output 数组
            cipher.doFinal(data, offset, length, output, GCM_IV_LENGTH);

            return output;
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] encryptedData) {
        if (encryptedData == null) throw new IllegalArgumentException("Encrypted data must not be null");
        return decryptInternal(encryptedData, 0, encryptedData.length);
    }

    public byte[] decrypt(byte[] data, int inputOffset, int inputLen) {
        validateArrayBounds(data, inputOffset, inputLen);
        return decryptInternal(data, inputOffset, inputLen);
    }

    private byte[] decryptInternal(byte[] data, int offset, int length) {
        if (length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Encrypted data too short");
        }

        try {
            // 从数据中提取 IV 参数
            GCMParameterSpec params = new GCMParameterSpec(GCM_TAG_LENGTH, data, offset, GCM_IV_LENGTH);

            Cipher cipher = DECRYPT_CIPHER.get();
            cipher.init(Cipher.DECRYPT_MODE, key, params);

            // 计算密文偏移量：offset + IV_LENGTH
            // 密文长度：length - IV_LENGTH
            return cipher.doFinal(data, offset + GCM_IV_LENGTH, length - GCM_IV_LENGTH);
        } catch (AEADBadTagException e) {
            throw new SecurityException("Authentication failed - data may be tampered", e);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Decryption failed", e);
        }
    }

    public byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        return iv;
    }

    public String encryptToBase64(String plaintext) {
        if (plaintext == null) throw new IllegalArgumentException("Plaintext must not be null");
        byte[] encrypted = encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public String decryptFromBase64(String base64Ciphertext) {
        if (base64Ciphertext == null) throw new IllegalArgumentException("Ciphertext must not be null");
        byte[] encrypted = Base64.getDecoder().decode(base64Ciphertext);
        byte[] decrypted = decrypt(encrypted);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    public String getEncodedKey() {
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    public byte[] getKeyBytes() {
        return keyBytes;
    }

    public SecretKey getKey() {
        return key;
    }
}