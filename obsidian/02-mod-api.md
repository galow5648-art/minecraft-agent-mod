# API мода и MCP-инструменты

| Эндпоинт | MCP-инструмент | Что делает |
|---|---|---|
| GET /state | minecraft_state | позиция, взгляд, здоровье, голод, слот |
| POST /key | minecraft_key | зажать/отпустить: forward, back, left, right, jump, sneak, sprint, attack, use |
| POST /look | minecraft_look | yaw, pitch |
| POST /slot | minecraft_slot | слот 0–8 |
| GET /nearby?r= | minecraft_nearby | блоки вокруг (без воздуха) + предметы на земле |
| GET /look_at | minecraft_look_at | блок в прицеле |
| GET /inventory | minecraft_inventory | предметы по слотам |
| GET /entities?r= | minecraft_entities | враждебные мобы |
| GET /situation | minecraft_situation | сводка (через MCP) |
| POST /break | minecraft_break | сломать блок (дистанция ≤ 4.5) |
| POST /place | minecraft_place | поставить блок сверху |
| POST /goto | minecraft_goto | идти к точке, спускаться, прыгать |
| GET /job | — | статус последней задачи |
| GET /recipes | minecraft_recipes | рецепты из книги игрока |
| POST /craft_id | minecraft_craft_id | крафт по индексу через автокрафт игры |
| GET /debug_api, /debug_recipes, /debug_sync, /debug_book | minecraft_debug_* | служебные дампы классов |
| POST /craft | minecraft_craft | крафт по названию и количеству: книга рецептов, затем шаблон, при нужде верстак (MCP сам идёт, открывает, закрывает) |
| GET /status | minecraft_status | диагностика: API, мир, экран, меню, измерение, задача |
| POST /cancel | minecraft_cancel | отменить текущую задачу |
| GET /find?kind=&q=&r=&max= | minecraft_find | поиск блоков/сущностей/предметов по подстроке id (`=stone` — точно), сортировка по расстоянию, флаг exposed |
| GET /surroundings?r= | minecraft_surroundings | компактная сводка блоков вокруг (количество и ближайший) |
| POST /select_tool | minecraft_select_tool | лучший инструмент хотбара для блока (`break` делает это сам) |
| POST /open | minecraft_open | открыть меню блока (верстак, печь, сундук) |
| GET /menu, POST /click, POST /close | minecraft_menu, minecraft_click, minecraft_close | работа с открытым меню (слоты, PICKUP/QUICK_MOVE/SWAP) |

Только в MCP (оркестрация поверх API): `minecraft_mine` (найти, дойти, сломать, подобрать, откопать туннелем), `minecraft_dig_to`, `minecraft_collect`, `minecraft_smelt` (печь: топливо, ожидание, забор), `minecraft_place` с параметром `item`.

Безопасность: запросы с заголовком `Origin` (из браузера) получают 403. HTTP-сервер многопоточный (4 потока).
`/goto` — A* по загруженным блокам (шаг, подъём на 1, спуск до 4, обход воды и лавы), пересчёт при застревании; если цель непроходима, идёт к ближайшей клетке.

Задачи (`break`, `goto`, `place`) выполняются в клиентском тике; MCP ждёт их завершения через /job.
