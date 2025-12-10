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
 * 集成了 Virtual Threads 用于高并发执行，以及 ScheduledExecutor 用于周期性任务。
 */
public final class ThreadManager implements AutoCloseable {

    // 1. 全局虚拟线程池：用于执行高并发、IO密集型任务 (如处理 TCP 连接、转发流量)
    private static final ExecutorService SHARED_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    // 2. 全局调度器：用于处理定时任务 (如心跳包、超时检测)
    // 使用核心数作为池大小，防止个别定时任务耗时稍长导致后续任务排队延迟
    // 注意：心跳任务虽然由这里触发，但建议心跳内部若有重IO，再转交虚拟线程，或者依赖 Http Client 自身的异步特性
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "Plethora-Scheduler");
                t.setDaemon(true); // 设置为守护线程，防止阻碍 JVM 关闭
                return t;
            }
    );

    static {
        // JVM 关闭时的钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // 优雅关闭虚拟线程池
            try {
                SHARED_EXECUTOR.close();
            } catch (Exception ignored) {
            }

            // 优雅关闭调度器
            SCHEDULER.shutdownNow();
        }));
    }

    private final List<Runnable> tasks;
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
        Objects.requireNonNull(executor, "Executor cannot be null");
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("Tasks cannot be null or empty");
        }
        this.tasks = List.copyOf(tasks);
        this.executor = executor;
    }

    /**
     * 获取全局调度器实例
     * 供 HostClient 发送心跳包使用
     */
    public static ScheduledExecutorService getScheduledExecutor() {
        return SCHEDULER;
    }

    /**
     * 异步运行任务 (使用虚拟线程)
     */
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
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

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
        tasks.forEach(executor::execute);
    }

    public void startAsyncWithCallback(Consumer<TaskResult> callback) {
        Objects.requireNonNull(callback, "Callback cannot be null");

        // 🔥【优化】弃用 Stream API，改用传统数组/循环，减少高并发下的对象创建开销
        int size = tasks.size();
        CompletableFuture<?>[] futures = new CompletableFuture[size];

        for (int i = 0; i < size; i++) {
            futures[i] = CompletableFuture.runAsync(tasks.get(i), executor);
        }

        CompletableFuture.allOf(futures)
                .whenComplete((unused, ex) -> {
                    List<Throwable> allExceptions = new ArrayList<>();
                    for (CompletableFuture<?> future : futures) {
                        if (future.isCompletedExceptionally()) {
                            try {
                                future.join();
                            } catch (CompletionException ce) {
                                allExceptions.add(ce.getCause());
                            } catch (CancellationException ce) {
                                allExceptions.add(ce);
                            }
                        }
                    }
                    callback.accept(new TaskResult(Collections.unmodifiableList(allExceptions)));
                });
    }

    @Override
    public void close() {
        // 实例级别的 close 只关闭自己的 executor
        // 全局的 SHARED_EXECUTOR 和 SCHEDULER 由 JVM ShutdownHook 管理
        if (executor != SHARED_EXECUTOR) {
            executor.close();
        }
    }

    public record TaskResult(List<Throwable> exceptions) {
        public boolean hasErrors() {
            return !exceptions.isEmpty();
        }
    }
}