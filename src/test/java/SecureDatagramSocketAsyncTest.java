import plethora.net.SecureDatagramSocket;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SecureDatagramSocket 异步（多线程）测试类
 * 演示了服务器和客户端在独立线程中进行加密通信。
 * 服务器先调用 receive 开始，客户端先调用 send 开始。
 * 握手过程由 SecureDatagramSocket 内部自动完成。
 */
public class SecureDatagramSocketAsyncTest {

    private static final String SERVER_HOST = "127.0.0.1";
    private static final int SERVER_PORT = 9876;

    public static void main(String[] args) {
        System.out.println("=== SecureDatagramSocket 异步测试 ===");

        // 创建一个固定大小的线程池
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 启动服务器线程
        executor.submit(() -> {
            try {
                System.out.println("服务器线程启动...");
                runServer();
            } catch (Exception e) {
                System.err.println("服务器线程发生错误: " + e.getMessage());
                e.printStackTrace();
            }
        });

        // 短暂延迟，确保服务器线程已启动并准备好接收
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 启动客户端线程
        executor.submit(() -> {
            try {
                System.out.println("客户端线程启动...");
                runClient();
            } catch (Exception e) {
                System.err.println("客户端线程发生错误: " + e.getMessage());
                e.printStackTrace();
            }
        });

        // 关闭线程池，等待所有任务完成
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                System.err.println("线程池未在规定时间内终止，强制关闭。");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            System.err.println("等待线程池终止时被中断。");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("=== 测试结束 ===");
    }

    /**
     * 服务器端逻辑
     * 在独立线程中运行。
     * 1. 创建 SecureDatagramSocket (绑定到 SERVER_PORT)。
     * 2. 循环调用 receiveStr()。第一次调用会触发自动握手 (作为服务器)。
     * 3. 打印收到的消息。
     * 4. 如果收到停止信号，则退出循环。
     */
    private static void runServer() {
        SecureDatagramSocket serverSocket = null;
        try {
            // 1. 创建服务器端 SecureDatagramSocket，绑定到指定端口
            serverSocket = new SecureDatagramSocket(SERVER_PORT);
            System.out.println("服务器已启动，绑定到端口 " + SERVER_PORT + "，等待客户端连接...");

            // 2. 循环接收消息
            while (true) {
                // receiveStr() 会自动触发服务器端握手流程（等待 -> 发送 -> 完成）
                byte[] receivedMessage = serverSocket.receiveByte();
                if (receivedMessage == null) {
                    System.out.println("服务器收到停止信号，退出循环。");
                    break;
                }
                System.out.println("服务器收到消息: " + Arrays.toString(receivedMessage));
                // 可选：发送回执或响应
                // serverSocket.sendStr("回执: " + receivedMessage, serverSocket.getLastReceivedFromAddress(), serverSocket.getLastReceivedFromPort());
            }

        } catch (IOException e) {
            System.err.println("服务器运行时发生 IO 错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (serverSocket != null) {
                serverSocket.close();
                System.out.println("服务器套接字已关闭。");
            }
        }
    }


    /**
     * 客户端逻辑
     * 在独立线程中运行。
     * 1. 创建 SecureDatagramSocket。
     * 2. 循环调用 sendStr() 向服务器发送消息。第一次调用会触发自动握手 (作为客户端)。
     * 3. 发送几条消息后，发送停止信号并退出。
     */
    private static void runClient() {
        SecureDatagramSocket clientSocket = null;
        try {
            // 1. 创建客户端 SecureDatagramSocket
            clientSocket = new SecureDatagramSocket();
            System.out.println("客户端已启动，准备连接服务器 " + SERVER_HOST + ":" + SERVER_PORT + " ...");

            // 2. 发送几条消息
            for (int i = 1; i <= 5; i++) {
                byte[] message = new byte[]{(byte) i};
                // sendStr() 会自动触发客户端握手流程（发送 -> 接收 -> 完成）
                clientSocket.sendByte(message, InetAddress.getByName(SERVER_HOST), SERVER_PORT);
                System.out.println("客户端发送消息: " + Arrays.toString(message));
                Thread.sleep(1000); // 每秒发一条
            }

            // 3. 发送停止信号
            System.out.println("客户端发送停止信号。");
            clientSocket.sendByte(null, InetAddress.getByName(SERVER_HOST), SERVER_PORT); // 发送 null 代表停止

        } catch (IOException | InterruptedException e) {
            System.err.println("客户端运行时发生错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (clientSocket != null) {
                clientSocket.close();
                System.out.println("客户端套接字已关闭。");
            }
        }
    }
}