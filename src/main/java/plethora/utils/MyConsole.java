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
 * General interactive console with support for colored logs, file persistence, and command registration.
 * New feature: Support for registering default commands (when name is null or empty).
 */
public class MyConsole {

    // ANSI color codes
    private static final String ANSI_RESET = "\u001b[0m";
    private static final String ANSI_GREEN = "\u001b[32m";
    private static final String ANSI_YELLOW = "\u001b[33m";
    private static final String ANSI_RED = "\u001b[31m";

    // Log levels
    private static final String LEVEL_INFO = "INFO";
    private static final String LEVEL_WARN = "WARN";
    private static final String LEVEL_ERROR = "ERROR";
    private static final String LEVEL_INPUT = "INPUT"; // Only for log file

    private final Terminal terminal;
    private final LineReader lineReader;
    private final Map<String, CommandMeta> commands = new ConcurrentHashMap<>();
    // ✅ New: for storing default command
    private CommandMeta defaultCommand = null;
    private final boolean isAnsiSupported;

    private final Path logFile;
    private final ReentrantLock fileWriteLock = new ReentrantLock();

    // File name format
    private static final DateTimeFormatter LOG_FILE_NAME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                    .withZone(ZoneId.systemDefault());
    // Unified timestamp format
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    public boolean printWelcome=false;

    // New: running status flag
    private volatile boolean running = true;

    // New: shutdown hook for cleanup operations before exit
    private Runnable shutdownHook = null;

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

        registerCommand("help", "Show all available commands", args -> printHelp());
        // Modified exit command to call shutdown method instead of direct System.exit
        registerCommand("exit", "Exit console", args -> shutdown());
    }

    /**
     * Register a new command.
     * - If {@code name} is {@code null} or empty string {@code ""}, it's registered as a default command.
     *   All inputs that don't match other commands will trigger this default command.
     * - If the command already exists, the new command will overwrite the old one.
     *
     * @param name        Command name, {@code null} or {@code ""} for default command
     * @param description Command description
     * @param executor    Command execution logic
     */
    public void registerCommand(String name, String description, Consumer<List<String>> executor) {
        if (executor == null) {
            throw new IllegalArgumentException("Command executor cannot be null");
        }

        // ✅ Handle default command
        if (name == null || name.trim().isEmpty()) {
            this.defaultCommand = new CommandMeta("(default)", description, executor);
            return;
        }

        // Handle regular command
        String normalizedName = name.toLowerCase().trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("Command name cannot be empty");
        }
        commands.put(normalizedName, new CommandMeta(name.trim(), description, executor));
    }

    /**
     * Set shutdown hook to execute cleanup operations before program exit
     * @param hook Code to execute when shutting down
     */
    public void setShutdownHook(Runnable hook) {
        this.shutdownHook = hook;
    }

    /**
     * Gracefully shutdown the console
     */
    public void shutdown() {
        running = false;

        // Execute shutdown hook
        if (shutdownHook != null) {
            try {
                log("Console", "Executing shutdown hook...");
                shutdownHook.run();
            } catch (Exception e) {
                error("Console", "Error while executing shutdown hook", e);
            }
        }

        // Close terminal
        try {
            terminal.close();
        } catch (IOException ignored) {
        }

        // Exit JVM last
        Runtime.getRuntime().halt(0);
    }

    // --- Log API with source parameter ---

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
     * Start the console main loop (runs in background thread).
     */
    public void start() {
        Thread consoleThread = new Thread(() -> {
            try {
                if (printWelcome){
                    log("Console", "Console started. Type 'help' to see available commands, 'exit' to quit.");
                }

                // Modified main loop to check running flag
                while (running) {
                    String input = lineReader.readLine("> ");
                    if (input == null) break;

                    input = input.trim();
                    if (input.isEmpty()) continue;

                    appendToLogFile(LEVEL_INPUT, "User", input);

                    String[] tokens = input.split("\\s+");
                    String cmdName = tokens[0].toLowerCase();
                    List<String> args = Arrays.asList(tokens).subList(1, tokens.length);

                    // ✅ Corrected: Command lookup and default command execution logic
                    CommandMeta cmd = commands.get(cmdName);
                    if (cmd != null) {
                        // Execute registered command
                        try {
                            cmd.executor.accept(args);
                        } catch (Exception e) {
                            error("Command", "Error executing command", e);
                        }
                    } else if (this.defaultCommand != null) {
                        // Execute default command
                        try {
                            // Pass the entire input line as arguments to the default command
                            // This allows the default command to handle raw input, e.g., for script interpreters
                            this.defaultCommand.executor.accept(Arrays.asList(tokens));
                        } catch (Exception e) {
                            error("DefaultCommand", "Error executing default command", e);
                        }
                    } else {
                        // No default command, output unknown command prompt
                        log("Console", "Unknown command: '" + cmdName + "'. Type 'help' to see available commands.");
                    }
                }
            } catch (Exception e) {
                error("System", "Unexpected exception in console", e);
            } finally {
                // Ensure resources are released
                try {
                    terminal.close();
                } catch (IOException ignored) {
                }
            }
        }, "Console-Thread");

        consoleThread.setDaemon(false);
        consoleThread.start();
    }

    // --- Private helper methods ---

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
            log("Console", "No commands available.");
        } else {
            StringBuilder helpText = new StringBuilder("Available commands:\n");
            // List all regular commands
            commands.values().stream()
                    .sorted(Comparator.comparing(cmd -> cmd.name))
                    .forEach(cmd ->
                            helpText.append(String.format("  %-15s %s%n", cmd.name, cmd.description))
                    );
            // If exists, list default command as well
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