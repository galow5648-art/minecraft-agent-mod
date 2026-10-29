package com.example.agent;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;

/**
 * Локальный HTTP-API только на 127.0.0.1:8765.
 * Команды ставятся в очередь и выполняются в клиентском тике, чтобы не трогать игру из чужого потока.
 */
public class AgentMod implements ClientModInitializer {
    public static final int PORT = 8765;
    private static final ConcurrentLinkedQueue<Runnable> TASKS = new ConcurrentLinkedQueue<>();
    private static volatile String lastState = "{}";

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Runnable task;
            while ((task = TASKS.poll()) != null) {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    System.err.println("[agent] ошибка команды: " + e);
                }
            }
            lastState = buildState(mc);
        });

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext("/state", ex -> send(ex, 200, lastState));
            server.createContext("/key", ex -> {
                String body = read(ex);
                String action = field(body, "action");
                boolean down = body.contains("\"down\":true") || body.contains("\"down\": true");
                TASKS.add(() -> setKey(action, down));
                send(ex, 200, "{\"ok\":true}");
            });
            server.createContext("/look", ex -> {
                String body = read(ex);
                float yaw = (float) num(body, "yaw");
                float pitch = (float) num(body, "pitch");
                TASKS.add(() -> {
                    LocalPlayer p = Minecraft.getInstance().player;
                    if (p != null) {
                        p.setYRot(yaw);
                        p.setXRot(Math.max(-90f, Math.min(90f, pitch)));
                    }
                });
                send(ex, 200, "{\"ok\":true}");
            });
            server.createContext("/slot", ex -> {
                int slot = (int) num(read(ex), "slot");
                TASKS.add(() -> {
                    LocalPlayer p = Minecraft.getInstance().player;
                    if (p != null && slot >= 0 && slot <= 8) {
                        p.getInventory().setSelectedSlot(slot);
                    }
                });
                send(ex, 200, "{\"ok\":true}");
            });
            server.setExecutor(null);
            server.start();
            System.out.println("[agent] API запущен на http://127.0.0.1:" + PORT);
        } catch (IOException e) {
            System.err.println("[agent] не удалось запустить API: " + e);
        }
    }

    private static void setKey(String action, boolean down) {
        var opts = Minecraft.getInstance().options;
        KeyMapping key = switch (action) {
            case "forward" -> opts.keyUp;
            case "back" -> opts.keyDown;
            case "left" -> opts.keyLeft;
            case "right" -> opts.keyRight;
            case "jump" -> opts.keyJump;
            case "sneak" -> opts.keyShift;
            case "sprint" -> opts.keySprint;
            case "attack" -> opts.keyAttack;
            case "use" -> opts.keyUse;
            default -> null;
        };
        if (key != null) {
            key.setDown(down);
        }
    }

    private static String buildState(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return "{\"inWorld\":false}";
        }
        return "{\"inWorld\":true"
                + ",\"x\":" + round(p.getX())
                + ",\"y\":" + round(p.getY())
                + ",\"z\":" + round(p.getZ())
                + ",\"yaw\":" + round(p.getYRot())
                + ",\"pitch\":" + round(p.getXRot())
                + ",\"health\":" + round(p.getHealth())
                + ",\"food\":" + p.getFoodData().getFoodLevel()
                + ",\"slot\":" + p.getInventory().getSelectedSlot()
                + "}";
    }

    private static String round(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static String read(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String field(String json, String name) {
        int i = json.indexOf("\"" + name + "\"");
        if (i < 0) return "";
        int start = json.indexOf('"', json.indexOf(':', i) + 1) + 1;
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : "";
    }

    private static double num(String json, String name) {
        int i = json.indexOf("\"" + name + "\"");
        if (i < 0) return 0;
        int start = json.indexOf(':', i) + 1;
        int end = start;
        while (end < json.length() && "-+.0123456789 ".indexOf(json.charAt(end)) >= 0) end++;
        try {
            return Double.parseDouble(json.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
