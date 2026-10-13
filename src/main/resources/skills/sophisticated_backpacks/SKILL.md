---
name: sophisticated_backpacks
description: 精妙背包里的东西怎么读、怎么存取。它的内容按 UUID 存在世界里、不在物品自身，所以 numen.gui.view 和 toolbox.items 都读不到；用 toolbox.backpack 这一组直接读写。
---

# 精妙背包:里面装了什么,怎么存取

精妙背包和原版容器不是一回事:它的内容不在那件物品身上,而是按一个 UUID 存在世界里的一份存档数据里。
所以你能找到背包这件物品(`toolbox.items.search("backpack")`),却读不出它装了什么——`numen.gui.view` 也看不见,
原版那种"读容器组件"同样看不见。

## 看清有哪些背包

`toolbox.backpack.packs()`:列出你手上、身上、界面上开着的三个背包,每个给 `id`、`name`、`where`、`slots`、`filled`、`upgraded`。

```lua
for _, p in ipairs(toolbox.backpack.packs()) do
  print(p.where, p.id, p.slots, p.filled)
end
```

`where` 三种:`held`(手上)、`worn`(穿在胸口)、`open`(界面上开着的那个)。三个都可以空着。

## 读里面有什么

`toolbox.backpack.items()`:里面每样东西一条,给 `item`、`name`、`count`、`slot`。
`toolbox.backpack.items({where = "worn"})`:读穿在身上的那个。

```lua
for _, e in ipairs(toolbox.backpack.items({where = "worn"})) do
  print(e.item, e.count)
end
```

这些 `slot` 是背包自己的储物格,不是你背包的格。

## 存取

- `toolbox.backpack.put("minecraft:cobblestone")`:把身上的圆石放进(默认)手上的背包。
- `toolbox.backpack.put("minecraft:raw_iron", {where = "worn", count = 16})`:放 16 个进身上的。
- `toolbox.backpack.take("minecraft:torch", {where = "worn", count = 32})`:从身上的拿 32 个火把出来。
- `toolbox.backpack.take("minecraft:dried_kelp")`:有多少拿多少。

`put` 装到你带的那一格满了、或背包满了为止,剩下的留在你身上;`take` 拿到你背包满了为止。
两个都返回 `{moved = 实际搬了多少, left = 剩下的}`。

## 什么时候用

- 主人说"把东西收起来"、"从背包里拿点 XX" → 先 `toolbox.backpack.packs()` 看清哪个背包,再 `items`/`put`/`take`。
- `numen.gear.hold("sophisticatedbackpacks:backpack")` 拿到手上后,`toolbox.backpack.put(...)` 就能直接往里装,不必开界面。
- 要整理背包里的升级、排序这些界面上的事,才用 `numen.use.item()` 开界面,再用 `numen.gui.view/put/take/close`。

## 注意

- 没装精妙背包时这一组整个不存在,先 `toolbox.mods.has("sophisticatedbackpacks")` 确认。
- 往包里放升级、或者要触发升级效果,走界面那套(`numen.use.item` → `numen.gui`)。
