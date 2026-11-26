# PocketLinux · 设计稿

- 日期：2026-11-26
- 状态：草案（待 M0 验证后转定稿）
- 代号：PocketLinux（手机上的 Linux 桌面 + Windows 兼容层）

## 1. 背景

用户希望在 Android 手机上获得一个兼容性极强的环境：

1. 完整的 Linux 桌面，作为生产力底座；
2. 通过 Wine 运行 Windows（x86/x64）程序；
3. 后续扩展：PPSSPP / RetroArch 等游戏机模拟器、更多平台应用。

目标设备：Redmi K80 至尊版（天玑 9400+ / Immortalis-G925，Android 15，内核 6.6.77，16GB RAM）。
开发方式：AI 单线程实现，用户负责安装 APK 与体验测试，无人工编码环节。
协作约束：每轮交付必须是「可直接安装的 APK + 一句话验收清单」，不要求用户执行任何命令。

## 2. 目标与非目标

### MVP 目标（第一版）

- 安装 APK → 首次准备 → 进入 XFCE 桌面；
- 桌面内可用：终端、文件管理、浏览器、中文显示与中文输入；
- Wine 可安装并运行 exe（2D 游戏、老游戏、轻量 Windows 工具）；
- 在天玑 9400+ 上稳定，冷启动 ≤ 30 秒、二次进入 ≤ 20 秒；
- 从安装到开玩 ≤ 5 步，全中文。

### 非目标（MVP 明确不做）

- 游戏机模拟器整合、云配置同步、多用户、账号体系、商店与内购；
- 骁龙专项优化（后续兼容，不作为验收门槛）；
- 反作弊游戏（内核级反作弊永久不支持）；
- D3D12 重度 3D 游戏（VKD3D 在天玑上不具备可用性）。

## 3. 产品形态

用户旅程（5 步）：

1. 从官网下载并安装 APK；
2. 首次启动自动准备运行时（解压 rootfs、初始化桌面，不含下载 ≤ 5 分钟）；
3. 进入 XFCE 桌面，可像电脑一样使用；
4. 通过「Windows 程序」入口选择 exe → 自动创建独立 wine prefix → 运行；
5. 在桌面与 Windows 窗口之间切换，退出时保留容器状态。

产品定位：**手机上的 Linux 电脑**，Wine 是其中一项能力，不是「Windows 模拟器」。

## 4. 架构

五层，自研只覆盖 L4/L5 与部分 L2：

| 层 | 组成 | 来源 | 许可证 |
|---|---|---|---|
| L1 显示与输入 | X Server、Surface 输出、键鼠/触屏注入 | Termux:X11 | GPL-3.0 |
| L2 运行时 | proot 容器、Debian rootfs、文件系统映射 | 自研组装（沿用 Termux 生态思路） | 自有 |
| L3 兼容引擎 | Wine 构建、Box64、vortek（Vulkan wrapper）、gladio（GL wrapper）、android_alsa | Winlator 11.2 资产 | LGPL-2.1 |
| L4 桌面 | XFCE4、中文语言包、fcitx5、常用应用集 | 自研预设 | 自有 |
| L5 壳 | Compose UI、安装器、桌面启动、Windows 程序入口、性能面板 | 自研 | 自有 |

### 数据流

Compose 壳 → 启动 X11 Server（Java 侧 Surface / EGL）→ proot 内启动 XFCE 会话 → 桌面内应用与 Wine 进程共同 attach 到同一 X Server → Surface 合成显示。

### 关键边界

- GPU 加速只能经由 Android framework 的 EGL/Vulkan（bionic 侧），不能依赖 `/dev/mali*`；本机探测已确认 proot 内无 GPU 设备节点。
- 壳不修改 Wine / Box64 / vortek 源码，只做进程编排、环境变量与配置下发，降低许可证与维护成本。

## 5. 关键决策与理由

- **D1 显示栈选 Termux:X11，不选 Winlator XServer**：Winlator 的 XServer 是面向 Wine 的精简 Java 实现，缺少 XFCE 所需的 XRender/XKB/RANDR 等能力。
- **D2 接受 GPL-3.0 全量开源**：Termux:X11 为 GPL-3.0，整包分发即触发开源义务。用户已确认接受，商业化不以闭源为前提。
- **D3 `targetSdk=28`，官网分发**：Android 10+ 的 W^X 限制会阻止 proot 内二进制执行，Winlator 同样采用该策略；代价是无法上架 Play/国内商店。
- **D4 GPU 走 framework**：复用 vortek（Vulkan wrapper）与 gladio（OpenGL→GLES wrapper），保证在 Mali 上有一条降级路径。
- **D5 不自研转译层与 Wine**：直接复用成熟资产，产品价值放在「壳 + 配置 + 兼容库 + 中文体验」。

## 6. 风险与验证计划（M0）

