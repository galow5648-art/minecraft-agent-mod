"""
MCP-сервер (stdio) для мода minecraft-agent-mod.
Запускается на компьютере пользователя, ходит в локальный API мода на 127.0.0.1:8765.
Зависимости: только стандартная библиотека Python 3.9+.
"""
import json
import sys
import urllib.request
import urllib.error

API = "http://127.0.0.1:8765"
PROTOCOL = "2024-11-05"
KEY_ACTIONS = ["forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use"]

TOOLS = [
    {
        "name": "minecraft_debug_api",
        "description": "Служебный: список классов и методов игры для крафта (дамп API).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_situation",
        "description": "Сводка ситуации: здоровье, голод, инвентарь (брёвна, доски, палки, камень, земля, инструменты), мобы рядом, предметы на земле, ближайшее бревно.",
        "inputSchema": {"type": "object", "properties": {"radius": {"type": "integer", "minimum": 2, "maximum": 12}}},
    },
    {
        "name": "minecraft_entities",
        "description": "Враждебные мобы рядом: тип, координаты, здоровье.",
        "inputSchema": {"type": "object", "properties": {"r": {"type": "integer", "minimum": 1, "maximum": 48}}},
    },
    {
        "name": "minecraft_craft",
        "description": "Скрафтить предмет по рецепту из книги рецептов (например minecraft:oak_planks, minecraft:stick, minecraft:crafting_table, minecraft:wooden_pickaxe). Ингредиенты должны быть в инвентаре.",
        "inputSchema": {"type": "object", "properties": {"item": {"type": "string"}, "times": {"type": "integer", "minimum": 1, "maximum": 64}}, "required": ["item"]},
    },
    {
        "name": "minecraft_goto",
        "description": "Идти к точке (x, z) по прямой с прыжками через ступеньки. Ждёт окончания. Возвращает статус.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "number"}, "y": {"type": "number"}, "z": {"type": "number"}}, "required": ["x", "y", "z"]},
    },
    {
        "name": "minecraft_break",
        "description": "Сломать блок по координатам. Игрок должен стоять в 4.5 блоках. Ждёт окончания.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}}, "required": ["x", "y", "z"]},
    },
    {
        "name": "minecraft_place",
        "description": "Поставить блок из выбранного слота сверху на блок по координатам. Ждёт окончания.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}}, "required": ["x", "y", "z"]},
    },
    {
        "name": "minecraft_chop_tree",
        "description": "Найти ближайшее бревно в радиусе, дойти и срубить. Повторяет, пока есть бревна (до max_logs).",
        "inputSchema": {"type": "object", "properties": {"radius": {"type": "integer", "minimum": 2, "maximum": 12}, "max_logs": {"type": "integer", "minimum": 1, "maximum": 30}}},
    },
    {
        "name": "minecraft_nearby",
        "description": "Блоки вокруг игрока в кубе радиуса r (1–12): координаты и id блока, воздух не возвращается.",
        "inputSchema": {"type": "object", "properties": {"r": {"type": "integer", "minimum": 1, "maximum": 12}}},
    },
    {
        "name": "minecraft_look_at",
        "description": "Блок, на который сейчас смотрит прицел: координаты, id, грань, расстояние. target=null если смотрим в воздух.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_inventory",
        "description": "Инвентарь игрока: непустые слоты с id предмета, количеством и прочностью инструментов.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_state",
        "description": "Состояние персонажа: координаты, взгляд (yaw/pitch), здоровье, голод, выбранный слот.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_key",
        "description": "Зажать (down=true) или отпустить (down=false) клавишу действия. attack — ломать и бить, use — ставить и открывать.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": KEY_ACTIONS},
                "down": {"type": "boolean"},
            },
            "required": ["action", "down"],
        },
    },
    {
        "name": "minecraft_look",
        "description": "Повернуть камеру: yaw — влево/вправо в градусах, pitch — вверх/вниз (от -90 до 90).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "yaw": {"type": "number"},
                "pitch": {"type": "number"},
            },
            "required": ["yaw", "pitch"],
        },
    },
    {
        "name": "minecraft_slot",
        "description": "Выбрать слот хотбара (0–8).",
        "inputSchema": {
            "type": "object",
            "properties": {"slot": {"type": "integer", "minimum": 0, "maximum": 8}},
            "required": ["slot"],
        },
    },
]


