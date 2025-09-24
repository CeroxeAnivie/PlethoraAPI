package plethora.security;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 高性能文件属性工具类
 * 提供文件和字符串的MD5、SHA-1和SHA-256哈希计算功能
 */
public class FilePropertyUtil {

    // 缓冲区大小（可根据需要调整）
    private static final int BUFFER_SIZE = 8192;

    /**
     * 计算文件的MD5哈希值
     * @param filePath 文件路径
     * @return MD5哈希值的十六进制字符串表示
     * @throws IOException 如果读取文件时发生错误
     */
    public static String getFileMD5(String filePath) throws IOException {
        return getFileHash(filePath, "MD5");
    }
    public static String getFileMD5(File file) throws IOException {
        return getFileHash(file.getAbsolutePath(), "MD5");
    }

    /**
     * 计算文件的SHA-1哈希值
     * @param filePath 文件路径
     * @return SHA-1哈希值的十六进制字符串表示
     * @throws IOException 如果读取文件时发生错误
     */
    public static String getFileSHA1(String filePath) throws IOException {
        return getFileHash(filePath, "SHA-1");
    }
    public static String getFileSHA1(File file) throws IOException {
        return getFileHash(file.getAbsolutePath(), "SHA-1");
    }

    /**
     * 计算文件的SHA-256哈希值
     * @param filePath 文件路径
     * @return SHA-256哈希值的十六进制字符串表示
     * @throws IOException 如果读取文件时发生错误
     */
    public static String getFileSHA256(String filePath) throws IOException {
        return getFileHash(filePath, "SHA-256");
    }
    public static String getFileSHA256(File file) throws IOException {
        return getFileHash(file.getAbsolutePath(), "SHA-256");
    }

    /**
     * 计算字符串的MD5哈希值
     * @param input 输入字符串
     * @return MD5哈希值的十六进制字符串表示
     */
    public static String getStringMD5(String input) {
        return getStringHash(input, "MD5");
    }

    /**
     * 计算字符串的SHA-1哈希值
     * @param input 输入字符串
     * @return SHA-1哈希值的十六进制字符串表示
     */
    public static String getStringSHA1(String input) {
        return getStringHash(input, "SHA-1");
    }

    /**
     * 计算字符串的SHA-256哈希值
     * @param input 输入字符串
     * @return SHA-256哈希值的十六进制字符串表示
     */
    public static String getStringSHA256(String input) {
        return getStringHash(input, "SHA-256");
    }

    /**
     * 计算文件的哈希值（通用方法）
     * @param filePath 文件路径
     * @param algorithm 哈希算法（MD5、SHA-1、SHA-256）
     * @return 哈希值的十六进制字符串表示
     * @throws IOException 如果读取文件时发生错误
     */
    private static String getFileHash(String filePath, String algorithm) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath);
             FileChannel channel = fis.getChannel()) {

            MessageDigest digest = MessageDigest.getInstance(algorithm);
            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

            while (channel.read(buffer) != -1) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }

            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("不支持的哈希算法: " + algorithm, e);
        }
    }

    /**
     * 计算字符串的哈希值（通用方法）
     * @param input 输入字符串
     * @param algorithm 哈希算法（MD5、SHA-1、SHA-256）
     * @return 哈希值的十六进制字符串表示
     */
    private static String getStringHash(String input, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hash = digest.digest(input.getBytes());
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("不支持的哈希算法: " + algorithm, e);
        }
    }

    /**
     * 将字节数组转换为十六进制字符串
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}