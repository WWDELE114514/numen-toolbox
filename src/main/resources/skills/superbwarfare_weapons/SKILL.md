---
name: superbwarfare_weapons
description: 卓越前线(SuperbWarfare)的枪:怎么列出和查看枪的属性,怎么把枪拿到手上、开火(可点名目标)和换弹。装了卓越前线才有 toolbox.sbw。
---

# 卓越前线的枪

服务器装了卓越前线(SuperbWarfare)。你能像主人一样用它的枪:查枪、拿枪、扣扳机、换弹。函数在 `toolbox.sbw` 组里。

## 看有哪些枪

- `toolbox.sbw.weapons()`:全部枪,每行给 `id`、`name`、`damage`(单发伤害)、`magazine`(弹匣容量)、`rpm`(每分钟射速)、`velocity`、`range`、`projectile`。
- `toolbox.sbw.info("superbwarfare:ak_47")`:一把枪的详情;id 不写命名空间时按 `superbwarfare:` 算。

## 用枪

1. 先找到枪和子弹:`toolbox.items.of_mod("superbwarfare")`,或者 `toolbox.items.search("ak_47")`、`toolbox.items.search("ammo")`。
2. 把枪拿到手上:`numen.gear.hold("superbwarfare:ak_47")`。子弹留在背包里就行,换弹时从背包取。
3. 开火:
   - `toolbox.sbw.fire()`:朝当前视线方向打一发。
   - `toolbox.sbw.fire({target = 184, count = 5})`:先把视线对准实体 184,再打 5 发。目标编号从 `numen.scan.entities` 来。
4. 换弹:`toolbox.sbw.reload()`。

## 要记住的

- **点名的目标会先问主人**(和攻击一样要过权限层);不点名就是朝前放,不用问。
- 枪里没弹时 `fire` 会以 `no_material` 失败,回执会提示先换弹;背包里没子弹时 `reload` 也失败,先去找对应的弹药物品。
- `fire` 是占身体的活:它自己会拉栓、该换弹就换弹,做完才返回实际打出的发数,期间你做别的事会被顶掉。
- 打谁之前先看清是谁:`numen.scan.entities` 里挑,别打主人或同伴。
