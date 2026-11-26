# PocketLinux 交接文档

- 日期：2026-11-26
- 项目代号：PocketLinux（手机上的 Linux 桌面 + Wine 兼容层）
- 工作区：`/root/workspace`（DSH proot Ubuntu 内）
- 项目目录：`/root/workspace/pocketlinux`
- 设计稿：`/root/workspace/docs/specs/2026-11-26-pocketlinux-design.md`

---

## 一句话状态

**31MB 的 APK 已经能在 K80 上自带 Ubuntu 并联网装包**；XFCE 正在安装；显示层方案已定（VNC），尚未接入。

---

## 二、设备与通道

| 项 | 值 |
|---|---|
| 机型 | Redmi K80 Ultra（25060RK16C，代号 dali） |
| 芯片 | MT6991（天玑 9400+） |
| GPU | Mali-G925-Immortalis MC12，`/vendor/lib64/hw/vulkan.mali.so`（ARM r49p1） |
| 系统 | Android 16（API 36），HyperOS 3 |
| Root | KernelSU（`su` 返回 `u:r:ksu:s0`） |

**操作通道（重要）**：

- `mcp__phone__android_shell`（dsh-go 的 Shizuku 桥）一直 `connectFailed`，不可用
- 无线调试 adb 可用过，但会断
- **实际使用：aShell You（包名 `in.hridayan.ashell`，Shizuku root 模式），可 `pm install` / `am start` / `logcat` / 执行 root 命令**
- 手机操控（`mcp__phone__screen/tap/type`）正常工作，可用于 UI 操作

---

## 三、已完成并验证（有证据）

| # | 结论 | 证据 |
|---|---|---|
| 1 | targetSdk 28 应用可在私有目录执行 ELF | 探针 APK 输出 `PROBE_OK`、`exit=0`、SELinux `untrusted_app_27`、/data 无 noexec |
| 2 | 手机内可完整构建 APK | Gradle 8.7 + AGP 8.5.2 + AndroidIDE aarch64 静态 aapt2 34.0.4，产物可安装运行 |
| 3 | APK 内置 Ubuntu 可启动 | 日志 `PROOT_OK`、`aarch64`、`ls /` 正常 |
| 4 | 内置 Ubuntu 可联网 | `apt-get update` 下载 38.3MB 成功，apt 2.8.3 |
| 5 | 部署链路 | aShell root 执行 `cp` + `pm install` + `am start` 成功 |

---

## 四、构建环境（全部在这台手机上）

- JDK 17：`/usr/lib/jvm/java-17-openjdk-arm64`（apt 安装，需先 `mkdir -p /usr/share/man/man1` 否则 dpkg 报错）
- Gradle 8.7：`/opt/gradle/gradle-8.7/bin/gradle`
- Android SDK：`/root/android-sdk`
  - `platforms/android-34`（注意：**必须 34**，aapt2 34 读不了 API 35 的 android.jar）
  - `build-tools/34.0.4`（AndroidIDE 的 aarch64 静态版，来自 https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/v34.0.4/build-tools-34.0.4-aarch64.tar.xz）
  - `platform-tools`
- 关键配置：`gradle.properties` 里 `android.aapt2FromMavenOverride=/root/android-sdk/build-tools/34.0.4/aapt2`
- **NDK 无 aarch64 宿主版**，所有 C 组件只能复用预编译 `.so`

构建命令：

```sh
cd /root/workspace/pocketlinux
export ANDROID_HOME=/root/android-sdk ANDROID_SDK_ROOT=/root/android-sdk
/opt/gradle/gradle-8.7/bin/gradle --no-daemon --console=plain :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk （31MB）
```

---

## 五、APK 内部结构（当前）

```
app/src/main/assets/
  rootfs.tar        # 104MB，Ubuntu Base 24.04 arm64（aapt2 会把 .tar.gz 自动解成 .tar！）
  native/
    proot           # Termux proot 5.1.107（bionic 动态链接）
    loader          # proot 的 libexec loader
    libandroid-shmem.so
    libtalloc.so*
```

代码：

