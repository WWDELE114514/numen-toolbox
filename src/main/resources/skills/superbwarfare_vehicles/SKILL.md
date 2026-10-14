---
name: superbwarfare_vehicles
description: 骑上卓越前线的坦克、炮车、自行火炮之后怎么开炮。炮塔不跟你的视线走，用 toolbox.vehicles.fire 让它自己索敌、算提前量、转炮塔再打。
---

# 卓越前线载具:开炮的火控

骑上坦克、炮车、自行火炮之后，火炮**不跟你的视线走**——它跟炮塔走，而炮塔得有人拧。你自己瞄不准，
炮弹还会飞到旁边打到人。用 `toolbox.vehicles` 这一组把火控整套交给它。

## 先看这辆车

`toolbox.vehicles.status()` 给你:炮塔有没有(`turret`)、炮叫什么(`weapon`)、剩多少炮弹(`ammo`)、
初速(`velocity`)、是不是抛物线弹(`ballistic`，迫击炮是、激光不是)、射程(`range`)、炮塔坏了没有(`turretDamaged`)。

```lua
local v = toolbox.vehicles.status()
print(v.kind, v.weapon, v.ammo, v.range)
```

## 开火

`toolbox.vehicles.fire()` —— 它自己干这几件事:

1. **索敌**:以炮口为心，在炮塔的偏转/俯仰射界和射程内挑最近的敌人，被挡住的不算。
2. **算提前量**:目标在动，炮弹还要飞一阵。它按弹速、重力、目标速度解出飞行时间，往目标将要到的
   地方打——比直瞄准得多。激光类没有飞行时间，直接指。
3. **转炮塔**:每刻按这座炮塔自己的转速拧过去，夹在射界里，和真人转得一样快。
4. **开火**:炮口对准到容差之内才打，一发一发按这门炮的射速来。

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

## 注意

- **它不会打你、你的主人、同队的人，也不会打你骑的这辆车。**
- 炮弹打光了要先装填——走界面那套，或者下车、再骑上去。
- 你**不用看着目标**，炮塔自己会转；但你也别乱转头，炮塔方向和你的朝向是两回事。
- 迫击炮(`ballistic = true`)是抛物线，近距离打不到——目标太近时它会等目标进射界。
