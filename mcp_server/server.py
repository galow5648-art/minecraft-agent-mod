"""
Постоянная прослойка MCP (stdio) между приложением Claude и ядром agent_core.py.

Зачем:
  * Этот файл почти не меняется, поэтому приложению Claude не нужно переподключать сервер после обновлений:
    логика инструментов лежит в agent_core.py, и прослойка подхватывает его новую версию на лету
    (проверка раз в пару секунд и перед каждым вызовом; список инструментов обновляется через
    notifications/tools/list_changed).
  * Игру и мод можно закрывать и запускать сколько угодно: список инструментов отвечает всегда,
    а вызовы без игры возвращают понятную ошибку и сами начинают работать, когда мод снова доступен.
  * stdin читается в отдельном потоке, а долгие вызовы идут в рабочих потоках. Поэтому minecraft_cancel и
    сигнал отмены от клиента (notifications/cancelled) срабатывают, даже пока выполняется mine/goto.

Где ищется ядро (берётся самый свежий по времени изменения файл):
  1. переменная окружения MINECRAFT_AGENT_CORE
  2. agent_core.py рядом с этим файлом (C:\\MinecraftAgent\\agent_core.py)
  3. %USERPROFILE%\\minecraft-agent-mod\\mcp_server\\agent_core.py (куда update.cmd распаковывает репозиторий)
Если новая версия ядра не загрузилась (ошибка в коде), прослойка остаётся на предыдущей и пишет об этом в ответах.

Зависимости: только стандартная библиотека Python 3.9+.
"""
import hashlib
import json
import os
import sys
import threading
import time
import types

PROTOCOL = "2024-11-05"
SERVER_NAME = "minecraft-agent"
SHIM_VERSION = "2.0.0"
WATCH_INTERVAL = 2.0

# Вызовы, которые можно выполнять параллельно с долгой задачей (только чтение и отмена)
LIGHT_TOOLS = {"minecraft_cancel", "minecraft_status", "minecraft_alerts", "minecraft_state",
               "minecraft_inventory", "minecraft_entities", "minecraft_entities_all", "minecraft_look_at",
               "minecraft_block_info", "minecraft_surroundings", "minecraft_proxy"}

PROXY_TOOL = {
    "name": "minecraft_proxy",
    "description": "Состояние прослойки: версия, какой файл ядра загружен и когда, сколько раз перезагружали, "
                   "есть ли ошибка в новой версии, доступен ли мод. Для диагностики после обновления.",
    "inputSchema": {"type": "object", "properties": {}},
}

STARTED = time.time()
write_lock = threading.Lock()
action_lock = threading.Lock()          # одновременно выполняется один игровой вызов
state_lock = threading.Lock()
inflight = {}                           # id запроса -> модуль ядра, который его выполняет
cancelled = set()                       # id запросов, отменённых клиентом (ответ на них не шлём)
client_ready = False


def send(msg):
    with write_lock:
        sys.stdout.write(json.dumps(msg, ensure_ascii=True) + "\n")  # чистый ASCII: не зависит от кодовой страницы
        sys.stdout.flush()


def reply(msg_id, result=None, error=None):
    msg = {"jsonrpc": "2.0", "id": msg_id}
    if error is not None:
        msg["error"] = error
    else:
        msg["result"] = result
    send(msg)


def log(text):
    try:
        sys.stderr.write(f"[minecraft-agent] {text}\n")
        sys.stderr.flush()
    except Exception:
        pass


# ------------------------------------------------------------------ загрузка ядра

def core_candidates():
    out = []
    env = os.environ.get("MINECRAFT_AGENT_CORE")
    if env:
        out.append(env)
    out.append(os.path.join(os.path.dirname(os.path.abspath(__file__)), "agent_core.py"))
    out.append(os.path.join(os.path.expanduser("~"), "minecraft-agent-mod", "mcp_server", "agent_core.py"))
    return [p for p in out if os.path.isfile(p)]


class Core:
    def __init__(self):
        self.mod = None
        self.path = None
        self.key = None          # (путь, mtime, размер) последней попытки загрузки
        self.loaded_at = None
        self.reloads = 0
        self.warning = None
        self.tools_hash = None
        self.lock = threading.Lock()

    def refresh(self):
        """Подхватить новую версию ядра, если файл изменился. Возвращает True, если загружена новая версия."""
        with self.lock:
            cands = core_candidates()
            if not cands:
                if self.mod is None:
                    self.warning = ("agent_core.py не найден (ищу рядом с server.py и в "
                                    "%USERPROFILE%\\minecraft-agent-mod\\mcp_server). Запусти update.cmd.")
                return False
            best = max(cands, key=os.path.getmtime)
            try:
                st = os.stat(best)
            except OSError:
                return False
            key = (best, st.st_mtime_ns, st.st_size)
            if key == self.key:
                return False
            self.key = key
            try:
                with open(best, "rb") as f:
                    src = f.read()
                mod = types.ModuleType("agent_core")
                mod.__file__ = best
                exec(compile(src, best, "exec"), mod.__dict__)
                tools = mod.TOOLS
                if not callable(getattr(mod, "run_tool", None)) or not isinstance(tools, list):
                    raise RuntimeError("в ядре нет TOOLS или run_tool")
            except BaseException as e:  # SyntaxError и любые ошибки при импорте: остаёмся на прошлой версии
                self.warning = f"новая версия ядра ({os.path.basename(best)}) не загрузилась: {type(e).__name__}: {e}"
                log(self.warning)
                return False
            self.mod = mod
            self.path = best
            self.loaded_at = time.time()
            self.reloads += 1
            self.warning = None
            log(f"ядро загружено: {best} (загрузок: {self.reloads})")
            return True

    def tools(self):
        return list(self.mod.TOOLS) + [PROXY_TOOL] if self.mod else [PROXY_TOOL]

    def digest(self):
        return hashlib.sha1(json.dumps(self.tools(), sort_keys=True).encode("utf-8")).hexdigest()


