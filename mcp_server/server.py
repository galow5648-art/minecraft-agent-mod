"""
MCP-сервер (stdio) для мода minecraft-agent-mod.
Запускается на компьютере пользователя, ходит в локальный API мода на 127.0.0.1:8765.
Зависимости: только стандартная библиотека Python 3.9+.
"""
import json
import math
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "http://127.0.0.1:8765"
PROTOCOL = "2024-11-05"
KEY_ACTIONS = ["forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use"]

TOOLS = [
    {
        "name": "minecraft_entities_all",
        "description": "Все сущности рядом (до 60, по расстоянию): id, тип, имя, координаты, расстояние, hostile, здоровье, baby, что держит; для предметов на земле item/count. id нужен для attack и interact_entity.",
        "inputSchema": {"type": "object", "properties": {"r": {"type": "integer", "minimum": 1, "maximum": 48}}},
    },
    {
        "name": "minecraft_attack",
        "description": "Бить сущность по id до hits ударов (с учётом кулдауна). По умолчанию сам подходит (chase). Остановится, когда цель мертва. Возвращает, изменилось ли здоровье и инвентарь. Условия остановки: stop_health (<=), чтобы не умереть.",
        "inputSchema": {"type": "object", "properties": {"id": {"type": "integer"}, "hits": {"type": "integer", "minimum": 1, "maximum": 60}, "chase": {"type": "boolean"}}, "required": ["id"]},
    },
    {
        "name": "minecraft_use_item",
        "description": "Использовать предмет в руке (правая кнопка по воздуху): съесть еду, бросить жемчуг/глаз Эндера/снежок, вылить или набрать ведро, натянуть лук. slot (0–8) или item (сам положит в хотбар). hold_ticks — сколько держать (еда доедается сама, лук: hold_ticks около 20 и release=true). Возвращает изменения инвентаря, здоровья и голода.",
        "inputSchema": {"type": "object", "properties": {"slot": {"type": "integer", "minimum": 0, "maximum": 8}, "item": {"type": "string"}, "hold_ticks": {"type": "integer", "minimum": 0, "maximum": 400}, "release": {"type": "boolean"}}},
    },
    {
        "name": "minecraft_interact_block",
        "description": "Правый клик по блоку с выбранной гранью (up/down/north/south/east/west): открыть дверь, сундук, печь, верстак, лечь на кровать, нажать кнопку или рычаг, вспахать землю мотыгой, посадить семена, использовать предмет на блок. Расстояние до 5 блоков. Предмет берётся из выбранного слота.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}, "face": {"type": "string", "enum": ["up", "down", "north", "south", "east", "west"]}}, "required": ["x", "y", "z"]},
    },
    {
        "name": "minecraft_interact_entity",
        "description": "Правый клик по сущности по id (до 4 блоков): покормить (держи еду в руке), остричь, подоить, сесть, торговать, привязать. Возвращает изменения инвентаря.",
        "inputSchema": {"type": "object", "properties": {"id": {"type": "integer"}}, "required": ["id"]},
    },
    {
        "name": "minecraft_equip",
        "description": "Экипировать предмет: броня, щит и элитры надеваются; остальное кладётся в хотбар и выбирается. Меню не должно быть открыто.",
        "inputSchema": {"type": "object", "properties": {"item": {"type": "string"}}, "required": ["item"]},
    },
    {
        "name": "minecraft_drop",
        "description": "Выбросить предмет из инвентаря: count штук или весь стак (all=true).",
        "inputSchema": {"type": "object", "properties": {"item": {"type": "string"}, "count": {"type": "integer", "minimum": 1, "maximum": 64}, "all": {"type": "boolean"}}, "required": ["item"]},
    },
    {
        "name": "minecraft_block_info",
        "description": "Подробности о блоке: id, свойства (age у пшеницы, open у двери, lit у печи, facing), уровень света, видно ли небо, твёрдость, нужен ли инструмент.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}}, "required": ["x", "y", "z"]},
    },
    {
        "name": "minecraft_alerts",
        "description": "События с прошлого вызова: полученный урон, новые враждебные мобы рядом (с id и расстоянием), смерть. Очищается при чтении. Вызывай между действиями, чтобы вовремя заметить опасность.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_chat",
        "description": "Отправить сообщение в чат или команду (строка с '/'). Для отладки и аварийных случаев, например /time set day, если читы включены.",
        "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
    },
    {
        "name": "minecraft_respawn",
        "description": "Возродиться после смерти (экран смерти).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_status",
        "description": "Диагностика: доступен ли API мода, в мире ли игрок, открытый экран/меню, измерение, текущая задача. Не падает, если игра закрыта.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_find",
        "description": "Поиск ближайших блоков/сущностей/предметов по подстроке id (iron_ore, log, zombie). kind: block|entity|item. Префикс '=' даёт точное совпадение ('=stone'). Результат отсортирован по расстоянию, у блоков есть флаг exposed (открыт ли воздуху). Радиус до 48.",
        "inputSchema": {"type": "object", "properties": {"q": {"type": "string"}, "kind": {"type": "string", "enum": ["block", "entity", "item"]}, "r": {"type": "integer", "minimum": 1, "maximum": 48}, "max": {"type": "integer", "minimum": 1, "maximum": 50}}, "required": ["q"]},
    },
    {
        "name": "minecraft_mine",
        "description": "Добыть block в количестве count: сам ищет ближайшие блоки (алиасы: tree/wood, iron, coal, diamond, stone), идёт к ним (A*), выбирает лучший инструмент, ломает, подбирает дроп. Закрытые блоки откапывает туннелем. Проверяет, хватает ли кирки. Возвращает сколько добыто и что мешает.",
        "inputSchema": {"type": "object", "properties": {"block": {"type": "string"}, "count": {"type": "integer", "minimum": 1, "maximum": 64}, "radius": {"type": "integer", "minimum": 4, "maximum": 48}}, "required": ["block"]},
    },
    {
        "name": "minecraft_dig_to",
        "description": "Прорыть туннель к точке (x, y, z) (горизонтально, лесенкой вверх/вниз), ломая блоки на пути. Останавливается перед водой и лавой.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}, "max_steps": {"type": "integer", "minimum": 1, "maximum": 200}}, "required": ["x", "y", "z"]},
    },
    {
        "name": "minecraft_collect",
        "description": "Подобрать предметы на земле в радиусе r (по умолчанию 8).",
        "inputSchema": {"type": "object", "properties": {"r": {"type": "integer", "minimum": 1, "maximum": 24}}},
    },
    {
        "name": "minecraft_surroundings",
        "description": "Компактная сводка блоков вокруг (радиус до 16): сколько каждого типа и координаты ближайшего. Руды, жидкости, верстаки, печи, сундуки показываются всегда. Лучше, чем minecraft_nearby.",
        "inputSchema": {"type": "object", "properties": {"r": {"type": "integer", "minimum": 2, "maximum": 16}}},
    },
    {
        "name": "minecraft_smelt",
        "description": "Переплавить count штук item (например raw_iron, cobblestone, oak_log) в ближайшей печи (радиус 16): сам загружает топливо, ждёт, забирает результат. Печь должна стоять в мире; её можно скрафтить (minecraft:furnace, 8 булыжников) и поставить minecraft_place с item.",
        "inputSchema": {"type": "object", "properties": {"item": {"type": "string"}, "count": {"type": "integer", "minimum": 1, "maximum": 64}}, "required": ["item"]},
    },
    {
        "name": "minecraft_open",
        "description": "Открыть меню блока (верстак, печь, сундук) по координатам. Игрок должен стоять в 4.5 блоках.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}}, "required": ["x", "y", "z"]},
    },
    {
        "name": "minecraft_menu",
        "description": "Содержимое открытого меню: тип, номера слотов, предметы.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_click",
        "description": "Клик по слоту открытого меню. type: PICKUP, QUICK_MOVE, SWAP, THROW, PICKUP_ALL. button: 0 левая, 1 правая (для SWAP — номер слота хотбара).",
        "inputSchema": {"type": "object", "properties": {"slot": {"type": "integer"}, "button": {"type": "integer"}, "type": {"type": "string"}}, "required": ["slot"]},
    },
    {
        "name": "minecraft_close",
        "description": "Закрыть открытое меню или экран.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_cancel",
        "description": "Отменить текущую задачу (goto/break/place/open) и отпустить клавиши.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_select_tool",
        "description": "Выбрать в хотбаре лучший инструмент для блока по координатам.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}}, "required": ["x", "y", "z"]},
    },
    {
        "name": "minecraft_recipes",
        "description": "Все рецепты из книги рецептов игрока: индекс, описание результата. Индекс нужен для minecraft_craft_id.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_craft_id",
        "description": "Крафт по индексу из minecraft_recipes (игровой автокрафт из инвентаря). times — сколько раз.",
        "inputSchema": {"type": "object", "properties": {"index": {"type": "integer"}, "times": {"type": "integer", "minimum": 1, "maximum": 64}}, "required": ["index"]},
    },
    {
        "name": "minecraft_debug_book",
        "description": "Служебный: дамп записей книги рецептов и коллекций (для автокрафта).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_debug_sync",
        "description": "Служебный: дамп SynchronizedRecipes и книги рецептов (для поиска списка всех рецептов).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "minecraft_debug_recipes",
        "description": "Служебный: клиентский доступ к рецептам и классы отображения результатов (дамп API).",
        "inputSchema": {"type": "object", "properties": {}},
    },
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
        "description": "Скрафтить count штук предмета по названию (oak_planks, stick, crafting_table, stone_pickaxe, furnace; префикс minecraft: необязателен). Сам выбирает рецепт из книги или шаблон, при необходимости идёт к верстаку (ищет в радиусе 16), открывает его и закрывает. Результат проверяется по инвентарю.",
        "inputSchema": {"type": "object", "properties": {"item": {"type": "string"}, "count": {"type": "integer", "minimum": 1, "maximum": 64}, "times": {"type": "integer", "minimum": 1, "maximum": 64}}, "required": ["item"]},
    },
    {
        "name": "minecraft_goto",
        "description": "Идти к точке (x, y, z) по найденному пути (A*: шаги, подъём на 1, спуск до 4, обход воды и лавы). Если точка сама непроходима (блок), идёт к ближайшей. Ждёт окончания, при застревании перестраивает путь.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "number"}, "y": {"type": "number"}, "z": {"type": "number"}}, "required": ["x", "y", "z"]},
    },
    {
        "name": "minecraft_break",
        "description": "Сломать блок по координатам. Игрок должен стоять в 4.5 блоках. Ждёт окончания.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}}, "required": ["x", "y", "z"]},
    },
    {
        "name": "minecraft_place",
        "description": "Поставить блок сверху на блок по координатам. Если указан item, сам перекладывает его в хотбар и выбирает слот. Ждёт окончания.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}, "item": {"type": "string"}}, "required": ["x", "y", "z"]},
    },
    {
        "name": "minecraft_chop_tree",
        "description": "Срубить до max_logs брёвен в радиусе (до 48): то же, что minecraft_mine с block=log. Подбирает дроп.",
        "inputSchema": {"type": "object", "properties": {"radius": {"type": "integer", "minimum": 2, "maximum": 48}, "max_logs": {"type": "integer", "minimum": 1, "maximum": 30}}},
    },
    {
        "name": "minecraft_nearby",
        "description": "Сырой список блоков в кубе радиуса r (1–12). Большой вывод: лучше minecraft_surroundings или minecraft_find.",
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

STOP_PROPS = {
    "stop_health": {"type": "number", "description": "прервать задачу, если здоровье <= значения"},
    "stop_hostile": {"type": "number", "description": "прервать задачу, если враждебный моб ближе этого расстояния"},
}
LONG_OPS = ("minecraft_mine", "minecraft_dig_to", "minecraft_chop_tree")
for _t in TOOLS:
    if _t["name"] in ("minecraft_goto", "minecraft_break", "minecraft_place", "minecraft_attack", "minecraft_use_item"):
        _t["inputSchema"]["properties"].update(STOP_PROPS)
    if _t["name"] in LONG_OPS:
        # у долгих операций защита включена по умолчанию; значение 0 её отключает
        _t["inputSchema"]["properties"].update({
            "stop_health": {"type": "number", "description": "прервать, если здоровье <= значения (по умолчанию 8; 0 — выключить)"},
            "stop_hostile": {"type": "number", "description": "прервать, если враждебный моб ближе этого расстояния (по умолчанию 5; 0 — выключить)"},
            "max_seconds": {"type": "number", "description": "лимит времени операции, по умолчанию 120 (клиент обрывает вызовы дольше ~4 мин)"},
        })
        _t["description"] += " Перед стартом проверяет инструмент (почти сломанные не берёт), прерывается при низком здоровье, мобах рядом, жидкости и по лимиту времени; в ответе есть tool, tool_wear, stopped_by."
    if _t["name"] == "minecraft_recipes":
        _t["inputSchema"]["properties"].update({
            "q": {"type": "string", "description": "подстрока в названии результата (например iron_helmet); без неё вернётся только первые max записей"},
            "max": {"type": "integer", "minimum": 1, "maximum": 100},
        })
    if _t["name"] == "minecraft_place":
        _t["inputSchema"]["properties"]["face"] = {"type": "string", "enum": ["up", "down", "north", "south", "east", "west"],
                                                   "description": "грань опорного блока, к которой ставим (по умолчанию up)"}
    if _t["name"] == "minecraft_goto":
        _t["inputSchema"]["required"] = ["x", "z"]
        _t["inputSchema"]["properties"]["y"]["description"] = "высота; если не указана, идём по любой высоте. Дальше 35 блоков идёт отрезками."


def call_api(method, path, payload=None, timeout=8):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(API + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.read().decode("utf-8", "replace")
    except (urllib.error.URLError, OSError) as e:
        reason = getattr(e, "reason", e)
        if isinstance(reason, ConnectionRefusedError):
            raise RuntimeError("API мода не отвечает на 127.0.0.1:8765: игра не запущена, мод не загрузился "
                               "или порт занят. Запусти Minecraft с модом (вызови minecraft_status для диагностики).")
        raise RuntimeError(f"API мода недоступен ({reason}). Игра может быть на загрузке или зависла.")


def api(method, path, payload=None, timeout=8):
    return json.loads(call_api(method, path, payload, timeout))


def wait_job(timeout=100):
    end = time.time() + timeout
    while time.time() < end:
        st = api("GET", "/job")
        if st.get("status") != "running":
            return st
        time.sleep(0.15)
    api("POST", "/cancel", {})
    return {"status": "failed", "error": "таймаут ожидания"}


def run_job(path, payload):
    api("POST", path, payload)
    return wait_job()


def stops_of(args):
    out = {}
    if args and args.get("stop_health"):
        out["stop_health"] = float(args["stop_health"])
    if args and args.get("stop_hostile"):
        out["stop_hostile"] = float(args["stop_hostile"])
    return out


# Активные условия остановки длинной операции (mine/dig_to/chop_tree): подмешиваются во все goto/break,
# которые эта операция вызывает внутри, и проверяются между шагами через guard().
STOPPED = ("stopped", "interrupted")  # "interrupted" — статус мода при срабатывании stop_health/stop_hostile
ACTIVE = {"stops": {}, "deadline": None}


def set_active(stops, max_seconds):
    ACTIVE["stops"] = dict(stops or {})
    ACTIVE["deadline"] = (time.time() + float(max_seconds)) if max_seconds else None


def clear_active():
    ACTIVE["stops"] = {}
    ACTIVE["deadline"] = None


def guard():
    """Причина, по которой длинную операцию пора прервать, или None."""
    d = ACTIVE["deadline"]
    if d and time.time() > d:
        return "лимит времени вышел"
    s = ACTIVE["stops"]
    if s.get("stop_health"):
        hp = api("GET", "/state").get("health")
        if hp is not None and hp <= s["stop_health"]:
            return f"здоровье {hp} <= {s['stop_health']}"
    if s.get("stop_hostile"):
        r = int(min(48, max(8, s["stop_hostile"] + 2)))
        for e in api("GET", f"/entities_all?r={r}").get("entities", []):
            if e.get("hostile") and e.get("distance", 999) <= s["stop_hostile"]:
                return f"враждебный {e.get('type')} в {e.get('distance')} м"
    return None


def default_stops(args):
    """Для долгих операций защита включена по умолчанию: здоровье <= 8 и враждебный моб ближе 5 м."""
    args = args or {}
    out = {}
    hp = args.get("stop_health", 8)
    hostile = args.get("stop_hostile", 5)
    if hp:
        out["stop_health"] = float(hp)
    if hostile:
        out["stop_hostile"] = float(hostile)
    return out


def goto(x, y, z, stops=None, any_y=False):
    payload = {"x": float(x), "y": float(y if y is not None else 0), "z": float(z)}
    if any_y or y is None:
        payload["any_y"] = True
    payload.update(ACTIVE["stops"])
    payload.update(stops or {})
    return run_job("/goto", payload)


def goto_far(x, y, z, stops=None):
    """Длинный маршрут отрезками по 30 блоков (путь строится только по загруженным чанкам)."""
    for _ in range(14):
        me = api("GET", "/state")
        dx, dz = x - me["x"], z - me["z"]
        d = math.hypot(dx, dz)
        if d <= 35:
            return goto(x, y, z, stops)
        res = goto(me["x"] + dx / d * 30, None, me["z"] + dz / d * 30, stops, any_y=True)
        if res.get("status") != "done":
            return res
    return {"status": "partial", "error": "дальше 14 отрезков, повтори вызов"}


def brk(x, y, z, stops=None):
    payload = {"x": int(x), "y": int(y), "z": int(z)}
    payload.update(ACTIVE["stops"])
    payload.update(stops or {})
    return run_job("/break", payload)


def inv_counts():
    counts = {}
    for slot in api("GET", "/inventory").get("slots", []):
        counts[slot["item"]] = counts.get(slot["item"], 0) + slot["count"]
    return counts


def norm_item(name):
    name = name.strip()
    return name if ":" in name else "minecraft:" + name


def short(name):
    return name[10:] if name.startswith("minecraft:") else name


def count_of(counts, name, suffix=False):
    n = short(name)
    if suffix:
        return sum(c for i, c in counts.items() if short(i).endswith(n))
    return counts.get("minecraft:" + n, 0)


# ------------------------------------------------------------------ поиск и добыча

ALIASES = {"tree": "_log", "wood": "_log", "log": "_log", "logs": "_log", "iron": "iron_ore", "coal": "coal_ore",
           "diamond": "diamond_ore", "gold": "gold_ore", "redstone": "redstone_ore", "copper": "copper_ore",
           "lapis": "lapis_ore", "emerald": "emerald_ore"}
EXACT = {"stone", "dirt", "sand", "gravel", "clay", "grass_block", "cobblestone", "netherrack", "obsidian"}
TIERS = ["wooden", "golden", "stone", "iron", "diamond", "netherite"]
TIER_RANK = {"wooden": 0, "golden": 0, "stone": 1, "iron": 2, "diamond": 3, "netherite": 4}
# минимальный уровень кирки для добычи с дропом
NEED_PICK = {"stone": 0, "cobblestone": 0, "coal_ore": 0, "andesite": 0, "diorite": 0, "granite": 0, "sandstone": 0,
             "iron_ore": 1, "copper_ore": 1, "lapis_ore": 1, "gold_ore": 2, "redstone_ore": 2, "diamond_ore": 2,
             "emerald_ore": 2, "obsidian": 3}


def best_pick_rank(counts):
    best = -1
    for item in counts:
        n = short(item)
        if n.endswith("_pickaxe"):
            best = max(best, TIER_RANK.get(n[:-8], 0))
    return best


def find(q, kind="block", r=16, mx=10):
    qs = urllib.parse.quote(q)
    return api("GET", f"/find?kind={kind}&q={qs}&r={int(r)}&max={int(mx)}")


def resolve_block(name):
    n = short(name.strip().lower())
    if n in ALIASES:
        return ALIASES[n]
    if n in EXACT:
        return "=" + n
    return n


def collect(r=8):
    got = 0
    for _ in range(8):
        items = find("", "item", r, 6).get("results", [])
        if not items:
            break
        it = items[0]
        if it["distance"] < 1.0:
            time.sleep(0.3)
            continue
        res = goto(it["x"], it["y"], it["z"])
        got += 1
        if res.get("status") != "done":
            break
    return got


OCCLUDER = re.compile(r"\((-?\d+),(-?\d+),(-?\d+)\)")


def brk_clear(x, y, z, log=None, stops=None, depth=0):
    """Сломать блок; если его закрывает другой блок, рекурсивно убрать цепочку закрывающих и повторить.
    Луч от глаз к цели может пройти через несколько блоков подряд (диагональ вверх/вниз), поэтому
    помеху нужно ломать тем же brk_clear, а не одним brk."""
    res = {"status": "failed", "error": "не удалось сломать"}
    for _ in range(6):
        res = brk(x, y, z, stops)
        if res.get("status") == "done":
            return res
        err = res.get("error") or ""
        m = OCCLUDER.search(err)
        if "закрыт" in err and m and depth < 5:
            ox, oy, oz = map(int, m.groups())
            if (ox, oy, oz) == (int(x), int(y), int(z)):
                return res
            if log is not None:
                log.append(f"убираю мешающий блок ({ox},{oy},{oz})")
            r2 = brk_clear(ox, oy, oz, log, stops, depth + 1)
            if r2.get("status") != "done":
                return r2
            continue
        return res
    return res


def break_with_retry(t, log):
    """Сломать блок t: подойти, при необходимости убрать загораживающий блок."""
    res = goto(t["x"], t["y"], t["z"])
    if res.get("status") in STOPPED:
        return res
    if res.get("status") != "done":
        return {"status": "failed", "error": "не дойти: " + str(res.get("error"))}
    for _ in range(4):
        res = brk_clear(t["x"], t["y"], t["z"], log)
        if res.get("status") in ("done",) + STOPPED:
            return res
        err = res.get("error") or ""
        if "далеко" in err:
            g = goto(t["x"], t["y"], t["z"])
            if g.get("status") != "done":
                return g
            continue
        return res
    return {"status": "failed", "error": "не удалось сломать"}


def dig_to(x, y, z, max_steps=60):
    """Туннель к точке: горизонтально по большей оси, вверх/вниз лесенкой."""
    notes = []
    for step in range(int(max_steps)):
        reason = guard()
        if reason:
            return {"status": "stopped", "error": reason, "notes": notes}
        me = api("GET", "/state")
        cx, cy, cz = math.floor(me["x"]), math.floor(me["y"]), math.floor(me["z"])
        dx, dy, dz = x - cx, y - cy, z - cz
        if abs(dx) + abs(dz) == 0 and abs(dy) <= 1:
            return {"status": "done", "steps": step, "notes": notes}
        if abs(dx) + abs(dz) + abs(dy) <= 1:
            return {"status": "done", "steps": step, "notes": notes}
        # следующая клетка для ног
        if abs(dx) + abs(dz) == 0:
            # строго вверх без опоры не поднимешься: идём лесенкой, шаг в сторону
            nx, nz = (cx + 1, cz) if dy > 0 else (cx, cz)
        elif abs(dx) >= abs(dz):
            nx, nz = cx + (1 if dx > 0 else -1), cz
        else:
            nx, nz = cx, cz + (1 if dz > 0 else -1)
        ny = cy + (1 if dy > 0 else (-1 if dy < 0 else 0))
        to_break = [(nx, ny, nz), (nx, ny + 1, nz)]
        if ny > cy:
            to_break.append((cx, cy + 2, cz))
        if nx == cx and nz == cz and ny < cy:
            to_break = [(cx, cy - 1, cz)]
        liquids = [k for k in api("GET", "/surroundings?r=2").get("blocks", {}) if "lava" in k or k == "water"]
        if liquids:
            return {"status": "stopped", "error": "рядом жидкость: " + ",".join(liquids) + ", остановился", "notes": notes}
        for (bx, by, bz) in to_break:
            res = brk_clear(bx, by, bz, notes)
            if res.get("status") in STOPPED:
                return {"status": "stopped", "error": res.get("error") or res.get("info"), "notes": notes}
            if res.get("status") != "done":
                return {"status": "failed", "error": f"на шаге {step}: {res.get('error')}", "notes": notes}
        g = goto(nx, ny, nz)
        if g.get("status") in STOPPED:
            return {"status": "stopped", "error": g.get("error") or g.get("info"), "notes": notes}
        if g.get("status") != "done":
            return {"status": "failed", "error": f"не прошёл на ({nx},{ny},{nz}): {g.get('error')}", "notes": notes}
    return {"status": "partial", "error": "лимит шагов", "notes": notes}


def tool_kind_for(plain):
    """Какой инструмент нужен для блока (по id или алиасу из resolve_block)."""
    p = plain.lstrip("=")
    if p.endswith("_log") or p.endswith("_wood") or "planks" in p or p in ("_log", "pumpkin", "melon"):
        return "axe"
    if p in ("dirt", "sand", "gravel", "clay", "grass_block", "soul_sand", "snow", "mud", "podzol", "mycelium"):
        return "shovel"
    return "pickaxe"


def is_worn(s, margin=0.10):
    """Инструмент почти сломан: осталось <= 10% прочности (минимум 8 единиц)."""
    d, m = s.get("durability"), s.get("maxDurability")
    return d is not None and bool(m) and d <= max(8, int(m * margin))


def stash_worn_tools():
    """Убрать из хотбара почти сломанные инструменты в свободный слот основного инвентаря."""
    st = api("GET", "/status")
    if st.get("menu") not in (None, "InventoryMenu"):
        api("POST", "/close", {})
    slots = api("GET", "/inventory").get("slots", [])
    used = {s["slot"] for s in slots}
    free = [i for i in range(9, 36) if i not in used]
    moved = []
    for s in slots:
        if s["slot"] < 9 and is_worn(s) and free:
            api("POST", "/click", {"slot": free.pop(0), "button": s["slot"], "type": "SWAP"})
            moved.append(short(s["item"]))
    return moved


def prepare_tool(plain):
    """Выбрать и положить в хотбар подходящий инструмент для блока.
    Возвращает (имя_инструмента | None, ошибка | None). Ошибка только если без инструмента не обойтись (кирка)."""
    kind = tool_kind_for(plain)
    stash_worn_tools()
    slots = api("GET", "/inventory").get("slots", [])
    need = NEED_PICK.get(plain.lstrip("="), 0) if kind == "pickaxe" else 0
    cands = []
    worn_names = []
    for s in slots:
        n = short(s["item"])
        if not n.endswith("_" + kind):
            continue
        tier = n[: -len(kind) - 1]
        rank = TIER_RANK.get(tier, 0)
        if is_worn(s):
            worn_names.append(f"{n} {s.get('durability')}/{s.get('maxDurability')}")
            continue
        if rank >= need:
            cands.append((rank, s.get("durability") or 0, s["item"]))
    if not cands:
        if kind == "pickaxe":
            names = ["деревянная", "каменная", "железная", "алмазная"]
            msg = f"нет пригодной кирки (нужна {names[min(need, 3)]} или лучше)"
            if worn_names:
                msg += "; почти сломаны: " + ", ".join(worn_names)
            return None, msg
        return None, None
    # для дешёвых блоков берём самый слабый подходящий инструмент, чтобы беречь железный
    cands.sort(key=lambda c: (c[0], -c[1]))
    chosen = cands[0][2] if need <= 1 else cands[-1][2]
    if ensure_hotbar(chosen) is None:
        return None, f"не удалось положить {short(chosen)} в хотбар"
    return short(chosen), None


def tool_durabilities():
    out = {}
    for s in api("GET", "/inventory").get("slots", []):
        if s.get("durability") is not None:
            out.setdefault(short(s["item"]), []).append(s["durability"])
    return out


def wear_report(before, after):
    """Сколько прочности потеряли инструменты (по именам)."""
    rep = {}
    for name, ds in before.items():
        a = after.get(name, [])
        diff = sum(ds) - sum(a) if len(a) == len(ds) else None
        if diff:
            rep[name] = diff
    return rep


def mine(block, count=1, radius=24, stops=None, max_seconds=120):
    q = resolve_block(block)
    plain = q.lstrip("=")
    log = []
    started = time.time()
    set_active(stops, max_seconds)
    wear_before = tool_durabilities()
    mined = 0
    fails = 0
    skip = set()
    stopped_by = None
    error = None
    tool = None
    try:
        for _ in range(count * 4 + 6):
            if mined >= count or fails >= 4:
                break
            stopped_by = guard()
            if stopped_by:
                break
            tool, terr = prepare_tool(q)
            if terr:
                error = terr
                break
            res = find(q, "block", radius, 12)
            cands = [t for t in res.get("results", []) if (t["x"], t["y"], t["z"]) not in skip]
            if not cands:
                break
            exposed = [t for t in cands if t["exposed"]]
            target = (exposed or cands)[0]
            skip_key = (target["x"], target["y"], target["z"])
            if not target["exposed"]:
                log.append(f"блок {target['id']} на {skip_key} закрыт, копаю туннель")
                d = dig_to(target["x"], target["y"], target["z"], 40)
                log.append(f"туннель: {d.get('status')} {d.get('error', '')}")
                if d.get("status") in STOPPED:
                    stopped_by = d.get("error") or d.get("info") or "остановлено"
                    break
                if d.get("status") not in ("done",):
                    fails += 1
                    skip.add(skip_key)
                    continue
            before = inv_counts()
            r = break_with_retry(target, log)
            if r.get("status") == "done":
                mined += 1
                collect(6)
                after = inv_counts()
                gained = {short(i): c - before.get(i, 0) for i, c in after.items() if c > before.get(i, 0)}
                log.append(f"{target['id']} на {skip_key}: +{gained}")
            elif r.get("status") in STOPPED:
                stopped_by = r.get("error") or r.get("info") or "остановлено"
                break
            else:
                fails += 1
                skip.add(skip_key)
                log.append(f"не вышло {target['id']} на {skip_key}: {r.get('error')}")
    finally:
        clear_active()
    out = {"mined": mined, "wanted": count, "tool": tool,
           "tool_wear": wear_report(wear_before, tool_durabilities()),
           "elapsed_s": round(time.time() - started, 1), "log": log[-12:]}
    if error:
        out["error"] = error
    if stopped_by:
        out["stopped_by"] = stopped_by
        out["hint"] = "операция прервана защитой: оцени обстановку (minecraft_alerts, minecraft_entities_all) и реши, продолжать ли"
    elif mined < count and not error:
        out["hint"] = ("блоков не нашлось в радиусе — иди дальше (minecraft_goto) и повтори" if fails == 0
                       else "были неудачи, см. log; можно вызвать снова или сменить место")
    return json.dumps(out, ensure_ascii=False)


def chop_tree(radius=12, max_logs=6, stops=None, max_seconds=120):
    return mine("_log", max_logs, radius, stops, max_seconds)


# ------------------------------------------------------------------ крафт, меню, печь

ITEM_RE = re.compile(r"minecraft:[a-z0-9_]+")


def result_matches(result_str, item):
    return re.search(re.escape(item) + r"(?![a-z0-9_])", result_str) is not None


def open_block(x, y, z):
    res = run_job("/open", {"x": int(x), "y": int(y), "z": int(z)})
    return res


def station_near(kind, r=16):
    res = find("=" + kind, "block", r, 3).get("results", [])
    return res[0] if res else None


def with_station(kind, fn):
    """Подойти к станку (crafting_table/furnace), открыть, выполнить fn(), закрыть."""
    st = station_near(kind)
    if not st:
        return {"ok": False, "error": f"рядом (16 блоков) нет {kind}"}
    g = goto(st["x"], st["y"], st["z"])
    if g.get("status") != "done":
        return {"ok": False, "error": f"не дойти до {kind}: {g.get('error')}"}
    o = open_block(st["x"], st["y"], st["z"])
    if o.get("status") != "done":
        return {"ok": False, "error": f"не открыть {kind}: {o.get('error')}"}
    time.sleep(0.4)  # меню должно полностью открыться до первого клика
    try:
        return fn()
    finally:
        api("POST", "/close", {})


def craft_item(item, count=1):
    item = norm_item(item)
    inv_before = inv_counts()
    before = count_of(inv_before, item)

    def find_idxs():
        # индексы зависят от открытого меню (инвентарь/верстак) и меняются при открытии новых рецептов,
        # поэтому берём свежий список непосредственно перед каждым craft_id
        book = api("GET", "/recipes")
        return [r["index"] for r in book.get("recipes", []) if result_matches(r.get("result", ""), item)]

    idxs = find_idxs()

    def attempt_loop():
        nonlocal idxs
        stalled = 0
        last_err = None
        for _ in range(count * 2 + 2):
            have = count_of(inv_counts(), item) - before
            if have >= count:
                return True, None
            idxs = find_idxs()
            if idxs:
                r = api("POST", "/craft_id", {"index": idxs[0], "times": 1})
            else:
                r = api("POST", "/craft", {"item": item, "times": 1})
            last_err = r.get("error")
            time.sleep(0.4)  # даём меню/инвентарю синхронизироваться с сервером
            if count_of(inv_counts(), item) - before == have:
                stalled += 1
                if stalled >= 2:
                    return False, last_err or "ингредиентов не хватает или нужен верстак"
            else:
                stalled = 0
        return count_of(inv_counts(), item) - before >= count, last_err

    ok, err = attempt_loop()
    via = "инвентарь"
    if not ok:
        via = "верстак"
        res = with_station("crafting_table", lambda: dict(zip(("ok", "error"), attempt_loop())))
        ok, err = res.get("ok"), res.get("error")
    inv_after = inv_counts()
    made = count_of(inv_after, item) - before
    out = {"ok": bool(ok), "item": item, "made": made, "via": via}
    # предупреждение, если скрафтилось что-то постороннее (признак сбившегося индекса рецепта)
    extra = {short(i): c - inv_before.get(i, 0) for i, c in inv_after.items()
             if c > inv_before.get(i, 0) and i != item and not short(i).endswith(("_planks", "stick"))
             and short(i) not in ("raw_iron", "coal", "cobblestone", "dirt", "leaf_litter")}
    if extra:
        out["unexpected_new_items"] = extra
    if not ok:
        out["error"] = err
        if not idxs:
            out["hint"] = "рецепта нет в книге (открываются при наличии ингредиентов) и нет шаблона — собери ингредиенты и повтори"
    return json.dumps(out, ensure_ascii=False)


FUELS = [("coal", 8.0), ("charcoal", 8.0), ("_planks", 1.5), ("_log", 1.5), ("stick", 0.5)]


def menu_slots():
    return api("GET", "/menu")


def move_n(src, dst, n):
    """Переложить n штук из слота src в слот dst: взять стак, по одному вправо, остаток обратно."""
    api("POST", "/click", {"slot": src, "button": 0, "type": "PICKUP"})
    for _ in range(n):
        api("POST", "/click", {"slot": dst, "button": 1, "type": "PICKUP"})
    api("POST", "/click", {"slot": src, "button": 0, "type": "PICKUP"})


def smelt(item, count=1):
    item = norm_item(item)

    def work():
        m = menu_slots()
        if "Furnace" not in m.get("type", "") and "Smoker" not in m.get("type", "") and "Blast" not in m.get("type", ""):
            return {"ok": False, "error": "открылось не меню печи: " + m.get("type", "?")}
        src = next((s for s in m["slots"] if s["slot"] >= 3 and norm_item(s["item"]) == item), None)
        if not src:
            return {"ok": False, "error": f"нет {item} в инвентаре"}
        n = min(count, src["count"])
        # топливо: первое подходящее по приоритету, где хватает на всю партию, иначе самое большое
        fuel_slot = None
        fuel_units = 0
        fallback = None
        for key, per in FUELS:
            need = int(math.ceil(n / per))
            for cand in m["slots"]:
                if cand["slot"] < 3 or cand["slot"] == src["slot"]:
                    continue
                if not short(cand["item"]).endswith(key):
                    continue
                if cand["count"] >= need:
                    fuel_slot, fuel_units = cand, need
                    break
                if fallback is None or cand["count"] * per > fallback[2]:
                    fallback = (cand, cand["count"], cand["count"] * per)
            if fuel_slot:
                break
        if fuel_slot is None and fallback is not None:
            fuel_slot, fuel_units = fallback[0], fallback[1]
        if fuel_slot is None:
            return {"ok": False, "error": "нет топлива (уголь, доски, брёвна)"}
        move_n(src["slot"], 0, n)
        move_n(fuel_slot["slot"], 1, fuel_units)
        deadline = time.time() + 11 * n + 8
        out = 0
        while time.time() < deadline:
            time.sleep(1.0)
            slots = menu_slots()["slots"]
            res = next((s for s in slots if s["slot"] == 2), None)
            if res and res["count"] >= n:
                out = res["count"]
                break
            if res:
                out = res["count"]
        api("POST", "/click", {"slot": 2, "button": 0, "type": "QUICK_MOVE"})
        # забрать недоплавленное и остаток топлива
        for s in (0, 1):
            api("POST", "/click", {"slot": s, "button": 0, "type": "QUICK_MOVE"})
        return {"ok": out >= n, "smelted": out, "wanted": n, "item": item}

    return json.dumps(with_station("furnace", work), ensure_ascii=False)


def ensure_hotbar(item):
    """Положить предмет в хотбар и выбрать его слот. Возвращает слот или None."""
    item = norm_item(item)
    slots = api("GET", "/inventory").get("slots", [])
    hot = next((s for s in slots if s["slot"] < 9 and s["item"] == item), None)
    if hot:
        api("POST", "/slot", {"slot": hot["slot"]})
        return hot["slot"]
    main = next((s for s in slots if 9 <= s["slot"] < 36 and s["item"] == item), None)
    if not main:
        return None
    sel = api("GET", "/state").get("slot", 0)
    api("POST", "/click", {"slot": main["slot"], "button": sel, "type": "SWAP"})
    return sel


INTERACTIVE_EXACT = {"crafting_table", "furnace", "blast_furnace", "smoker", "chest", "trapped_chest", "barrel",
                     "ender_chest", "anvil", "chipped_anvil", "damaged_anvil", "enchanting_table", "loom",
                     "cartography_table", "smithing_table", "fletching_table", "grindstone", "stonecutter",
                     "lectern", "beacon", "hopper", "dropper", "dispenser", "brewing_stand", "note_block",
                     "lever", "bell", "cauldron", "composter", "jukebox", "crafter", "respawn_anchor"}
INTERACTIVE_SUFFIX = ("_door", "_trapdoor", "_button", "_fence_gate", "_bed", "shulker_box", "_sign")
REPLACEABLE = {"leaf_litter", "short_grass", "tall_grass", "fern", "large_fern", "snow", "vine", "dead_bush",
               "seagrass", "tall_seagrass", "glow_lichen", "moss_carpet", "pink_petals", "wildflowers"}
FACE_DELTA = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1),
              "east": (1, 0, 0), "west": (-1, 0, 0)}