已在目标机确认的事实：

- proot 环境可在 K80 上运行（本设计对话本身就跑在其内），UID 0 可用，`/sdcard` 可访问，exec 在该环境下可行；
- proot 内 `/dev/mali*`、`/dev/kgsl*` 不可见，GPU 必须走 framework。

待验证探针（任一失败则调整方案）：

| # | 探针 | 判定标准 | 失败后备 |
|---|---|---|---|
| P1 ✅ | 自研壳（targetSdk 28）内 proot 执行二进制 | **已验证通过**：私有目录执行 ELF 成功，`exit=0`，SELinux 域 `untrusted_app_27`，/data 无 noexec | — |
| P2 | Termux:X11 在 K80 上输出画面 | 60fps 稳定合成，触屏坐标正确 | 换 Xvfb + VNC（体验降级） |
| P3 | XFCE4 在 Termux:X11 下启动 | 桌面、窗口、任务栏正常 | 降级 LabWC / Openbox |
| P4 | Wine 运行一个 exe | `notepad.exe` 与一个 2D 游戏可运行 | 换 Wine 版本 / Box64 预设 |
| P5 | Mali-G925 Vulkan 被 vortek 接住 | DXVK 测试程序可渲染 | 降级 gladio（GLES），牺牲 D3D11 |

## 7. 里程碑与验收

| 里程碑 | 周期 | 验收 |
|---|---|---|
| M0 生死验证 | 2 周 | 五个探针全绿，形成验证报告 |
| M1 骨架 | 4 周 | K80 上点开 App → 30 秒进 XFCE，中文显示、WiFi、终端可用 |
| M2 闭环 | 4 周 | 桌面内运行 exe、中文输入法、剪贴板互通 |
| M3 打磨 | 3 周 | 性能预设、崩溃恢复、兼容配置库 |
| M4 发布 | 1 周 | 官网 APK、安装文档、反馈渠道 |

总量约 14 周（乐观估计，按复用比例推算）。

## 8. 分发与合规

- 官网 APK 直发，`targetSdk=28`，附源码仓库（GPL-3.0）；
- 不内置游戏、BIOS、Windows 镜像或任何授权素材，exe 由用户自备；
- 首次启动展示来源与免责声明；
- 第三方组件许可证清单随包提供（Wine/LGPL、Termux:X11/GPL-3.0、XFCE/GPL 系）。

## 9. 后续路线（非 MVP）

1. 模拟器整合（PPSSPP / RetroArch 作为桌面应用或独立入口）；
2. 兼容性配置库云端下发（游戏 → 最佳参数）；
3. 骁龙适配与更多机型；
4. 桌面环境可选（XFCE / LabWC）。

## 10. 内嵌架构（开箱即用 APK）

用户明确要求单 APK 开箱即用，不依赖用户另装 Termux 或 Termux:X11。App 内置以下资产，首次启动释放到私有目录：

| 资产 | 内容 | 体积 |
|---|---|---|
| bootstrap | Termux 官方 usr 目录（bionic 二进制：bash/coreutils/tar/dpkg） | 28MB 压缩 / 77MB 解压 |
| proot | Termux proot 5.1.107 + libandroid-shmem + libtalloc | <200KB |
| rootfs | Ubuntu Base 24.04 arm64，首次启动后续装 XFCE | 30MB 压缩 / 105MB 解压 |
| X Server | lorie（libXlorie.so，复用 Termux:X11 官方 APK 中的预编译库） | ~4MB |
| 壳 | 解压器、启动器、Surface 显示、生命周期 | 自研 |

APK 体积预算约 75–90MB；XFCE 依赖（约 300MB）在首次启动后由 rootfs 内 apt 安装。

关键约束：APK 内的 ELF 无法直接执行（APK 是 zip），必须首次启动解压到私有目录后再调用；C 组件一律复用预编译 `.so`，不在本机构建（Android NDK 无 aarch64 宿主版）。

## 11. M0 验证记录（2026-11-26）

| 项 | 状态 | 证据 |
|---|---|---|
| P1 私有目录 exec | ✅ 通过 | 探针 APK 在 K80 上执行 ELF 成功，`PROBE_OK`、`exit=0`、SELinux `untrusted_app_27` |
| 本机构建链 | ✅ 打通 | Gradle 8.7 + AGP 8.5.2 + AndroidIDE aapt2 34.0.4（aarch64 静态链接）；产物可安装运行 |
| P2 X Server 画面 | ⏳ 待设备 | 需 Termux:X11 运行 |
| P3 XFCE 启动 | ⏳ 待设备 | |
| P4 Wine 运行 exe | ⏳ 待设备 | |
| P5 Mali Vulkan | ⏳ 待设备 | 已确认 `/vendor/lib64/hw/vulkan.mali.so` 存在（ARM r49p1） |

