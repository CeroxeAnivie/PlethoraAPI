package fun.ceroxe.api.security.encryption;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import java.security.*;

public final class RSAUtil {
    private Cipher enCipher;
    private Cipher deCipher;
    private PublicKey publicKey;
    private PrivateKey privateKey;

    public RSAUtil(int keySize) {
        try {
            KeyPairGenerator keyPairGenerator;
            keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(keySize); // 设置密钥长度
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            // 获取公钥和私钥
            this.publicKey = keyPair.getPublic();
            this.privateKey = keyPair.getPrivate();

            this.enCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            this.enCipher.init(Cipher.ENCRYPT_MODE, publicKey);

            this.deCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            this.deCipher.init(Cipher.DECRYPT_MODE, privateKey);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public RSAUtil(PublicKey publicKey, PrivateKey privateKey) {
        try {
            this.privateKey = privateKey;
            this.publicKey = publicKey;

            this.enCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            this.enCipher.init(Cipher.ENCRYPT_MODE, publicKey);

            this.deCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            this.deCipher.init(Cipher.DECRYPT_MODE, privateKey);
        } catch (InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException e) {
            e.printStackTrace();
        }
    }

    public synchronized byte[] encrypt(byte[] data) {
        try {
            return enCipher.doFinal(data);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public synchronized byte[] encrypt(byte[] data, int inputOffset, int inputLen) {
        try {
            return enCipher.doFinal(data, inputOffset, inputLen);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public synchronized byte[] decrypt(byte[] data) {
        try {
            return deCipher.doFinal(data);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public synchronized byte[] decrypt(byte[] data, int inputOffset, int inputLen) {
        try {
            return deCipher.doFinal(data, inputOffset, inputLen);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(PrivateKey privateKey) {
        this.privateKey = privateKey;
        try {
            this.deCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            this.deCipher.init(Cipher.DECRYPT_MODE, privateKey);
        } catch (Exception ignore) {
        }//impossible
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(PublicKey publicKey) {
        this.publicKey = publicKey;
        try {
            this.enCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            this.enCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        } catch (Exception ignore) {
        }//impossible
    }
}
