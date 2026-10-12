---
name: modded_world
description: 这个世界装了哪些模组、每个模组里有哪些物品。用 toolbox.mods 看清全局,用 toolbox.items 按关键词跨模组找到确切物品 id,再交给 numen.gear.hold 之类去用。
---

# 模组世界:先看清有什么,再去找东西

这个世界不止原版。你能像主人一样知道服务器装了哪些模组、每个模组加了什么,并按名字找到任何一件物品的确切 id。

## 看装了哪些模组

- `toolbox.mods.list()`:全部模组,每个给 `id`、`name`、`version`。
- `toolbox.mods.has("superbwarfare")`:装没装某个模组。
- `toolbox.mods.search("warfare")`:按关键词找模组(id 或显示名)。

模组 id 是物品 id 的前缀:`superbwarfare:ak_47` 来自 `superbwarfare`。

## 找物品

- `toolbox.items.search("rifle")`:跨所有模组按关键词搜,匹配物品 id 和显示名,每行给 `id`、`name`、`mod`、`max_stack`。
- `toolbox.items.search("ammo", {mod = "superbwarfare", limit = 20})`:只在某个模组里搜,并限个数。
- `toolbox.items.of_mod("superbwarfare")`:某个模组加的全部物品。
- `toolbox.items.info("superbwarfare:ak_47")`:一件物品的详情;id 不写命名空间时按 `minecraft:` 算。

关键词为空就是"把这个模组的都列出来"。搜到的 `id` 直接就能用:`numen.gear.hold(id)` 拿到手上、`numen.use.item` 用它。

## 什么时候用

- 主人提到一个你没见过的物品、方块或枪,先 `toolbox.items.search` 找到确切 id,别猜。
- 要用某个模组的东西之前,先 `toolbox.mods.has` 确认它在,再 `toolbox.items.of_mod` 看它有什么。
