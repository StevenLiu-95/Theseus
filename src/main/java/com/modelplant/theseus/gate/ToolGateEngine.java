package com.modelplant.theseus.gate;

import com.modelplant.theseus.api.event.TheseusGateEvent;
import com.modelplant.theseus.api.gate.GateContext;
import com.modelplant.theseus.api.gate.GateRule;
import com.modelplant.theseus.api.tool.Tool;
import com.modelplant.theseus.tool.TheseusToolRegistry;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ToolGateEngine {
    private static final ToolGateEngine INSTANCE = new ToolGateEngine();

    private final CopyOnWriteArrayList<GateRule> rules = new CopyOnWriteArrayList<>();

    private ToolGateEngine() {
    }

    public static ToolGateEngine get() {
        return INSTANCE;
    }

    public void addRule(GateRule rule) {
        rules.add(rule);
        rules.sort(Comparator.comparingInt(GateRule::priority));
    }

    public void clearRules() {
        rules.clear();
    }

    public List<GateRule> rules() {
        return List.copyOf(rules);
    }

    public List<Tool> filter(GateContext context) {
        List<Tool> accepted = new ArrayList<>();
        for (Tool tool : TheseusToolRegistry.get().all()) {
            boolean allow = true;
            for (GateRule rule : rules) {
                if (!rule.allow(tool, context)) {
                    allow = false;
                    break;
                }
            }
            if (allow) {
                accepted.add(tool);
            }
        }

        TheseusGateEvent event = new TheseusGateEvent(context, accepted);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return List.of();
        }
        return List.copyOf(event.getTools());
    }
}
