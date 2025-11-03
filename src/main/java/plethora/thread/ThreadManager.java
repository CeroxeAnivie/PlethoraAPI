package plethora.thread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 增强版 ThreadManager for Java 21+.
 * <p>
 * 核心特性：
 * - 使用虚拟线程（Java 21 正式特性）
 * - 支持同步阻塞等待所有任务完成
 * - 支持异步非阻塞启动任务
 * - **【新增】支持异步非阻塞回调，在所有任务完成后执行**
 * - 收集所有任务抛出的异常
 * - 支持超时控制
 * - 实现 AutoCloseable，推荐使用 try-with-resources
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
     * 【新增】一个用于保存所有任务执行结果的不可变记录。
     *
     * @param exceptions 所有任务执行过程中抛出的异常列表。如果列表为空，表示所有任务都成功完成。
     */
    public record TaskResult(List<Throwable> exceptions) {
        /**
         * @return 如果有任务抛出异常，返回 true。
         */
        public boolean hasErrors() {
            return !exceptions.isEmpty();
        }
    }

    /**
     * 执行所有任务，无限等待直到全部完成。
     *
     * @return 所有任务中抛出的异常列表。
     */
    public List<Throwable> start() {
        return startWithTimeout(null);
    }

    /**
     * 执行所有任务，支持超时。
     *
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
     * 异步启动所有任务，不等待完成，也不提供回调。
     * 这是一种 "启动并忘记" 的模式。
     */
    public void startAsync() {
        for (Runnable task : tasks) {
            executor.execute(task);
        }
    }

    /**
     * 【新增】异步启动所有任务，并在所有任务（无论成功或失败）完成后，执行一个回调。
     * 此方法是非阻塞的，会立即返回。
     *
     * @param callback 当所有任务都执行完毕后，会被一个虚拟线程调用的回调函数。
     *                 回调接收一个 {@link TaskResult} 对象，其中包含了所有任务的执行结果（异常列表）。
     * @throws NullPointerException 如果 callback 为 null。
     *
     * <p>
     * <b>使用示例：</b>
     * <pre>{@code
     * ThreadManager manager = new ThreadManager(task1, task2, task3);
     * manager.startAsyncWithCallback(result -> {
     *     if (result.hasErrors()) {
     *         System.err.println("部分任务失败:");
     *         result.exceptions().forEach(e -> e.printStackTrace());
     *     } else {
     *         System.out.println("所有任务均已成功完成！");
     *     }
     * });
     * System.out.println("任务已启动，主线程继续执行...");
     * }</pre>
     */
    public void startAsyncWithCallback(Consumer<TaskResult> callback) {
        Objects.requireNonNull(callback, "Callback cannot be null");

        // 1. 将每个 Runnable 任务包装成 CompletableFuture，并确保它们在虚拟线程执行器中运行
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = tasks.stream()
                .map(task -> CompletableFuture.runAsync(task, executor))
                .toArray(CompletableFuture[]::new);

        // 2. 使用 CompletableFuture.allOf 创建一个组合的 Future
        // 这个 allOfFuture 会在所有传入的 futures 都完成时（无论正常完成还是异常完成）才完成
        CompletableFuture<Void> allOfFuture = CompletableFuture.allOf(futures);

        // 3. 当 allOfFuture 完成时，异步执行我们提供的回调
        allOfFuture.whenComplete((unused, ex) -> {
            // 这个回调本身会在一个虚拟线程中执行，不会阻塞任何平台线程

            // 4. 收集所有子任务的异常
            List<Throwable> allExceptions = new ArrayList<>();
            for (CompletableFuture<?> future : futures) {
                // 检查每个 future 是否以异常结束
                if (future.isCompletedExceptionally()) {
                    // exceptionNow() (Java 21+) 是获取异常的简洁方式
                    allExceptions.add(future.exceptionNow());
                }
            }

            // 5. 调用用户提供的回调，并传入封装好的结果
            callback.accept(new TaskResult(Collections.unmodifiableList(allExceptions)));
        });
    }


    /**
     * 关闭内部的虚拟线程执行器（推荐使用 try-with-resources）
     */
    @Override
    public void close() {
        executor.close(); // Java 19+ ExecutorService 实现了 AutoCloseable
    }
}