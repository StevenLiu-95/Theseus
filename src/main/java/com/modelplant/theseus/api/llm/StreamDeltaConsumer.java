package com.modelplant.theseus.api.llm;

@FunctionalInterface
public interface StreamDeltaConsumer {
    void onDelta(String text);
}
