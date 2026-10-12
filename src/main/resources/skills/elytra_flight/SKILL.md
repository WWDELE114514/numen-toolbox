---
name: elytra_flight
description: 穿鞘翅飞行:从背包穿上鞘翅、起跳滑翔、一路转向滑翔到某个坐标、降落。装了言出法随就能用 toolbox.elytra。
---

# 穿鞘翅飞行

你能像主人一样穿鞘翅飞。函数在 `toolbox.elytra` 组里。

## 一步步来

- `toolbox.elytra.status()`:背上是不是鞘翅、现在是不是在滑翔。
- `toolbox.elytra.wear()`:把背包里的鞘翅穿到背上(原来穿的胸甲回背包)。没有鞘翅会以 `no_material` 失败——先 `toolbox.items.search("elytra")` 找一副,或者让主人给。
- `toolbox.elytra.takeoff()`:穿上(需要的话)、跳一下、打开滑翔。**最好从高处起飞**:在平地上会立刻落地。
- `toolbox.elytra.fly_to({x = 120, y = 80, z = -35})`:一边滑翔一边把视线对准这个点,一路飞过去。可加 `arrive`(多近算到,默认 3 格)。
- `toolbox.elytra.land()`:停下滑翔,让重力接手。

## 要记住的

- **滑翔只能往下换速度**:目标比你现在高很多时飞不到,挑个低一点的落点,或者先落下来走上去。
- `takeoff` 和 `fly_to` 是占身体的活:调用之后一直到滑翔结束(或到达、落地、超时)才返回,期间别指望同时做别的。
- 飞之前抬头看看:别一头扎进墙里或岩浆里。快到地方了用 `fly_to` 给个稳妥的落点,或者 `land` 下来。
- 鞘翅会消耗耐久,飞完记得留意它还在不在身上。
