import json, os, queue, shutil, subprocess, sys, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATE = {"job": "idle", "cancels": 0, "posts": []}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        p = self.path
        if p == "/status":
            return self._send({"inWorld": True, "menu": "InventoryMenu", "health": 20.0})
        if p == "/state":
            return self._send({"x": 0.0, "y": 64.0, "z": 0.0, "health": 20.0, "food": 20, "slot": 0})
        if p == "/job":
            return self._send({"status": STATE["job"]})
        if p == "/alerts":
            return self._send({"events": []})
        if p.startswith("/entities_all"):
            return self._send({"entities": []})
        return self._send({})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode() if n else ""
        STATE["posts"].append((self.path, body))
        if self.path == "/goto":
            STATE["job"] = "running"
        if self.path == "/cancel":
            STATE["cancels"] += 1
            STATE["job"] = "idle"
        return self._send({"ok": True})


def start_mod():
    srv = ThreadingHTTPServer(("127.0.0.1", 8765), H)
    srv.daemon_threads = True
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv


class Shim:
    def __init__(self, shim_dir):
        env = dict(os.environ, PYTHONIOENCODING="cp1252", HOME=shim_dir)  # HOME без репозитория: ядро только рядом
        self.p = subprocess.Popen([sys.executable, os.path.join(shim_dir, "server.py")], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, text=True,
                                  encoding="utf-8")
        self.q = queue.Queue()
        self.all = []
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._err, daemon=True).start()
        self.err = []

    def _read(self):
        for line in self.p.stdout:
            m = json.loads(line)
            self.all.append(m)
            self.q.put(m)

    def _err(self):
        for line in self.p.stderr:
            self.err.append(line.rstrip())

    def send(self, obj):
        self.p.stdin.write(json.dumps(obj) + "\n")
        self.p.stdin.flush()

    def wait(self, pred, timeout=8):
        end = time.time() + timeout
        for m in list(self.all):
            if pred(m):
                return m
        while time.time() < end:
            try:
                m = self.q.get(timeout=0.2)
            except queue.Empty:
                continue
            if pred(m):
                return m
        return None

    def reply_to(self, i, timeout=8):
        return self.wait(lambda m: m.get("id") == i, timeout)


def text_of(m):
    return m["result"]["content"][0]["text"]


def check(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + (" :: " + str(extra) if extra and not cond else ""))
    if not cond:
        check.failed = True


check.failed = False

tmp = tempfile.mkdtemp()
shutil.copy(os.path.join(REPO, "server.py"), tmp)
shutil.copy(os.path.join(REPO, "agent_core.py"), tmp)
core_path = os.path.join(tmp, "agent_core.py")

srv = start_mod()
s = Shim(tmp)
s.send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2024-11-05"}})
r = s.reply_to(1)
check("initialize", r and r["result"]["serverInfo"]["name"] == "minecraft-agent" and r["result"]["capabilities"]["tools"]["listChanged"])
s.send({"jsonrpc": "2.0", "method": "notifications/initialized"})
s.send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
r = s.reply_to(2)
names = [t["name"] for t in r["result"]["tools"]]
check("tools/list has core tools + proxy", len(names) >= 45 and "minecraft_proxy" in names and "minecraft_mine" in names, len(names))

s.send({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "minecraft_status", "arguments": {}}})
r = s.reply_to(3)
check("status call", r and not r["result"]["isError"] and '"api": "up"' in text_of(r), r)

# --- отмена minecraft_cancel во время долгого goto
s.send({"jsonrpc": "2.0", "id": 10, "method": "tools/call", "params": {"name": "minecraft_goto", "arguments": {"x": 5, "z": 5}}})
time.sleep(1.0)
t0 = time.time()
s.send({"jsonrpc": "2.0", "id": 11, "method": "tools/call", "params": {"name": "minecraft_cancel", "arguments": {}}})
r11 = s.reply_to(11, 5)
check("cancel answered while goto runs", r11 is not None and time.time() - t0 < 3)
r10 = s.reply_to(10, 6)
check("long goto ended after cancel", r10 is not None and ("interrupted" in text_of(r10) or "отменено" in text_of(r10)), r10)
check("mod /cancel was hit", STATE["cancels"] >= 1, STATE)

