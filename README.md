# Numen Toolbox

[言出法随(Numen)](https://github.com/Dwinovo/minecraft-numen)的附属模组,给智能体补上四件事:

- **模组感知**:`toolbox.mods.*` —— 看清服务器装了哪些模组。
- **跨模组物品搜索**:`toolbox.items.*` —— 按关键词在任何模组里找到确切物品 id。
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

没装卓越前线时,`toolbox.sbw` 一组不会出现,其余功能照常。

## 构建

```bash
./gradlew build
```

产物在 `build/libs/`。也可以直接看 GitHub Actions 的构建结果。

## 许可

LGPL-3.0-only