core = Core()
announced_hash = None


def refresh_and_announce():
    """Обновить ядро и, если список инструментов изменился, сообщить клиенту."""
    global announced_hash
    changed = core.refresh()
    if core.mod is None:
        return changed
    h = core.digest()
    with state_lock:
        if announced_hash is None:
            announced_hash = h
        elif h != announced_hash:
            announced_hash = h
            if client_ready:
                send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
    return changed


def watcher():
    while True:
        time.sleep(WATCH_INTERVAL)
        try:
            refresh_and_announce()
        except Exception as e:
            log(f"watcher: {e}")


# ------------------------------------------------------------------ вызовы инструментов

def mod_available(mod):
    try:
        mod.call_api("GET", "/status", None, 2)
        return True
    except Exception:
        return False


def proxy_status():
    mod = core.mod
    info = {
        "shim_version": SHIM_VERSION,
        "uptime_s": round(time.time() - STARTED),
        "core_path": core.path,
        "core_loaded_ago_s": round(time.time() - core.loaded_at) if core.loaded_at else None,
        "core_reloads": core.reloads,
        "core_warning": core.warning,
        "tools": len(mod.TOOLS) if mod else 0,
        "inflight_calls": len(inflight),
        "mod_api": ("up" if mod_available(mod) else "down") if mod else "unknown",
    }
    return json.dumps(info, ensure_ascii=False)


def cancel_module(mod):
    try:
        mod.CANCEL.set()
        mod.call_api("POST", "/cancel", {}, 3)   # остановить задачу в самой игре
    except Exception:
        pass


def cancel_request(request_id):
    with state_lock:
        cancelled.add(request_id)
        mod = inflight.get(request_id)
    if mod is not None:
        cancel_module(mod)


def handle_call(req):
    rid = req.get("id")
    params = req.get("params") or {}
    name = params.get("name")
    args = params.get("arguments") or {}
    refresh_and_announce()
    mod = core.mod
    text, is_error = "", False
    if name == "minecraft_proxy":
        text = proxy_status()
    elif mod is None:
        text, is_error = core.warning or "ядро не загружено", True
    else:
        with state_lock:
            inflight[rid] = mod
        try:
            if name in LIGHT_TOOLS:
                if name == "minecraft_cancel":
                    mod.CANCEL.set()
                text = mod.run_tool(name, args)
            else:
                with action_lock:
                    with state_lock:
                        skip = rid in cancelled
                    if skip:
                        return
                    mod.CANCEL.clear()          # новый игровой вызов начинается с чистого флага отмены
                    text = mod.run_tool(name, args)
        except Exception as e:
            text, is_error = str(e) or type(e).__name__, True
        finally:
            with state_lock:
                inflight.pop(rid, None)
    with state_lock:
        was_cancelled = rid in cancelled
        cancelled.discard(rid)
    if was_cancelled:
        return                                   # клиент отменил запрос и ответа не ждёт
    if core.warning and mod is not None:
        text = f"[прослойка] {core.warning}; работаю на предыдущей версии ядра.\n{text}"
    reply(rid, {"content": [{"type": "text", "text": str(text)}], "isError": is_error})


def dispatch(req):
    global client_ready
    method = req.get("method")
    msg_id = req.get("id")
    if method == "initialize":
        refresh_and_announce()
        return reply(msg_id, {
            "protocolVersion": PROTOCOL,
            "capabilities": {"tools": {"listChanged": True}},
            "serverInfo": {"name": SERVER_NAME, "version": SHIM_VERSION},
        })
    if method == "notifications/initialized":
        client_ready = True
        return None
    if method == "notifications/cancelled":
        rid = (req.get("params") or {}).get("requestId")
        if rid is not None:
            cancel_request(rid)
        return None
    if method == "ping":
        return reply(msg_id, {})
    if method == "tools/list":
        refresh_and_announce()
        return reply(msg_id, {"tools": core.tools()})
    if method == "tools/call":
        threading.Thread(target=safe_call, args=(req,), daemon=True).start()
        return None
    if msg_id is not None:
        return reply(msg_id, error={"code": -32601, "message": f"Method not found: {method}"})
    return None


def safe_call(req):
    try:
        handle_call(req)
    except Exception as e:
        log(f"ошибка вызова: {e}")
        try:
            reply(req.get("id"), {"content": [{"type": "text", "text": f"ошибка прослойки: {e}"}], "isError": True})
        except Exception:
            pass


def main():
    # На Windows stdio по умолчанию в cp1251/charmap: принудительно UTF-8
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    refresh_and_announce()
    threading.Thread(target=watcher, daemon=True).start()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        req = None
        try:
            req = json.loads(line)
            dispatch(req)
        except Exception as e:
            log(f"ошибка: {e}")
            if isinstance(req, dict) and req.get("id") is not None:
                try:
                    reply(req["id"], error={"code": -32603, "message": str(e)})
                except Exception:
                    pass
    # клиент закрыл stdin: остановить всё, что ещё выполняется в игре
    with state_lock:
        mods = list(set(inflight.values()))
    for m in mods:
        cancel_module(m)


if __name__ == "__main__":
    main()
