package com.modelplant.theseus.session;

import com.modelplant.theseus.api.llm.ChatMessage;
import com.modelplant.theseus.api.llm.ImageData;
import com.modelplant.theseus.config.TheseusServerConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AiSession {
    private final UUID id;
    private final UUID playerId;
    private final List<ChatMessage> history = new ArrayList<>();
    private final List<ImageData> pendingVision = new ArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private String systemPrompt;
    private long createdAt = System.currentTimeMillis();
    private long lastActiveAt = createdAt;

    public AiSession(UUID id, UUID playerId) {
        this.id = id;
        this.playerId = playerId;
        this.systemPrompt = defaultSystemPrompt();
        history.add(ChatMessage.system(systemPrompt));
    }

    public UUID id() {
        return id;
    }

    public UUID playerId() {
        return playerId;
    }

    public List<ChatMessage> historyView() {
        return List.copyOf(history);
    }

    public synchronized List<ChatMessage> mutableHistoryCopy() {
        return new ArrayList<>(history);
    }

    public synchronized void add(ChatMessage message) {
        history.add(message);
        trim();
        lastActiveAt = System.currentTimeMillis();
    }

    public synchronized void clearHistory() {
        history.clear();
        history.add(ChatMessage.system(systemPrompt));
        pendingVision.clear();
        lastActiveAt = System.currentTimeMillis();
    }

    public synchronized void enqueueVision(ImageData image) {
        if (image != null && image.base64() != null && !image.base64().isBlank()) {
            pendingVision.add(image);
            // Keep at most one fresh frame to avoid blowing context
            while (pendingVision.size() > 1) {
                pendingVision.removeFirst();
            }
        }
    }

    public synchronized List<ImageData> drainVision() {
        if (pendingVision.isEmpty()) {
            return List.of();
        }
        List<ImageData> copy = List.copyOf(pendingVision);
        pendingVision.clear();
        return copy;
    }

    public boolean tryAcquire() {
        return busy.compareAndSet(false, true);
    }

    public void release() {
        busy.set(false);
    }

    public boolean busy() {
        return busy.get();
    }

    public long createdAt() {
        return createdAt;
    }

    public long lastActiveAt() {
        return lastActiveAt;
    }

    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt == null || prompt.isBlank() ? defaultSystemPrompt() : prompt;
        synchronized (this) {
            if (!history.isEmpty() && "system".equals(history.getFirst().role())) {
                history.set(0, ChatMessage.system(this.systemPrompt));
            }
        }
    }

    private void trim() {
        int max = TheseusServerConfig.MAX_HISTORY_MESSAGES.getAsInt();
        while (history.size() > max) {
            // keep system message at index 0
            if (history.size() > 1) {
                history.remove(1);
            } else {
                break;
            }
        }
    }

    public static String defaultSystemPrompt() {
        return """
                You are Theseus, an AI agent inside Minecraft.
                Use tools to inspect and change the world when helpful.
                Prefer concise replies. Confirm destructive actions briefly after completing them.
                Coordinates use Minecraft block positions. Prefer tool results over guessing.
                """;
    }
}
