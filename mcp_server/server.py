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


def run_tool(name, args):
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
