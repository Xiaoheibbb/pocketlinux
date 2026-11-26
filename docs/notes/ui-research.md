# 竞品 UI 调研：安卓上的 Linux 桌面 / Windows 游戏壳

- 日期：2026-11-27
- 目的：PocketLinux 首页 / Steam 快速启动 / 设置页改版前，先看成熟方案怎么组织信息、有哪些功能项
- 结论先行：**我们不做 Winlator 的「容器」，改为「桌面入口 + Steam 快捷启动 + 程序库」三层结构；渲染器等后端能力先以设置项占位，不放假开关。**

## 1. Winlator（最直接的对标）

- 官网 / 源码：<https://winlator.org/>、<https://github.com/brunodev85/winlator>
- 中文笔记载体的 shortcuts 文档：<https://github.com/K11MCH1/Winlator101/blob/main/docs/shortcuts.md>

观察到的信息架构：

1. 首页是**快捷方式（Shortcuts）列表**，不是「设置首页」。用户的主路径是「点游戏 → 开玩」，容器/环境配置是可选项。
2. **容器（Container）** 管一套环境：屏幕尺寸、Graphics Driver（Turnip/VirGL/Zink）、DX Wrapper（DXVK/VKD3D/WineD3D）、Box64 预设、Windows 版本、环境变量、目录映射。
3. **每个快捷方式可以覆盖容器设置**（per-game profile），这是「兼容性玄学」类 App 的核心能力。
4. 快捷方式管理菜单：Add to Home Screen / Remove / Export for Frontend / Clone to Another Container / Properties（游玩统计）。
5. 设置按 **General / Advanced / Environment Variables / Directories** 分 Tab，Box64 预设分 `Performance`、`Stability` 等档位，README 明确建议「出问题先换 Box64 preset」。
6. 引导文案很具体：首次安装等待环境解压、Mono 用于 .NET、老游戏加 `MESA_EXTENSION_MAX_YEAR=2003`、Unity 改 Box64 preset 或加 `-force-gfx-direct`。

对我们的启发：入口卡片必须直给（桌面 / Steam / 游戏），设置项必须有**一句人话说明 + 当前值**；渲染器和 Box64 这类高风险选项要能选、但要说清「何时生效 / 是否已接通」。

## 2. DroidDeck（Steam 优先的安卓掌机壳）

- 官网：<https://droiddeck.app/>
- 设置分区地图（很有参考价值）：<https://github.com/droid-deck/droiddeck/blob/main/docs/settings-map.md>
- 0.6.7 更新说明：<https://github.com/Droid-Deck/DroidDeck/blob/main/docs/releases/0.6.7.md>

观察到的做法：

1. **首页 = Library**：一个游戏库合并 Steam/GOG/Epic/Xbox 等来源，用 Source chips 过滤、Filter/Sort、Installed only；卡片是「art-first」（大图 + 底部渐隐标题 + 角标状态）。
2. 游戏详情页有 **大号 Play capsule**（磨砂玻璃风格主按钮），About / Achievements / Launch / Saves 分 Tab。
3. 设置按场景分组：**Overview / Performance / Controller / Launcher / Developer / Diagnostics / Storage**，而不是按技术栈分组；每项设置可被搜索命中（`droiddeck://settings/...` 深链）。
4. 会话内是**覆盖式菜单**（Steam quick menu、in-game menu），不改主页视觉。
5. 支持竖屏 launcher + 竖屏 session，底部导航；手柄/触屏两套交互都做。

对我们的启发：Steam 页应该像「库 + 大号启动按钮」；设置分组用**用户场景**（显示/性能/图形/Wine/高级），每项带当前值和说明；深色 + 一个强调色 + 少量角标，足够高级。

## 3. Cassia（未发布即终止，但设计目标值得看）

- Phoronix 报道：<https://www.phoronix.com/news/Cassia-Windows-Games-Android>
- 官方 FAQ：<https://gist.github.com/PixelyIon/94bb9aa8d999d9886575a2d52020325c>
- 项目终止报道：<https://www.androidauthority.com/cassia-app-emulator-cancelled-3451597/>

观察到：

1. 定位是 **all-in-one、plug-and-play**：不需要 Termux、Input Bridge 等外部 App，「一键开玩」是核心卖点。
2. 明确把 **Steam 等游戏启动器列为第一优先级**（虽然 CEF 在 FEX 下性能差）。
3. 输入同时支持手柄 + 触屏虚拟按键；GPU 侧优先 Adreno，Mali 只保证「能用」。
4. 不试图跑完整 Windows，只跑应用。

