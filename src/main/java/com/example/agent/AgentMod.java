package com.example.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;

/**
 * Локальный HTTP-API только на 127.0.0.1:8765.
 * Все запросы к игре выполняются в клиентском тике, HTTP-поток только ждёт результат.
 */
public class AgentMod implements ClientModInitializer {
    public static final int PORT = 8765;
    private static final ConcurrentLinkedQueue<Runnable> TASKS = new ConcurrentLinkedQueue<>();
    private static volatile String lastState = "{\"inWorld\":false}";

    /** Текущая задача, которая выполняется в клиентском тике. */
    private static final AtomicInteger JOB_IDS = new AtomicInteger();
    private static final class Job {
        final int id = JOB_IDS.incrementAndGet();
        final String type;
        final int x, y, z;
        final long startedMs = System.currentTimeMillis();
        long timeoutMs = 30_000;
        volatile String status = "running";
        volatile String error = null;
        volatile String info = null;
        // состояние goto
        List<BlockPos> path = null;
        int idx = 0;
        int replans = 0;
        long lastCheckMs = 0;
        double lastX, lastZ;
        // состояние break и open
        int occludedTicks = 0;
        boolean toolChosen = false;
        boolean destroyStarted = false;
        // условия остановки, которые задаёт вызывающий (<=0 — выключено)
        double stopHealth = -1;
        double stopHostile = -1;
        // place
        Direction face = Direction.UP;
        int placeTicks = 0;
        // attack
        int entityId = -1;
        int hitsWanted = 1;
        int hitsDone = 0;
        boolean chase = true;
        // use
        int slot = -1;
        int holdTicks = 0;
        int ticks = 0;
        boolean release = false;
        long lastUseMs = 0;
        Job(String type, int x, int y, int z) {
            this.type = type; this.x = x; this.y = y; this.z = z;
            this.timeoutMs = switch (type) { case "goto" -> 90_000; case "break" -> 25_000; case "place" -> 5_000; case "attack" -> 40_000; case "use" -> 15_000; default -> 30_000; };
        }
    }
    private static volatile boolean focusFixed = false;
    private static volatile Job job = null;

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
            fixFocus(mc);
            collectEvents(mc);
            tickJob(mc);
            lastState = buildState(mc);
        });

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            route(server, "/state", ex -> send(ex, 200, lastState));
            route(server, "/key", ex -> {
                String body = read(ex);
                String action = field(body, "action");
                boolean down = body.contains("\"down\":true") || body.contains("\"down\": true");
                TASKS.add(() -> setKey(action, down));
                send(ex, 200, "{\"ok\":true}");
            });
            route(server, "/look", ex -> {
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
            route(server, "/slot", ex -> {
                int slot = (int) num(read(ex), "slot");
                TASKS.add(() -> {
                    LocalPlayer p = Minecraft.getInstance().player;
                    if (p != null && slot >= 0 && slot <= 8) {
                        p.getInventory().setSelectedSlot(slot);
                    }
                });
                send(ex, 200, "{\"ok\":true}");
            });
            route(server, "/nearby", ex -> {
                int r = clamp((int) queryNum(ex, "r", 8), 1, 12);
                respondFromGame(ex, () -> nearby(r));
            });
            route(server, "/look_at", ex -> respondFromGame(ex, AgentMod::lookAt));
            route(server, "/inventory", ex -> respondFromGame(ex, AgentMod::inventory));
            route(server, "/job", ex -> send(ex, 200, jobJson()));
            route(server, "/debug_api", ex -> send(ex, 200, dumpApi()));
            route(server, "/debug_recipes", ex -> send(ex, 200, dumpRecipes()));
            route(server, "/debug_sync", ex -> respondFromGame(ex, AgentMod::dumpSync));
            route(server, "/debug_book", ex -> respondFromGame(ex, AgentMod::dumpBook));
            route(server, "/recipes", ex -> respondFromGame(ex, AgentMod::recipesJson));
            route(server, "/craft_id", ex -> {
                String b = read(ex);
                int idx = (int) num(b, "index");
                int times = clamp((int) num(b, "times"), 1, 64);
                respondFromGame(ex, () -> craftById(idx, times));
            });
            route(server, "/entities", ex -> {
                int r = clamp((int) queryNum(ex, "r", 16), 1, 48);
                respondFromGame(ex, () -> entities(r));
            });
            route(server, "/craft", ex -> {
                String b = read(ex);
                String item = field(b, "item");
                int times = clamp((int) num(b, "times"), 1, 64);
                respondFromGame(ex, () -> craft(item, times));
            });
            route(server, "/break", ex -> {
                String b = read(ex);
                int x = (int) num(b, "x"), y = (int) num(b, "y"), z = (int) num(b, "z");
                startJob(applyStops(new Job("break", x, y, z), b));
                send(ex, 200, "{\"ok\":true}");
            });
            route(server, "/goto", ex -> {
                String b = read(ex);
                int x = (int) Math.floor(num(b, "x")), y = (int) num(b, "y"), z = (int) Math.floor(num(b, "z"));
                if (flag(b, "any_y")) y = -9999;
                startJob(applyStops(new Job("goto", x, y, z), b));
                send(ex, 200, "{\"ok\":true}");
            });
            route(server, "/place", ex -> {
                String b = read(ex);
                int x = (int) num(b, "x"), y = (int) num(b, "y"), z = (int) num(b, "z");
                Job pj = applyStops(new Job("place", x, y, z), b);
                pj.face = faceOf(field(b, "face"));
                startJob(pj);
                send(ex, 200, "{\"ok\":true}");
            });
            route(server, "/entities_all", ex -> {
                int r = clamp((int) queryNum(ex, "r", 16), 1, 48);
                respondFromGame(ex, () -> entitiesAll(r));
            });
            route(server, "/attack", ex -> {
                String b = read(ex);
                Job aj = applyStops(new Job("attack", 0, 0, 0), b);
                aj.entityId = (int) num(b, "id");
                aj.hitsWanted = Math.max(1, Math.min(60, (int) num(b, "hits")));
                aj.chase = !b.contains("\"chase\":false") && !b.contains("\"chase\": false");
                startJob(aj);
                send(ex, 200, "{\"ok\":true}");
            });
            route(server, "/use_item", ex -> {
                String b = read(ex);
                Job uj = applyStops(new Job("use", 0, 0, 0), b);
                uj.slot = b.contains("\"slot\"") ? (int) num(b, "slot") : -1;
                uj.holdTicks = Math.max(0, Math.min(400, (int) num(b, "hold_ticks")));
                uj.release = flag(b, "release");
                startJob(uj);
                send(ex, 200, "{\"ok\":true}");
            });
            route(server, "/interact_block", ex -> {
                String b = read(ex);
                int x = (int) num(b, "x"), y = (int) num(b, "y"), z = (int) num(b, "z");
                Direction f = faceOf(field(b, "face"));
                boolean sneak = flag(b, "sneak");
                respondFromGame(ex, () -> interactBlock(x, y, z, f, sneak));
            });
            route(server, "/interact_entity", ex -> {
                int id = (int) num(read(ex), "id");
                respondFromGame(ex, () -> interactEntity(id));
            });
            route(server, "/block_info", ex -> {
                int x = (int) queryNum(ex, "x", 0), y = (int) queryNum(ex, "y", 0), z = (int) queryNum(ex, "z", 0);
                respondFromGame(ex, () -> blockInfo(x, y, z));
            });
            route(server, "/alerts", ex -> {
                StringBuilder sb = new StringBuilder("{\"events\":[");
                synchronized (EVENTS) {
                    for (int i = 0; i < EVENTS.size(); i++) {
                        if (i > 0) sb.append(',');
                        sb.append('"').append(escape(EVENTS.get(i))).append('"');
                    }
                    EVENTS.clear();
                }
                sb.append("]}");
                send(ex, 200, sb.toString());
            });
            route(server, "/chat", ex -> {
                String text = field(read(ex), "text");
                respondFromGame(ex, () -> chat(text));
            });
            route(server, "/respawn", ex -> respondFromGame(ex, () -> {
                LocalPlayer rp = Minecraft.getInstance().player;
                if (rp == null) return "{\"ok\":false}";
                try {
                    call(rp, "respawn");
                    return "{\"ok\":true}";
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return "{\"ok\":false,\"error\":\"" + escape(String.valueOf(e)) + "\"}";
                }
            }));
            route(server, "/status", ex -> respondFromGame(ex, AgentMod::status));
            route(server, "/cancel", ex -> {
                Job j = job;
                if (j != null && "running".equals(j.status)) {
                    j.status = "cancelled";
                }
                TASKS.add(() -> releaseKeys(Minecraft.getInstance()));
                send(ex, 200, "{\"ok\":true}");
            });
            route(server, "/find", ex -> {
                String kind = queryStr(ex, "kind", "block");
                String q = queryStr(ex, "q", "");
                int r = clamp((int) queryNum(ex, "r", 16), 1, 48);
                int max = clamp((int) queryNum(ex, "max", 10), 1, 50);
                respondFromGame(ex, () -> find(kind, q, r, max));
            });
            route(server, "/surroundings", ex -> {
                int r = clamp((int) queryNum(ex, "r", 8), 2, 16);
                respondFromGame(ex, () -> surroundings(r));
            });
            route(server, "/select_tool", ex -> {
                String b = read(ex);
                int x = (int) num(b, "x"), y = (int) num(b, "y"), z = (int) num(b, "z");
                respondFromGame(ex, () -> selectToolJson(x, y, z));
            });
            route(server, "/open", ex -> {
                String b = read(ex);
                int x = (int) num(b, "x"), y = (int) num(b, "y"), z = (int) num(b, "z");
                Job nj = new Job("open", x, y, z);
                nj.timeoutMs = 8_000;
                startJob(nj);
                send(ex, 200, "{\"ok\":true}");
            });
            route(server, "/menu", ex -> respondFromGame(ex, AgentMod::menuJson));
            route(server, "/click", ex -> {
                String b = read(ex);
                int slot = (int) num(b, "slot");
                int button = (int) num(b, "button");
                String type = field(b, "type");
                respondFromGame(ex, () -> clickJson(slot, button, type));
            });
            route(server, "/close", ex -> respondFromGame(ex, () -> {
                LocalPlayer p = Minecraft.getInstance().player;
                if (p == null) return "{\"ok\":false}";
                p.closeContainer();
                return "{\"ok\":true}";
            }));
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            System.out.println("[agent] API запущен на http://127.0.0.1:" + PORT);
        } catch (IOException e) {
            System.err.println("[agent] не удалось запустить API: " + e);
        }
    }


    // ---------------------------------------------------------------- инфраструктура

    /** Обёртка над createContext: запрещает запросы из браузера (заголовок Origin) и ловит исключения. */
    private static void route(HttpServer server, String path, com.sun.net.httpserver.HttpHandler handler) {
        server.createContext(path, ex -> {
            try {
                if (ex.getRequestHeaders().getFirst("Origin") != null) {
                    send(ex, 403, "{\"error\":\"запросы из браузера запрещены\"}");
                    return;
                }
                handler.handle(ex);
            } catch (IOException | RuntimeException e) {
                try {
                    send(ex, 500, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
                } catch (IOException ignored) {
                    // клиент уже отключился
                }
            }
        });
    }

    /** Новая задача отменяет текущую и отпускает клавиши. */
    private static void startJob(Job nj) {
        Job old = job;
        if (old != null && "running".equals(old.status)) {
            old.status = "cancelled";
        }
        TASKS.add(() -> releaseKeys(Minecraft.getInstance()));
        job = nj;
    }

    /** Не ставить игру на паузу при потере фокуса окна (иначе тик замирает и API молчит). */
    private static void fixFocus(Minecraft mc) {
        if (focusFixed || mc.options == null) return;
        focusFixed = true;
        try {
            java.lang.reflect.Field f = mc.options.getClass().getField("pauseOnLostFocus");
            Object cur = f.get(mc.options);
            if (cur instanceof Boolean) {
                f.setBoolean(mc.options, false);
            } else if (cur != null) {
                cur.getClass().getMethod("set", Object.class).invoke(cur, Boolean.FALSE);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[agent] не удалось отключить паузу при потере фокуса: " + e);
        }
    }

    private static String queryStr(HttpExchange ex, String name, String def) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return def;
        for (String part : q.split("&")) {
            if (part.startsWith(name + "=")) {
                return java.net.URLDecoder.decode(part.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return def;
    }

    /** Имя открытого экрана через рефлексию (в 26.3 поле screen недоступно напрямую). */
    private static String screenJson(Minecraft mc) {
        Object scr = null;
        try {
            for (String name : new String[]{"screen", "getScreen", "currentScreen"}) {
                try {
                    java.lang.reflect.Method m = Minecraft.class.getMethod(name);
                    scr = m.invoke(mc);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // пробуем следующий вариант
                }
            }
            if (scr == null) {
                for (java.lang.reflect.Field f : Minecraft.class.getDeclaredFields()) {
                    if (f.getName().equals("screen")) {
                        f.setAccessible(true);
                        scr = f.get(mc);
                        break;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "\"?\"";
        }
        return scr == null ? "null" : "\"" + escape(scr.getClass().getSimpleName()) + "\"";
    }

    /** Время суток, свет, эффекты, броня, опыт: данные для решений модели. Каждое поле необязательное. */
    private static String statusExtra(Minecraft mc, LocalPlayer p) {
        StringBuilder sb = new StringBuilder();
        try {
            long t = (Long) call(mc.level, "getDayTime");
            long tod = ((t % 24000) + 24000) % 24000;
            sb.append(",\"time_of_day\":").append(tod).append(",\"is_night\":").append(tod >= 13000 && tod <= 23000);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // нет метода
        }
        try {
            sb.append(",\"light\":").append(call(mc.level, "getMaxLocalRawBrightness", p.blockPosition()));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // нет метода
        }
        try {
            sb.append(",\"raining\":").append(call(mc.level, "isRaining"));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // нет метода
        }
        sb.append(",\"on_ground\":").append(p.onGround())
          .append(",\"in_water\":").append(p.isInWater())
          .append(",\"using_item\":").append(p.isUsingItem())
          .append(",\"xp_level\":").append(p.experienceLevel);
        try {
            StringBuilder ef = new StringBuilder();
            for (Object e : (java.util.Collection<?>) call(p, "getActiveEffects")) {
                if (ef.length() > 0) ef.append(';');
                ef.append(String.valueOf(e));
            }
            sb.append(",\"effects\":\"").append(escape(ef.toString())).append('"');
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // нет метода
        }
        try {
            StringBuilder ar = new StringBuilder();
            AbstractContainerMenu im = p.inventoryMenu;
            for (int i = 5; i <= 8 && i < im.getItems().size(); i++) {
                ItemStack st = (ItemStack) im.getItems().get(i);
                if (ar.length() > 0) ar.append(',');
                ar.append(st.isEmpty() ? "-" : shortId(String.valueOf(BuiltInRegistries.ITEM.getKey(st.getItem()))));
            }
            sb.append(",\"armor\":\"").append(escape(ar.toString())).append('"');
        } catch (RuntimeException ignored) {
            // нет доступа к инвентарному меню
        }
        return sb.toString();
    }

    private static String status() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return "{\"inWorld\":false,\"screen\":" + screenJson(mc) + "}";
        }
        return "{\"inWorld\":true"
                + ",\"screen\":" + screenJson(mc)
                + ",\"menu\":\"" + escape(p.containerMenu.getClass().getSimpleName()) + "\""
                + ",\"dimension\":\"" + escape(String.valueOf(mc.level.dimension())) + "\""
                + ",\"health\":" + round(p.getHealth())
                + ",\"food\":" + p.getFoodData().getFoodLevel()
                + ",\"x\":" + round(p.getX()) + ",\"y\":" + round(p.getY()) + ",\"z\":" + round(p.getZ())
                + statusExtra(mc, p)
                + ",\"job\":" + jobJson() + "}";
    }

    // ---------------------------------------------------------------- поиск

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    /** Поиск блоков или сущностей по подстроке id. Сортировка по расстоянию. */
    private static String find(String kind, String query, int r, int max) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null) {
            return "{\"inWorld\":false}";
        }
        String q = shortId(query.toLowerCase(Locale.ROOT));
        boolean exact = q.startsWith("=");
        if (exact) q = q.substring(1);
        BlockPos base = p.blockPosition();
        StringBuilder sb = new StringBuilder("{\"kind\":\"" + escape(kind) + "\",\"query\":\"" + escape(q) + "\",\"results\":[");
        if (kind.equals("entity") || kind.equals("item")) {
            AABB box = p.getBoundingBox().inflate(r);
            List<double[]> found = new ArrayList<>();
            List<String> names = new ArrayList<>();
            if (kind.equals("item")) {
                for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
                    String id = shortId(String.valueOf(BuiltInRegistries.ITEM.getKey(ie.getItem().getItem())));
                    if (!q.isEmpty() && !id.contains(q)) continue;
                    found.add(new double[]{ie.getX(), ie.getY(), ie.getZ(), ie.getItem().getCount()});
                    names.add(id);
                }
            } else {
                for (Entity e : level.getEntitiesOfClass(Entity.class, box)) {
                    if (e == p) continue;
                    String id = shortId(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())));
                    if (!q.isEmpty() && !id.contains(q)) continue;
                    found.add(new double[]{e.getX(), e.getY(), e.getZ(), 1});
                    names.add(id);
                }
            }
            Integer[] order = new Integer[found.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Double.compare(dist2(found.get(a), p), dist2(found.get(b), p)));
            for (int k = 0; k < Math.min(max, order.length); k++) {
                double[] f = found.get(order[k]);
                if (k > 0) sb.append(',');
                sb.append("{\"id\":\"").append(escape(names.get(order[k]))).append("\",\"x\":").append(round(f[0]))
                  .append(",\"y\":").append(round(f[1])).append(",\"z\":").append(round(f[2]))
                  .append(",\"count\":").append((int) f[3])
                  .append(",\"distance\":").append(round(Math.sqrt(dist2(f, p)))).append('}');
            }
            sb.append("],\"total\":").append(found.size()).append('}');
            return sb.toString();
        }
        // блоки: сканируем куб радиуса r
        List<int[]> hits = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    mp.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    BlockState st = level.getBlockState(mp);
                    if (st.isAir()) continue;
                    String id = shortId(blockId(st));
                    if (exact ? !id.equals(q) : !id.contains(q)) continue;
                    boolean exposed = false;
                    for (Direction d : Direction.values()) {
                        if (level.getBlockState(mp.relative(d)).isAir()) { exposed = true; break; }
                    }
                    hits.add(new int[]{mp.getX(), mp.getY(), mp.getZ(), exposed ? 1 : 0});
                    ids.add(id);
                }
            }
        }
        Integer[] order = new Integer[hits.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(blockDist2(hits.get(a), p), blockDist2(hits.get(b), p)));
        for (int k = 0; k < Math.min(max, order.length); k++) {
            int[] h = hits.get(order[k]);
            if (k > 0) sb.append(',');
            sb.append("{\"id\":\"").append(escape(ids.get(order[k]))).append("\",\"x\":").append(h[0])
              .append(",\"y\":").append(h[1]).append(",\"z\":").append(h[2])
              .append(",\"exposed\":").append(h[3] == 1)
              .append(",\"distance\":").append(round(Math.sqrt(blockDist2(h, p)))).append('}');
        }
        sb.append("],\"total\":").append(hits.size()).append('}');
        return sb.toString();
    }

    private static double dist2(double[] f, LocalPlayer p) {
        double dx = f[0] - p.getX(), dy = f[1] - p.getY(), dz = f[2] - p.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double blockDist2(int[] h, LocalPlayer p) {
        double dx = h[0] + 0.5 - p.getX(), dy = h[1] + 0.5 - p.getY(), dz = h[2] + 0.5 - p.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Компактная сводка: сколько блоков каждого типа и где ближайший; руды, жидкости и станки всегда в списке. */
    private static String surroundings(int r) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null) {
            return "{\"inWorld\":false}";
        }
        BlockPos base = p.blockPosition();
        Map<String, int[]> agg = new HashMap<>(); // id -> {count, nx, ny, nz, bestDist2}
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    mp.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    BlockState st = level.getBlockState(mp);
                    if (st.isAir()) continue;
                    String id = shortId(blockId(st));
                    int d2 = dx * dx + dy * dy + dz * dz;
                    int[] a = agg.get(id);
                    if (a == null) {
                        agg.put(id, new int[]{1, mp.getX(), mp.getY(), mp.getZ(), d2});
                    } else {
                        a[0]++;
                        if (d2 < a[4]) { a[1] = mp.getX(); a[2] = mp.getY(); a[3] = mp.getZ(); a[4] = d2; }
                    }
                }
            }
        }
        List<Map.Entry<String, int[]>> list = new ArrayList<>(agg.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder sb = new StringBuilder("{\"center\":[" + base.getX() + "," + base.getY() + "," + base.getZ() + "],\"blocks\":{");
        boolean first = true;
        int shown = 0;
        for (Map.Entry<String, int[]> e : list) {
            String id = e.getKey();
            boolean notable = id.contains("ore") || id.contains("lava") || id.contains("water") || id.contains("table")
                    || id.contains("furnace") || id.contains("chest") || id.contains("log") || id.contains("portal")
                    || id.contains("diamond") || id.contains("spawner");
            if (!notable && shown >= 25) continue;
            if (!notable) shown++;
            int[] a = e.getValue();
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(id)).append("\":{\"n\":").append(a[0])
              .append(",\"nearest\":[").append(a[1]).append(',').append(a[2]).append(',').append(a[3]).append("]}");
        }
        sb.append("}}");
        return sb.toString();
    }

    // ---------------------------------------------------------------- инструмент и меню

    private static int bestToolSlot(LocalPlayer p, BlockState st) {
        // Почти сломанные инструменты пропускаем; при равной скорости предпочитаем предмет без прочности
        // (рука/блок), чтобы не тратить меч или кирку на блок, который они не ускоряют.
        int selected = p.getInventory().getSelectedSlot();
        int best = -1;
        float bestSpeed = 0f;
        boolean bestDamageable = true;
        for (int k = 0; k < 9; k++) {
            int i = (selected + k) % 9;
            ItemStack s = p.getInventory().getItem(i);
            boolean damageable = !s.isEmpty() && s.isDamageableItem();
            if (damageable) {
                int left = s.getMaxDamage() - s.getDamageValue();
                if (left <= Math.max(8, s.getMaxDamage() / 10)) continue;
            }
            float sp = s.getDestroySpeed(st);
            boolean better = best < 0 || sp > bestSpeed + 0.01f
                    || (Math.abs(sp - bestSpeed) <= 0.01f && bestDamageable && !damageable);
            if (better) {
                best = i;
                bestSpeed = sp;
                bestDamageable = damageable;
            }
        }
        return best < 0 ? selected : best;
    }

    private static String selectToolJson(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "{\"ok\":false}";
        BlockState st = mc.level.getBlockState(new BlockPos(x, y, z));
        int slot = bestToolSlot(p, st);
        p.getInventory().setSelectedSlot(slot);
        return "{\"ok\":true,\"slot\":" + slot + "}";
    }

    private static String menuJson() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "{\"inWorld\":false}";
        AbstractContainerMenu menu = p.containerMenu;
        StringBuilder sb = new StringBuilder("{\"type\":\"" + escape(menu.getClass().getSimpleName()) + "\",\"id\":" + menu.containerId + ",\"slots\":[");
        boolean first = true;
        int size = menu.getItems().size();
        for (int i = 0; i < size; i++) {
            ItemStack st = (ItemStack) menu.getItems().get(i);
            if (st.isEmpty()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"slot\":").append(i).append(",\"item\":\"")
              .append(escape(shortId(String.valueOf(BuiltInRegistries.ITEM.getKey(st.getItem()))))).append("\",\"count\":")
              .append(st.getCount()).append('}');
        }
        sb.append("],\"size\":").append(size).append('}');
        return sb.toString();
    }

    private static String clickJson(int slot, int button, String type) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return "{\"ok\":false,\"error\":\"нет игрока\"}";
        ContainerInput ci;
        try {
            ci = ContainerInput.valueOf(type == null || type.isEmpty() ? "PICKUP" : type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "{\"ok\":false,\"error\":\"неизвестный тип клика: " + escape(type) + "\"}";
        }
        handleClick(mc, p, p.containerMenu.containerId, slot, button, ci);
        return "{\"ok\":true}";
    }


    // ---------------------------------------------------------------- рефлексивные вызовы и утилиты

    private static Class<?> wrapPrim(Class<?> c) {
        if (c == boolean.class) return Boolean.class;
        if (c == int.class) return Integer.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == long.class) return Long.class;
        return c;
    }

    /** Вызов метода по имени и числу аргументов (имена методов Minecraft могут отличаться между версиями). */
    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            Class<?>[] ps = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < ps.length; i++) {
                if (args[i] == null) continue;
                if (!wrapPrim(ps[i]).isAssignableFrom(args[i].getClass())) { ok = false; break; }
            }
            if (ok) return m.invoke(target, args);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);
    }

    /** Анимация замаха: только визуальная; сигнатура swing менялась между версиями, поэтому через рефлексию. */
    private static void swingHand(LocalPlayer p) {
        try {
            call(p, "swing", InteractionHand.MAIN_HAND);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // замах не нужен для работы пакетов атаки и использования
        }
    }

    private static boolean flag(String json, String name) {
        return json.contains("\"" + name + "\":true") || json.contains("\"" + name + "\": true");
    }

    private static Direction faceOf(String name) {
        if (name == null) return Direction.UP;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "down" -> Direction.DOWN;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> Direction.UP;
        };
    }

    private static Job applyStops(Job j, String body) {
        j.stopHealth = num(body, "stop_health");
        j.stopHostile = num(body, "stop_hostile");
        if (j.stopHealth <= 0) j.stopHealth = -1;
        if (j.stopHostile <= 0) j.stopHostile = -1;
        return j;
    }

    /** Условия остановки задачи, заданные вызывающим. null — продолжать. */
    private static String checkStops(Minecraft mc, LocalPlayer p, Job j) {
        if (j.stopHealth > 0 && p.getHealth() <= j.stopHealth) {
            return "здоровье " + round(p.getHealth()) + " <= " + round(j.stopHealth);
        }
        if (j.stopHostile > 0 && !"attack".equals(j.type)) {
            AABB box = p.getBoundingBox().inflate(j.stopHostile);
            for (Monster m : mc.level.getEntitiesOfClass(Monster.class, box)) {
                double d = Math.sqrt(p.distanceToSqr(m));
                if (d <= j.stopHostile) {
                    return "враждебный моб " + shortId(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()))) + " в " + round(d) + " м (id " + m.getId() + ")";
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- события

    private static final List<String> EVENTS = new ArrayList<>();
    private static final java.util.Set<Integer> SEEN_MOBS = new java.util.HashSet<>();
    private static float lastHealth = -1;
    private static boolean deathReported = false;

    private static void addEvent(String e) {
        synchronized (EVENTS) {
            if (EVENTS.size() >= 60) EVENTS.remove(0);
            EVENTS.add(e);
        }
    }

    /** Копит события, которые модель читает через /alerts: урон, новые враждебные мобы рядом, смерть. */
    private static void collectEvents(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        float hp = p.getHealth();
        if (lastHealth >= 0 && hp < lastHealth - 0.01f) {
            addEvent("урон " + round(lastHealth - hp) + ", здоровье " + round(hp));
        }
        lastHealth = hp;
        if (hp <= 0 || p.isDeadOrDying()) {
            if (!deathReported) { deathReported = true; addEvent("СМЕРТЬ: нужен /respawn"); }
        } else {
            deathReported = false;
        }
        if (mc.level.getGameTime() % 10 != 0) return;
        AABB box = p.getBoundingBox().inflate(10);
        java.util.Set<Integer> now = new java.util.HashSet<>();
        for (Monster m : mc.level.getEntitiesOfClass(Monster.class, box)) {
            now.add(m.getId());
            if (SEEN_MOBS.add(m.getId())) {
                addEvent("враждебный моб " + shortId(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType())))
                        + " id " + m.getId() + " в " + round(Math.sqrt(p.distanceToSqr(m))) + " м");
            }
        }
        SEEN_MOBS.retainAll(now);
    }

    // ---------------------------------------------------------------- сущности и действия

    private static String entitiesAll(int r) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null) return "{\"inWorld\":false}";
        AABB box = p.getBoundingBox().inflate(r);
        List<Entity> list = new ArrayList<>();
        for (Entity e : level.getEntitiesOfClass(Entity.class, box)) {
            if (e != p) list.add(e);
        }
        list.sort((a, b) -> Double.compare(p.distanceToSqr(a), p.distanceToSqr(b)));
        StringBuilder sb = new StringBuilder("{\"entities\":[");
        int n = 0;
        for (Entity e : list) {
            if (n >= 60) break;
            if (n++ > 0) sb.append(',');
            sb.append("{\"id\":").append(e.getId())
              .append(",\"type\":\"").append(escape(shortId(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()))))).append('"')
              .append(",\"name\":\"").append(escape(e.getName().getString())).append('"')
              .append(",\"x\":").append(round(e.getX())).append(",\"y\":").append(round(e.getY())).append(",\"z\":").append(round(e.getZ()))
              .append(",\"distance\":").append(round(Math.sqrt(p.distanceToSqr(e))))
              .append(",\"hostile\":").append(e instanceof Monster);
            if (e instanceof net.minecraft.world.entity.LivingEntity le) {
                sb.append(",\"health\":").append(round(le.getHealth())).append(",\"max_health\":").append(round(le.getMaxHealth()))
                  .append(",\"baby\":").append(le.isBaby());
                ItemStack held = le.getMainHandItem();
                if (!held.isEmpty()) {
                    sb.append(",\"holds\":\"").append(escape(shortId(String.valueOf(BuiltInRegistries.ITEM.getKey(held.getItem()))))).append('"');
                }
            }
            if (e instanceof ItemEntity ie) {
                sb.append(",\"item\":\"").append(escape(shortId(String.valueOf(BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()))))).append("\",\"count\":").append(ie.getItem().getCount());
            }
            sb.append('}');
        }
        sb.append("],\"total\":").append(list.size()).append('}');
        return sb.toString();
    }

    private static void tickAttack(Minecraft mc, LocalPlayer p, Job j) {
        Entity e = mc.level.getEntity(j.entityId);
        if (e == null || !e.isAlive()) {
            j.info = "цель мертва или исчезла, ударов: " + j.hitsDone;
            finish(j, "done", null);
            return;
        }
        double dist = Math.sqrt(p.distanceToSqr(e));
        lookAtPoint(p, e.getX(), e.getY() + e.getBbHeight() * 0.6, e.getZ());
        if (dist > 3.0) {
            if (!j.chase) {
                finish(j, "failed", "цель далеко (" + round(dist) + " м), подойди или chase=true");
                return;
            }
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(dist > 5);
            BlockPos ahead = BlockPos.containing(p.getX() + Math.sin(-Math.toRadians(p.getYRot())) * 0.9, p.getY(), p.getZ() + Math.cos(Math.toRadians(p.getYRot())) * 0.9);
            mc.options.keyJump.setDown(p.onGround() && !passable(mc.level, ahead));
            return;
        }
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        if (p.getAttackStrengthScale(0.5f) >= 0.95f) {
            try {
                call(mc.gameMode, "attack", p, e);
                swingHand(p);
                j.hitsDone++;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                finish(j, "failed", "атака недоступна: " + ex);
                return;
            }
            if (j.hitsDone >= j.hitsWanted) {
                j.info = "ударов: " + j.hitsDone + (e.isAlive() ? ", цель жива" : ", цель убита");
                finish(j, "done", null);
            }
        }
    }

    private static void tickUse(Minecraft mc, LocalPlayer p, Job j) {
        if (j.ticks == 0) {
            if (j.slot >= 0 && j.slot <= 8) p.getInventory().setSelectedSlot(j.slot);
            ItemStack held = p.getInventory().getItem(p.getInventory().getSelectedSlot());
            if (held.isEmpty()) {
                finish(j, "failed", "в выбранном слоте пусто");
                return;
            }
            try {
                call(mc.gameMode, "useItem", p, InteractionHand.MAIN_HAND);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                finish(j, "failed", "использование недоступно: " + ex);
                return;
            }
            j.ticks = 1;
            if (!p.isUsingItem() && j.holdTicks == 0) {
                finish(j, "done", null);
            }
            return;
        }
        j.ticks++;
        boolean using = p.isUsingItem();
        if (j.holdTicks > 0 && j.ticks > j.holdTicks) {
            if (j.release && using) {
                try {
                    call(mc.gameMode, "releaseUsingItem", p);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    finish(j, "failed", "отпускание недоступно: " + ex);
                    return;
                }
            }
            finish(j, "done", null);
            return;
        }
        if (!using && j.ticks > 3) {
            finish(j, "done", null);
        }
    }

    private static String interactBlock(int x, int y, int z, Direction f, boolean sneak) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "{\"ok\":false,\"error\":\"нет игрока\"}";
        BlockPos pos = new BlockPos(x, y, z);
        if (mc.level.getBlockState(pos).isAir()) return "{\"ok\":false,\"error\":\"в этой точке воздух\"}";
        double cx = x + 0.5 + f.getStepX() * 0.5, cy = y + 0.5 + f.getStepY() * 0.5, cz = z + 0.5 + f.getStepZ() * 0.5;
        double dist = Math.sqrt(p.distanceToSqr(cx, cy, cz));
        if (dist > 5.0) return "{\"ok\":false,\"error\":\"слишком далеко (" + round(dist) + " м)\"}";
        lookAtPoint(p, cx, cy, cz);
        try {
            BlockHitResult bhr = new BlockHitResult(new Vec3(cx, cy, cz), f, pos, false);
            Object r = call(mc.gameMode, "useItemOn", p, InteractionHand.MAIN_HAND, bhr);
            swingHand(p);
            return "{\"ok\":true,\"result\":\"" + escape(String.valueOf(r)) + "\",\"block\":\"" + escape(shortId(blockId(mc.level.getBlockState(pos)))) + "\"}";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "{\"ok\":false,\"error\":\"" + escape(String.valueOf(e)) + "\"}";
        }
    }

    private static String interactEntity(int id) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "{\"ok\":false,\"error\":\"нет игрока\"}";
        Entity e = mc.level.getEntity(id);
        if (e == null) return "{\"ok\":false,\"error\":\"нет сущности с id " + id + "\"}";
        double dist = Math.sqrt(p.distanceToSqr(e));
        if (dist > 4.0) return "{\"ok\":false,\"error\":\"слишком далеко (" + round(dist) + " м), подойди\"}";
        lookAtPoint(p, e.getX(), e.getY() + e.getBbHeight() * 0.6, e.getZ());
        try {
            Object r = call(mc.gameMode, "interact", p, e, InteractionHand.MAIN_HAND);
            swingHand(p);
            return "{\"ok\":true,\"result\":\"" + escape(String.valueOf(r)) + "\"}";
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return "{\"ok\":false,\"error\":\"" + escape(String.valueOf(ex)) + "\"}";
        }
    }

    private static String blockInfo(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (mc.player == null || level == null) return "{\"inWorld\":false}";
        BlockPos pos = new BlockPos(x, y, z);
        BlockState st = level.getBlockState(pos);
        String raw = String.valueOf(st);
        int b0 = raw.indexOf('[');
        String props = (b0 >= 0 && raw.endsWith("]")) ? raw.substring(b0 + 1, raw.length() - 1) : "";
        StringBuilder sb = new StringBuilder("{\"id\":\"" + escape(shortId(blockId(st))) + "\",\"properties\":{");
        if (!props.isEmpty()) {
            boolean first = true;
            for (String kv : props.split(",")) {
                int eq = kv.indexOf('=');
                if (eq < 0) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(kv.substring(0, eq))).append("\":\"").append(escape(kv.substring(eq + 1))).append('"');
            }
        }
        sb.append("}");
        try {
            sb.append(",\"light\":").append(call(level, "getMaxLocalRawBrightness", pos));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // нет метода в этой версии
        }
        try {
            sb.append(",\"sky_visible\":").append(call(level, "canSeeSky", pos));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // нет метода в этой версии
        }
        try {
            sb.append(",\"hardness\":").append(call(st, "getDestroySpeed", level, pos));
            sb.append(",\"needs_tool\":").append(call(st, "requiresCorrectToolForDrops"));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // нет метода в этой версии
        }
        sb.append("}");
        return sb.toString();
    }

    private static String chat(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || text == null || text.isEmpty()) return "{\"ok\":false}";
        try {
            Object conn = mc.getConnection();
            if (text.startsWith("/")) {
                call(conn, "sendCommand", text.substring(1));
            } else {
                call(conn, "sendChat", text);
            }
            return "{\"ok\":true}";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "{\"ok\":false,\"error\":\"" + escape(String.valueOf(e)) + "\"}";
        }
    }

    // ---------------------------------------------------------------- поиск пути (A*)

    private static boolean passable(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) return true;
        if (!st.getFluidState().isEmpty()) return false;
        return st.getCollisionShape(level, pos).isEmpty() && !dangerous(blockId(st));
    }

    private static boolean dangerous(String id) {
        return id.contains("lava") || id.contains("fire") || id.contains("cactus") || id.contains("berry_bush")
                || id.contains("magma") || id.contains("campfire") || id.contains("cobweb") || id.contains("powder_snow");
    }

    private static boolean solidFloor(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.isAir() || !st.getFluidState().isEmpty()) return false;
        return !st.getCollisionShape(level, pos).isEmpty() && !dangerous(blockId(st));
    }

    /** Можно ли стоять ногами в этой клетке. */
    private static boolean standable(Level level, BlockPos feet) {
        return passable(level, feet) && passable(level, feet.above()) && solidFloor(level, feet.below());
    }

    private record Node(BlockPos pos, double g, double f, Node parent) {}

    /** A* по клеткам, где стоят ноги: шаг, подъём на 1, спуск до 4. Возвращает путь или null. */
    private static List<BlockPos> findPath(Level level, BlockPos start, BlockPos goal, int maxNodes) {
        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<BlockPos, Double> best = new HashMap<>();
        open.add(new Node(start, 0, heuristic(start, goal), null));
        best.put(start, 0.0);
        Node closest = null;
        double closestH = Double.MAX_VALUE;
        int expanded = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty() && expanded < maxNodes) {
            Node cur = open.poll();
            if (cur.g() > best.getOrDefault(cur.pos(), Double.MAX_VALUE)) continue;
            expanded++;
            double h = heuristic(cur.pos(), goal);
            if (h < closestH) { closestH = h; closest = cur; }
            boolean anyY = goal.getY() < -1000;
            if (cur.pos().getX() == goal.getX() && cur.pos().getZ() == goal.getZ() && (anyY || Math.abs(cur.pos().getY() - goal.getY()) <= 1)) {
                return rebuild(cur);
            }
            for (int[] d : dirs) {
                BlockPos side = cur.pos().offset(d[0], 0, d[1]);
                if (Math.abs(side.getX() - start.getX()) > 80 || Math.abs(side.getZ() - start.getZ()) > 80) continue;
                // ровно
                if (standable(level, side)) {
                    relax(open, best, cur, side, 1.0, goal);
                    continue;
                }
                // подъём на 1 блок: над головой текущей клетки должно быть свободно
                BlockPos up = side.above();
                if (passable(level, cur.pos().above(2)) && standable(level, up) ) {
                    relax(open, best, cur, up, 1.6, goal);
                    continue;
                }
                // спуск: проходим сбоку и падаем до опоры
                if (passable(level, side) && passable(level, side.above())) {
                    for (int drop = 1; drop <= 4; drop++) {
                        BlockPos lower = side.below(drop);
                        if (!passable(level, lower)) break;
                        if (standable(level, lower)) {
                            relax(open, best, cur, lower, 1.0 + drop * 0.7, goal);
                            break;
                        }
                    }
                }
            }
        }
        if (closest != null && closestH <= 2.5) {
            return rebuild(closest);
        }
        return null;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ(), dy = b.getY() < -1000 ? 0 : Math.abs(a.getY() - b.getY());
        return Math.sqrt(dx * dx + dz * dz) + dy * 0.5;
    }

    private static void relax(java.util.PriorityQueue<Node> open, Map<BlockPos, Double> best, Node cur, BlockPos next, double cost, BlockPos goal) {
        double g = cur.g() + cost;
        if (g < best.getOrDefault(next, Double.MAX_VALUE)) {
            best.put(next, g);
            open.add(new Node(next, g, g + heuristic(next, goal), cur));
        }
    }

    private static List<BlockPos> rebuild(Node end) {
        List<BlockPos> out = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent()) out.add(0, n.pos());
        return out;
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
            result = future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            result = "{\"error\":\"игра не ответила вовремя (окно свёрнуто или экран загрузки?)\"}";
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
        sb.append("],\"truncated\":false,\"items\":[");
        AABB box = new AABB(base).inflate(r + 0.5);
        boolean firstItem = true;
        for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
            ItemStack st = ie.getItem();
            if (!firstItem) sb.append(',');
            firstItem = false;
            sb.append("{\"x\":").append(round(ie.getX()))
              .append(",\"y\":").append(round(ie.getY()))
              .append(",\"z\":").append(round(ie.getZ()))
              .append(",\"item\":\"").append(escape(String.valueOf(BuiltInRegistries.ITEM.getKey(st.getItem()))))
              .append("\",\"count\":").append(st.getCount()).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String entities(int r) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return "{\"inWorld\":false}";
        }
        AABB box = p.getBoundingBox().inflate(r);
        StringBuilder sb = new StringBuilder("{\"hostile\":[");
        boolean first = true;
        for (Monster m : mc.level.getEntitiesOfClass(Monster.class, box)) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"type\":\"").append(escape(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()))))
              .append("\",\"x\":").append(round(m.getX()))
              .append(",\"y\":").append(round(m.getY()))
              .append(",\"z\":").append(round(m.getZ()))
              .append(",\"health\":").append(round(m.getHealth())).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Рецепт по шаблону: строки сетки, P=любые доски, L=любое бревно, S=палка, C=булыжник, _=пусто. */
    private record CraftRecipe(String item, int yield, String[] rows) {}

    private static final CraftRecipe[] RECIPES = {
        new CraftRecipe("planks", 4, new String[]{"L"}),
        new CraftRecipe("minecraft:stick", 4, new String[]{"P", "P"}),
        new CraftRecipe("minecraft:crafting_table", 1, new String[]{"PP", "PP"}),
        new CraftRecipe("minecraft:wooden_pickaxe", 1, new String[]{"PPP", "_S_", "_S_"}),
        new CraftRecipe("minecraft:wooden_axe", 1, new String[]{"PP", "PS", "_S"}),
        new CraftRecipe("minecraft:wooden_shovel", 1, new String[]{"P", "S", "S"}),
        new CraftRecipe("minecraft:wooden_sword", 1, new String[]{"P", "P", "S"}),
        new CraftRecipe("minecraft:wooden_hoe", 1, new String[]{"PP", "_S", "_S"}),
        new CraftRecipe("minecraft:stone_pickaxe", 1, new String[]{"CCC", "_S_", "_S_"}),
        new CraftRecipe("minecraft:stone_axe", 1, new String[]{"CC", "CS", "_S"}),
        new CraftRecipe("minecraft:stone_shovel", 1, new String[]{"C", "S", "S"}),
        new CraftRecipe("minecraft:stone_sword", 1, new String[]{"C", "C", "S"}),
    };

    /** Крафт через клики по слотам открытого меню (инвентарь 2x2 или верстак 3x3). */
    private static String craft(String itemId, int times) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return "{\"ok\":false,\"error\":\"нет игрока в мире\"}";
        }
        CraftRecipe rec = null;
        if (itemId.endsWith("_planks")) {
            rec = RECIPES[0];
        } else {
            for (CraftRecipe r : RECIPES) {
                if (r.item().equals(itemId)) { rec = r; break; }
            }
        }
        if (rec == null) {
            return "{\"ok\":false,\"error\":\"нет шаблона рецепта для " + escape(itemId) + "\"}";
        }
        AbstractContainerMenu menu = p.containerMenu;
        int w;
        int h;
        if (menu instanceof CraftingMenu cm) {
            w = cm.getGridWidth();
            h = cm.getGridHeight();
        } else if (menu instanceof InventoryMenu im) {
            w = im.getGridWidth();
            h = im.getGridHeight();
        } else {
            return "{\"ok\":false,\"error\":\"открытый экран не поддерживает крафт\"}";
        }
        int rh = rec.rows().length;
        int rw = rec.rows()[0].length();
        if (rw > w || rh > h) {
            return "{\"ok\":false,\"error\":\"нужен верстак (сетка 3x3) для " + escape(itemId) + "\"}";
        }
        if (!menu.getCarried().isEmpty()) {
            return "{\"ok\":false,\"error\":\"в руке предмет, положи его в инвентарь\"}";
        }
        // Для досок берём конкретное дерево из id, например minecraft:spruce_planks -> spruce.
        String wood = itemId.endsWith("_planks") ? itemId.substring("minecraft:".length(), itemId.length() - "_planks".length()) : null;
        int playerStart = (w == 2) ? 9 : 10;
        int crafted = 0;
        String error = null;
        for (int t = 0; t < times; t++) {
            error = craftOnce(mc, p, menu, rec, w, playerStart, wood);
            if (error != null) break;
            crafted++;
        }
        return "{\"ok\":" + (crafted > 0) + ",\"crafted\":" + (crafted * rec.yield())
                + ",\"item\":\"" + escape(itemId) + "\""
                + (error != null ? ",\"error\":\"" + escape(error) + "\"" : "") + "}";
    }

    private static String craftOnce(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu, CraftRecipe rec,
                                    int w, int playerStart, String wood) {
        int menuId = menu.containerId;
        String[] rows = rec.rows();
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                char ch = rows[r].charAt(c);
                if (ch == '_') continue;
                int src = findSource(menu, playerStart, ch, wood);
                if (src < 0) {
                    return "не хватает ингредиента: " + describe(ch, wood);
                }
                int grid = 1 + r * w + c;
                // Берём стак, кладём один предмет в ячейку правой кнопкой, остаток возвращаем.
                handleClick(mc, p, menuId, src, 0, ContainerInput.PICKUP);
                handleClick(mc, p, menuId, grid, 1, ContainerInput.PICKUP);
                handleClick(mc, p, menuId, src, 0, ContainerInput.PICKUP);
            }
        }
        // Забираем результат (слот 0) в инвентарь.
        handleClick(mc, p, menuId, 0, 0, ContainerInput.QUICK_MOVE);
        return null;
    }

    private static void handleClick(Minecraft mc, LocalPlayer p, int menuId, int slot, int button, ContainerInput type) {
        mc.gameMode.handleContainerInput(menuId, slot, button, type, p);
    }

    private static int findSource(AbstractContainerMenu menu, int playerStart, char ch, String wood) {
        int size = menu.getItems().size();
        for (int i = playerStart; i < size; i++) {
            ItemStack st = (ItemStack) menu.getItems().get(i);
            if (st.isEmpty()) continue;
            String id = String.valueOf(BuiltInRegistries.ITEM.getKey(st.getItem()));
            if (matches(ch, id, wood)) return i;
        }
        return -1;
    }

    private static boolean matches(char ch, String id, String wood) {
        return switch (ch) {
            case 'L' -> wood == null ? id.endsWith("_log") : id.equals("minecraft:" + wood + "_log");
            case 'P' -> wood == null ? id.endsWith("_planks") : id.equals("minecraft:" + wood + "_planks");
            case 'S' -> id.equals("minecraft:stick");
            case 'C' -> id.equals("minecraft:cobblestone");
            default -> false;
        };
    }

    private static String describe(char ch, String wood) {
        return switch (ch) {
            case 'L' -> "бревно";
            case 'P' -> wood == null ? "доски" : "доски " + wood;
            case 'S' -> "палка";
            case 'C' -> "булыжник";
            default -> "?";
        };
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

    private static String jobJson() {
        Job j = job;
        if (j == null) {
            return "{\"status\":\"idle\"}";
        }
        return "{\"id\":" + j.id + ",\"status\":\"" + j.status + "\",\"type\":\"" + j.type + "\""
                + (j.error != null ? ",\"error\":\"" + escape(j.error) + "\"" : "")
                + (j.info != null ? ",\"info\":\"" + escape(j.info) + "\"" : "") + "}";
    }

    /** Выполняется каждый клиентский тик, пока есть задача. */
    private static void tickJob(Minecraft mc) {
        Job j = job;
        if (j == null || !"running".equals(j.status)) {
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            finish(j, "failed", "нет игрока в мире");
            return;
        }
        if (System.currentTimeMillis() - j.startedMs > j.timeoutMs) {
            finish(j, "failed", "таймаут " + (j.timeoutMs / 1000) + " с");
            return;
        }
        String stop = checkStops(mc, p, j);
        if (stop != null) {
            finish(j, "interrupted", stop);
            return;
        }
        switch (j.type) {
            case "attack" -> tickAttack(mc, p, j);
            case "use" -> tickUse(mc, p, j);
            case "break" -> tickBreak(mc, p, j);
            case "goto" -> tickGoto(mc, p, j);
            case "place" -> tickPlace(mc, p, j);
            case "open" -> tickOpen(mc, p, j);
            default -> finish(j, "failed", "неизвестная задача");
        }
    }

    private static void finish(Job j, String status, String error) {
        j.status = status;
        j.error = error;
        releaseKeys(Minecraft.getInstance());
    }

    private static void releaseKeys(Minecraft mc) {
        var o = mc.options;
        o.keyAttack.setDown(false);
        o.keyUse.setDown(false);
        o.keyUp.setDown(false);
        o.keyDown.setDown(false);
        o.keyLeft.setDown(false);
        o.keyRight.setDown(false);
        o.keyJump.setDown(false);
        o.keySprint.setDown(false);
        o.keyShift.setDown(false);
    }

    private static void lookAtPoint(LocalPlayer p, double tx, double ty, double tz) {
        double dx = tx - p.getX();
        double dy = ty - p.getEyeY();
        double dz = tz - p.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        p.setYRot(yaw);
        p.setXRot(Math.max(-90f, Math.min(90f, pitch)));
    }

    private static void tickBreak(Minecraft mc, LocalPlayer p, Job j) {
        BlockPos pos = new BlockPos(j.x, j.y, j.z);
        BlockState st = mc.level.getBlockState(pos);
        if (st.isAir()) {
            finish(j, "done", null);
            return;
        }
        double dist = Math.sqrt(p.distanceToSqr(j.x + 0.5, j.y + 0.5, j.z + 0.5));
        if (dist > 4.5) {
            finish(j, "failed", "блок слишком далеко (" + round(dist) + " м), сначала goto");
            return;
        }
        if (!j.toolChosen) {
            p.getInventory().setSelectedSlot(bestToolSlot(p, st));
            j.toolChosen = true;
        }
        lookAtPoint(p, j.x + 0.5, j.y + 0.5, j.z + 0.5);
        HitResult hr = mc.hitResult;
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK && !bhr.getBlockPos().equals(pos)) {
            j.occludedTicks++;
            if (j.occludedTicks > 10) {
                BlockPos o = bhr.getBlockPos();
                finish(j, "failed", "блок закрыт другим блоком: " + shortId(blockId(mc.level.getBlockState(o)))
                        + " на (" + o.getX() + "," + o.getY() + "," + o.getZ() + ")");
                return;
            }
            mc.options.keyAttack.setDown(false);
            return;
        }
        j.occludedTicks = 0;
        Direction face = (hr instanceof BlockHitResult hb && hr.getType() == HitResult.Type.BLOCK) ? hb.getDirection() : Direction.UP;
        if (!destroyDirect(mc, p, pos, face, j)) {
            // запасной путь: клавиша атаки (работает только при активном окне игры)
            mc.options.keyAttack.setDown(true);
        }
    }

    private static java.lang.reflect.Method destroyStart = null;
    private static java.lang.reflect.Method destroyContinue = null;
    private static boolean destroyLookupDone = false;

    /** Ломает блок напрямую через MultiPlayerGameMode, не завися от фокуса окна. Возвращает false, если методов нет. */
    private static boolean destroyDirect(Minecraft mc, LocalPlayer p, BlockPos pos, Direction face, Job j) {
        try {
            if (!destroyLookupDone) {
                destroyLookupDone = true;
                Class<?> gm = mc.gameMode.getClass();
                destroyStart = gm.getMethod("startDestroyBlock", BlockPos.class, Direction.class);
                destroyContinue = gm.getMethod("continueDestroyBlock", BlockPos.class, Direction.class);
            }
            if (destroyStart == null || destroyContinue == null) return false;
            mc.options.keyAttack.setDown(false);
            if (!j.destroyStarted) {
                destroyStart.invoke(mc.gameMode, pos, face);
                j.destroyStarted = true;
            } else {
                destroyContinue.invoke(mc.gameMode, pos, face);
            }
            swingHand(p);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            destroyStart = null;
            destroyContinue = null;
            System.err.println("[agent] прямой слом блока недоступен: " + e);
            return false;
        }
    }

    private static void tickGoto(Minecraft mc, LocalPlayer p, Job j) {
        long now = System.currentTimeMillis();
        if (j.path == null) {
            BlockPos start = p.blockPosition();
            BlockPos goal = new BlockPos(j.x, j.y, j.z);
            List<BlockPos> path = findPath(mc.level, start, goal, 9000);
            if (path == null) {
                finish(j, "failed", "цель недостижима: нет пути по загруженным блокам (препятствие, вода, лава или далеко)");
                return;
            }
            j.path = path;
            j.idx = path.size() > 1 ? 1 : 0;
            j.lastCheckMs = now;
            j.lastX = p.getX();
            j.lastZ = p.getZ();
            BlockPos end = path.get(path.size() - 1);
            j.info = (end.getX() == goal.getX() && end.getZ() == goal.getZ())
                    ? "путь: " + path.size() + " клеток"
                    : "дошли до ближайшей точки (" + end.getX() + "," + end.getY() + "," + end.getZ() + "), путь " + path.size();
        }
        if (j.idx >= j.path.size()) {
            finish(j, "done", null);
            return;
        }
        BlockPos wp = j.path.get(j.idx);
        double tx = wp.getX() + 0.5, tz = wp.getZ() + 0.5;
        double dx = tx - p.getX(), dz = tz - p.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        boolean last = j.idx == j.path.size() - 1;
        if (horiz < (last ? 0.35 : 0.5) && Math.abs(p.getY() - wp.getY()) < 1.3) {
            j.idx++;
            if (j.idx >= j.path.size()) {
                finish(j, "done", null);
            }
            return;
        }
        // проверка застревания раз в 1.5 с
        if (now - j.lastCheckMs > 1500) {
            double moved = Math.sqrt((p.getX() - j.lastX) * (p.getX() - j.lastX) + (p.getZ() - j.lastZ) * (p.getZ() - j.lastZ));
            j.lastCheckMs = now;
            j.lastX = p.getX();
            j.lastZ = p.getZ();
            if (moved < 0.2 && p.onGround()) {
                j.replans++;
                if (j.replans > 3) {
                    finish(j, "failed", "застрял на (" + round(p.getX()) + "," + round(p.getY()) + "," + round(p.getZ()) + ")");
                    return;
                }
                j.path = null;
                releaseKeys(mc);
                return;
            }
        }
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot(0f);
        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(false);
        boolean onGround = p.onGround();
        double sx = horiz > 0 ? p.getX() + dx / horiz * 0.9 : p.getX();
        double sz = horiz > 0 ? p.getZ() + dz / horiz * 0.9 : p.getZ();
        BlockPos ahead = BlockPos.containing(sx, p.getY(), sz);
        boolean wall = !passable(mc.level, ahead) && passable(mc.level, ahead.above());
        boolean higher = wp.getY() > p.getY() + 0.3;
        mc.options.keyJump.setDown(onGround && (wall || higher));
    }

    /** Открыть меню блока (верстак, печь, сундук) правой кнопкой и дождаться экрана. */
    private static void tickOpen(Minecraft mc, LocalPlayer p, Job j) {
        if (!(p.containerMenu instanceof InventoryMenu)) {
            finish(j, "done", null);
            return;
        }
        BlockPos pos = new BlockPos(j.x, j.y, j.z);
        if (mc.level.getBlockState(pos).isAir()) {
            finish(j, "failed", "в этой точке нет блока");
            return;
        }
        double dist = Math.sqrt(p.distanceToSqr(j.x + 0.5, j.y + 0.5, j.z + 0.5));
        if (dist > 4.5) {
            finish(j, "failed", "блок слишком далеко (" + round(dist) + " м), сначала goto");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - j.lastUseMs > 1000) {
            j.lastUseMs = now;
            lookAtPoint(p, j.x + 0.5, j.y + 0.5, j.z + 0.5);
            Vec3 hit = new Vec3(j.x + 0.5, j.y + 0.5, j.z + 0.5);
            BlockHitResult bhr = new BlockHitResult(hit, Direction.UP, pos, false);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhr);
        }
    }

    private static void tickPlace(Minecraft mc, LocalPlayer p, Job j) {
        BlockPos target = new BlockPos(j.x, j.y, j.z);
        BlockPos out = target.relative(j.face);
        if (j.placeTicks == 0) {
            if (mc.level.getBlockState(target).isAir()) {
                finish(j, "failed", "на этом месте нет опоры");
                return;
            }
            if (!mc.level.getBlockState(out).isAir()) {
                finish(j, "failed", "место занято: " + shortId(blockId(mc.level.getBlockState(out))));
                return;
            }
            double cx = j.x + 0.5 + j.face.getStepX() * 0.5, cy = j.y + 0.5 + j.face.getStepY() * 0.5, cz = j.z + 0.5 + j.face.getStepZ() * 0.5;
            double dist = Math.sqrt(p.distanceToSqr(cx, cy, cz));
            if (dist > 4.5) {
                finish(j, "failed", "блок слишком далеко (" + round(dist) + " м), сначала goto");
                return;
            }
            ItemStack held = p.getInventory().getItem(p.getInventory().getSelectedSlot());
            if (held.isEmpty()) {
                finish(j, "failed", "в выбранном слоте пусто, выбери блок (minecraft_slot)");
                return;
            }
            lookAtPoint(p, cx, cy, cz);
            Vec3 hit = new Vec3(cx, cy, cz);
            BlockHitResult bhr = new BlockHitResult(hit, j.face, target, false);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhr);
            swingHand(p);
            j.placeTicks = 1;
            return;
        }
        j.placeTicks++;
        BlockState now = mc.level.getBlockState(out);
        if (!now.isAir()) {
            j.info = "поставлен " + shortId(blockId(now)) + " на (" + out.getX() + "," + out.getY() + "," + out.getZ() + ")";
            finish(j, "done", null);
            return;
        }
        if (j.placeTicks > 10) {
            finish(j, "failed", "блок не поставлен (предмет не блок или нельзя ставить сюда)");
        }
    }

    /** Служебный дамп API игры (имена классов и методов для крафта). Только для разработки. */
    private static String dumpApi() {
        String[] classes = {
            "net.minecraft.client.multiplayer.MultiPlayerGameMode",
            "net.minecraft.client.multiplayer.ClientPacketListener",
            "net.minecraft.client.multiplayer.ClientLevel",
            "net.minecraft.world.level.Level",
            "net.minecraft.world.inventory.AbstractContainerMenu",
            "net.minecraft.world.inventory.CraftingMenu",
            "net.minecraft.world.inventory.ClickType",
            "net.minecraft.world.inventory.ContainerInput",
            "net.minecraft.world.inventory.InventoryMenu",
            "net.minecraft.world.item.crafting.RecipeManager",
            "net.minecraft.world.item.crafting.RecipeHolder",
            "net.minecraft.world.item.crafting.CraftingRecipe",
            "net.minecraft.world.item.crafting.Recipe",
            "net.minecraft.world.item.crafting.RecipeAccess",
            "net.minecraft.world.item.crafting.RecipeMap",
            "net.minecraft.world.item.crafting.ShapedRecipe",
            "net.minecraft.world.item.crafting.ShapelessRecipe"
        };
        java.util.regex.Pattern keep = java.util.regex.Pattern.compile(
            "(?i)craft|recipe|click|place|slot|container|menu|inventory|input|quick|pickup|result|book|manager|value|holder");
        // Эти классы выводим целиком, без фильтра по словам.
        java.util.Set<String> full = java.util.Set.of(
            "net.minecraft.world.item.crafting.Ingredient",
            "net.minecraft.world.item.crafting.PlacementInfo",
            "net.minecraft.world.item.crafting.ShapelessRecipe",
            "net.minecraft.world.item.crafting.ShapedRecipe",
            "net.minecraft.world.item.crafting.RecipeType",
            "net.minecraft.world.item.crafting.display.RecipeDisplay",
            "net.minecraft.world.item.ItemStack",
            "net.minecraft.world.item.Item");
        String[] extra = {
            "net.minecraft.world.item.crafting.Ingredient",
            "net.minecraft.world.item.crafting.PlacementInfo",
            "net.minecraft.world.item.crafting.RecipeType",
            "net.minecraft.world.item.crafting.display.RecipeDisplay",
            "net.minecraft.world.item.ItemStack",
            "net.minecraft.world.item.Item"
        };
        classes = java.util.stream.Stream.concat(java.util.Arrays.stream(classes),
                java.util.Arrays.stream(extra)).distinct().toArray(String[]::new);
        StringBuilder sb = new StringBuilder();
        for (String name : classes) {
            try {
                Class<?> c = Class.forName(name);
                sb.append("## ").append(name).append('\n');
                boolean all = full.contains(name);
                if (c.isEnum()) {
                    for (Object o : c.getEnumConstants()) sb.append("  const ").append(o).append('\n');
                }
                for (java.lang.reflect.Method m : c.getMethods()) {
                    if (!all && !keep.matcher(m.getName()).find() && !keep.matcher(c.getSimpleName()).find()) continue;
                    sb.append("  ").append(m.getReturnType().getName()).append(' ').append(m.getName()).append('(');
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(ps[i].getName());
                    }
                    sb.append(")\n");
                }
            } catch (ClassNotFoundException | LinkageError e) {
                sb.append("## MISSING ").append(name).append('\n');
            }
        }
        return sb.toString();
    }

    /** Служебный дамп: клиентский доступ к рецептам и классы отображения результата. */
    private static String dumpRecipes() {
        StringBuilder sb = new StringBuilder();
        try {
            Minecraft mc = Minecraft.getInstance();
            Object acc = mc.getConnection().recipes();
            sb.append("## client RecipeAccess impl: ").append(acc.getClass().getName()).append('\n');
            for (Class<?> c = acc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    sb.append("  ").append(c.getSimpleName()).append(": ").append(m.getReturnType().getName())
                      .append(' ').append(m.getName()).append('(').append(m.getParameterCount()).append(")\n");
                }
            }
        } catch (RuntimeException e) {
            sb.append("## client RecipeAccess error: ").append(e).append('\n');
        }
        String[] classes = {
            "net.minecraft.world.item.crafting.display.SlotDisplay",
            "net.minecraft.world.item.crafting.display.RecipeDisplayId",
            "net.minecraft.world.item.crafting.display.RecipeDisplay$Type",
            "net.minecraft.world.item.crafting.CraftingInput",
            "net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay",
            "net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay",
            "net.minecraft.world.item.crafting.display.SlotDisplay$ItemStackSlotDisplay",
            "net.minecraft.world.item.crafting.display.SlotDisplay$ItemSlotDisplay",
            "net.minecraft.world.item.crafting.RecipeHolder"
        };
        for (String name : classes) {
            try {
                Class<?> c = Class.forName(name);
                sb.append("## ").append(name).append('\n');
                for (java.lang.reflect.Method m : c.getMethods()) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    sb.append("  ").append(m.getReturnType().getName()).append(' ').append(m.getName()).append('(');
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(ps[i].getName());
                    }
                    sb.append(")\n");
                }
            } catch (ClassNotFoundException | LinkageError e) {
                sb.append("## MISSING ").append(name).append('\n');
            }
        }
        return sb.toString();
    }

    /** Служебный дамп: SynchronizedRecipes и клиентская книга рецептов (рефлексия, без жёстких имён). */
    private static String dumpSync() {
        StringBuilder sb = new StringBuilder();
        try {
            Minecraft mc = Minecraft.getInstance();
            Object acc = mc.getConnection().recipes();
            Object sync = acc.getClass().getMethod("getSynchronizedRecipes").invoke(acc);
            sb.append("## SynchronizedRecipes impl: ").append(sync == null ? "null" : sync.getClass().getName()).append('\n');
            if (sync != null) {
                dumpClass(sb, sync.getClass());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            sb.append("## SynchronizedRecipes error: ").append(e).append('\n');
        }
        try {
            Object conn = Minecraft.getInstance().getConnection();
            for (java.lang.reflect.Field f : conn.getClass().getDeclaredFields()) {
                String t = f.getType().getName();
                if (t.toLowerCase(java.util.Locale.ROOT).contains("recipe")) {
                    sb.append("## connection field: ").append(f.getName()).append(" : ").append(t).append('\n');
                }
            }
            Minecraft mc = Minecraft.getInstance();
            for (java.lang.reflect.Field f : mc.getClass().getDeclaredFields()) {
                String t = f.getType().getName();
                if (t.toLowerCase(java.util.Locale.ROOT).contains("recipe")) {
                    sb.append("## minecraft field: ").append(f.getName()).append(" : ").append(t).append('\n');
                }
            }
        } catch (RuntimeException e) {
            sb.append("## fields error: ").append(e).append('\n');
        }
        for (String name : new String[]{
                "net.minecraft.client.ClientRecipeBook",
                "net.minecraft.client.gui.screens.recipebook.RecipeBookComponent",
                "net.minecraft.world.item.crafting.SelectableRecipe",
                "net.minecraft.world.item.crafting.RecipePropertySet"}) {
            try {
                sb.append("## ").append(name).append('\n');
                dumpClass(sb, Class.forName(name));
            } catch (ClassNotFoundException | LinkageError e) {
                sb.append("## MISSING ").append(name).append('\n');
            }
        }
        return sb.toString();
    }

    private static void dumpClass(StringBuilder sb, Class<?> c) {
        for (java.lang.reflect.Method m : c.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            sb.append("  ").append(m.getReturnType().getName()).append(' ').append(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(ps[i].getName());
            }
            sb.append(")\n");
        }
    }

    /** Служебный дамп: записи книги рецептов и коллекции (для автокрафта по RecipeDisplayId). */
    private static String dumpBook() {
        StringBuilder sb = new StringBuilder();
        String[] names = {
            "net.minecraft.world.item.crafting.display.RecipeDisplayEntry",
            "net.minecraft.client.RecipeCollection",
            "net.minecraft.client.gui.screens.recipebook.RecipeCollection",
            "net.minecraft.world.item.crafting.display.RecipeDisplayId",
            "net.minecraft.world.item.crafting.display.RecipeDisplay"
        };
        for (String name : names) {
            try {
                sb.append("## ").append(name).append('\n');
                dumpClass(sb, Class.forName(name));
            } catch (ClassNotFoundException | LinkageError e) {
                sb.append("## MISSING ").append(name).append('\n');
            }
        }
        return sb.toString();
    }

    /** Записи книги рецептов игрока (рефлексия: поле ClientRecipeBook -> getCollections -> getRecipes). */
    private static java.util.List<Object> bookEntries() throws ReflectiveOperationException {
        LocalPlayer p = Minecraft.getInstance().player;
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (p == null) return out;
        Object book = null;
        for (Class<?> c = p.getClass(); c != null && book == null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType().getName().endsWith("ClientRecipeBook")) {
                    f.setAccessible(true);
                    book = f.get(p);
                    break;
                }
            }
        }
        if (book == null) return out;
        Object cols = book.getClass().getMethod("getCollections").invoke(book);
        for (Object col : (java.util.List<?>) cols) {
            java.util.List<?> recs = (java.util.List<?>) col.getClass().getMethod("getRecipes").invoke(col);
            out.addAll(recs);
        }
        return out;
    }

    private static String recipesJson() {
        try {
            StringBuilder sb = new StringBuilder("{\"recipes\":[");
            int idx = 0;
            boolean first = true;
            for (Object e : bookEntries()) {
                Object id = e.getClass().getMethod("id").invoke(e);
                int rid = (Integer) id.getClass().getMethod("index").invoke(id);
                Object display = e.getClass().getMethod("display").invoke(e);
                Object result = display.getClass().getMethod("result").invoke(display);
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"index\":").append(rid)
                  .append(",\"result\":\"").append(escape(String.valueOf(result))).append("\"")
                  .append(",\"display\":\"").append(escape(String.valueOf(display))).append("\"}");
                idx++;
            }
            sb.append("],\"count\":").append(idx).append('}');
            return sb.toString();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return "{\"error\":\"" + escape(ex.toString()) + "\"}";
        }
    }

    /** Крафт по индексу записи книги: игровой автокрафт, затем забираем результат. */
    private static String craftById(int index, int times) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return "{\"ok\":false,\"error\":\"нет игрока в мире\"}";
        }
        try {
            int done = 0;
            for (int t = 0; t < times; t++) {
                RecipeDisplayId target = null;
                for (Object e : bookEntries()) {
                    Object id = e.getClass().getMethod("id").invoke(e);
                    if ((Integer) id.getClass().getMethod("index").invoke(id) == index) {
                        target = (RecipeDisplayId) id;
                        break;
                    }
                }
                if (target == null) {
                    return "{\"ok\":false,\"crafted\":" + done + ",\"error\":\"рецепт с индексом " + index + " не найден в книге\"}";
                }
                int menuId = p.containerMenu.containerId;
                mc.gameMode.handlePlaceRecipe(menuId, target, false);
                mc.gameMode.handleContainerInput(menuId, 0, 0, ContainerInput.QUICK_MOVE, p);
                done++;
            }
            return "{\"ok\":true,\"crafted\":" + done + "}";
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return "{\"ok\":false,\"error\":\"" + escape(ex.toString()) + "\"}";
        }
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
