# Numen:Extra

[言出法随(Numen)](https://github.com/Dwinovo/minecraft-numen)的附属模组,给智能体补上几件事:

- **模组感知**:`toolbox.mods.*` —— 看清服务器装了哪些模组。
- **跨模组物品搜索**:`toolbox.items.*` —— 按关键词在任何模组里找到确切物品 id。
- **精妙背包存取**:`toolbox.backpack.*` —— 读写精妙背包里的东西。它的内容按 UUID 存在世界里、不在物品自身,原版的读容器和 `numen.gui.view` 都看不见它,这一组直接向精妙背包要它的存储。
- **卓越前线武器**:`toolbox.sbw.*` —— 列出、查看、开火与换弹(装了 [SuperbWarfare](https://github.com/Mercurows/SuperbWarfare) 时)。
- **鞘翅飞行**:`toolbox.elytra.*` —— 穿上鞘翅、起飞、滑翔到某点、降落。

另加一条 `/numen op <名字> [等级]` / `/numen deop <名字>` 命令,把智能体的权限接到原版的 op 列表上,让她和玩家一样按权限等级执行指令。

## 依赖

| 模组 | 需要 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.203+ |
| 言出法随(Numen) | 必需 |
| 卓越前线(SuperbWarfare) | 可选 |
| 精妙背包(Sophisticated Backpacks) | 可选 |

没装可选模组时,对应的那一组不会出现,其余功能照常。

## 已知的本体问题与修补

`numen.use.item` 在言出法随 0.1.4.1 里声明的返回类型写成了 `Clicks.Clicked`,可右键一个会开界面的物品(精妙背包、末影箱、工作台……)时真正交回的是 `GuiOps.Window`,派发器编码时会抛异常并让服务器整刻崩掉。本模组在服务器第一次刻把这个函数的返回编码器换成一个按运行期类型编码的,不改本体的代码就把这一处补齐了。

## 构建

```bash
./gradlew build
```

产物在 `build/libs/`。也可以直接看 GitHub Actions 的构建结果。

## 许可

LGPL-3.0-only