def is_interactive(block_id):
    return block_id in INTERACTIVE_EXACT or block_id.endswith(INTERACTIVE_SUFFIX)


def place(x, y, z, item=None, face="up", stops=None):
    face = face or "up"
    # зависшее меню (например, верстака после крафта) перехватывает клик и мешает ставить блоки
    st = api("GET", "/status")
    if st.get("menu") not in (None, "InventoryMenu"):
        api("POST", "/close", {})
    if item:
        if ensure_hotbar(item) is None:
            return json.dumps({"status": "failed", "error": f"нет {norm_item(item)} в инвентаре"}, ensure_ascii=False)
    payload = {"x": int(x), "y": int(y), "z": int(z), "face": face}
    payload.update(stops or {})
    # на верстак, печь, сундук, дверь и т.п. без приседания клик открывает блок, а не ставит
    sneak = False
    try:
        sup = api("GET", f"/block_info?x={int(x)}&y={int(y)}&z={int(z)}").get("id", "")
        sneak = is_interactive(sup)
    except Exception:
        pass
    if sneak:
        api("POST", "/key", {"action": "sneak", "down": True})
    try:
        res = run_job("/place", payload)
        err = res.get("error") or ""
        # на месте установки растёт заменяемая мелочь (сухие листья, трава): убираем и повторяем один раз
        if err.startswith("место занято: "):
            occupant = err.split(": ", 1)[1].strip()
            if occupant in REPLACEABLE:
                d = FACE_DELTA.get(face, (0, 1, 0))
                brk(int(x) + d[0], int(y) + d[1], int(z) + d[2])
                res = run_job("/place", payload)
                err = res.get("error") or ""
        if err.startswith("блок не поставлен"):
            res["hint"] = ("возможные причины: целевая клетка пересекается с игроком (отойди), опорный блок не твёрдый, "
                           "в руке не блок")
    finally:
        if sneak:
            api("POST", "/key", {"action": "sneak", "down": False})
    return json.dumps(res, ensure_ascii=False)


