---
name: superbwarfare_vehicles
description: 骑上卓越前线的坦克、炮车、自行火炮之后怎么开炮、怎么装炮弹、怎么切换弹种。炮塔不跟你的视线走，用 toolbox.vehicles 让它自己索敌、算提前量、转炮塔再打。
---

# 卓越前线载具:开炮、装填、切弹种

骑上坦克、炮车、自行火炮之后，火炮**不跟你的视线走**——它跟炮塔走，而炮塔得有人拧。你自己瞄不准，
炮弹还会飞到旁边打到人。用 `toolbox.vehicles` 这一组把火控整套交给它。

## 先看这辆车和这门炮

```lua
local v = toolbox.vehicles.status()   -- 炮塔、炮名、剩多少炮弹、初速、射程、炮塔坏没坏
local a = toolbox.vehicles.ammo()     -- 这门炮吃什么炮弹、车上有多少、你身上有多少
print(v.kind, v.weapon, v.ammo, v.range)
for _, k in ipairs(a.kinds) do print(k.index, k.item, k.aboard) end
```

`status()` 给你:炮塔有没有(`turret`)、炮叫什么(`weapon`)、剩多少炮弹(`ammo`)、初速(`velocity`)、
是不是抛物线弹(`ballistic`，迫击炮是、激光不是)、射程(`range`)、炮塔坏了没有(`turretDamaged`)。

`ammo()` 给你:
- `kinds`:这门炮能装的每一种炮弹，`index` 就是后面 `load`/`shift` 要用的号
- `aboard`:车上已经有多少这个
- `loaded`:现在能打多少发
- `backpackFed`:true 表示炮弹从**车上的储物**里直接吃(坦克主炮多是这个),没有弹匣

## 装炮弹(开不了炮通常就是这一步)

**载具的炮弹不在你的背包里**——炮从车自己的储物里数弹药、吃弹药。所以开火前得先把炮弹搬上车。

```lua
toolbox.vehicles.load()                    -- 有什么装什么，能装多少装多少
toolbox.vehicles.load({kind = 0})          -- 只装第 0 种炮弹
toolbox.vehicles.load({kind = 1, stacks = 3}) -- 第 1 种，搬 3 组
```

它一组一组地搬，搬到车上放不下、或者你身上没有了为止。返回 `{stacks = 装了几组, carried = 你身上还剩多少}`。

炮弹从哪儿来:`toolbox.items.search("shell")` 或 `toolbox.items.of_mod("superbwarfare")` 找，
`numen.gear.hold(...)` 拿到手上，**再骑上车**，然后 `toolbox.vehicles.load()`。

## 切换弹种

一门炮能吃好几种炮弹时(高爆弹 / 破甲弹 / 烟雾弹……)，一次只装一种:

```lua
toolbox.vehicles.shift(1)     -- 切到第 1 种
toolbox.vehicles.fire()       -- 打完新弹种要重新装填
```

切换会把膛里那一发放回车上的储物，不浪费。

## 开火

`toolbox.vehicles.fire()` —— 它自己干这几件事:

1. **索敌**:以炮口为心，在炮塔的偏转/俯仰射界和射程内挑最近的敌人，被挡住的不算。
2. **算提前量**:目标在动，炮弹还要飞一阵。它按弹速、重力、目标速度解出飞行时间，往目标将要到的
   地方打——比直瞄准得多。激光类没有飞行时间，直接指。
3. **转炮塔**:每刻按这座炮塔自己的转速拧过去，夹在射界里，和真人转得一样快。
4. **开火**:炮口对准到容差之内才打，一发一发按这门炮的射速来。
5. 顺手把你的视线也拧向目标——**它不会再看你**，除非你在打的目标旁边。

```lua
toolbox.vehicles.fire()                              -- 自己找目标，默认打 10 发
toolbox.vehicles.fire({shots = 5})                   -- 就打 5 发
toolbox.vehicles.fire({seconds = 20})                -- 打 20 秒
toolbox.vehicles.fire({target = 184, shots = 3})     -- 点名打某个实体
```

打某个具体的目标前它会先问主人（攻击行为）。

## 它交回什么

`{shots = 实际打了几发, target = 打了谁, because = 为什么停}`。

`because` 几种:打完了(`done`)、到时间了(`time_up`)、视野里没目标(`no_target`)、炮弹打光了、
你下车了(`off_vehicle`)、被叫停了(`interrupted`)。

## 一整套流程长这样

```lua
-- 上车前把炮弹拿在手上
numen.gear.hold("superbwarfare:shell_120mm")
-- 骑上车(对着它右键，或 numen.use.entity)
numen.use.entity(812)
-- 装弹，然后开
toolbox.vehicles.load()
toolbox.vehicles.fire({shots = 5})
```

`toolbox.vehicles.status()` 说 `you are not riding` 就是你还没骑上去——先 `numen.scan.entities` 找到车，
`numen.use.entity(车的编号)` 骑它。

## 注意

- **它不会打你、你的主人、同队的人，也不会打你骑的这辆车。**
- **打不响先 `toolbox.vehicles.ammo()` 看 `loaded`**——0 就是没装弹，`load()` 一下。
- 你**不用看着目标**，炮塔自己会转。
- 迫击炮(`ballistic = true`)是抛物线，近距离打不到——目标太近时它会等目标进射界。
