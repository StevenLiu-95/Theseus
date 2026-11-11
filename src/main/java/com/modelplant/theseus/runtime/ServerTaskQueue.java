package com.modelplant.theseus.runtime;

import com.modelplant.theseus.Theseus;
import com.modelplant.theseus.config.TheseusServerConfig;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Queues work onto the server main thread with a per-tick budget.
 */
public final class ServerTaskQueue {
    private static final ServerTaskQueue INSTANCE = new ServerTaskQueue();

    private final ConcurrentLinkedQueue<QueuedTask<?>> queue = new ConcurrentLinkedQueue<>();
    private final Queue<Runnable> deferred = new ArrayDeque<>();
    private volatile MinecraftServer server;
    private int blockChangesThisTick;

    private ServerTaskQueue() {
    }

    public static ServerTaskQueue get() {
        return INSTANCE;
    }

    public void bind(MinecraftServer server) {
        this.server = server;
    }

    public void unbind() {
        this.server = null;
        queue.clear();
        deferred.clear();
    }

    public MinecraftServer server() {
        return server;
    }

    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        MinecraftServer s = server;
        if (s != null && s.isSameThread()) {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
            return future;
        }
        queue.add(new QueuedTask<>(task, future));
        return future;
    }

    public CompletableFuture<Void> submit(Runnable task) {
        return submit(() -> {
            task.run();
            return null;
        });
    }

    public boolean tryConsumeBlockChangeBudget(int amount) {
        int max = TheseusServerConfig.MAX_BLOCK_CHANGES_PER_TICK.getAsInt();
        if (blockChangesThisTick + amount > max) {
            return false;
        }
        blockChangesThisTick += amount;
        return true;
    }

    public int remainingBlockBudget() {
        return Math.max(0, TheseusServerConfig.MAX_BLOCK_CHANGES_PER_TICK.getAsInt() - blockChangesThisTick);
    }

    public void onServerTick(ServerTickEvent.Post event) {
        blockChangesThisTick = 0;
        int budget = 64;
        while (budget-- > 0) {
            QueuedTask<?> task = queue.poll();
            if (task == null) {
                break;
            }
            task.run();
        }
        while (!deferred.isEmpty()) {
            try {
                deferred.poll().run();
            } catch (Exception e) {
                Theseus.LOGGER.error("Deferred task failed", e);
            }
        }
    }

    public int pending() {
        return queue.size();
    }

    private record QueuedTask<T>(Supplier<T> supplier, CompletableFuture<T> future) {
        void run() {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
    }
}