设备与构建环境事实：

- Redmi K80 至尊版 / MT6991（天玑 9400+）/ Mali-G925 Immortalis MC12 / Android 16 (API 36) / KernelSU root
- compileSdk 必须固定为 34：aapt2 34.0.4 无法加载 API 35 的 android.jar
- HyperOS 拦截 `adb install`（`INSTALL_FAILED_USER_RESTRICTED`），开发期用 root 执行 `pm install` 部署

## 12. 无 root 路线（DroidDeck 参考，2026-11-26 新增）

用户明确要求：**必须无 root**，root 仅作为稳定性增强的可选项。

### 12.1 根本约束（M0 实测）

- Android 16 SELinux 禁止 App 私有目录（`app_data_file`）**mmap 执行动态库**
- 静态二进制 ✅、bionic 程序（INTERP 在 `/system`）✅、glibc 动态程序 ❌
- 我们第一版走 su 是权宜之计，不是产品方案

### 12.2 DroidDeck（22k★，targetSdk 34）的做法

调研结论（tools/proot/PATCHES.md、tools/build.sh、app/build.gradle）：

1. **proot 不是放在 files 目录，而是编译成 `libproot.so` 打进 APK 的 `lib/arm64-v8a/`**
   - 安装后位于 `nativeLibraryDir`，SELinux 上下文是 `apk_data_file` —— **允许执行**
2. **loader 同理**：`libproot-loader.so` 也在 APK lib 目录
3. **proot 用 NDK 自行编译**（termux/proot 4dba3afb = 5.1.107），静态链接 talloc
4. 携带一批 Android 兼容 patch：
   - 硬链接被 SELinux 拒绝（Android 禁止 app 建硬链接）
   - 可执行栈被拒（`EACCES` 导致程序启动前就挂）
   - `execveat` 绝对路径、`clone3`、seccomp 优化等

### 12.3 我们的实施路线

1. 把 proot + loader 从 `assets` 改为 `jniLibs/*.so`，从 `nativeLibraryDir` 执行
2. 实测 glibc bash 能否被拉起
3. 若仍被拒，直接复用 DroidDeck 的预编译 `libproot.so` + `libproot-loader.so`（GPLv2+ 开源，需保留来源与许可）
4. root 存在时：检测到 su 则走 root 域（更稳），否则走无 root 路径

### 12.4 显示链路（已实现）

自研 ~150 行 RFB 客户端（Raw 编码）替代 noVNC：
`APK → proot(UFW 无) → Xvnc(:99) → websockify:6080 → WebView RFB → canvas`

## 13. 无 root 验证通过（2026-11-26 晚，关键结论）

### 13.1 根因（真正的拦路虎）

不是 SELinux 禁止 `app_data_file` 执行，而是 **MCS category 不匹配**：

| 场景 | 文件上下文 | 结果 |
|---|---|---|
| App 自己解压 assets | `app_data_file:s0:c139,c257,c512,c768` | ✅ 可执行 |
| 用 root 从 `/data/local/tmp` `cp -a` 复制 | `app_data_file:s0`（无 category） | ❌ `Permission denied` |

`cp -a` 保留了来源的 SELinux 上下文（无 category），而进程在 `c139` 下运行，MCS 隔离直接拒绝 exec。

### 13.2 修复与验证

- **rootfs 必须由 App 自己创建**（assets 解压或 proot 内 apt 安装）
- 验证结果：`NO_ROOT_PROOT_OK`（无 root、无 su，proot + glibc bash 完全跑通）
- proot 本体从 `nativeLibraryDir`（`apk_data_file`）执行，依赖库用符号链接桥接

### 13.3 修正后的无 root 链路

```
APK assets(rootfs.tar) 
  → App 自己解压到 files/rootfs（继承 c139 category）
  → nativeLibraryDir/libproot.so 执行
  → 无 root 安装 XFCE（proot 内 apt）
  → Xvnc + XFCE + websockify + 自研 RFB 客户端
```

### 13.4 与 DroidDeck 的对比

| 项 | DroidDeck | 我们 |
|---|---|---|
| 体积 | 100MB APK + 数 GB 组件 | 31MB APK + ~1.5GB rootfs |
| 功能 | Steam + Wine + 多桌面 | 只做 XFCE（更轻） |
| 卡顿原因 | Steam/游戏栈 + VNC 渲染 | 需优化 VNC 分辨率与编码 |
| 参考点 | proot 放 APK lib 目录、自建 rootfs | 已复现并验证 |

### 13.5 性能优化方向（针对"卡"）

1. 降低虚拟分辨率（1280x800 → 1024x640）
2. RFB 增量更新（已实现局部矩形）
3. 减少帧率/合并请求（仅在有更新时请求）
4. 后续可换 Tight/ZRLE 编码（需扩客户端）
