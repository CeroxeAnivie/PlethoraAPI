package plethora.utils;

import java.io.Console;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ConsoleApp {
    private volatile boolean running = true;
    private final BlockingQueue<String> inputQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> outputQueue = new LinkedBlockingQueue<>();
    private final List<CommandListener> commandListeners = new ArrayList<>();
    private Console console;

    public static void main(String[] args) {
        ConsoleApp app = new ConsoleApp();
        app.start();
    }

    public void start() {
        console = System.console();
        if (console == null) {
            System.err.println("无法获取控制台，请在命令行环境中运行此程序");
            return;
        }

        // 启动输出处理线程
        Thread outputThread = new Thread(this::processOutput);
        outputThread.setName("Output-Thread");
        outputThread.start();

        // 注册示例命令
        registerCommands();

        // 显示欢迎信息
        addOutput("控制台已启动。输入 'help' 查看可用命令。");

        // 主循环 - 处理命令
        processInput();

        // 等待线程结束
        try {
            outputThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processInput() {
        while (running) {
            try {
                // 直接读取一行输入
                String line = console.readLine();
                if (line != null) {
                    inputQueue.put(line);

                    // 检查退出命令
                    if ("exit".equalsIgnoreCase(line)) {
                        running = false;
                        addOutput("正在退出...");
                        break;
                    }

                    // 处理命令
                    processCommand(line);
                }
            } catch (Exception e) {
                addOutput("输入错误: " + e.getMessage());
            }
        }
    }

    private void processCommand(String command) {
        if (command.trim().isEmpty()) {
            return;
        }

        // 通知所有命令监听器
        for (CommandListener listener : commandListeners) {
            if (listener.onCommand(command)) {
                break;
            }
        }
    }

    private void processOutput() {
        while (running) {
            try {
                String output = outputQueue.poll(100, TimeUnit.MILLISECONDS);
                if (output != null) {
                    // 清除当前行并输出消息
                    console.printf("\r%s\n", output);
                    // 重新显示提示符
                    console.printf("> ");
                    console.flush();
                }

                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void addOutput(String message) {
        outputQueue.offer(message);
    }

    public void registerCommandListener(CommandListener listener) {
        commandListeners.add(listener);
    }

    private void registerCommands() {
        // 帮助命令
        registerCommandListener(command -> {
            if (command.equals("help")) {
                addOutput("可用命令:");
                addOutput("  help - 显示帮助信息");
                addOutput("  time - 显示当前时间");
                addOutput("  echo [text] - 回显文本");
                addOutput("  clear - 清空屏幕");
                addOutput("  exit - 退出程序");
                return true;
            }
            return false;
        });

        // 时间命令
        registerCommandListener(command -> {
            if (command.equals("time")) {
                addOutput("当前时间: " + new Date());
                return true;
            }
            return false;
        });

        // 回显命令
        registerCommandListener(command -> {
            if (command.startsWith("echo ")) {
                String text = command.substring(5);
                addOutput("回显: " + text);
                return true;
            }
            return false;
        });

        // 清屏命令
        registerCommandListener(command -> {
            if (command.equals("clear")) {
                // 简单的清屏方法 - 输出多个空行
                for (int i = 0; i < 50; i++) {
                    console.printf("\n");
                }
                console.flush();
                addOutput("控制台已清空");
                return true;
            }
            return false;
        });

        // 未知命令处理
        registerCommandListener(command -> {
            addOutput("未知命令: " + command + "。输入 'help' 查看帮助。");
            return true;
        });
    }

    // 命令监听器接口
    @FunctionalInterface
    public interface CommandListener {
        boolean onCommand(String command);
    }
}