- `MainActivity.java`：自动流程 = 解压 native → 解压 rootfs → 写 resolv.conf → 启动 proot → （可选）apt 安装 XFCE
- `TarExtractor.java`：自写 tar 解压器（处理 dir/file/symlink/PAX 长名）

proot 启动参数（关键）：

```
proot --link2symlink -0 -r <rootfs> -b /dev -b /proc -b /sys -w /root /bin/sh -c "..."
env: LD_LIBRARY_PATH=<native>  PROOT_LOADER=<native>/loader  PROOT_TMP_DIR=<files>/tmp
```

---

## 六、踩过的坑（避免重复踩）

1. **aapt2 34 无法加载 API 35 的 android.jar** → compileSdk 固定 34
2. **aapt2/zipalign 只有 x86_64 官方版** → 用 AndroidIDE 的 aarch64 **静态**版
3. **aapt2 会把 assets 里 `.tar.gz` 自动解压成 `.tar`** → 代码读 `rootfs.tar`，用 `TarExtractor.extractRaw()`
4. **HyperOS 拦截 `adb install`**（`INSTALL_FAILED_USER_RESTRICTED`）→ root 执行 `pm install`
5. **`pm install` 读不了 `/sdcard`**（`Unable to open file`）→ 先 `cp` 到 `/data/local/tmp`
6. **App 在后台被冻结**（`do_freezer_trap`）→ `settings put global cached_apps_freezer disabled` + `cmd deviceidle whitelist +com.pocketlinux.probe`
7. **root shell 的 `/data/data` 被 namespace 隔离**（只看得到 3 个目录）→ `ls /proc/<pid>/root/data/user/0/...` 可列目录，但 **proot 无法 bind 该路径**（`can't sanitize binding`），所以 root 代跑 proot 的方案行不通
8. **`dexdump` 是 bionic 程序**，在 Ubuntu proot 里跑不了
9. **App UI 文本不刷新**（只有前几行）→ 不影响功能，一切以 `logcat -s PLPROBE:I` 为准
10. **dsh-go 的 Shizuku 桥连不上**，但 Shizuku 里已授权 DSH Go

---

## 七、当前进行中的事

- **XFCE 安装**：App 已重启自动流程，`apt-get install xfce4 xvfb x11-apps imagemagick` 正在跑（约 300MB，3–8 分钟）；最后一条日志停在 `16:40:41 resolv.conf 写入`，即 apt 正在下载/解包（该阶段无输出）
- 监控日志：`/sdcard/Download/pl-progress.log`（由 aShell 后台循环每 10s 追加 logcat）
- **noVNC 已就绪**：`/root/novnc/novnc-web.tar.gz`（162KB，含 `vnc.html` + `lib/`），直接解到 APK 的 `assets/novnc/` 即可用

### VNC 显示方案（已定稿，下次直接实现）

1. rootfs 内启动：`Xvfb :99 -screen 0 1280x800x24`
2. `DISPLAY=:99 startxfce4`
3. `x11vnc -display :99 -forever -shared -nopw -listen 127.0.0.1 -rfbport 5900`
4. `websockify 6080 127.0.0.1:5900`
5. App 内 WebView 加载 `file:///android_asset/novnc/vnc.html`（页面会自动连 `ws://127.0.0.1:6080/websockify`）
6. 加分项：WebView 里加一层触屏→鼠标事件映射（noVNC 自带触屏支持）

---

## 八、下一步计划（手机充电后按序做）

1. **确认 XFCE 装完**
   ```sh
   # aShell 执行
   PID=$(pidof com.pocketlinux.probe)
   ls -l /proc/$PID/root/data/user/0/com.pocketlinux.probe/files/rootfs/usr/bin/startxfce4
   logcat -d -s PLPROBE:I | tail -8
   ```
2. **装显示组件**
   ```sh
   apt-get install -y --no-install-recommends x11vnc websockify xvfb
   ```
