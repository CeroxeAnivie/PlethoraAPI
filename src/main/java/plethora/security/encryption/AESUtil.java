package plethora.security.encryption;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class AESUtil {
    // GCM 参数配置
    private static final int GCM_IV_LENGTH = 12; // 推荐12字节IV
    private static final int GCM_TAG_LENGTH = 16 * 8; // 128位认证标签
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final byte[] keyBytes;
    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AESUtil(int keySize) {
        try {
            // 生成新密钥
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(keySize);
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
        ;
        this.keyBytes = key.getEncoded();
    }

    public synchronized byte[] encrypt(byte[] plaintext) {
        return encryptInternal(plaintext, 0, plaintext.length);
    }

    public synchronized byte[] encrypt(byte[] data, int inputOffset, int inputLen) {
        return encryptInternal(data, inputOffset, inputLen);
    }

    private byte[] encryptInternal(byte[] data, int offset, int length) {
        try {
            // 生成随机IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            // 初始化GCM加密器
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);

            // 执行加密（返回结果包含认证标签）
            byte[] ciphertext = cipher.doFinal(data, offset, length);

            // 组合IV + 密文 + 标签
            return ByteBuffer.allocate(GCM_IV_LENGTH + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
        } catch (NoSuchAlgorithmException | NoSuchPaddingException |
                 InvalidKeyException | InvalidAlgorithmParameterException |
                 IllegalBlockSizeException | BadPaddingException e) {
            throw new SecurityException("Encryption failed", e);
        }
    }

    public synchronized byte[] decrypt(byte[] encryptedData) {
        return decryptInternal(encryptedData, 0, encryptedData.length);
    }

    public synchronized byte[] decrypt(byte[] data, int inputOffset, int inputLen) {
        return decryptInternal(data, inputOffset, inputLen);
    }

    private byte[] decryptInternal(byte[] data, int offset, int length) {
        try {
            // 验证最小数据长度
            if (length <= GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Encrypted data too short");
            }

            // 提取IV和实际密文
            ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            // 初始化GCM解密器
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);

            // 执行解密并验证标签
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new SecurityException("Authentication failed - data may be tampered", e);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException |
                 InvalidKeyException | InvalidAlgorithmParameterException |
                 IllegalBlockSizeException | BadPaddingException e) {
            throw new SecurityException("Decryption failed", e);
        }
    }

    // 辅助方法：生成随机IV
    public byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        return iv;
    }

    // 辅助方法：加密数据并返回Base64字符串
    public String encryptToBase64(String plaintext) {
        byte[] encrypted = encrypt(plaintext.getBytes());
        return Base64.getEncoder().encodeToString(encrypted);
    }

    // 辅助方法：解密Base64字符串
    public String decryptFromBase64(String base64Ciphertext) {
        byte[] encrypted = Base64.getDecoder().decode(base64Ciphertext);
        byte[] decrypted = decrypt(encrypted);
        return new String(decrypted);
    }

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