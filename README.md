# minecraft-agent-mod

Fabric-мод для Minecraft 26.3, который открывает локальный API (127.0.0.1:8765). Через него ИИ-агент может читать состояние персонажа и управлять им в одиночной игре.

## Как установить
1. Запусти `build.bat` двойным кликом.
2. Скрипт спросит разрешение на скачивание JDK 25, Gradle и Fabric API. Без твоего «y» ничего не скачивается.
3. Мод соберётся и попадёт в папку `mods` игры.
4. Запусти Minecraft с профилем Fabric 26.3.

## API
- `GET /state` — позиция, направление взгляда, здоровье, голод, выбранный слот.
- `POST /key` — `{"action":"forward","down":true}`. Действия: forward, back, left, right, jump, sneak, sprint, attack, use.
- `POST /look` — `{"yaw":0,"pitch":0}`.
- `POST /slot` — `{"slot":0}` (0–8).

## Версии
- Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, Java 25, Gradle 9.7.1, Fabric Loom 1.18-SNAPSHOT.

## Статус
Черновик. Сборка ещё не проверена на реальной машине.