3. **验证 XFCE 能起（无头 + 截图）**
   ```sh
   Xvfb :99 -screen 0 1280x800x24 &
   DISPLAY=:99 dbus-launch --exit-with-session startxfce4 &
   sleep 8
   DISPLAY=:99 import -window root /sdcard/Download/xfce.png
   ```
   然后把 png 拉出来看，确认桌面渲染正常。
4. **接显示（VNC 方案）**
   - rootfs 里：`x11vnc -display :99 -forever -shared -nopw -listen 127.0.0.1 -rfbport 5900`
   - `websockify 6080 127.0.0.1:5900`
   - App 里加 WebView，加载打包进 assets 的 noVNC（`vnc.html?autoconnect=1&path=websockify`）
   - 判断标准：能在 App 里看到 XFCE 桌面并点击
5. **Wine**：rootfs 里装 Wine（Box64 转译），跑 notepad.exe 验证
6. **Mali Vulkan 实测**：装 vulkan-tools，看 `vulkaninfo` 是否列出 Mali-G925

**长期**：显示层最终要换回 lorie（libXlorie.so 复用 Termux:X11 的预编译库，Java 层自己写），VNC 只是快速验证方案。

---

## 九、关键文件路径

| 内容 | 路径 |
|---|---|
| 项目源码 | `/root/workspace/pocketlinux` |
| APK 产物 | `/root/workspace/pocketlinux/app/build/outputs/apk/debug/app-debug.apk` |
| 手机上的 APK | `/sdcard/Download/pl.apk` |
| 安装脚本 | `/sdcard/Download/install-xfce.sh`、`install-xfce2.sh` |
| 进度日志 | `/sdcard/Download/pl-progress.log` |
| 设计稿 | `/root/workspace/docs/specs/2026-11-26-pocketlinux-design.md` |
| AndroidIDE build-tools | `/root/android-sdk/build-tools/34.0.4`（静态 aarch64） |
| Ubuntu Base rootfs 源 | `/root/rootfs-build/ubuntu-base.tar.gz` |
| Termux 包解包 | `/root/termux-extract/` |

---

## 十、给下一轮 AI 的提示

- 一切以 **logcat（TAG=PLPROBE）** 为准，不要相信 App 界面上的文字（UI 刷新有 bug）
- 部署流程固定为：`cp /sdcard/Download/pl.apk /data/local/tmp/ && pm install -r /data/local/tmp/pl.apk && am start -n com.pocketlinux.probe/.MainActivity`
- 每次改动都要重新构建 + 装 + 用 logcat 验证，**不要跳过验证就说完成**
- 记住：`cached_apps_freezer` 已关闭，App 后台不会被冻结

---

## 补充（2026-11-26 23:00 后）：无 root 路线突破

### 关键结论：无 root 完全可行

| 场景 | 文件 SELinux 上下文 | exec 结果 |
|---|---|---|
| App 自己解压 assets | `app_data_file:s0:c139,...` | ✅ 成功（`NO_ROOT_PROOT_OK`） |
| root 用 `cp -a` 从 /data/local/tmp 复制 | `app_data_file:s0`（无 category） | ❌ Permission denied |

**根因是 MCS category 不匹配，不是禁止执行。** rootfs 必须由 App 自己创建。

### 当前架构（无 root）

```
assets/rootfs.tar → App 自己解压（files/rootfs，继承 c139）
nativeLibraryDir/libproot.so（apk_data_file，可执行）
  + PROOT_LOADER=nativeLibraryDir/libproot_loader.so
  + LD_LIBRARY_PATH=files/native:nativeLibraryDir
  （libtalloc.so.2 / libandroid-shmem.so 用符号链接桥接）
→ proot --link2symlink -0（必须！Android 拒绝硬链接，用符号链接模拟）
→ apt install xfce4（dpkg 需要 --link2symlink 才能建 backup link）
→ Xvnc + XFCE + websockify + 自研 RFB 客户端
```

### 已解决的其他坑

1. **硬链接被拒**：`ln` 报 Permission denied → 必须 `--link2symlink`（proot 用符号链接模拟硬链接），否则 dpkg 无法安装
2. **App 被 lowmemorykiller 杀**：安装 10 分钟期间被杀 → 加 `KeepAliveService` 前台服务（通知栏常驻）
3. **dpkg 状态损坏**：中断后 `perl-base` 进入 inconsistent state → 彻底删 rootfs 重新解压最干净

