---
name: superbwarfare_weapons
description: 卓越前线(SuperbWarfare)的枪:怎么列出和查看枪的属性、怎么把枪拿到手上、开火(可选三种火控:弹道预判/直指/盯住)、可点名目标和换弹。装了卓越前线才有 toolbox.sbw。
---

# 卓越前线的枪

服务器装了卓越前线(SuperbWarfare)。你能像主人一样用它的枪:查枪、拿枪、扣扳机、换弹。函数在 `toolbox.sbw` 组里。

## 看有哪些枪

- `toolbox.sbw.weapons()`:全部的枪,每行给 `id`、`name`、`damage`(单发伤害)、`magazine`(弹匣容量)、`rpm`(每分钟射速)、`velocity`、`range`、`projectile`
- `toolbox.sbw.info("superbwarfare:ak_47")`:一把枪的详情。id 不写命名空间时按 `superbwarfare:` 算

## 用枪

1. 先找到枪和子弹:`toolbox.items.of_mod("superbwarfare")`,或者 `toolbox.items.search("ak_47")`、`toolbox.items.search("ammo")`
2. 把枪拿到手上:`numen.gear.hold("superbwarfare:ak_47")`。子弹留在背包里就行,换弹时从背包取
3. 开火
   - `toolbox.sbw.fire()`:朝当前视线方向打一发
   - `toolbox.sbw.fire({target = 184, count = 5})`:先把准星对住实体 184,再打 5 发。目标编号从 `numen.scan.entities` 来
4. 换弹:`toolbox.sbw.reload()`

## 弹药种类与切换

一把枪能吃好几种子弹时，一次只装一种:

```lua
local kinds = toolbox.sbw.ammo()          -- 这个枪能吃哪些子弹
for _, a in ipairs(kinds) do print(a.index, a.item, a.loaded) end
toolbox.sbw.shift(1)                       -- 切到第 1 种
toolbox.sbw.reload()                       -- 用新弹种把弹匣装满
```

`ammo()` 每行给 `item`、`name`、`index`(就是 shift 要用的号)、`loaded`(现在是不是这一种)。
`shift` 会把膛里那一发还回你的背包，不浪费。

## 火控:三种瞄准,一次选一种

`aim` 这一项决定怎么瞄:

| 值 | 怎么瞄 | 什么时候用 |
|---|---|---|
| `ballistic`(默认) | 按子弹飞行时间算提前量,往目标将要到的地方打 | 打移动目标,最准 |
| `direct` | 直接对准目标中心,不算提前量 | 近处、或者子弹没飞行时间的激光类 |
| `visual` | 只把你的视线拧过去,不动枪口方向 | 有些枪的子弹从眼睛出 |

```lua
toolbox.sbw.fire({target = 184, count = 20, aim = "ballistic"})
toolbox.sbw.fire({target = 90, count = 3, aim = "direct"})
```

认不出的值会以 `bad_argument` 失败。

## 打起来是什么节奏

节奏跟着枪自己走,不是你另算一套:

- **只有真的能打才走冷却**。换弹、拉栓、过热、没弹药的时候计时归零;重新能打的那一刻**首发立刻出去**,不会再等一个完整的冷却。
- 一 tick 内该打几发就补几发,卡一下不会少吃子弹。
- 半自动和连发的最后一发会额外等一手,和那把枪自己的脾气一样。
- 开火前会先瞄一小会儿(大约半秒),瞄稳了才打第一枪。

## 要记住的

- **点名的目标会先问主人**(和攻击一样要过权限层);不点名就是朝前放,不用问
- 枪里没弹药时 `fire` 会以 `no_material` 失败,回执会提示先换弹;背包里没子弹时 `reload` 也失败,先去找对应的弹药物品
- `fire` 是占身体的活:它自己会拉栓、该换弹就换,做完才返回实际打出的发数,期间你做别的事会被顶掉
- 打谁之前先看清是谁:`numen.scan.entities` 里挑,**别打主人或同伴**
- 换着用:目标跑得快就 `ballistic`,目标贴着你就 `direct`