# ------------------------------------------------------------------ наблюдение за последствиями

WEAPON_ORDER = ["netherite_sword", "diamond_sword", "iron_sword", "stone_sword", "golden_sword", "wooden_sword",
                "netherite_axe", "diamond_axe", "iron_axe", "stone_axe", "golden_axe", "wooden_axe"]


def equip_best_weapon():
    """Взять в руку лучший меч/топор из инвентаря. Возвращает короткое имя или None."""
    counts = inv_counts()
    for w in WEAPON_ORDER:
        if counts.get("minecraft:" + w, 0) > 0:
            if ensure_hotbar(w) is not None:
                return w
    return None


def snapshot():
    st = api("GET", "/state")
    return {"inv": inv_counts(), "hp": st.get("health"), "food": st.get("food")}


def observed(fn):
    """Выполнить действие и вернуть результат вместе с изменениями инвентаря, здоровья и голода."""
    before = snapshot()
    res = fn()
    time.sleep(0.15)
    after = snapshot()
    delta = {}
    for item in set(before["inv"]) | set(after["inv"]):
        d = after["inv"].get(item, 0) - before["inv"].get(item, 0)
        if d:
            delta[short(item)] = d
    return json.dumps({"result": res, "inventory_delta": delta,
                       "health": [before["hp"], after["hp"]], "food": [before["food"], after["food"]]}, ensure_ascii=False)


