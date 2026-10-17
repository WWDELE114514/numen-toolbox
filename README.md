# Numen:Extra

[言出法随(Numen)](https://github.com/Dwinovo/minecraft-numen)的附属模组。装上它，她多几样本事——看清这世界装了哪些模组、跨模组找东西、存取精妙背包、用卓越前线的枪和车、穿鞘翅飞。

下面按用途说，每一组都在 Lua 里以 `toolbox.` 开头。没装某个可选模组时，对应的那一组不会出现，其余照常。

## 模组感知与物品搜索

`toolbox.mods` 和 `toolbox.items` 是一对，配合着用。

- `toolbox.mods.list()` 列出服务器加载的每个模组，给 `id`、`name`、`version`。
- `toolbox.mods.has("superbwarfare")` 判断某个模组在不在。
- `toolbox.mods.search("warfare")` 按 id 或名字找模组。

模组 id 是它物品 id 的前缀，所以知道装了哪些模组，就能顺藤摸瓜找到东西：

- `toolbox.items.search("rifle")` 跨所有模组按关键词搜，匹配物品 id 和显示名，每行给确切 `id`、`name`、`mod`、`max_stack`。
- `toolbox.items.search("ammo", {mod = "superbwarfare", limit = 20})` 只在某个模组里搜、并限条数。
- `toolbox.items.of_mod("superbwarfare")` 列出某个模组的全部物品。
- `toolbox.items.info("superbwarfare:ak_47")` 查一件物品的详情；id 不写命名空间时按 `minecraft:` 算。

搜到的 `id` 直接就能用：`numen.gear.hold(id)` 拿到手上，`numen.use.item` 用它。

## 精妙背包

精妙背包和原版容器不是一回事：它的内容不在那件物品身上，而是按一个 UUID 存在世界的一份存档数据里。所以你能找到背包这件物品、原版那套读容器却读不出它装了什么，`numen.gui.view` 也看不见。`toolbox.backpack` 这一组直接向精妙背包要它的存储，读写都不经过界面，不用按键也不用走路。

- `toolbox.backpack.packs()` 列出你手上、身上、界面上开着的三个背包，每个给 `where`（held / worn / open）、`id`、`slots`、`filled`、`upgraded`。
- `toolbox.backpack.items()` 读一个背包里每样东西，给 `item`、`name`、`count`、`slot`；`{where = "worn"}` 读穿在身上的那个。
- `toolbox.backpack.put("minecraft:cobblestone", {where = "worn"})` 把身上的存进去。
- `toolbox.backpack.take("minecraft:torch", {where = "worn", count = 32})` 取出来。

两个都返回 `{moved = 搬了多少, left = 剩下的}`，装到你背包满了或背包满了为止。

## 卓越前线的枪

装了 [SuperbWarfare](https://github.com/Mercurows/SuperbWarfare) 才有这一组。

- `toolbox.sbw.weapons()` 列出全部枪，`toolbox.sbw.info("superbwarfare:ak_47")` 查一把。
- 把枪拿到手上后 `toolbox.sbw.fire()` 开火；`toolbox.sbw.reload()` 换弹。
- `toolbox.sbw.ammo()` 看这把枪吃哪几种子弹，`toolbox.sbw.shift(1)` 切换——切完膛里那发会还回你背包，不浪费，再 `reload()` 用新弹种装满。

开火时可选三种瞄准，一次选一种：`ballistic`（默认）按子弹飞行时间算提前量，打移动目标最准；`direct` 直接对准目标；`visual` 只把你的视线拧过去。节奏跟着枪自己走——只有真能打才走冷却，重新能打那一刻首发立刻出去，卡一下也不会少吃子弹。

## 卓越前线的车

骑上坦克、炮车、导弹车之后用这一组。先分清两件事：炮怎么打是 `fire`，炮弹从哪来是 `ammo`/`load`。

`toolbox.vehicles.status()` 看这辆车：炮塔有没有（`turret`）、这一座的炮叫什么（`weapon`）、现在能打几发（`ammo`）、初速、是不是抛物线弹、射程、炮塔坏没坏。`weapon` 这一项是按她坐的座位算出来的——每辆车的武器名是自己起的，坦克是 `Cannon` 和 `MachineGun`、飞机是 `Main`，所以她坐在哪个座，打的就是那座上的炮。

炮弹不在她背包里，炮从车自己的储物里数弹药、吃弹药。所以开火前得先把炮弹搬上车：

- `toolbox.vehicles.ammo()` 看这门炮吃哪几种炮弹（`kinds`，每个带 `index`、`item`、车上已有的数量 `aboard`）、现在能打几发、是不是从车里直接吃（`backpackFed`）、你身上带着多少。
- `toolbox.vehicles.load()` 把炮弹一组组搬上车；`{kind = 1, stacks = 3}` 指定第 1 种搬 3 组。
- `toolbox.vehicles.shift(1)` 一门炮能吃多种炮弹时切换，切完膛里那发还回车里。

`toolbox.vehicles.fire()` 开火，它会自己找人、算提前量、拧炮塔、打。`{shots = 5}` 打 5 发，`{seconds = 20}` 打 20 秒，`{target = 184}` 点名打某个实体（会先问主人）。交回 `{shots, target, because}`，`because` 说清为什么停：done、time_up、no_target、no_ammo、off_vehicle、interrupted。

一套流程是：把炮弹拿在手上 → 骑上车（`numen.use.entity` 车的编号）→ `toolbox.vehicles.load()` → `toolbox.vehicles.fire()`。

### 火控是怎么打的

坦克、炮车的火炮不跟她的视线走，跟炮塔走，而炮塔得有人拧，这一组就把拧炮塔接了过来。难的地方在于每门炮脾气差得远——坦克主炮打一发就得换五秒弹，机枪没弹匣、从弹药里直接供着按射速连发，导弹打出去自己会追目标。所以这里不给开火定任何"隔几刻一发"的规矩，能不能打、什么时候能打，一律问炮自己的状态：弹药够不够、是不是在换弹、是不是在拉栓、有没有过热，它说能打才打，不能打就等，不硬戳。

炮弹的提前量用卓越前线自己的弹道解算，按弹速、重力和目标速度算它飞多久、该瞄目标将要到的地方。坦克炮有下坠、弹速也不快，不算这一笔弹会全落在目标脚前。导弹和激光不飞时间，直接指目标就行——导弹发射后自己纠偏追，给它算提前量反而一上来就歪丢了。索敌只挑炮塔射界和射程内最近的敌人，制导武器按它自己的锁定射程算，能到几百格；被挡住的不算，她自己、主人、同队、她骑的这辆车一律不打。

转炮塔直接调卓越前线自己的函数，跟真人转得一样自然。开火时她的视线也顺着炮口走，所以她看着目标，不会一会儿回头看主人一眼。

## 鞘翅

- `toolbox.elytra.status()` 看现在穿没穿、在不在滑翔。
- `toolbox.elytra.wear()` 从背包拿一件鞘翅穿上。
- `toolbox.elytra.takeoff()` 穿上（若需要）、起跳、开始滑翔。
- `toolbox.elytra.fly_to({x = 120, y = 80, z = -35})` 一路操纵着滑翔到某点。
- `toolbox.elytra.land()` 停下滑翔落下去。

## 权限命令

`/numen op <名字> [等级]` 和 `/numen deop <名字>` 把她的权限接到原版 op 列表上，让她跟玩家一样按权限等级执行命令。

## 依赖

Minecraft 1.21.1、NeoForge 21.1.203+、言出法随（必需）。卓越前线和精妙背包可选，没装时对应的那一组不出现。

## 顺带修掉的一个本体 bug

`numen.use.item` 在言出法随 0.1.4.1 里把返回类型写成了 `Clicks.Clicked`。可对一个会开界面的东西（精妙背包、末影箱、工作台……）右键时，真正交回的其实是 `GuiOps.Window`。派发器拿着 Clicked 的编码器去编码一个 Window 会抛异常，一路冒到服务器整刻，直接把服务器带崩。本模组在服务器第一次刻把这个函数的返回编码器换成一个按运行期类型编码的，不动言出法随本身的代码就补上了。

## 构建

```bash
./gradlew build
```

产物在 `build/libs/`，也可以直接看 GitHub Actions 的构建结果。

## 许可

LGPL-3.0-only
