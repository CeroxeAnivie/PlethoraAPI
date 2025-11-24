package plethora.thread;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 增强版 ThreadManager for Java 21+.
 */
public final class ThreadManager implements AutoCloseable {

    private static final ExecutorService SHARED_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    static {
        // 虚拟线程执行器通常不需要显式关闭，但为了保持干净的退出语义
        Runtime.getRuntime().addShutdownHook(new Thread(SHARED_EXECUTOR::close));
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
        if (tasks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Tasks cannot contain null elements");
        }
        this.tasks = List.copyOf(tasks);
        this.executor = executor;
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
        // 使用线程安全的 List 收集异常
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

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = tasks.stream()
                .map(task -> CompletableFuture.runAsync(task, executor))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures)
                .whenComplete((unused, ex) -> {
                    // 无论成功还是失败，收集所有异常
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
                    // 在回调中处理结果
                    callback.accept(new TaskResult(Collections.unmodifiableList(allExceptions)));
                });
    }

    @Override
    public void close() {
        // 只有当 executor 属于当前实例创建时才关闭 (在构造函数中目前的逻辑是总是传入新创建的或私有的)
        // 如果未来支持传入共享 executor，这里需要判断所有权。目前实现总是安全的。
        executor.close();
    }

    public record TaskResult(List<Throwable> exceptions) {
        public boolean hasErrors() {
            return !exceptions.isEmpty();
        }
    }
}