ARMOR_SUFFIX = ("_helmet", "_chestplate", "_leggings", "_boots")


def menu_slot_of(inv_slot):
    return 36 + inv_slot if inv_slot < 9 else inv_slot


def equip(item):
    item = norm_item(item)
    status_now = api("GET", "/status")
    if status_now.get("menu") != "InventoryMenu":
        return {"ok": False, "error": "открыто другое меню, закрой его (minecraft_close)"}
    slots = api("GET", "/inventory").get("slots", [])
    src = next((sl for sl in slots if sl["item"] == item), None)
    if not src:
        return {"ok": False, "error": f"нет {item} в инвентаре"}
    n = short(item)
    if n.endswith(ARMOR_SUFFIX) or n in ("shield", "elytra", "totem_of_undying"):
        api("POST", "/click", {"slot": menu_slot_of(src["slot"]), "button": 0, "type": "QUICK_MOVE"})
        st = api("GET", "/status")
        return {"ok": True, "armor": st.get("armor")}
    slot = ensure_hotbar(item)
    return {"ok": slot is not None, "slot": slot}


def drop(item, count=1, all_=False):
    item = norm_item(item)
    slots = api("GET", "/inventory").get("slots", [])
    src = next((sl for sl in slots if sl["item"] == item), None)
    if not src:
        return {"ok": False, "error": f"нет {item} в инвентаре"}
    ms = menu_slot_of(src["slot"])
    if all_:
        api("POST", "/click", {"slot": ms, "button": 1, "type": "THROW"})
    else:
        for _ in range(min(count, src["count"])):
            api("POST", "/click", {"slot": ms, "button": 0, "type": "THROW"})
    return {"ok": True}


