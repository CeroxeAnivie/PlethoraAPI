package plethora.utils;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 通用交互式控制台，支持彩色日志、文件持久化和命令注册。
 */
public class MyConsole {

    // ANSI 颜色代码
    private static final String ANSI_RESET = "\u001b[0m";
    private static final String ANSI_GREEN = "\u001b[32m";
    private static final String ANSI_YELLOW = "\u001b[33m";
    private static final String ANSI_RED = "\u001b[31m";

    // 日志级别
    private static final String LEVEL_INFO = "INFO";
    private static final String LEVEL_WARN = "WARN";
    private static final String LEVEL_ERROR = "ERROR";
    private static final String LEVEL_INPUT = "INPUT"; // 仅用于日志文件

    private final Terminal terminal;
    private final LineReader lineReader;
    private final Map<String, CommandMeta> commands = new ConcurrentHashMap<>();
    private final boolean isAnsiSupported;

    private final Path logFile;
    private final ReentrantLock fileWriteLock = new ReentrantLock();

    // 文件名格式
    private static final DateTimeFormatter LOG_FILE_NAME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                    .withZone(ZoneId.systemDefault());
    // 统一的时间戳格式
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    public MyConsole(String appName) throws IOException {
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .jna(true)
                .build();

        this.isAnsiSupported = !Terminal.TYPE_DUMB.equals(terminal.getType());

        Path logDirectory = Path.of("logs");
        Files.createDirectories(logDirectory);
        String fileName = "console-" + LOG_FILE_NAME_FORMATTER.format(Instant.now()) + ".log";
        this.logFile = logDirectory.resolve(fileName);

        this.lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName(appName != null ? appName : "Console")
                .build();

        registerCommand("help", "显示所有可用命令", args -> printHelp());
        registerCommand("exit", "退出控制台", args -> System.exit(0));
        registerCommand("quit", "退出控制台", args -> System.exit(0));
    }

    /**
     * 注册一个新命令。如果命令已存在，新命令将覆盖旧命令。
     */
    public void registerCommand(String name, String description, Consumer<List<String>> executor) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("命令名称不能为空");
        }
        commands.put(name.toLowerCase().trim(), new CommandMeta(name.trim(), description, executor));
    }

    // --- 新的日志 API，包含 source 参数 ---

    public void log(String source, String message) {
        String consoleMsg = formatConsoleMessage(LEVEL_INFO, ANSI_GREEN, source, message);
        safePrintToConsole(consoleMsg);
        appendToLogFile(LEVEL_INFO, source, message);
    }

    public void warn(String source, String message) {
        String consoleMsg = formatConsoleMessage(LEVEL_WARN, ANSI_YELLOW, source, message);
        safePrintToConsole(consoleMsg);
        appendToLogFile(LEVEL_WARN, source, message);
    }

    public void error(String source, String message) {
        String consoleMsg = formatConsoleMessage(LEVEL_ERROR, ANSI_RED, source, message);
        safePrintToConsole(consoleMsg);
        appendToLogFile(LEVEL_ERROR, source, message);
    }

    public void error(String source, String message, Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        printWriter.println(message);
        throwable.printStackTrace(printWriter);
        printWriter.flush();

        String fullMessage = stringWriter.toString();
        String briefMessage = message + ": " + throwable.getMessage();
        String consoleMsg = formatConsoleMessage(LEVEL_ERROR, ANSI_RED, source, briefMessage);
        safePrintToConsole(consoleMsg);
        appendToLogFile(LEVEL_ERROR, source, fullMessage);
    }

    /**
     * 启动控制台主循环（在后台线程中运行）。
     * 调用此方法后，主线程可以继续执行其他任务，例如从其他线程调用 log/warn/error。
     */
    public void start() {
        // 启动控制台的欢迎消息
//        log("Console", "控制台已启动。输入 'help' 查看可用命令，'exit' 退出。");

        // 在一个新的后台线程中运行控制台循环
        Thread consoleThread = new Thread(() -> {
            try {
                while (true) {
                    String input = lineReader.readLine("> ");
                    if (input == null) break;

                    input = input.trim();
                    if (input.isEmpty()) continue;

                    appendToLogFile(LEVEL_INPUT, "User", input);

                    String[] tokens = input.split("\\s+");
                    String cmdName = tokens[0].toLowerCase();
                    List<String> args = Arrays.asList(tokens).subList(1, tokens.length);

                    CommandMeta cmd = commands.get(cmdName);
                    if (cmd != null) {
                        try {
                            cmd.executor.accept(args);
                        } catch (Exception e) {
                            error("Command", "命令执行出错", e);
                        }
                    } else {
                        log("Console", "未知命令: '" + cmdName + "'. 输入 'help' 查看可用命令。");
                    }
                }
            } catch (Exception e) {
                error("System", "控制台发生未预期异常", e);
            } finally {
                try {
                    terminal.close();
                } catch (IOException ignored) {
                }
            }
        }, "Console-Thread");

        consoleThread.setDaemon(false); // 控制台线程是非守护线程，保证程序不会意外退出
        consoleThread.start();
    }

    // --- 私有辅助方法 ---

    private void safePrintToConsole(String message) {
        lineReader.printAbove(message);
    }

    /**
     * 格式化控制台显示的消息。
     * 格式: [时间 LEVEL] [Source]: 消息
     */
    private String formatConsoleMessage(String level, String ansiColor, String source, String message) {
        String timestamp = TIMESTAMP_FORMATTER.format(LocalDateTime.now());
        if (isAnsiSupported) {
            return String.format("[%s %s%s%s] [%s]: %s",
                    timestamp,
                    ansiColor,
                    level,
                    ANSI_RESET,
                    source,
                    message);
        } else {
            return String.format("[%s %s] [%s]: %s", timestamp, level, source, message);
        }
    }

    private void printHelp() {
        if (commands.isEmpty()) {
            log("Console", "暂无可用命令。");
        } else {
            StringBuilder helpText = new StringBuilder("可用命令:\n");
            commands.values().stream()
                    .sorted(Comparator.comparing(cmd -> cmd.name))
                    .forEach(cmd ->
                            helpText.append(String.format("  %-15s %s%n", cmd.name, cmd.description))
                    );
            log("Console", helpText.toString().trim());
        }
    }

    /**
     * 将消息以统一格式追加到日志文件。
     * 格式: [时间 LEVEL] [Source] 消息
     */
    private void appendToLogFile(String level, String source, String message) {
        fileWriteLock.lock();
        try {
            String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
            // ✅ 统一的日志文件格式，包含来源
            String logEntry = String.format("[%s %s] [%s] %s%n", timestamp, level, source, message);
            Files.writeString(logFile, logEntry, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        } finally {
            fileWriteLock.unlock();
        }
    }

    // 为保持向后兼容性（如果需要），可以添加无 source 的重载方法
    // 但根据要求，我们主推带 source 的 API
    /*
    public void log(String message) { log("App", message); }
    public void warn(String message) { warn("App", message); }
    public void error(String message) { error("App", message); }
    public void error(String message, Throwable t) { error("App", message, t); }
    */

    private record CommandMeta(String name, String description, Consumer<List<String>> executor) {
    }

    public File getLogFile() {
        return new File(logFile.toUri());
    }
}