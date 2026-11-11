package com.modelplant.theseus.gate;

import com.modelplant.theseus.api.gate.GateContext;
import com.modelplant.theseus.api.gate.GateRule;
import com.modelplant.theseus.api.tool.Tool;
import com.modelplant.theseus.config.TheseusServerConfig;

import java.util.Set;

public final class BuiltinGateRules {
    private static final Set<String> DANGEROUS = Set.of("command", "control", "world_edit", "possess");

    private BuiltinGateRules() {
    }

    public static void registerDefaults() {
        ToolGateEngine engine = ToolGateEngine.get();
        engine.clearRules();
        engine.addRule(capabilityRule());
        engine.addRule(operatorRule());
        engine.addRule(visionRule());
    }

    private static GateRule capabilityRule() {
        return new GateRule() {
            @Override
            public String id() {
                return "capability";
            }

            @Override
            public int priority() {
                return 10;
            }

            @Override
            public boolean allow(Tool tool, GateContext context) {
                return context.capabilityEnabled(tool.capability());
            }
        };
    }

    private static GateRule operatorRule() {
        return new GateRule() {
            @Override
            public String id() {
                return "operator";
            }

            @Override
            public int priority() {
                return 20;
            }

            @Override
            public boolean allow(Tool tool, GateContext context) {
                if (!TheseusServerConfig.REQUIRE_OP_FOR_DANGEROUS.getAsBoolean()) {
                    return true;
                }
                if (!DANGEROUS.contains(tool.capability())) {
                    return true;
                }
                return context.operator();
            }
        };
    }

    private static GateRule visionRule() {
        return new GateRule() {
            @Override
            public String id() {
                return "vision";
            }

            @Override
            public int priority() {
                return 30;
            }

            @Override
            public boolean allow(Tool tool, GateContext context) {
                if (!"vision".equals(tool.capability())) {
                    return true;
                }
                return context.visionCapableModel();
            }
        };
    }
}
