package com.modelplant.theseus.session;

import com.modelplant.theseus.api.llm.ChatMessage;
import com.modelplant.theseus.api.llm.ImageData;
import com.modelplant.theseus.config.TheseusServerConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        return sanitizeToolHistory(new ArrayList<>(history));
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
        while (history.size() > max && history.size() > 1) {
            int drop = dropCountFromOldest();
            for (int i = 0; i < drop && history.size() > 1; i++) {
                history.remove(1);
            }
        }
        // Drop any orphan tool rows left by older sessions / partial failures.
        List<ChatMessage> cleaned = sanitizeToolHistory(new ArrayList<>(history));
        if (cleaned.size() != history.size()) {
            history.clear();
            history.addAll(cleaned);
        }
    }

    /**
     * Remove the oldest non-system message as an atomic unit so we never leave
     * {@code role=tool} without a preceding assistant {@code tool_calls}.
     */
    private int dropCountFromOldest() {
        ChatMessage oldest = history.get(1);
        if ("assistant".equals(oldest.role()) && oldest.toolCalls() != null && !oldest.toolCalls().isEmpty()) {
            int count = 1;
            for (int i = 2; i < history.size(); i++) {
                if ("tool".equals(history.get(i).role())) {
                    count++;
                } else {
                    break;
                }
            }
            return count;
        }
        if ("tool".equals(oldest.role())) {
            int count = 0;
            for (int i = 1; i < history.size(); i++) {
                if ("tool".equals(history.get(i).role())) {
                    count++;
                } else {
                    break;
                }
            }
            return Math.max(1, count);
        }
        return 1;
    }

    /**
     * OpenAI-compatible APIs reject histories where a tool message is not a reply
     * to the immediately preceding assistant tool_calls block (or an unmatched id).
     */
    static List<ChatMessage> sanitizeToolHistory(List<ChatMessage> messages) {
        List<ChatMessage> out = new ArrayList<>(messages.size());
        Set<String> openToolCallIds = null;
        for (ChatMessage message : messages) {
            if ("assistant".equals(message.role()) && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                out.add(message);
                openToolCallIds = new LinkedHashSet<>();
                for (var call : message.toolCalls()) {
                    if (call.id() != null) {
                        openToolCallIds.add(call.id());
                    }
                }
                continue;
            }
            if ("tool".equals(message.role())) {
                if (openToolCallIds == null) {
                    continue; // orphan — drop
                }
                String id = message.toolCallId();
                if (id != null && openToolCallIds.contains(id)) {
                    out.add(message);
                    openToolCallIds.remove(id);
                    if (openToolCallIds.isEmpty()) {
                        openToolCallIds = null;
                    }
                } else if (id == null && !openToolCallIds.isEmpty()) {
                    // Best-effort: accept unnamed tool reply against the open block
                    out.add(message);
                    openToolCallIds.remove(openToolCallIds.iterator().next());
                    if (openToolCallIds.isEmpty()) {
                        openToolCallIds = null;
                    }
                }
                // else: unmatched tool_call_id — drop
                continue;
            }
            // Any non-tool message closes an unfinished tool block for subsequent rows.
            openToolCallIds = null;
            out.add(message);
        }
        return out;
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