### 当前状态

- 干净 rootfs 已重新解压，正在无 root 安装 XFCE（第 N 次尝试，已带 --link2symlink + 前台服务）
- APK：`/root/workspace/pocketlinux/app/build/outputs/apk/debug/app-debug.apk`
- 设备：无线调试 192.168.31.56:45337（会变）

---

## 最终成果（2026-11-26 23:25）：纯无 root 全链路打通

```
APK(31MB) 
 → App 自己解压 rootfs（files/rootfs，SELinux category c139）
 → nativeLibraryDir/libproot.so（apk_data_file，可执行）
 → proot --link2symlink -0  （模拟硬链接，dpkg 需要）
 → 无 root apt install xfce4（run-as / App 进程内）
 → Xvnc(:99) + XFCE + websockify(6080)
 → WebView 内置 RFB 客户端 → 桌面显示 + 触摸操控
```

验证日志：
```
### 环境已就绪，启动桌面
STARTING
DESKTOP_READY
已加载 VNC 页面
fb 1280x800
```
连接全部 ESTABLISHED（6080 ↔ 5900）。

截图：`docs/images/no-root-desktop.png`

### 三个决定性修复

1. **rootfs 必须 App 自己创建**（MCS category 匹配），不能用 root `cp -a` 复制
2. **proot 放 nativeLibraryDir**（`apk_data_file` 才允许 exec）
3. **必须 `--link2symlink`**（Android 禁止 App 建硬链接，dpkg 装包会失败）

### 剩余待优化

- WebView 目前只占下半屏（布局要全屏化）
- 性能：Raw 编码 4MB/帧，可降分辨率/换编码
- 首次安装 XFCE 需 10 分钟（可改为预置 rootfs 或分包下载）

---

## Wine 集成进展（2026-11-27 00:30）

### 已完成

1. **box64 安装成功**：`apt install box64`（Ubuntu arm64 仓库，0.2.6+dfsg-3）
2. **Wine 11.0 (Proton) 部署**：
   - 来源：Kron4ek/Wine-Builds `wine-proton-11.0-3-amd64-wow64.tar.xz`（75MB）
   - 解压到 rootfs `/opt/wine-proton-11.0-3-amd64-wow64`
   - 传输方式：`cat | run-as` 管道（绕过 SELinux 隔离）
3. **x86_64 运行时**：下载 libc6_amd64.deb 解压到 `/lib/x86_64-linux-gnu/`
4. **验证**：`box64 /opt/wine-proton-*/bin/wine --version` → `wine-11.0-g957ac7cfa4f (Proton)` ✅

### 当前卡点

```
BOX64: Wine preloader detected ✓
Warning: Unsupported libc Syscall 0x143 (323)   ← userfaultfd
```
- Wine 的 preloader 依赖 `userfaultfd`，Ubuntu 的 box64 **0.2.6 不支持**
- 绕过尝试失败（wine 本体 `lib/wine/x86_64-unix/wine` 也是 preloader stub）

### 下一步（二选一）

1. **FEX-Emu**（推荐，DroidDeck 用它跑 Steam/Wine）
   - 可从 DroidDeck 提取（`tools/fex`）
   - 或自建（GitHub Actions / 交叉编译）
2. **新版 box64**（0.3.x，可能已支持 userfaultfd）
   - 需自行编译或找 aarch64 预编译包

### 环境现状

- rootfs 里已有：xvfb/x11vnc/websockify/tigervnc/xz-utils/box64/wine
- App 已能无 root 跑 XFCE 桌面（`docs/images/no-root-desktop.png`）

---

## Wine 突破（2026-11-27 00:40）

### 成果

1. **box64 v0.4.4 编译成功**（手机内 gcc 13.3 编译，31MB 二进制）
   - 源码：`/root/box64/src.tar.gz`，构建脚本 `/tmp/build.sh`
   - 安装到 rootfs `/usr/bin/box64`
