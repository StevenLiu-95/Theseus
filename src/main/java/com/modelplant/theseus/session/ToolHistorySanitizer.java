package com.modelplant.theseus.session;

import com.modelplant.theseus.api.llm.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps OpenAI-compatible tool history well-formed (no orphan {@code role=tool} rows).
 */
public final class ToolHistorySanitizer {
    private ToolHistorySanitizer() {
    }

    /**
     * Remove tool messages that are not replies to an open assistant {@code tool_calls} block.
     */
    public static List<ChatMessage> sanitize(List<ChatMessage> messages) {
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
}
