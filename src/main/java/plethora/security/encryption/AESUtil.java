package plethora.security.encryption;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class AESUtil {
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16 * 8; // 128 bits
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    // ThreadLocal 缓存 Cipher 实例（每个线程独立，避免重复初始化）
    private static final ThreadLocal<Cipher> ENCRYPT_CIPHER = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(TRANSFORMATION);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new SecurityException("Failed to initialize AES/GCM cipher", e);
        }
    });

    private static final ThreadLocal<Cipher> DECRYPT_CIPHER = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(TRANSFORMATION);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new SecurityException("Failed to initialize AES/GCM cipher", e);
        }
    });

    private final SecretKey key;
    private final byte[] keyBytes;
    private final SecureRandom secureRandom = new SecureRandom(); // 仍保留，用于生成 IV

    // --- 构造函数（完全不变） ---
    public AESUtil(int keySize) {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(keySize, secureRandom); // 显式传入 SecureRandom（更安全）
            this.key = keyGen.generateKey();
            this.keyBytes = this.key.getEncoded();
        } catch (NoSuchAlgorithmException e) {
            throw new SecurityException("AES algorithm not available", e);
        }
    }

    public AESUtil(SecretKey key) {
        this.key = key;
        this.keyBytes = key.getEncoded();
    }

    public AESUtil(String encodedKeyString) {
        byte[] decodedKey = Base64.getDecoder().decode(encodedKeyString);
        this.key = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
        this.keyBytes = key.getEncoded();
    }

    // --- 新增：参数校验工具 ---
    private static void validateArrayBounds(byte[] array, int offset, int length) {
        if (array == null) {
            throw new IllegalArgumentException("Input array must not be null");
        }
        if (offset < 0 || length < 0 || offset + length > array.length) {
            throw new IllegalArgumentException("Invalid offset or length");
        }
    }

    // --- 加密方法（移除 synchronized，内部线程安全） ---
    public byte[] encrypt(byte[] plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Plaintext must not be null");
        }
        return encryptInternal(plaintext, 0, plaintext.length);
    }

    public byte[] encrypt(byte[] data, int inputOffset, int inputLen) {
        validateArrayBounds(data, inputOffset, inputLen);
        return encryptInternal(data, inputOffset, inputLen);
    }

    private byte[] encryptInternal(byte[] data, int offset, int length) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = ENCRYPT_CIPHER.get();
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(data, offset, length);

            return ByteBuffer.allocate(GCM_IV_LENGTH + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
        } catch (InvalidKeyException | InvalidAlgorithmParameterException |
                 IllegalBlockSizeException | BadPaddingException e) {
            throw new SecurityException("Encryption failed", e);
        }
    }

    // --- 解密方法（移除 synchronized） ---
    public byte[] decrypt(byte[] encryptedData) {
        if (encryptedData == null) {
            throw new IllegalArgumentException("Encrypted data must not be null");
        }
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

        ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
        byte[] iv = new byte[GCM_IV_LENGTH];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        try {
            Cipher cipher = DECRYPT_CIPHER.get();
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new SecurityException("Authentication failed - data may be tampered", e);
        } catch (InvalidKeyException | InvalidAlgorithmParameterException |
                 IllegalBlockSizeException | BadPaddingException e) {
            throw new SecurityException("Decryption failed", e);
        }
    }

    // --- 辅助方法（完全不变，仅优化字符串编码） ---
    public byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        return iv;
    }

    public String encryptToBase64(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Plaintext must not be null");
        }
        byte[] encrypted = encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public String decryptFromBase64(String base64Ciphertext) {
        if (base64Ciphertext == null) {
            throw new IllegalArgumentException("Ciphertext must not be null");
        }
        byte[] encrypted = Base64.getDecoder().decode(base64Ciphertext);
        byte[] decrypted = decrypt(encrypted);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    // --- 密钥访问（不变） ---
    public String getEncodedKey() {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public byte[] getKeyBytes() {
        return keyBytes;
    }

    public SecretKey getKey() {
        return key;
    }
}