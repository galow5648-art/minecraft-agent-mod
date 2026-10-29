package com.example.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Локальный HTTP-API только на 127.0.0.1:8765.
 * Все запросы к игре выполняются в клиентском тике, HTTP-поток только ждёт результат.
 */
public class AgentMod implements ClientModInitializer {
    public static final int PORT = 8765;
    private static final ConcurrentLinkedQueue<Runnable> TASKS = new ConcurrentLinkedQueue<>();
    private static volatile String lastState = "{\"inWorld\":false}";

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
            server.createContext("/nearby", ex -> {
                int r = clamp((int) queryNum(ex, "r", 8), 1, 12);
                respondFromGame(ex, () -> nearby(r));
            });
            server.createContext("/look_at", ex -> respondFromGame(ex, AgentMod::lookAt));
            server.createContext("/inventory", ex -> respondFromGame(ex, AgentMod::inventory));
            server.setExecutor(null);
            server.start();
            System.out.println("[agent] API запущен на http://127.0.0.1:" + PORT);
        } catch (IOException e) {
            System.err.println("[agent] не удалось запустить API: " + e);
        }
    }

    /** Выполняет запрос в клиентском тике и отдаёт результат. */
    private static void respondFromGame(HttpExchange ex, Supplier<String> query) throws IOException {
        CompletableFuture<String> future = new CompletableFuture<>();
        TASKS.add(() -> {
            try {
                future.complete(query.get());
            } catch (RuntimeException e) {
                future.complete("{\"error\":\"" + escape(e.toString()) + "\"}");
            }
        });
        String result;
        try {
            result = future.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            result = "{\"error\":\"игра не ответила вовремя\"}";
        }
        send(ex, 200, result);
    }

    private static String nearby(int r) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null) {
            return "{\"inWorld\":false}";
        }
        BlockPos base = p.blockPosition();
        StringBuilder sb = new StringBuilder("{\"center\":[")
                .append(base.getX()).append(',').append(base.getY()).append(',').append(base.getZ())
                .append("],\"blocks\":[");
        boolean first = true;
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    BlockState st = level.getBlockState(pos);
                    if (st.isAir()) {
                        continue;
                    }
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"x\":").append(pos.getX())
                      .append(",\"y\":").append(pos.getY())
                      .append(",\"z\":").append(pos.getZ())
                      .append(",\"block\":\"").append(escape(blockId(st))).append("\"}");
                    count++;
                    if (count >= 1500) {
                        sb.append("],\"truncated\":true}");
                        return sb.toString();
                    }
                }
            }
        }
        sb.append("],\"truncated\":false}");
        return sb.toString();
    }

    private static String lookAt() {
        Minecraft mc = Minecraft.getInstance();
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            return "{\"target\":null}";
        }
        BlockPos pos = bhr.getBlockPos();
        BlockState st = mc.level.getBlockState(pos);
        return "{\"target\":{\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ()
                + ",\"block\":\"" + escape(blockId(st)) + "\""
                + ",\"face\":\"" + bhr.getDirection().getName() + "\""
                + ",\"distance\":" + round(Math.sqrt(bhr.getLocation().distanceToSqr(
                        mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()))) + "}}";
    }

    private static String inventory() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return "{\"inWorld\":false}";
        }
        StringBuilder sb = new StringBuilder("{\"slots\":[");
        boolean first = true;
        int size = p.getInventory().getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!first) sb.append(',');
            first = false;
            String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            sb.append("{\"slot\":").append(i)
              .append(",\"item\":\"").append(escape(id)).append("\"")
              .append(",\"count\":").append(stack.getCount());
            if (stack.isDamageableItem()) {
                sb.append(",\"durability\":").append(stack.getMaxDamage() - stack.getDamageValue())
                  .append(",\"maxDurability\":").append(stack.getMaxDamage());
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String blockId(BlockState st) {
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(st.getBlock()));
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
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static double queryNum(HttpExchange ex, String name, double def) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return def;
        for (String part : q.split("&")) {
            if (part.startsWith(name + "=")) {
                try {
                    return Double.parseDouble(part.substring(name.length() + 1));
                } catch (NumberFormatException e) {
                    return def;
                }
            }
        }
        return def;
    }

    private static String read(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
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
