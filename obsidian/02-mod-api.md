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
| POST /craft | minecraft_craft | старый крафт по шаблонам (2×2 и 3×3) |

Задачи (`break`, `goto`, `place`) выполняются в клиентском тике; MCP ждёт их завершения через /job.
