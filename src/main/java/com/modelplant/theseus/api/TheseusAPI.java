package com.modelplant.theseus.api;

import com.modelplant.theseus.api.gate.GateRule;
import com.modelplant.theseus.api.llm.LlmProvider;
import com.modelplant.theseus.api.tool.Tool;
import com.modelplant.theseus.api.tool.ToolRegistry;
import com.modelplant.theseus.gate.ToolGateEngine;
import com.modelplant.theseus.llm.LlmProviderRegistry;
import com.modelplant.theseus.runtime.ServerTaskQueue;
import com.modelplant.theseus.session.AiSessionManager;
import com.modelplant.theseus.tool.TheseusToolRegistry;

/**
 * Stable entry point for other mods integrating with Theseus.
 */
public final class TheseusAPI {
    private TheseusAPI() {
    }

    public static ToolRegistry tools() {
        return TheseusToolRegistry.get();
    }

    public static void registerTool(Tool tool) {
        TheseusToolRegistry.get().register(tool);
    }

    public static void registerProvider(LlmProvider provider) {
        LlmProviderRegistry.get().register(provider);
    }

    public static void registerGateRule(GateRule rule) {
        ToolGateEngine.get().addRule(rule);
    }

    public static AiSessionManager sessions() {
        return AiSessionManager.get();
    }

    public static ServerTaskQueue taskQueue() {
        return ServerTaskQueue.get();
    }
}