# ------------------------------------------------------------------ диагностика и сводка

def status():
    try:
        st = api("GET", "/status")
    except Exception as e:
        return json.dumps({"api": "down", "hint": str(e)}, ensure_ascii=False)
    st["api"] = "up"
    if not st.get("inWorld"):
        st["hint"] = "игрок не в мире: зайди в мир (или экран загрузки ещё идёт)"
    return json.dumps(st, ensure_ascii=False)


def situation(radius=8):
    state = api("GET", "/state")
    inv = api("GET", "/inventory")
    mobs = api("GET", f"/entities?r={radius * 2}")
    counts = {}
    for slot in inv.get("slots", []):
        counts[slot["item"]] = counts.get(slot["item"], 0) + slot["count"]
    nearest_log = None
    items = []
    if state.get("inWorld"):
        logs = find("_log", "block", max(radius, 8), 1).get("results", [])
        nearest_log = logs[0] if logs else None
        items = find("", "item", radius, 8).get("results", [])
    warnings = []
    if state.get("health", 20) <= 8:
        warnings.append("мало здоровья")
    if state.get("food", 20) <= 6:
        warnings.append("голод")
    if mobs.get("hostile"):
        warnings.append(f"враждебные мобы рядом: {len(mobs['hostile'])}")
    pick = best_pick_rank(counts)
    return json.dumps({
        "state": state,
        "inventory": counts,
        "best_pickaxe": None if pick < 0 else ["wooden", "stone", "iron", "diamond", "netherite"][pick],
        "nearest_log": nearest_log,
        "items_on_ground": items,
        "hostile_mobs": mobs.get("hostile", []),
        "warnings": warnings,
    }, ensure_ascii=False)