对我们的启发：**一个 App 内完成全部操作**的路线是对的（我们已经把 noVNC 内嵌在 MainActivity 里，比 Termux:X11 + 浏览器的组合体验好）；Steam 是用户最在意的场景，值得单独一页。

## 4. 盖世游戏（GameHub，小鸡快跑）

- 应用宝页面（含官方功能描述）：<https://sj.qq.com/appdetail/com.xiaoji.egggame>
- 官网：<https://gamehub.xiaoji.com/>

官方描述里的功能结构：

1. **竖屏瀑布流找游戏、横屏秒变掌机**（双形态布局）。
2. 绑定 **Steam / EPIC 账号**，把 PC 游戏库延伸到手机（云/串流/本地混合）。
3. 支持 **PC 独立游戏与复古游戏基础文件自主导入**，导入即存、离线可玩。
4. 云游戏「秒玩」（不占存储）。
5. 手柄连接管理与硬件体检。

对我们的启发：中文用户对「Steam 库 + 本地导入」的心智已经形成；我们没有账号体系和云游戏后端，这一版做「Steam 快捷方式 + 本地导入」，文案里明确说清边界，不画饼。

## 5. Termux:X11（我们没采用、但要知道它的问题）

- 项目：<https://github.com/termux/termux-x11>
- 使用说明：<https://deepwiki.com/termux/termux-x11/6.2-usage-and-common-tasks>

它是「Termux 的 X Server 外挂」，用户需要自己装 Termux、跑 proot、配 DISPLAY、再切 App，学习成本高；Winlator/Cassia/盖世都在刻意消灭这种多 App 拼接。**我们继续用「MainActivity 内嵌 noVNC」的一条龙方案，不引入 Termux:X11 的交互负担。**

## 6. 取舍与落地决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 首页结构 | 桌面大卡 + Steam 卡 + 程序网格（非 Tab） | 竖屏手机上「一屏看到三个主入口」比 Winlator 的纯 shortcut 列表更适合本 App 的「桌面 + Wine 双能力」定位 |
| 容器概念 | 暂不引入 | 当前只有一套固定 rootfs + 单一 wine prefix，做「容器管理」是假功能；先做全局设置，后续再升级 per-game |
| 渲染器 | 设置页保存 `DXVK / DXVK(vortek) / wined3d` 三选一，标「待接入」 | 后端 `run-wine.sh` 目前没有接 `WINEDLLOVERRIDES`/dxvk 切换，先存值 + 说明，避免假开关 |
| Box64 预设 | 设置页占位（性能/平衡/兼容），标「未实现」 | 同 Winlator 的 Advanced 页思路，但后端未读取该值 |
| Steam | 独立页 + 快捷方式本地 JSON（参照 GameStore） | 账号登录/CEF/`steam -applaunch <appid>` 都需要后端加 `EXTRA_ARGS`，这一版做「已安装游戏的快捷启动」 |
| 视觉 | 深色 #0B0C11 底、#171A23 卡片、圆角 16–22dp、主色 #2A6CFF、状态胶囊 | 用户指定主色；Winlator 的 Material 深色 + DroidDeck 的胶囊/角标是当前同类产品的主流观感 |
| 资源 | 不用图片/图标库，emoji + 首字母色块 | 项目无 androidx、无图片资源，保持 APK 小、零第三方依赖 |

## 7. 本版仍未做 / 需要后端配合

1. **渲染器真正生效**：需要 `MainActivity.runExeInWine()` / `desktopScript()` 读取 `Settings.renderer`，设置 `WINEDLLOVERRIDES`、DXVK dll 路径、`WINEDLLPATH` 等；vortek 需要对应的 `.so`。
2. **Box64 预设真正生效**：需要给 wine 命令加 `BOX64_*` 环境变量（Winlator 的 Performance/Stability 是一组 dynarec 参数）。
3. **Steam 账号库 / appid 启动**：需要 `MainActivity` 支持 `EXTRA_ARGS`（如 `-applaunch 730`）或协议 URL 转发；当前 Steam 页只能直接启动 exe 快捷方式。
4. **游戏图标/封面**：DroidDeck/盖世是 art-first，我们需要图标提取（PE 资源解析）或 SteamGridDB 拉图，属于后端任务。
5. **per-game 设置**（分辨率/渲染器覆盖）：`Game.java` 已有 width/height/dxvk 字段，但启动链路未读取，UI 暂不暴露。
6. **输入法/触控设置页**：Winlator 有独立 Input Controls 页；我们目前把触屏说明放在 MainActivity 的「?」帮助里，后续可单独做一页。
