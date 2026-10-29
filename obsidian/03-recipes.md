# Рецепты и крафт

- Книга рецептов клиента: `ClientRecipeBook` в поле игрока. Коллекции: `getCollections()` → `RecipeCollection.getRecipes()` → записи `RecipeDisplayEntry`.
- У записи: `id()` → `RecipeDisplayId` (поле `index()`), `display()` → `RecipeDisplay` (есть `result()`).
- Автокрафт: `MultiPlayerGameMode.handlePlaceRecipe(containerId, RecipeDisplayId, false)`, затем забрать результат `handleContainerInput(menuId, 0, 0, ContainerInput.QUICK_MOVE, player)`.
- Полный список рецептов сервера (Fabric): `SynchronizedRecipesImpl.recipes()`. Клиентский доступ: `ClientPacketListener.recipes()` → `ClientRecipeContainer.getSynchronizedRecipes()`.
- Тип ContainerInput в 26.3 (не ClickType): PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL.
- Размер сетки: `CraftingMenu.getGridWidth()/getGridHeight()`, у `InventoryMenu` тоже есть.
- Проверено в игре: `/recipes` вернул 1 рецепт (верстак, индекс 339) — книга содержит только открытые рецепты.
- Не проверено: крафт по индексу (`/craft_id`) — не было ингредиентов.
