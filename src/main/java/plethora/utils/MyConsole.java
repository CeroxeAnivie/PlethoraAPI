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
 * 新增特性：支持注册默认命令（name 为 null 或 "" 时）。
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
    // ✅ 新增：用于存储默认命令
    private CommandMeta defaultCommand = null;
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
     * 注册一个新命令。
     * - 如果 {@code name} 为 {@code null} 或空字符串 {@code ""}，则注册为默认命令。
     *   所有未匹配到其他命令的输入都将触发此默认命令。
     * - 如果命令已存在，新命令将覆盖旧命令。
     *
     * @param name        命令名称，{@code null} 或 {@code ""} 表示默认命令
     * @param description 命令描述
     * @param executor    命令执行逻辑
     */
    public void registerCommand(String name, String description, Consumer<List<String>> executor) {
        if (executor == null) {
            throw new IllegalArgumentException("命令执行器 (executor) 不能为空");
        }

        // ✅ 处理默认命令
        if (name == null || name.trim().isEmpty()) {
            this.defaultCommand = new CommandMeta("(default)", description, executor);
            return;
        }

        // 处理普通命令
        String normalizedName = name.toLowerCase().trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("命令名称不能为空");
        }
        commands.put(normalizedName, new CommandMeta(name.trim(), description, executor));
    }

    // --- 日志 API，包含 source 参数 ---

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
     */
    public void start() {
        Thread consoleThread = new Thread(() -> {
            try {
                log("Console", "控制台已启动。输入 'help' 查看可用命令，'exit' 退出。");
                while (true) {
                    String input = lineReader.readLine("> ");
                    if (input == null) break;

                    input = input.trim();
                    if (input.isEmpty()) continue;

                    appendToLogFile(LEVEL_INPUT, "User", input);

                    String[] tokens = input.split("\\s+");
                    String cmdName = tokens[0].toLowerCase();
                    List<String> args = Arrays.asList(tokens).subList(1, tokens.length);

                    // ✅ 修正：命令查找和默认命令执行逻辑
                    CommandMeta cmd = commands.get(cmdName);
                    if (cmd != null) {
                        // 执行已注册的命令
                        try {
                            cmd.executor.accept(args);
                        } catch (Exception e) {
                            error("Command", "命令执行出错", e);
                        }
                    } else if (this.defaultCommand != null) {
                        // 执行默认命令
                        try {
                            // 将整个输入行作为参数传递给默认命令
                            // 这样默认命令可以处理原始输入，例如用于脚本解释器
                            this.defaultCommand.executor.accept(Arrays.asList(tokens));
                        } catch (Exception e) {
                            error("DefaultCommand", "默认命令执行出错", e);
                        }
                    } else {
                        // 没有默认命令，输出未知命令提示
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

        consoleThread.setDaemon(false);
        consoleThread.start();
    }

    // --- 私有辅助方法 ---

    private void safePrintToConsole(String message) {
        lineReader.printAbove(message);
    }

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
        if (commands.isEmpty() && defaultCommand == null) {
            log("Console", "暂无可用命令。");
        } else {
            StringBuilder helpText = new StringBuilder("可用命令:\n");
            // 列出所有普通命令
            commands.values().stream()
                    .sorted(Comparator.comparing(cmd -> cmd.name))
                    .forEach(cmd ->
                            helpText.append(String.format("  %-15s %s%n", cmd.name, cmd.description))
                    );
            // 如果存在，默认命令也列出来
            if (defaultCommand != null) {
                helpText.append(String.format("  %-15s %s%n", "(default)", defaultCommand.description));
            }
            log("Console", helpText.toString().trim());
        }
    }

    private void appendToLogFile(String level, String source, String message) {
        fileWriteLock.lock();
        try {
            String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
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

    private record CommandMeta(String name, String description, Consumer<List<String>> executor) {}

    public File getLogFile() {
        return logFile.toFile();
    }
}