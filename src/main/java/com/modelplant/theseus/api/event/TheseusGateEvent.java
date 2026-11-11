package com.modelplant.theseus.api.event;

import com.modelplant.theseus.api.gate.GateContext;
import com.modelplant.theseus.api.tool.Tool;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Fired after built-in gate rules; listeners may remove tools from the visible set.
 */
public final class TheseusGateEvent extends Event implements ICancellableEvent {
    private final GateContext context;
    private final List<Tool> tools;
    private boolean canceled;

    public TheseusGateEvent(GateContext context, List<Tool> tools) {
        this.context = context;
        this.tools = new ArrayList<>(tools);
    }

    public GateContext getContext() {
        return context;
    }

    public List<Tool> getTools() {
        return tools;
    }

    @Override
    public boolean isCanceled() {
        return canceled;
    }

    @Override
    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
}