def run_tool(name, args):
    if name == "minecraft_status":
        return status()
    if name == "minecraft_find":
        return call_api("GET", f"/find?kind={args.get('kind', 'block')}&q={urllib.parse.quote(args['q'])}"
                               f"&r={int(args.get('r', 16))}&max={int(args.get('max', 10))}")
    if name == "minecraft_mine":
        return mine(args["block"], int(args.get("count", 1)), int(args.get("radius", 24)),
                    default_stops(args), float(args.get("max_seconds", 120)))
    if name == "minecraft_dig_to":
        set_active(default_stops(args), float(args.get("max_seconds", 120)))
        try:
            res = dig_to(int(args["x"]), int(args["y"]), int(args["z"]), int(args.get("max_steps", 60)))
        finally:
            clear_active()
        return json.dumps(res, ensure_ascii=False)
    if name == "minecraft_collect":
        return json.dumps({"visited": collect(int(args.get("r", 8)))}, ensure_ascii=False)
    if name == "minecraft_surroundings":
        return call_api("GET", f"/surroundings?r={int(args.get('r', 8))}")
    if name == "minecraft_smelt":
        return smelt(args["item"], int(args.get("count", 1)))
    if name == "minecraft_open":
        return json.dumps(open_block(args["x"], args["y"], args["z"]), ensure_ascii=False)
    if name == "minecraft_menu":
        return call_api("GET", "/menu")
    if name == "minecraft_click":
        return call_api("POST", "/click", {"slot": int(args["slot"]), "button": int(args.get("button", 0)), "type": args.get("type", "PICKUP")})
    if name == "minecraft_close":
        return call_api("POST", "/close", {})
    if name == "minecraft_cancel":
        return call_api("POST", "/cancel", {})
    if name == "minecraft_select_tool":
        return call_api("POST", "/select_tool", {"x": int(args["x"]), "y": int(args["y"]), "z": int(args["z"])})
    if name == "minecraft_recipes":
        # полный список весит ~190 КБ и не влезает в ответ: отдаём только индекс и результат, с фильтром
        data = api("GET", "/recipes")
        allr = data.get("recipes", [])
        q = (args.get("q") or "").strip().lower()
        lim = int(args.get("max", 40))
        rows = [{"index": r["index"], "result": ITEM_RE.search(r.get("result", "")).group(0)
                 if ITEM_RE.search(r.get("result", "")) else r.get("result", "")[:80]}
                for r in allr if not q or q in r.get("result", "").lower()]
        return json.dumps({"total_in_book": len(allr), "matched": len(rows), "recipes": rows[:lim]}, ensure_ascii=False)
    if name == "minecraft_craft_id":
        return call_api("POST", "/craft_id", {"index": int(args["index"]), "times": int(args.get("times", 1))})
    if name == "minecraft_debug_book":
        return call_api("GET", "/debug_book")
    if name == "minecraft_debug_sync":
        return call_api("GET", "/debug_sync")
    if name == "minecraft_debug_recipes":
        return call_api("GET", "/debug_recipes")
    if name == "minecraft_debug_api":
        return call_api("GET", "/debug_api")
    if name == "minecraft_situation":
        return situation(int(args.get("radius", 8)))
    if name == "minecraft_entities":
        return call_api("GET", f"/entities?r={int(args.get('r', 16))}")
    if name == "minecraft_craft":
        return craft_item(args["item"], int(args.get("count", args.get("times", 1))))
    if name == "minecraft_goto":
        y = args.get("y")
        return json.dumps(goto_far(float(args["x"]), None if y is None else float(y), float(args["z"]), stops_of(args)), ensure_ascii=False)
    if name == "minecraft_break":
        return json.dumps(brk(args["x"], args["y"], args["z"], stops_of(args)), ensure_ascii=False)
    if name == "minecraft_place":
        return place(args["x"], args["y"], args["z"], args.get("item"), args.get("face", "up"), stops_of(args))
    if name == "minecraft_entities_all":
        return call_api("GET", f"/entities_all?r={int(args.get('r', 16))}")
    if name == "minecraft_attack":
        payload = {"id": int(args["id"]), "hits": int(args.get("hits", 1)), "chase": bool(args.get("chase", True))}
        payload.update(stops_of(args))
        equip_best_weapon()
        return observed(lambda: run_job("/attack", payload))
    if name == "minecraft_use_item":
        payload = {"hold_ticks": int(args.get("hold_ticks", 0)), "release": bool(args.get("release", False))}
        if args.get("item"):
            slot = ensure_hotbar(args["item"])
            if slot is None:
                return json.dumps({"ok": False, "error": f"нет {norm_item(args['item'])} в инвентаре"}, ensure_ascii=False)
            payload["slot"] = slot
        elif "slot" in args:
            payload["slot"] = int(args["slot"])
        payload.update(stops_of(args))
        return observed(lambda: run_job("/use_item", payload))
    if name == "minecraft_interact_block":
        payload = {"x": int(args["x"]), "y": int(args["y"]), "z": int(args["z"]), "face": args.get("face", "up")}
        return observed(lambda: api("POST", "/interact_block", payload))
    if name == "minecraft_interact_entity":
        return observed(lambda: api("POST", "/interact_entity", {"id": int(args["id"])}))
    if name == "minecraft_equip":
        return json.dumps(equip(args["item"]), ensure_ascii=False)
    if name == "minecraft_drop":
        return json.dumps(drop(args["item"], int(args.get("count", 1)), bool(args.get("all", False))), ensure_ascii=False)
    if name == "minecraft_block_info":
        return call_api("GET", f"/block_info?x={int(args['x'])}&y={int(args['y'])}&z={int(args['z'])}")
    if name == "minecraft_alerts":
        return call_api("GET", "/alerts")
    if name == "minecraft_chat":
        return call_api("POST", "/chat", {"text": args["text"]})
    if name == "minecraft_respawn":
        return call_api("POST", "/respawn", {})
    if name == "minecraft_chop_tree":
        return chop_tree(int(args.get("radius", 12)), int(args.get("max_logs", 6)),
                         default_stops(args), float(args.get("max_seconds", 120)))
    if name == "minecraft_nearby":
        return call_api("GET", f"/nearby?r={int(args.get('r', 4))}")
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
    # ensure_ascii=True: вывод чисто ASCII, не зависит от кодовой страницы консоли Windows (cp1251/charmap)
    sys.stdout.write(json.dumps(msg, ensure_ascii=True) + "\n")
    sys.stdout.flush()