2. **x86 库部署**：box64-bundle 的 235 个库解压到 `/usr/lib/box64-x86_64-linux-gnu/`
3. **Wine 11.0 (Proton) 完整跑通（root 下）**：
   ```
   wine-11.0-g957ac7cfa4f (Proton)
   wineboot --init → drive_c 里出现 Program Files / ProgramData / users
   ```
4. **确认无 root 的卡点**：root 下 wine 正常，无 root 下报
   ```
   err:virtual:map_image_into_view failed to set 60000020 protection on ntdll.dll section .text, noexec filesystem?
   ```
   → SELinux 拒绝 `mmap(PROT_EXEC)` 对 `app_data_file`

### 下一步方向

1. **无 root Wine 解法**：
   - FEX-Emu（它的加载器可能用匿名内存 + memcpy，规避 file mmap）
   - 或研究 Winlator/DroidDeck 的 mmap 处理
2. **notepad 窗口**：chroot 内看不到宿主的 X11 socket，需要在同一 namespace 起 Xvnc
3. **决策**：root 模式 Wine 已可用；无 root 需要 FEX 验证

### 环境资产

- rootfs `/opt/wine-proton-11.0-3-amd64-wow64`（Wine）
- rootfs `/usr/bin/box64`（v0.4.4 自编译）
- rootfs `/home/pl/.wine`（已初始化的 prefix，root 属主）
- 截图/文档：`docs/images/`

---

## 当前状态速览（2026-11-27 00:45）

### 已完成（可演示）

| 项 | 状态 | 证据 |
|---|---|---|
| 无 root 跑 XFCE 桌面 | ✅ | `docs/images/no-root-desktop.png` |
| 游戏库 UI | ✅ | `docs/images/game-library-ui.png` |
| 导入 exe/文件夹 | ✅ 代码完成 | GameLibraryActivity |
| box64 0.4.4 | ✅ 手机内编译 | `/usr/bin/box64` |
| Wine 11.0 (root) | ✅ prefix 创建 | `drive_c/Program Files` |

### 未完成

| 项 | 状态 | 下一步 |
|---|---|---|
| 无 root Wine | ❌ mmap(PROT_EXEC) 被 SELinux 拦 | 试 FEX 或研究 Winlator/DroidDeck |
| 一键启动 exe | ⏳ 占位 Toast | 依赖 Wine |
| UI 美化 | ⏳ 骨架级 | 卡片图标/设置页 |
| DXVK | ⏳ 未接入 | 图形加速 |

### 关键路径

```
/root/workspace/pocketlinux              # 项目源码（git 已提交）
/root/workspace/HANDOFF.md               # 本文档
/root/workspace/docs/specs/              # 设计稿
/root/workspace/docs/images/             # 截图
手机: /sdcard/Download/DSH-Go/           # 给用户的文件
```

### 下次开工第一步

1. 无线调试重连（IP:端口会变）
2. 读本文档 + `git log`
3. 优先解决：**无 root Wine**（FEX 验证）
4. 然后：UI 美化 → DXVK → 模拟器

### 设备

Redmi K80 Ultra（天玑 9400+，Android 16，KernelSU）
无线调试端口每次开机/重开都会变，需用户提供。

---

## 新一轮进展（2026-11-27 00:xx）

### 通道变化（重要）

- **`adb shell su -c "..."` 可用**（KernelSU 给的 root，context `u:r:ksu:s0`）。安装/看 dmesg/看 avc 都能用 root 做，比 aShell 点屏快得多。
- 无线调试端口会掉：`adb connect 192.168.31.56:45337` 在 00:24 后失效，需要用户重新看端口。

### 结论：无 root Wine 其实可以跑（之前判断有误）

