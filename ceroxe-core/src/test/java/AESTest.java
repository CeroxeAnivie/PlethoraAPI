import fun.ceroxe.api.security.encryption.AESUtil;

import java.util.Arrays;

public class AESTest {
    public static void main(String[] args) {
        AESUtil aesUtil = new AESUtil(128);
        byte[] data = new byte[]{1, 2, 3, 4, 5, 44, 55, 77, 44, 22, 88, 8};
        byte[] endata = aesUtil.encrypt(data);
        System.out.println("Arrays.toString(endata) = " + Arrays.toString(endata));
        byte[] dedata = aesUtil.decrypt(endata);
        System.out.println("dedata = " + Arrays.toString(dedata));

        String encodedKey = aesUtil.getEncodedKey();
        AESUtil aesUtil1 = new AESUtil(encodedKey);
        byte[] aes1dedata = aesUtil1.decrypt(endata);
        System.out.println("aes1dedata = " + Arrays.toString(aes1dedata));
        System.out.println("encodedKey = " + encodedKey);


    }
}