# --- notifications/cancelled: ответа быть не должно, задача останавливается
c0 = STATE["cancels"]
STATE["job"] = "idle"
s.send({"jsonrpc": "2.0", "id": 12, "method": "tools/call", "params": {"name": "minecraft_goto", "arguments": {"x": 9, "z": 9}}})
time.sleep(1.0)
s.send({"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": 12}})
time.sleep(1.5)
check("no reply for client-cancelled request", s.reply_to(12, 0.5) is None)
check("mod /cancel hit on client cancel", STATE["cancels"] > c0, STATE["cancels"])
# после отмены следующий вызов работает
s.send({"jsonrpc": "2.0", "id": 13, "method": "tools/call", "params": {"name": "minecraft_state", "arguments": {}}})
r = s.reply_to(13)
check("call after cancel works", r and not r["result"]["isError"], r)

# --- горячая перезагрузка: добавим инструмент в ядро
src = open(core_path, encoding="utf-8").read()
src2 = src.replace('TOOLS = [', 'TOOLS = [\n    {"name": "minecraft_hot_test", "description": "t", "inputSchema": {"type": "object", "properties": {}}},', 1)
src2 = src2.replace('def run_tool(name, args):', 'def run_tool(name, args):\n    if name == "minecraft_hot_test":\n        return "hot-ok"', 1)
time.sleep(1.1)
open(core_path, "w", encoding="utf-8").write(src2)
n = s.wait(lambda m: m.get("method") == "notifications/tools/list_changed", 6)
check("list_changed notification after core edit", n is not None)
s.send({"jsonrpc": "2.0", "id": 20, "method": "tools/call", "params": {"name": "minecraft_hot_test", "arguments": {}}})
r = s.reply_to(20)
check("new tool callable without restart", r and text_of(r) == "hot-ok", r)

# --- ошибка в новой версии ядра: остаёмся на прошлой
time.sleep(1.1)
open(core_path, "w", encoding="utf-8").write(src2 + "\n def broken(:\n")
time.sleep(3.0)
s.send({"jsonrpc": "2.0", "id": 21, "method": "tools/call", "params": {"name": "minecraft_hot_test", "arguments": {}}})
r = s.reply_to(21)
check("old core still works after broken edit", r and "hot-ok" in text_of(r) and "[прослойка]" in text_of(r), r)
s.send({"jsonrpc": "2.0", "id": 22, "method": "tools/call", "params": {"name": "minecraft_proxy", "arguments": {}}})
r = s.reply_to(22)
check("proxy reports warning", r and "core_warning" in text_of(r) and "не загрузилась" in text_of(r), r)
# починили файл
time.sleep(1.1)
open(core_path, "w", encoding="utf-8").write(src)
time.sleep(3.0)
s.send({"jsonrpc": "2.0", "id": 23, "method": "tools/call", "params": {"name": "minecraft_proxy", "arguments": {}}})
r = s.reply_to(23)
d = json.loads(text_of(r))
check("recovered after fix", d["core_warning"] is None and d["tools"] == len(names) - 1, d)

# --- мод выключен и включён
srv.shutdown(); srv.server_close()
time.sleep(0.5)
s.send({"jsonrpc": "2.0", "id": 30, "method": "tools/list"})
check("tools/list works with mod down", s.reply_to(30) is not None)
s.send({"jsonrpc": "2.0", "id": 31, "method": "tools/call", "params": {"name": "minecraft_state", "arguments": {}}})
r = s.reply_to(31)
check("call with mod down -> readable error", r and r["result"]["isError"] and "8765" in text_of(r), r)
s.send({"jsonrpc": "2.0", "id": 32, "method": "tools/call", "params": {"name": "minecraft_status", "arguments": {}}})
r = s.reply_to(32)
check("status with mod down does not crash", r and '"api": "down"' in text_of(r), r)
srv = start_mod()
s.send({"jsonrpc": "2.0", "id": 33, "method": "tools/call", "params": {"name": "minecraft_state", "arguments": {}}})
r = s.reply_to(33)
check("works again after mod is back", r and not r["result"]["isError"], r)

s.p.stdin.close()
time.sleep(0.5)
print("stderr:", s.err[-4:])
print("RESULT:", "FAILED" if check.failed else "ALL PASSED")
