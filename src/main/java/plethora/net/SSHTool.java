package plethora.net;

import com.jcraft.jsch.*;

import java.io.*;

/**
 * SSH工具类，用于远程服务器的连接、命令执行、文件上传和下载
 */
public class SSHTool {

    private String host;
    private int port;
    private String username;
    private String password;
    private Session session;
    private ChannelSftp sftpChannel;

    /**
     * 构造函数，初始化SSH连接参数
     *
     * @param host     服务器主机名
     * @param port     SSH端口
     * @param username SSH用户名
     * @param password SSH密码
     */
    public SSHTool(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public static void main(String[] args) {
        // 示例用法
        SSHTool sshTool = new SSHTool("your_host", 22, "your_username", "your_password");
        try {
            sshTool.connect();

            // 上传文件
            sshTool.uploadFile("local_file.txt", "remote_file.txt");

            // 下载文件
            sshTool.downloadFile("remote_file.txt", "downloaded_file.txt");

            // 删除文件或文件夹
            sshTool.delete("remote_file.txt");
            sshTool.delete("remote_folder");

            // 执行远程命令
            String commandResult = sshTool.executeCommand("ls -l");
            System.out.println("Command Result:\n" + commandResult);

        } catch (JSchException | SftpException | IOException e) {
            e.printStackTrace();
        } finally {
            sshTool.disconnect();
        }
    }

    /**
     * 连接到SSH服务器，同时初始化SFTP通道
     *
     * @throws JSchException 连接异常
     */
    public void connect() throws JSchException {
        JSch jsch = new JSch();
        session = jsch.getSession(username, host, port);
        session.setPassword(password);

        // 配置StrictHostKeyChecking为no，避免第一次连接时的询问
        java.util.Properties config = new java.util.Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);

        session.connect();

        // 初始化SFTP通道
        Channel channel = session.openChannel("sftp");
        channel.connect();
        sftpChannel = (ChannelSftp) channel;
    }

    /**
     * 上传本地文件到远程服务器
     *
     * @param localFilePath  本地文件路径
     * @param remoteFilePath 远程服务器文件路径
     * @throws JSchException         SFTP通道连接异常
     * @throws SftpException         SFTP操作异常
     * @throws FileNotFoundException 文件未找到异常
     */
    public void uploadFile(String localFilePath, String remoteFilePath)
            throws JSchException, SftpException, FileNotFoundException {
        if (sftpChannel == null || !sftpChannel.isConnected()) {
            throw new JSchException("SFTP channel is not connected.");
        }

        // 打开本地文件流
        InputStream localFileStream = new FileInputStream(localFilePath);

        // 上传文件
        sftpChannel.put(localFileStream, remoteFilePath);

        // 关闭本地文件流
        try {
            localFileStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 下载远程服务器文件到本地
     *
     * @param remoteFilePath 远程服务器文件路径
     * @param localFilePath  本地文件路径
     * @throws JSchException SFTP通道连接异常
     * @throws SftpException SFTP操作异常
     * @throws IOException   IO异常
     */
    public void downloadFile(String remoteFilePath, String localFilePath)
            throws JSchException, SftpException, IOException {
        if (sftpChannel == null || !sftpChannel.isConnected()) {
            throw new JSchException("SFTP channel is not connected.");
        }

        // 打开远程文件流
        InputStream remoteFileStream = sftpChannel.get(remoteFilePath);

        // 打开本地文件流
        OutputStream localFileStream = new FileOutputStream(localFilePath);

        // 从远程读取文件内容并写入本地文件
        byte[] buffer = new byte[1024];
        int len;
        while ((len = remoteFileStream.read(buffer)) != -1) {
            localFileStream.write(buffer, 0, len);
        }

        // 关闭文件流
        try {
            remoteFileStream.close();
            localFileStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 删除远程服务器上的文件或文件夹
     *
     * @param remotePath 远程服务器路径，可以是文件或文件夹
     * @throws JSchException SFTP通道连接异常
     * @throws SftpException SFTP操作异常
     */
    public void delete(String remotePath) throws JSchException, SftpException {
        if (sftpChannel == null || !sftpChannel.isConnected()) {
            throw new JSchException("SFTP channel is not connected.");
        }

        // 判断是文件还是文件夹
        SftpATTRS attrs = sftpChannel.lstat(remotePath);
        if (attrs.isDir()) {
            // 删除文件夹
            sftpChannel.rmdir(remotePath);
        } else {
            // 删除文件
            sftpChannel.rm(remotePath);
        }
    }

    /**
     * 执行远程命令并返回结果
     *
     * @param command 远程命令
     * @return 命令执行结果
     * @throws JSchException SSH连接异常
     * @throws IOException   IO异常
     */
    public String executeCommand(String command) throws JSchException, IOException {
        if (session == null || !session.isConnected()) {
            throw new JSchException("SSH session is not connected.");
        }

        // 打开命令执行通道
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);

        // 获取命令执行结果流
        InputStream commandOutput = channel.getInputStream();

        // 执行命令
        channel.connect();

        // 读取命令执行结果
        StringBuilder result = new StringBuilder();
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = commandOutput.read(buffer)) > 0) {
            result.append(new String(buffer, 0, bytesRead));
        }

        // 关闭通道
        channel.disconnect();

        return result.toString();
    }

    /**
     * 关闭SSH连接和SFTP通道
     */
    public void disconnect() {
        if (sftpChannel != null && sftpChannel.isConnected()) {
            sftpChannel.disconnect();
        }
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }
}
