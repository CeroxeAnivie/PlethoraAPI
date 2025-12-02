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
 */
public final class ThreadManager implements AutoCloseable {

    private static final ExecutorService SHARED_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    static {
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
        executor.close();
    }

    public record TaskResult(List<Throwable> exceptions) {
        public boolean hasErrors() {
            return !exceptions.isEmpty();
        }
    }
}