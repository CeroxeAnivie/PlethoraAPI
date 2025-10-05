package plethora.thread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * ThreadManager for Java 21 (正式版，无预览 API).
 * <p>
 * - 使用虚拟线程（Java 21 正式特性）
 * - 所有任务独立运行，互不影响
 * - 等待所有任务完成（无论成功或失败）
 * - 收集所有异常
 * - 支持超时
 */
public final class ThreadManager implements AutoCloseable {

    private final List<Runnable> tasks;
    private final ExecutorService executor;

    // ===== 构造方法 1：可变参数 =====
    public ThreadManager(Runnable... tasks) {
        this(Executors.newVirtualThreadPerTaskExecutor(), tasks);
    }

    // ===== 构造方法 2：List<Runnable> =====
    public ThreadManager(List<Runnable> tasks) {
        this(Executors.newVirtualThreadPerTaskExecutor(), tasks);
    }

    // ===== 内部构造：允许自定义 Executor（用于测试或特殊场景）=====
    private ThreadManager(ExecutorService executor, Runnable... tasks) {
        this(executor, List.of(tasks));
    }

    private ThreadManager(ExecutorService executor, List<Runnable> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("Tasks cannot be null or empty");
        }
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i) == null) {
                throw new IllegalArgumentException("Task at index " + i + " is null");
            }
        }
        this.tasks = List.copyOf(tasks);
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * 执行所有任务，无限等待直到全部完成。
     * @return 所有任务中抛出的异常列表。
     */
    public List<Throwable> start() {
        return startWithTimeout(null);
    }

    /**
     * 执行所有任务，支持超时。
     * @param timeout 超时时间，null 表示无限等待。
     * @return 所有任务中抛出的异常列表。
     */
    public List<Throwable> startWithTimeout(Duration timeout) {
        int n = tasks.size();
        CountDownLatch latch = new CountDownLatch(n);
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>(n));

        // 提交所有任务
        for (Runnable task : tasks) {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            if (timeout == null) {
                latch.await(); // 无限等待
            } else {
                boolean completed = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!completed) {
                    // 超时：收集当前已发生的异常，并添加 TimeoutException
                    exceptions.add(new TimeoutException("Tasks did not complete within " + timeout));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted while waiting for tasks", e);
        }

        return List.copyOf(exceptions);
    }

    /**
     * 关闭内部的虚拟线程执行器（推荐使用 try-with-resources）
     */
    @Override
    public void close() {
        executor.close(); // Java 19+ ExecutorService 实现了 AutoCloseable
    }
}