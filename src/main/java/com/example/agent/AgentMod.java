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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;

/**
 * Локальный HTTP-API только на 127.0.0.1:8765.
 * Все запросы к игре выполняются в клиентском тике, HTTP-поток только ждёт результат.
 */
public class AgentMod implements ClientModInitializer {
    public static final int PORT = 8765;
    private static final ConcurrentLinkedQueue<Runnable> TASKS = new ConcurrentLinkedQueue<>();
    private static volatile String lastState = "{\"inWorld\":false}";

    /** Текущая задача, которая выполняется в клиентском тике. */
    private static final class Job {
        final String type;
        final int x, y, z;
        final long startedMs = System.currentTimeMillis();
        String status = "running";
        String error = null;
        Job(String type, int x, int y, int z) { this.type = type; this.x = x; this.y = y; this.z = z; }
    }
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
            tickJob(mc);
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
            server.createContext("/job", ex -> send(ex, 200, jobJson()));
            server.createContext("/debug_api", ex -> send(ex, 200, dumpApi()));
            server.createContext("/entities", ex -> {
                int r = clamp((int) queryNum(ex, "r", 16), 1, 48);
                respondFromGame(ex, () -> entities(r));
            });
            server.createContext("/craft", ex -> {
                String b = read(ex);
                String item = field(b, "item");
                int times = clamp((int) num(b, "times"), 1, 64);
                respondFromGame(ex, () -> craft(item, times));
            });
            server.createContext("/break", ex -> {
                String b = read(ex);
                int x = (int) num(b, "x"), y = (int) num(b, "y"), z = (int) num(b, "z");
                job = new Job("break", x, y, z);
                send(ex, 200, "{\"ok\":true}");
            });
            server.createContext("/goto", ex -> {
                String b = read(ex);
                int x = (int) Math.floor(num(b, "x")), y = (int) num(b, "y"), z = (int) Math.floor(num(b, "z"));
                job = new Job("goto", x, y, z);
                send(ex, 200, "{\"ok\":true}");
            });
            server.createContext("/place", ex -> {
                String b = read(ex);
                int x = (int) num(b, "x"), y = (int) num(b, "y"), z = (int) num(b, "z");
                job = new Job("place", x, y, z);
                send(ex, 200, "{\"ok\":true}");
            });
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
        int w = menu.getGridWidth();
        int h = menu.getGridHeight();
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
        return "{\"status\":\"" + j.status + "\",\"type\":\"" + j.type + "\""
                + (j.error != null ? ",\"error\":\"" + escape(j.error) + "\"" : "") + "}";
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
        if (System.currentTimeMillis() - j.startedMs > 30_000) {
            releaseKeys(mc);
            finish(j, "failed", "таймаут");
            return;
        }
        switch (j.type) {
            case "break" -> tickBreak(mc, p, j);
            case "goto" -> tickGoto(mc, p, j);
            case "place" -> tickPlace(mc, p, j);
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
        o.keyJump.setDown(false);
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
        if (mc.level.getBlockState(pos).isAir()) {
            releaseKeys(mc);
            finish(j, "done", null);
            return;
        }
        double dist = Math.sqrt(p.distanceToSqr(j.x + 0.5, j.y + 0.5, j.z + 0.5));
        if (dist > 4.5) {
            finish(j, "failed", "блок слишком далеко (" + round(dist) + " м), сначала /goto");
            return;
        }
        lookAtPoint(p, j.x + 0.5, j.y + 0.5, j.z + 0.5);
        mc.options.keyAttack.setDown(true);
    }

    private static void tickGoto(Minecraft mc, LocalPlayer p, Job j) {
        double tx = j.x + 0.5, tz = j.z + 0.5;
        double dx = tx - p.getX(), dz = tz - p.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 0.6) {
            releaseKeys(mc);
            finish(j, "done", null);
            return;
        }
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.options.keyUp.setDown(true);
        // Прыжок, если впереди блок на уровне ног, а над ним есть место.
        BlockPos ahead = BlockPos.containing(p.getX() + dx / horiz * 0.8, p.getY(), p.getZ() + dz / horiz * 0.8);
        boolean wallAhead = !mc.level.getBlockState(ahead).isAir();
        boolean headFree = mc.level.getBlockState(ahead.above()).isAir();
        mc.options.keyJump.setDown(p.onGround() && wallAhead && headFree);
    }

    private static void tickPlace(Minecraft mc, LocalPlayer p, Job j) {
        // Ставим блок сверху на блок (x, y, z): смотрим на его верхнюю грань.
        BlockPos target = new BlockPos(j.x, j.y, j.z);
        if (mc.level.getBlockState(target).isAir()) {
            finish(j, "failed", "на этом месте нет опоры");
            return;
        }
        double dist = Math.sqrt(p.distanceToSqr(j.x + 0.5, j.y + 1.0, j.z + 0.5));
        if (dist > 4.5) {
            finish(j, "failed", "блок слишком далеко, сначала /goto");
            return;
        }
        lookAtPoint(p, j.x + 0.5, j.y + 1.0, j.z + 0.5);
        Vec3 hit = new Vec3(j.x + 0.5, j.y + 1.0, j.z + 0.5);
        BlockHitResult bhr = new BlockHitResult(hit, Direction.UP, target, false);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhr);
        finish(j, "done", null);
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
        StringBuilder sb = new StringBuilder();
        for (String name : classes) {
            try {
                Class<?> c = Class.forName(name);
                sb.append("## ").append(name).append('\n');
                if (c.isEnum()) {
                    for (Object o : c.getEnumConstants()) sb.append("  const ").append(o).append('\n');
                }
                for (java.lang.reflect.Method m : c.getMethods()) {
                    if (!keep.matcher(m.getName()).find() && !keep.matcher(c.getSimpleName()).find()) continue;
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
