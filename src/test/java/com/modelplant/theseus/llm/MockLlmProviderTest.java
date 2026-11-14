package com.modelplant.theseus.llm;

import com.google.gson.JsonObject;
import com.modelplant.theseus.api.llm.ChatMessage;
import com.modelplant.theseus.api.llm.ChatRequest;
import com.modelplant.theseus.api.llm.ChatResponse;
import com.modelplant.theseus.api.tool.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockLlmProviderTest {

    @Test
    void toolDirectiveProducesToolCall() throws Exception {
        MockLlmProvider provider = new MockLlmProvider();
        JsonObject params = new JsonObject();
        ToolSchema echo = new ToolSchema("echo", "echo", params);
        ChatRequest request = new ChatRequest(
                "mock",
                List.of(ChatMessage.user("TOOL echo {\"message\":\"hi\"}")),
                List.of(echo),
                0.2,
                256,
                false
        );
        ChatResponse response = provider.chat(request, "").get(2, TimeUnit.SECONDS);
        assertTrue(response.hasToolCalls());
        assertEquals("echo", response.toolCalls().getFirst().name());
    }

    @Test
    void plainTextEchoes() throws Exception {
        MockLlmProvider provider = new MockLlmProvider();
        ChatRequest request = new ChatRequest(
                "mock",
                List.of(ChatMessage.user("hello")),
                List.of(),
                0.2,
                256,
                false
        );
        ChatResponse response = provider.chat(request, "").get(2, TimeUnit.SECONDS);
        assertTrue(response.content() != null && response.content().contains("hello"));
    }
}
