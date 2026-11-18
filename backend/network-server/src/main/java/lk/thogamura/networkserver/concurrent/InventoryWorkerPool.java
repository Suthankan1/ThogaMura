package lk.thogamura.networkserver.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded worker pool for executing mutating inventory commands and stock reservations.
 *
 * <p>Demonstrates Member 4's concurrent processing model:
 * <ul>
 *   <li>Bounded queue capacity preventing unbounded memory growth under burst load</li>
 *   <li>Fixed or elastic pool size bounded by maximum worker count</li>
 *   <li>Explicit worker thread naming ({@code inventory-worker-%d}) for traceable logging</li>
 *   <li>Graceful shutdown hooks ensuring in-flight transactions complete before termination</li>
 * </ul>
 */
public class InventoryWorkerPool implements ExecutorService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InventoryWorkerPool.class);

    private final ThreadPoolExecutor executor;
    private final int queueCapacity;

    public InventoryWorkerPool(int corePoolSize, int maxPoolSize, int queueCapacity) {
        if (corePoolSize < 1) {
            throw new IllegalArgumentException("corePoolSize must be at least 1: " + corePoolSize);
        }
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("maxPoolSize (" + maxPoolSize + ") must be >= corePoolSize (" + corePoolSize + ")");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least 1: " + queueCapacity);
        }

        this.queueCapacity = queueCapacity;
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(queueCapacity);
        ThreadFactory threadFactory = new InventoryWorkerThreadFactory();
        RejectedExecutionHandler rejectionHandler = new ThreadPoolExecutor.CallerRunsPolicy();

        this.executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L,
                TimeUnit.SECONDS,
                queue,
                threadFactory,
                rejectionHandler
        );
        log.info("Initialized InventoryWorkerPool [corePoolSize={}, maxPoolSize={}, queueCapacity={}]",
                corePoolSize, maxPoolSize, queueCapacity);
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public int getQueueSize() {
        return executor.getQueue().size();
    }

    public int getActiveCount() {
        return executor.getActiveCount();
    }

    public long getCompletedTaskCount() {
        return executor.getCompletedTaskCount();
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command must not be null");
        executor.execute(command);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        return executor.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        Objects.requireNonNull(task, "task must not be null");
        return executor.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        return executor.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return executor.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return executor.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return executor.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return executor.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void shutdown() {
        log.info("Shutting down InventoryWorkerPool...");
        executor.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        log.warn("Forcefully shutting down InventoryWorkerPool immediately");
        return executor.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return executor.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return executor.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    @Override
    public void close() {
        shutdown();
        try {
            if (!awaitTermination(5, TimeUnit.SECONDS)) {
                shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownNow();
        }
    }

    private static class InventoryWorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "inventory-worker-" + threadNumber.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        }
    }
}
