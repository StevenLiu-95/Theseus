package com.modelplant.theseus.session;

import com.modelplant.theseus.api.llm.ChatMessage;
import com.modelplant.theseus.api.llm.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolHistorySanitizerTest {

    @Test
    void dropsOrphanToolMessages() {
        List<ChatMessage> cleaned = ToolHistorySanitizer.sanitize(List.of(
                ChatMessage.system("sys"),
                ChatMessage.tool("orphan", "echo", "hi"),
                ChatMessage.user("hello")
        ));
        assertEquals(2, cleaned.size());
        assertEquals("system", cleaned.get(0).role());
        assertEquals("user", cleaned.get(1).role());
    }

    @Test
    void keepsMatchedToolBlock() {
        ToolCall call = new ToolCall("c1", "echo", "{\"message\":\"hi\"}");
        List<ChatMessage> cleaned = ToolHistorySanitizer.sanitize(List.of(
                ChatMessage.user("TOOL echo"),
                ChatMessage.assistantToolCalls(List.of(call)),
                ChatMessage.tool("c1", "echo", "ok")
        ));
        assertEquals(3, cleaned.size());
        assertEquals("tool", cleaned.get(2).role());
        assertEquals("c1", cleaned.get(2).toolCallId());
    }

    @Test
    void dropsUnmatchedToolCallId() {
        ToolCall call = new ToolCall("c1", "echo", "{}");
        List<ChatMessage> cleaned = ToolHistorySanitizer.sanitize(List.of(
                ChatMessage.assistantToolCalls(List.of(call)),
                ChatMessage.tool("other", "echo", "nope"),
                ChatMessage.user("next")
        ));
        assertEquals(2, cleaned.size());
        assertEquals("assistant", cleaned.get(0).role());
        assertEquals("user", cleaned.get(1).role());
    }

    @Test
    void acceptsUnnamedToolAgainstOpenBlock() {
        ToolCall call = new ToolCall("c1", "echo", "{}");
        List<ChatMessage> cleaned = ToolHistorySanitizer.sanitize(List.of(
                ChatMessage.assistantToolCalls(List.of(call)),
                new ChatMessage("tool", "ok", List.of(), null, "echo")
        ));
        assertEquals(2, cleaned.size());
        assertTrue(cleaned.stream().anyMatch(m -> "tool".equals(m.role())));
    }
}
