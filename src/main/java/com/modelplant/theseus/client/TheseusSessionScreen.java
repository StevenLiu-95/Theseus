package com.modelplant.theseus.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Lightweight auxiliary GUI for chatting with Theseus (commands remain primary).
 */
public final class TheseusSessionScreen extends Screen {
    private EditBox input;
    private String status = "Ready";

    public TheseusSessionScreen() {
        super(Component.literal("Theseus"));
    }

    @Override
    protected void init() {
        input = new EditBox(font, width / 2 - 140, height / 2 - 10, 280, 20, Component.literal("message"));
        input.setMaxLength(256);
        input.setHint(Component.literal("Talk to Theseus..."));
        addRenderableWidget(input);
        addRenderableWidget(Button.builder(Component.literal("Send"), b -> send())
                .bounds(width / 2 - 140, height / 2 + 20, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Tools"), b -> runCommand("theseus tools"))
                .bounds(width / 2 - 40, height / 2 + 20, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width / 2 + 60, height / 2 + 20, 80, 20).build());
        setInitialFocus(input);
    }

    private void send() {
        String text = input.getValue().trim();
        if (text.isEmpty()) {
            return;
        }
        runCommand("theseus chat " + text);
        status = "Sent: " + text;
        input.setValue("");
    }

    private void runCommand(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.connection.sendCommand(command);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, "Theseus AI", width / 2, height / 2 - 40, 0xFFFFFF);
        graphics.drawCenteredString(font, status, width / 2, height / 2 + 50, 0xA0A0A0);
    }

    public static final class KeybindHandler {
        @SubscribeEvent
        public static void onKey(InputEvent.Key event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null || mc.player == null) {
                return;
            }
            if (event.getKey() == GLFW.GLFW_KEY_H && event.getAction() == GLFW.GLFW_PRESS
                    && (event.getModifiers() & GLFW.GLFW_MOD_CONTROL) != 0
                    && (event.getModifiers() & GLFW.GLFW_MOD_ALT) != 0) {
                mc.setScreen(new TheseusSessionScreen());
            }
        }
    }
}