- 在 **App 自己的域 `untrusted_app_27`** 里，`wineboot --init` 成功：`drive_c` 出现 Program Files / ProgramData / users / windows。
- 之前“mmap(PROT_EXEC) 被 SELinux 拦”的结论来自 **`runas_app` 域**（`adb shell run-as ...`），该域对「已 COW 的私有文件映射 mprotect(PROT_EXEC)」返回 EACCES：
  - 实测：private 映射 + 先写 + `mprotect(RX)` → EACCES；不写直接 mprotect(RX) → OK；MAP_SHARED / 匿名 / memfd → OK。
  - `dmesg` 无 AVC，说明是内核/域策略层面而非普通 avc 记录。
- **必须用 App 进程自己跑**（或以后用 App 内的运行器），不要用 `run-as` 代理测试。

### 另一个关键坑：root 在 App 目录里创建的文件 App 读不到

- `su -c "cat > /data/data/.../files/xxx"` 创建的文件标签是 `app_data_file:s0`（**没有 MCS category**），App 打开报 `EACCES/Permission denied`。
- 部署脚本/二进制进 App 目录必须走 `cat | run-as com.pocketlinux.probe sh -c "cat > ..."`。

### App 内已实现

- 全屏 WebView 显示桌面（原来只占下半屏），准备好后浮层淡出，右下角「日志」按钮可切回。
- 桌面启动改为等待脚本输出 `DESKTOP_READY` + 6080 端口可连，再加载画面（不再固定 sleep 18s）。
- 内置 RFB 客户端（assets/novnc/index.html）加了断线重连（800ms 重试），修复“WebView 先加载、websockify 未就绪 → 永久黑屏”。
- `Xvnc` 加 `-ac`（关闭 X 访问控制），否则同容器里的 wine/x11 工具连不上 :99。
- 桌面分辨率按手机竖屏比例算（约 100 万像素），不再出现上下大黑边。
- `WINEDLLOVERRIDES=mscoree,mshtml=` 关掉 Wine Mono / Gecko 首次弹窗。
- 游戏库页面加了「测试 Wine」按钮 → 启动 `MainActivity` 带 `exe_path=notepad`。
- `MainActivity` 支持 `EXTRA_EXE`（内置程序名如 notepad，或 `/games/...` 路径），桌面就绪后用 box64+wine 启动，`games` 目录绑定为容器 `/games`。
- 开发调试通道：`files/dev-cmd.sh` 存在时在 App 域内执行（env 里带 `PL_ND`/`PL_FILES`），输出打到 logcat 的 PLPROBE。

### 已验证的画面证据

- 容器内 `import -window root` 截图：XFCE 桌面正常渲染（1280x800 / 竖屏 680x1472）。
- wine 在 App 域里跑起来并在 XFCE 桌面弹出 **Wine Mono Installer** 窗口（说明 wine 的窗口/图形栈通了）；禁用 mono 后待验证 notepad 窗口。

### 下一步

1. 用户给新的无线调试端口 → 继续装 APK 验证。
2. 验证「测试 Wine」→ notepad 窗口出现在 App 的桌面画面里，并截图存档到 `docs/images/`。
3. 之后：真实 exe 导入 → 启动链路打通；再考虑 DXVK / 输入法。

---

## 设置页 + 触屏手势（2026-11-27 00:40）

### 设置页（SettingsActivity，启动浮层「设置」按钮进入）
- 显示：自动分辨率（按屏幕比例 + 像素预算）/ 预设 540×1170、680×1472、900×1950 / 自定义宽高
- 性能：流畅 0.55M / 均衡 1.0M / 清晰 1.6M / 极清 2.4M 像素预算
- Wine：关闭 Mono/Gecko 首次弹窗（写 `WINEDLLOVERRIDES=mscoree,mshtml=`）
- 持久化：`shared_prefs/pl_settings.xml`（`Settings.java`）
- 底部两个按钮：「保存」和「保存并重启桌面」（后者启动 MainActivity，检测到分辨率变化才重启桌面，否则复用）

