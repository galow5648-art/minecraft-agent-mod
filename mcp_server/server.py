"""
MCP-сервер (stdio) для мода minecraft-agent-mod.
Запускается на компьютере пользователя, ходит в локальный API мода на 127.0.0.1:8765.
Зависимости: только стандартная библиотека Python 3.9+.
"""
import base64
import ctypes
import json
import struct
import sys
import urllib.request
import urllib.error
import zlib

API = "http://127.0.0.1:8765"
PROTOCOL = "2024-11-05"
KEY_ACTIONS = ["forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use"]

TOOLS = [
    {
        "name": "minecraft_screenshot",
        "description": "Снимок экрана Windows (как видит игрок). scale — уменьшение: 2 = в два раза меньше по ширине и высоте.",
        "inputSchema": {
            "type": "object",
            "properties": {"scale": {"type": "integer", "minimum": 1, "maximum": 4}},
        },
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


def _capture_bgra():
    """Снимок основного монитора через GDI (Windows). Возвращает (w, h, bytes BGRX)."""
    user32 = ctypes.windll.user32
    gdi32 = ctypes.windll.gdi32
    try:
        user32.SetProcessDPIAware()
    except Exception:
        pass
    w = user32.GetSystemMetrics(0)
    h = user32.GetSystemMetrics(1)
    hdc_screen = user32.GetDC(0)
    hdc = gdi32.CreateCompatibleDC(hdc_screen)
    bmp = gdi32.CreateCompatibleBitmap(hdc_screen, w, h)
    old = gdi32.SelectObject(hdc, bmp)
    gdi32.BitBlt(hdc, 0, 0, w, h, hdc_screen, 0, 0, 0x00CC0020)  # SRCCOPY

    class BITMAPINFOHEADER(ctypes.Structure):
        _fields_ = [
            ("biSize", ctypes.c_uint32), ("biWidth", ctypes.c_int32), ("biHeight", ctypes.c_int32),
            ("biPlanes", ctypes.c_uint16), ("biBitCount", ctypes.c_uint16), ("biCompression", ctypes.c_uint32),
            ("biSizeImage", ctypes.c_uint32), ("biXPelsPerMeter", ctypes.c_int32),
            ("biYPelsPerMeter", ctypes.c_int32), ("biClrUsed", ctypes.c_uint32), ("biClrImportant", ctypes.c_uint32),
        ]

    info = BITMAPINFOHEADER()
    info.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    info.biWidth = w
    info.biHeight = -h  # сверху вниз
    info.biPlanes = 1
    info.biBitCount = 32
    info.biCompression = 0  # BI_RGB
    buf = ctypes.create_string_buffer(w * h * 4)
    gdi32.GetDIBits(hdc, bmp, 0, h, buf, ctypes.byref(info), 0)

    gdi32.SelectObject(hdc, old)
    gdi32.DeleteObject(bmp)
    gdi32.DeleteDC(hdc)
    user32.ReleaseDC(0, hdc_screen)
    return w, h, buf.raw


def _png(width, height, rgb_rows):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + row for row in rgb_rows)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6))
            + chunk(b"IEND", b""))


def take_screenshot(scale=2):
    if sys.platform != "win32":
        raise RuntimeError("Снимок экрана работает только на Windows.")
    w, h, raw = _capture_bgra()
    rows = []
    for y in range(0, h, scale):
        line = raw[y * w * 4:(y + 1) * w * 4]
        r = line[2::4 * scale]
        g = line[1::4 * scale]
        b = line[0::4 * scale]
        out = bytearray(len(r) * 3)
        out[0::3] = r
        out[1::3] = g
        out[2::3] = b
        rows.append(bytes(out))
    width = (w + scale - 1) // scale
    height = len(rows)
    png = _png(width, height, rows)
    return {"type": "image", "data": base64.b64encode(png).decode("ascii"), "mimeType": "image/png"}


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
    if name == "minecraft_screenshot":
        return take_screenshot(int(args.get("scale", 2)))
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
            result = run_tool(params.get("name"), params.get("arguments", {}))
            if isinstance(result, dict):
                content = [result]
            else:
                content = [{"type": "text", "text": result}]
            return reply(msg_id, {"content": content, "isError": False})
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
