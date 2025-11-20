package plethora.print.log;

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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class Loggist implements AutoCloseable {

    // 使用Java 21的虚拟线程
    private static final ThreadFactory VIRTUAL_THREAD_FACTORY = Thread.ofVirtual().factory();
    // 缓存格式化器
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy.MM.dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    public static int WINDOWS_VERSION = -1;
    // 高性能异步日志队列
    private final BlockingQueue<LogEvent> logQueue = new ArrayBlockingQueue<>(10000);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(VIRTUAL_THREAD_FACTORY);
    // 使用原子布尔代替volatile
    private final AtomicBoolean isOpen = new AtomicBoolean(false);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final Path logFilePath;
    // 预分配缓冲区
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(8192);
    // 锁只用于文件操作
    private final ReentrantLock fileLock = new ReentrantLock();
    // 文件通道
    private FileChannel fileChannel;

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

    /**
     * 启动异步写入线程。
     * 修复：使用 take() 方法进行阻塞等待，避免CPU忙等待。
     */
    private void startAsyncWriter() {
        executor.submit(() -> {
            try {
                // 主循环：在关闭信号前持续等待并处理新日志
                while (!isShutdown.get()) {
                    // take() 会阻塞，直到队列中有元素可用，避免了CPU空转
                    LogEvent event = logQueue.take();
                    writeToFile(event);
                }
            } catch (InterruptedException e) {
                // 线程被中断，通常是在关闭期间发生，是正常退出流程
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("Error in async log writer: " + e.getMessage());
            }

            // 关闭流程：处理队列中剩余的所有日志
            // 确保在程序退出前，所有已入队的日志都被写入
            while (!logQueue.isEmpty()) {
                LogEvent event = logQueue.poll();
                if (event != null) {
                    try {
                        writeToFile(event);
                    } catch (Exception e) {
                        System.err.println("Error writing remaining log on shutdown: " + e.getMessage());
                    }
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

    /**
     * 关闭日志记录器。
     * 修复：增强了关闭逻辑，确保异步线程能安全退出，所有日志都被写入。
     */
    @Override
    public void close() {
        if (isShutdown.getAndSet(true)) {
            return; // 已经关闭
        }

        // 1. 中断正在阻塞的写入线程，使其从take()方法中唤醒
        executor.shutdownNow();

        // 2. 等待异步写入线程完成其工作（处理剩余日志）
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("Log writer did not terminate in 5 seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting for log writer to terminate.");
        }

        // 3. 关闭文件通道，刷新缓冲区
        closeWriteChannel();
    }

    // 使用Java 21的record作为不可变日志事件
    private record LogEvent(Instant timestamp, LogType type, String subject, String content) {
    }
}