def call_api(method, path, payload=None):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(API + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.read().decode("utf-8")
    except (urllib.error.URLError, OSError) as e:
        raise RuntimeError(f"API мода недоступен ({e}). Запущена ли игра с модом?")


def wait_job(timeout=40):
    import time
    end = time.time() + timeout
    while time.time() < end:
        st = json.loads(call_api("GET", "/job"))
        if st.get("status") != "running":
            return st
        time.sleep(0.2)
    return {"status": "failed", "error": "таймаут ожидания"}


def chop_tree(radius=6, max_logs=10):
    import math
    done = []
    for _ in range(max_logs):
        nearby = json.loads(call_api("GET", f"/nearby?r={radius}"))
        logs = [b for b in nearby.get("blocks", []) if b["block"].endswith("_log")]
        if not logs:
            break
        me = json.loads(call_api("GET", "/state"))
        logs.sort(key=lambda b: math.dist((b["x"], b["z"]), (me["x"], me["z"])))
        log = logs[0]
        # Подойти к бревну: точка рядом с ним, на той же высоте что и игрок
        call_api("POST", "/goto", {"x": log["x"] + 1.5, "y": me["y"], "z": log["z"]})
        r = wait_job()
        if r.get("status") != "done":
            done.append({"log": log, "result": r})
            break
        call_api("POST", "/break", {"x": log["x"], "y": log["y"], "z": log["z"]})
        r = wait_job()
        done.append({"log": log, "result": r})
        if r.get("status") != "done":
            break
    return json.dumps({"chopped": sum(1 for d in done if d["result"].get("status") == "done"), "details": done}, ensure_ascii=False)


def situation(radius=8):
    import math
    state = json.loads(call_api("GET", "/state"))
    inv = json.loads(call_api("GET", "/inventory"))
    near = json.loads(call_api("GET", f"/nearby?r={radius}"))
    mobs = json.loads(call_api("GET", f"/entities?r={radius * 2}"))
    counts = {}
    for slot in inv.get("slots", []):
        counts[slot["item"]] = counts.get(slot["item"], 0) + slot["count"]
    logs = [b for b in near.get("blocks", []) if b["block"].endswith("_log")]
    nearest_log = None
    if logs and state.get("inWorld"):
        best = min(logs, key=lambda b: math.dist((b["x"], b["y"], b["z"]), (state["x"], state["y"], state["z"])))
        nearest_log = {**best, "distance": round(math.dist((best["x"], best["y"], best["z"]), (state["x"], state["y"], state["z"])), 2)}
    warnings = []
    if state.get("health", 20) <= 8:
        warnings.append("мало здоровья")
    if state.get("food", 20) <= 6:
        warnings.append("голод")
    if mobs.get("hostile"):
        warnings.append(f"враждебные мобы рядом: {len(mobs['hostile'])}")
    return json.dumps({
        "state": state,
        "inventory": counts,
        "nearest_log": nearest_log,
        "items_on_ground": near.get("items", []),
        "hostile_mobs": mobs.get("hostile", []),
        "warnings": warnings,
    }, ensure_ascii=False)


def run_tool(name, args):
    if name == "minecraft_debug_api":
        return call_api("GET", "/debug_api")
    if name == "minecraft_situation":
        return situation(int(args.get("radius", 8)))
    if name == "minecraft_entities":
        return call_api("GET", f"/entities?r={int(args.get('r', 16))}")
    if name == "minecraft_craft":
        return call_api("POST", "/craft", {"item": args["item"], "times": int(args.get("times", 1))})
    if name == "minecraft_goto":
        call_api("POST", "/goto", {"x": float(args["x"]), "y": float(args.get("y", 0)), "z": float(args["z"])})
        return json.dumps(wait_job(), ensure_ascii=False)
    if name == "minecraft_break":
        call_api("POST", "/break", {"x": int(args["x"]), "y": int(args["y"]), "z": int(args["z"])})
        return json.dumps(wait_job(), ensure_ascii=False)
    if name == "minecraft_place":
        call_api("POST", "/place", {"x": int(args["x"]), "y": int(args["y"]), "z": int(args["z"])})
        return json.dumps(wait_job(), ensure_ascii=False)
    if name == "minecraft_chop_tree":
        return chop_tree(int(args.get("radius", 6)), int(args.get("max_logs", 10)))
    if name == "minecraft_nearby":
        r = int(args.get("r", 8))
        return call_api("GET", f"/nearby?r={r}")
    if name == "minecraft_look_at":
        return call_api("GET", "/look_at")
    if name == "minecraft_inventory":
        return call_api("GET", "/inventory")
    if name == "minecraft_state":
        return call_api("GET", "/state")
    if name == "minecraft_key":
        return call_api("POST", "/key", {"action": args["action"], "down": bool(args["down"])})
    if name == "minecraft_look":
        return call_api("POST", "/look", {"yaw": float(args["yaw"]), "pitch": float(args["pitch"])})
    if name == "minecraft_slot":
        return call_api("POST", "/slot", {"slot": int(args["slot"])})
    raise RuntimeError(f"Неизвестный инструмент: {name}")


def reply(msg_id, result=None, error=None):
    msg = {"jsonrpc": "2.0", "id": msg_id}
    if error is not None:
        msg["error"] = error
    else:
        msg["result"] = result
    sys.stdout.write(json.dumps(msg, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def handle(req):
    method = req.get("method")
    msg_id = req.get("id")
    if method == "initialize":
        return reply(msg_id, {
            "protocolVersion": PROTOCOL,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "minecraft-agent", "version": "1.0.0"},
        })
    if method == "notifications/initialized":
        return None
    if method == "tools/list":
        return reply(msg_id, {"tools": TOOLS})
    if method == "tools/call":
        params = req.get("params", {})
        try:
            text = run_tool(params.get("name"), params.get("arguments", {}))
            return reply(msg_id, {"content": [{"type": "text", "text": text}], "isError": False})
        except Exception as e:
            return reply(msg_id, {"content": [{"type": "text", "text": str(e)}], "isError": True})
    if msg_id is not None:
        return reply(msg_id, error={"code": -32601, "message": f"Method not found: {method}"})
    return None


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            handle(json.loads(line))
        except Exception as e:
            sys.stderr.write(f"[minecraft-agent] ошибка: {e}\n")


if __name__ == "__main__":
    main()
