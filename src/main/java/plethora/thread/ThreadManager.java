package plethora.thread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 增强版 ThreadManager for Java 21+.
 * <p>
 * 核心特性：
 * - 使用虚拟线程（Java 21 正式特性）
 * - 支持同步阻塞等待所有任务完成
 * - 支持异步非阻塞启动任务
 * - 支持异步非阻塞回调，在所有任务完成后执行
 * - 收集所有任务抛出的异常
 * - 支持超时控制
 * - 实现 AutoCloseable，推荐使用 try-with-resources
 * - 【新增】提供一个静态方法，用于向共享的虚拟线程执行器提交单个任务
 * - 【修复】在异步回调中优雅地处理未捕获的 RuntimeException，避免 JVM 打印 "Uncaught exception" 警告。
 */
public final class ThreadManager implements AutoCloseable {

    // ===== 静态部分：用于全局任务分发 =====
    private static final ExecutorService SHARED_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down shared virtual thread executor...");
            SHARED_EXECUTOR.close();
        }));
    }

    // ===== 实例部分：用于管理任务组 =====
    private final List<Runnable> tasks;
    // ===== 静态部分结束 =====
    private final ExecutorService executor;

    public ThreadManager(Runnable... tasks) {
        this(Executors.newVirtualThreadPerTaskExecutor(), tasks);
    }

    public ThreadManager(List<Runnable> tasks) {
        this(Executors.newVirtualThreadPerTaskExecutor(), tasks);
    }

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

    public static void runAsync(Runnable task) {
        Objects.requireNonNull(task, "Task cannot be null");
        SHARED_EXECUTOR.execute(task);
    }

    public List<Throwable> start() {
        return startWithTimeout(null);
    }

    public List<Throwable> startWithTimeout(Duration timeout) {
        int n = tasks.size();
        CountDownLatch latch = new CountDownLatch(n);
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>(n));

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
                latch.await();
            } else {
                boolean completed = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!completed) {
                    exceptions.add(new TimeoutException("Tasks did not complete within " + timeout));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted while waiting for tasks", e);
        }

        return List.copyOf(exceptions);
    }

    public void startAsync() {
        for (Runnable task : tasks) {
            executor.execute(task);
        }
    }

    /**
     * 【修复】异步启动所有任务，并在所有任务完成后，执行一个回调。
     * 此方法是非阻塞的，会立即返回。
     *
     * @param callback 当所有任务都执行完毕后，会被一个虚拟线程调用的回调函数。
     * @throws NullPointerException 如果 callback 为 null。
     */
    public void startAsyncWithCallback(Consumer<TaskResult> callback) {
        Objects.requireNonNull(callback, "Callback cannot be null");

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = tasks.stream()
                .map(task -> CompletableFuture.runAsync(() -> {
                    try {
                        task.run();
                    } catch (Throwable t) {
                        // 【关键修复】捕获所有 Throwable，防止 JVM 打印 "Uncaught exception"
                        // CompletableFuture 会自动处理这个异常，使 future 异常完成。
                        // 我们重新抛出它，以便后续逻辑能正确捕获。
                        throw t;
                    }
                }, executor))
                .toArray(CompletableFuture[]::new);

        CompletableFuture<Void> allOfFuture = CompletableFuture.allOf(futures);

        allOfFuture.whenComplete((unused, ex) -> {
            List<Throwable> allExceptions = new ArrayList<>();
            for (CompletableFuture<?> future : futures) {
                if (future.isCompletedExceptionally()) {
                    allExceptions.add(future.exceptionNow());
                }
            }
            callback.accept(new TaskResult(Collections.unmodifiableList(allExceptions)));
        });
    }

    @Override
    public void close() {
        executor.close();
    }

    public record TaskResult(List<Throwable> exceptions) {
        public boolean hasErrors() {
            return !exceptions.isEmpty();
        }
    }
}