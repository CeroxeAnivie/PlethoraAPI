package plethora.print.log;

import plethora.os.detect.OSDetector;
import plethora.print.Printer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class Loggist implements AutoCloseable {

    // 使用Java 21的虚拟线程
    private static final ThreadFactory VIRTUAL_THREAD_FACTORY = Thread.ofVirtual().factory();

    // 高性能异步日志队列
    private final BlockingQueue<LogEvent> logQueue = new ArrayBlockingQueue<>(10000);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(VIRTUAL_THREAD_FACTORY);

    // 使用Java 21的record作为不可变日志事件
    private record LogEvent(Instant timestamp, LogType type, String subject, String content) {}

    // 使用原子布尔代替volatile
    private final AtomicBoolean isOpen = new AtomicBoolean(false);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // 文件通道
    private FileChannel fileChannel;
    private final Path logFilePath;

    // 缓存格式化器
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy.MM.dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    // 预分配缓冲区
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(8192);

    // 锁只用于文件操作
    private final ReentrantLock fileLock = new ReentrantLock();

    public static int WINDOWS_VERSION = -1;

    public Loggist(String logFilePath) {
        this.logFilePath = Paths.get(Objects.requireNonNull(logFilePath));
        initializeFile();
        startAsyncWriter();
    }

    public Loggist(java.io.File logFile) {
        this(Objects.requireNonNull(logFile).getPath());
    }

    private void initializeFile() {
        try {
            if (!Files.exists(logFilePath)) {
                Files.createDirectories(logFilePath.getParent());
                Files.createFile(logFilePath);
            }

            fileChannel = FileChannel.open(
                    logFilePath,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );

            isOpen.set(true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize log file", e);
        }
    }

    private void startAsyncWriter() {
        executor.submit(() -> {
            while (!isShutdown.get() || !logQueue.isEmpty()) {
                try {
                    LogEvent event = logQueue.poll();
                    if (event != null) {
                        writeToFile(event);
                    } else {
                        Thread.yield(); // 让出CPU
                    }
                } catch (Exception e) {
                    // 错误处理
                }
            }
        });
    }

    private void writeToFile(LogEvent event) {
        if (!isOpen.get()) return;

        String message = formatLogMessage(event);
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);

        fileLock.lock();
        try {
            if (buffer.remaining() < bytes.length) {
                buffer.flip();
                fileChannel.write(buffer);
                buffer.clear();
            }
            buffer.put(bytes);
        } catch (IOException e) {
            isOpen.set(false);
            throw new RuntimeException("Failed to write log", e);
        } finally {
            fileLock.unlock();
        }
    }

    private String formatLogMessage(LogEvent event) {
        String time = FORMATTER.format(event.timestamp());
        return String.format("[%s]  [%s] [%s] %s%n",
                time,
                event.type().getDisplayName(),
                event.subject(),
                event.content());
    }

    public void say(State state) {
        Objects.requireNonNull(state);

        LogEvent event = new LogEvent(
                Instant.now(),
                state.type(),
                state.subject(),
                state.content()
        );

        // 异步写入队列
        if (!logQueue.offer(event)) {
            // 队列满时的处理策略
            System.err.println("Log queue is full, dropping log message");
        }

        // 控制台输出
        if (WINDOWS_VERSION == -1 || WINDOWS_VERSION >= 22000) {
            System.out.println(getLogString(state));
        } else {
            System.out.println(getNoColString(state));
        }
    }

    public void sayNoNewLine(State state) {
        Objects.requireNonNull(state);

        LogEvent event = new LogEvent(
                Instant.now(),
                state.type(),
                state.subject(),
                state.content()
        );

        if (!logQueue.offer(event)) {
            System.err.println("Log queue is full, dropping log message");
        }

        if (WINDOWS_VERSION == -1 || WINDOWS_VERSION >= 22000) {
            System.out.print(getLogString(state));
        } else {
            System.out.print(getNoColString(state));
        }
    }

    // 保留原始方法
    public String getLogString(State state) {
        String time = FORMATTER.format(Instant.now());
        StringBuilder result = new StringBuilder(Printer.getFormatLogString("[", Printer.color.PURPLE, Printer.style.NONE));
        result.append(Printer.getFormatLogString(time, Printer.color.YELLOW, Printer.style.NONE));
        result.append(Printer.getFormatLogString("]", Printer.color.PURPLE, Printer.style.NONE));
        result.append("  ");

        result.append(Printer.getFormatLogString("[", Printer.color.PURPLE, Printer.style.NONE));
        if (state.type() == LogType.ERROR) {
            result.append(Printer.getFormatLogString("ERROR", Printer.color.RED, Printer.style.NONE));
        } else if (state.type() == LogType.INFO) {
            result.append(Printer.getFormatLogString("INFO", Printer.color.GREEN, Printer.style.NONE));
        } else {
            result.append(Printer.getFormatLogString("WARNING", Printer.color.YELLOW, Printer.style.NONE));
        }
        result.append(Printer.getFormatLogString("]", Printer.color.PURPLE, Printer.style.NONE));

        result.append(" ");
        result.append(Printer.getFormatLogString("[", Printer.color.PURPLE, Printer.style.NONE));
        result.append(Printer.getFormatLogString(state.subject(), Printer.color.ORANGE, Printer.style.NONE));
        result.append(Printer.getFormatLogString("]", Printer.color.PURPLE, Printer.style.NONE));

        result.append(" ");
        result.append(state.content());
        return result.toString();
    }

    public String getNoColString(State state) {
        String time = FORMATTER.format(Instant.now());
        StringBuilder result = new StringBuilder("[");
        result.append(time);
        result.append("]");
        result.append("  ");

        result.append("[");
        if (state.type() == LogType.ERROR) {
            result.append("ERROR");
        } else if (state.type() == LogType.INFO) {
            result.append("INFO");
        } else {
            result.append("WARNING");
        }
        result.append("]");

        result.append(" ");
        result.append("[");
        result.append(state.subject());
        result.append("]");

        result.append(" ");
        result.append(state.content());
        return result.toString();
    }

    public void disableColor() {
        WINDOWS_VERSION = 1000;
    }

    public Path getLogFilePath() {
        return logFilePath;
    }

    public java.io.File getLogFile() {
        return logFilePath.toFile();
    }

    public void openWriteChannel() {
        if (!isOpen.get()) {
            initializeFile();
        }
    }

    public void closeWriteChannel() {
        fileLock.lock();
        try {
            if (buffer.position() > 0) {
                buffer.flip();
                fileChannel.write(buffer);
                buffer.clear();
            }
            if (fileChannel != null) {
                fileChannel.close();
            }
            isOpen.set(false);
        } catch (IOException e) {
            throw new RuntimeException("Failed to close log file", e);
        } finally {
            fileLock.unlock();
        }
    }

    public void write(String str, boolean isNewLine) {
        if (!isOpen.get()) return;

        String message = isNewLine ? str + System.lineSeparator() : str;
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);

        fileLock.lock();
        try {
            if (buffer.remaining() < bytes.length) {
                buffer.flip();
                fileChannel.write(buffer);
                buffer.clear();
            }
            buffer.put(bytes);
        } catch (IOException e) {
            isOpen.set(false);
            throw new RuntimeException("Failed to write log", e);
        } finally {
            fileLock.unlock();
        }
    }

    public boolean isOpenChannel() {
        return isOpen.get();
    }

    private void gc() {
        closeWriteChannel();
        System.gc();
    }

    @Override
    public void close() {
        isShutdown.set(true);
        executor.shutdown();
        closeWriteChannel();
    }
}