def handle(req):
    method = req.get("method")
    msg_id = req.get("id")
    if method == "initialize":
        return reply(msg_id, {
            "protocolVersion": PROTOCOL,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "minecraft-agent", "version": "1.1.0"},
        })
    if method == "notifications/initialized":
        return None
    if method == "ping":
        return reply(msg_id, {})
    if method == "tools/list":
        return reply(msg_id, {"tools": TOOLS})
    if method == "tools/call":
        params = req.get("params", {})
        try:
            text = run_tool(params.get("name"), params.get("arguments") or {})
            return reply(msg_id, {"content": [{"type": "text", "text": text}], "isError": False})
        except Exception as e:
            return reply(msg_id, {"content": [{"type": "text", "text": str(e)}], "isError": True})
    if msg_id is not None:
        return reply(msg_id, error={"code": -32601, "message": f"Method not found: {method}"})
    return None


def main():
    # На Windows stdio по умолчанию в cp1251/charmap: принудительно UTF-8 (русский текст в запросах и ошибках)
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        req = None
        try:
            req = json.loads(line)
            handle(req)
        except Exception as e:
            try:
                sys.stderr.write(f"[minecraft-agent] ошибка: {e}\n")
                sys.stderr.flush()
            except Exception:
                pass
            # не оставляем клиента без ответа
            if isinstance(req, dict) and req.get("id") is not None:
                try:
                    reply(req["id"], error={"code": -32603, "message": str(e)})
                except Exception:
                    pass


if __name__ == "__main__":
    main()