### 触摸修复（assets/novnc/index.html）
- **根因**：旧客户端 `touchend` 里发 `pointer(0,0,0)`，每次抬手把指针拉回左上角
- **新逻辑**：按下只移动指针；移动 >3px 才转成左键拖拽；轻点（<700ms、没移动）发按下+60ms 后抬起的真点击
- **手势**：长按 650ms = 右键（mask 4）；双指上下滑 = 滚轮（button4/5）；双指快速点按 = 中键（mask 2）；三指上滑 = 弹键盘
- **键盘**：右下/左下 ⌨ 按钮或三指上滑唤出隐藏 input，按 keysym 发 RFB KeyEvent（ASCII 可打）
- **性能**：像素格式改 16bpp（每帧减半）、流式环形缓冲（去掉 O(n²) 拷贝）、按帧计数重绘

### 已验证
- 单指点击 XFCE「Applications」→ 菜单弹出；点菜单项能启动应用（Application Finder）
- 长按桌面 → 右键菜单（Create Launcher / Open Terminal Here / Desktop Settings…）
- 帧计数 600+，画面持续更新（旧版只画第一帧）
- 设置写 900×1950 后重启，Xvnc 实际 `-geometry 900x1950`，桌面渲染正常

### 已知待优化
- WebView 里画布左右仍有约 7% 黑边（fit() 取值与布局差一点），不影响触摸坐标
- 键盘只支持 ASCII（中文输入法要走 Xvnc 内 fcitx/ibus，后面再做）
- 首次 wine 初始化 1–2 分钟

---

## P1 修复批（2026-11-27 01:20）

### 关键发现：触摸“完全没反应”的真因
`MainActivity` 里有两个 `setWebViewClient`：后加的那个（用于打 `jsver`）**覆盖**了带 `shouldInterceptRequest` 的客户端 → `http://appassets.local/index.html` 不再被拦截 → 页面加载不出来/走旧缓存 → `window.plTouch` 不存在 → 所有触摸事件进了黑洞。
修法：合并成一个客户端 + `onPageFinished` 里读 `window.PL_VER` + `clearCache(true)` + 带版本号 URL（`?v=pl6`）。验证：`logcat -s PLPROBE:I | grep jsver` → `jsver="pl5"`。

### 本轮修掉的
1. Wine 启动脚本引号 bug：路径改由 `PL_EXE`/`PL_WD` 环境变量传入，支持空格/中文/单引号。
2. websockify 绑 `127.0.0.1:6080`，桌面不再暴露到局域网。
3. MainActivity 全屏（NoActionBar.Fullscreen + immersive）、竖屏锁定、`singleTask` + configChanges，不再因重建误杀桌面。
4. `killDesktop` 只匹配本 App 的 rootfs 路径 / 本 App 的 Xvnc/websockify，不会误杀 DSH 的 proot。
5. 双指/三指手势前先延迟 80ms 再按左键，滚动不再误点；长按 0.6s 抬手才出右键（不再打断拖拽）。
6. RFB 循环按消息类型分发（ColourMap/Bell/CutText），复制文本不再冻结画面。
7. 触摸坐标改传 0~1 相对值，消除 DPR/view 换算偏差；画面里加了**本地光标圈**（Xvnc 不提供 Cursor 伪编码，之前是盲按）。
8. 键盘：退格/回车改发 XK_BackSpace / XK_Return；换行符不再当字符发。
9. `DESKTOP_READY` 跨块检测；MOVE 日志降噪；CANCEL 只释放不点击。

### UI（ui-designer 交付）
首页重做：Linux 桌面大卡（状态/分辨率/进入/重启）+ Steam 快速启动卡 + Windows 程序网格（导入/空态/进度/长按管理）；设置页分组（显示/性能/渲染器/Wine/高级，渲染器标「待接入」）；新增 Steam 页（本体检测 + 快捷方式 JSON 列表 + 一键启动）。

### 实测（本轮）
- `jsver=pl5` 确认新页面；全屏无黑边；单点开 Applications 菜单 ✓；长按弹桌面右键菜单（状态栏显示「右键」）✓；本地光标可见 ✓。

### 待办
- 拖拽窗口实拖回归（x11-prober 可接，只读+注入）。
- 渲染器/box64 预设真正下发给 wine（现在只存值）。
- Steam 本体安装 + appid 启动参数（`steam -applaunch <id>`）。
