# bionic-vulkan-wrapper 在 proot(glibc) 容器里的可行性探针

- 任务：task-4（PocketLinux Vulkan 可行性验证，A 路线）
- 时间：2026-11-27 01:40–02:10（半天时间盒内完成）
- 结论一句话：**A 路线（把 leegao/bionic-vulkan-wrapper 装进我们的 glibc proot 容器里跑出 Mali）不可行。它是 bionic-only 的 "Vk-on-Vk 直通 ICD"：必须在 bionic 进程内 `dlopen("/system/lib64/libvulkan.so")` 才能拿到 Mali。容器内要看到 Mali，只能走 B（bionic 侧 server + IPC，Vortek 式）。**

原始证据：`docs/notes/artifacts/vulkan-wrapper-probe-2026-11-27.log`（122 行，本机实测输出）
复现脚本：`docs/notes/artifacts/vulkan-wrapper-probe-collect.sh`

---

## 0. 先修正两条团队共享前提（本次实测，与已知信息不符）

| 已知（Lead 实测） | 本次复测 | 说明 |
|---|---|---|
| 容器里 `/dev/mali*` 不存在 | **`/dev/mali0` 存在**：`crw-rw-rw- system graphics 10,103 u:object_r:gpu_device:s0`；容器内 `exec 9<>/dev/mali0` → **OPEN_OK**；`run-as com.pocketlinux.probe`（app 域 `u:r:runas_app`）也能打开 | proot `-b /dev` 生效，GPU 内核节点在容器内可见可开。之前"不存在"的结论不成立（可能是当时用的探测脚本/环境差异） |
| 容器里 `vulkaninfo` 只有 llvmpipe | 复测一致：`PHYSICAL_DEVICE_TYPE_CPU` / `llvmpipe (LLVM 20.1.2)`，mesa 25.2.8 + vulkan-tools 1.3.275 | 现象不变 |
| —— | **Android bionic 侧 Vulkan 正常**：`cmd gpu vkjson` 枚举到 `Mali-G925-Immortalis MC12`（vendorID 5045=ARM，driverID 9=ARM 私有） | 证明"GPU 内核 + vendor 驱动"在本机是好的，缺的只是 **glibc 侧的 userspace UMD** |

**所以卡点不是"没有设备节点/驱动"，而是：本机唯一的 Mali Vulkan 用户态驱动是 bionic 版本（`/vendor/lib64/hw/mt6991/vulkan.mali.so`，NEEDED `libGLES_mali.so/libc++.so/libc.so/libm.so/libdl.so`），glibc 进程加载不了它。**

---

## 1. 三个仓库各是什么

| 仓库 | 角色 | 关键事实 |
|---|---|---|
| [leegao/bionic-vulkan-wrapper](https://github.com/leegao/bionic-vulkan-wrapper)（branch `wrapper`，commit `3abf487`） | Mesa fork（基于 Mesa 25.0），新增 Vulkan **wrapper driver** `src/vulkan/wrapper/`：把设备原生 Vulkan 包一层，给 DXVK 补 `textureCompressionBC` 等缺失扩展 | **不是 Mali 驱动**，不含 kbase ioctl；本身是"直通封装"，内部还是调设备原生 Vulkan。二进制里只有 `/system/lib64/libvulkan.so`、Termux 路径、`/dev/ion`、`/dev/dma_heap/system`（AHB/WSI）这类字符串 |
| [leegao/vulkan_wrapper_termux-packages](https://github.com/leegao/vulkan_wrapper_termux-packages)（branch `dev/wrapper`，commit `c5c45d8`） | termux-pacman 的 fork：构建配方 + `mesa_bionic/`（Mesa 源码目录）+ `wrapper/`（预编译 .so、ICD json、push 脚本、Termux deb） | 产物 = `libvulkan_wrapper.so` + `wrapper_icd.aarch64.json`；**只有 bionic 构建**（`wrapper.sh:10` 写死 `./build-package.sh --library bionic vulkan-wrapper-android`） |
| [leegao/vortek-patcher](https://github.com/leegao/vortek-patcher)（branch `main`） | 和 wrapper 无关：二进制 patch Winlator 的 `libvortekrenderer.so`，修 Vortek BCn 解码在 Mali 上的花屏 | 反向说明 Winlator 的 Vortek = **bionic renderer server + glibc client** 的 IPC 架构（见 §4 B 路线） |

构建环境（`vulkan_wrapper_termux-packages` 仓库 `packages/vulkan-wrapper-android/build.sh`，Mesa 源码在该仓库的 `mesa_bionic/` gitlink 里）：
- `:14` `TERMUX_PKG_DEPENDS="libandroid-shmem, libc++, libdrm, libx11, ..."`（全部 Termux/bionic 包）
- `:29` `-Dvulkan-drivers=wrapper`
- `:49` `LDFLAGS+=" -landroid-shmem -ladrenotools"`（libadrenotools 是 Winlator 的 bionic 驱动加载库）
- `wrapper/build_termux.sh:14-37` 生成 `mesa-vulkan-icd-wrapper` Termux deb，路径写死 `/data/data/com.termux/files/usr/...`
- `wrapper/push.sh:3` `adb shell run-as com.winlator.cmod "cp ... imagefs/usr/lib/..."`（推到 Winlator **Bionic 容器**的 `imagefs/usr/lib`）

---

## 2. 运行时到底依赖什么（源码行号证据）

`bionic-vulkan-wrapper` @ `wrapper` 分支，`src/vulkan/wrapper/`：

| 位置 | 代码 | 含义 |
|---|---|---|
| [`wrapper_instance.c:49`](https://github.com/leegao/bionic-vulkan-wrapper/blob/wrapper/src/vulkan/wrapper/wrapper_instance.c#L49) | `#define DEFAULT_VULKAN_PATH "/system/lib64/libvulkan.so"` | 默认后端 = **Android 系统 bionic Vulkan loader** |
| `wrapper_instance.c:82` | `return dlopen(DEFAULT_VULKAN_PATH, RTLD_NOW \| RTLD_LOCAL);` | **同一进程内** dlopen bionic 系统 loader（再由它加载 `/vendor/.../vulkan.mali.so`） |
| `wrapper_instance.c:59-79` | 读 `ADRENOTOOLS_DRIVER_PATH/HOOKS_PATH/NAME`，非空则 `adrenotools_open_libvulkan(...)`（`:79`） | 自定义驱动走 Winlator libadrenotools（**bionic-only**，用 Android linker namespace） |
| `wrapper_private.h:52` | `void *adrenotools_open_libvulkan(...)` | 直接声明 libadrenotools 接口 |
| `graphics_env_hooks.cpp:15-16` | `set_layer_paths()` 里 `dlopen("libgraphicsenv.so", RTLD_NOW)` | 需要 Android 的 `libgraphicsenv.so` |
| `graphics_env_hooks.cpp:39-44` | `dlsym` `_ZN7android11GraphicsEnv11getInstanceEv` 等 bionic libc++ ABI 符号 | 只有 **bionic 进程**里有这些符号 |
| `meson.build:210`、`:134` | `shared_library('vulkan_wrapper', ...)`，`wrapper_deps` 含 `dep_android` | 产物是 Android/bionic 目标 |

产物 ELF 实证（release [v0.0.4](https://github.com/leegao/bionic-vulkan-wrapper/releases/tag/v0.0.4) 的 `libvulkan_wrapper.so`，5239376 B）：
```
RUNPATH  /data/data/com.termux/files/usr/lib:$ORIGIN/../../android_stub
NEEDED   libandroid-sysvshm.so, libadrenotools.so, libcutils.so, liblog.so,
         libnativewindow.so, libsync.so, ..., libc++_shared.so, libdl.so, libc.so
```
→ bionic 专属依赖齐全（libc.so/libdl.so/libm.so/libcutils.so…），在 glibc 里连 `ldd` 都过不了：
```
libvulkan_wrapper.so: error while loading shared libraries: /lib/aarch64-linux-gnu/libm.so: invalid ELF header
```
（glibc 的 `/lib/aarch64-linux-gnu/libm.so`、`libc.so` 是 GNU ld script，与 bionic soname 直接冲突。）

**目标运行时（作者自己的措辞）**：
- release v0.0.4 说明：基于 [`Succubussix/winlator-bionic-glibc`](https://github.com/Succubussix/winlator-bionic-glibc) 的 **just-bionic** 版（该版 changelog 明确 "**Fully removed `glibc`**"）→ 目标 = **Winlator Bionic 的 bionic 容器**。
- [issue #186 "How to build this under proot ubuntu"](https://github.com/leegao/bionic-vulkan-wrapper/issues/186)（closed）：维护者/社区明确回答 "**bionic-vulkan-wrapper cannot run on glibc environment … only works in the Termux native bionic libc environment**，PRoot-Distro Ubuntu 是 glibc，要用得靠 libhybris"。
- [issue #185](https://github.com/leegao/bionic-vulkan-wrapper/issues/185) 作者 leegao 原话："**the main bottleneck for glibc is the issue that the system ICD is still Bionic**"；要避免 IPC 就得在 glibc 进程里塞一个 bionic loader（他给了研究性 sketch，未实现）。
- [issue #195 "[Feature] glibc-vulkan-wrapper request"](https://github.com/leegao/bionic-vulkan-wrapper/issues/195) 仍 open → **官方 glibc 版不存在**。

---

## 3. 容器内实测：把 bionic wrapper 当 ICD 交给 glibc（A 路线直接验证）

环境：proot Ubuntu 24.04.3 LTS（`-b /dev -b /proc -b /sys`，rootfs `/data/data/com.pocketlinux.probe/files/rootfs`）。

做法：把 v0.0.4 的 bionic `libvulkan_wrapper.so` 拷进容器 `/root/vkwrap/`，写 wrapper 的 ICD json（`library_path=/root/vkwrap/libvulkan_wrapper.so`），
`VK_ICD_FILENAMES=/root/vkwrap/wrapper_icd.aarch64.json vulkaninfo --summary`：

```
ERROR: [Loader Message] Code 0 : libandroid-sysvshm.so: cannot open shared object file: No such file or directory
ERROR: [Loader Message] Code 0 : loader_icd_scan: Failed loading library associated with ICD JSON
        /root/vkwrap/libvulkan_wrapper.so. Ignoring this JSON
ERROR: [Loader Message] Code 0 : vkCreateInstance: Found no drivers!
ERROR at ./vulkaninfo/./vulkaninfo.h:458:vkCreateInstance failed with ERROR_INCOMPATIBLE_DRIVER
```

第一个缺失的 `libandroid-sysvshm.so` 只是表象；即使把 bionic 依赖都补齐，也过不了 `wrapper_instance.c:82` 的
`dlopen("/system/lib64/libvulkan.so")`（glibc loader 无法加载 bionic 系统 loader，且 soname 与 glibc 冲突），
这与 §2 的静态分析、作者的 issue 回复一致。**A 路线到此判定不可行。**

对照：同一容器基线 `vulkaninfo` = llvmpipe；`/dev/mali0` 可见且可 `open()`。即容器里**有 GPU 节点、没有 glibc 版 UMD**。

---

## 4. 判定与路线对比

| 路线 | 内容 | 可行性 | 工作量 |
|---|---|---|---|
| **A. 直接把 wrapper 塞进 proot glibc** | 本任务 | ❌ 不可行（§2/§3） | — |
| **B. JNI/IPC 桥（推荐）** | bionic 侧（App 进程内 JNI）做 Vulkan server：`dlopen` 系统 libvulkan → 真实 Mali；容器内放一个 glibc 的 ICD client，把 Vk 调用经 unix socket + ashmem 环缓冲转发给 server。**这基本就是 Winlator Vortek 的架构**（[Vortek Internals Part 1](https://leegao.github.io/winlator-internals/2025/06/01/Vortek1.html)：glibc client `libvulkan_vortek.so` + bionic `libvortekrenderer.so`，JNI 在 App 进程起 server，ashmem 双环缓冲 + SCM_RIGHTS 传 fd） | ✅ 唯一能吃到 Mali 稳的路 | 大：最小 POC（enumerate + 简单命令）约 2–3 天；完整 DXVK 可用 2–3 周级 |
| **C. 整栈换 bionic（Winlator Bionic 模式）** | 容器用户态从 glibc 换成 bionic（bionic Wine/box64），直接用本 wrapper（甚至不用 IPC） | ✅ 现成轮子可用（leegao wrapper 就是为此做的，本机 bionic Vulkan 已实测枚举到 Mali） | 极大：放弃 Debian/apt/XFCE 用户态，产品形态变了 |
| **D. libhybris / Venus 等研究路线** | libhybris 在 glibc 进程内虚拟 bionic linker（[android-vulkan-bridge](https://github.com/kde-yyds/android-vulkan-bridge)）；或 Mesa Venus/virgl 远程协议 | ⚠️ 有前人试过但未成熟：issue #195 里 PRoot Debian 能加载 Vulkan 但 swapchain 不工作、TLS/stack-protector 崩；Venus 单次能跑但极慢/不可复现 | 不确定，不建议现在投入 |

**补充**：理论上还有 "glibc 版 libmali（ARM 官方 Linux blob）" 一条，但本机是 MTK 定制 kbase（`mediatek,mali` + `arm,mali-valhall`，MTK 私有 sysfs `mtk_fwlog_enable_mask`/`csg_scheduling_period`/`dvfs_*`），公开 glibc blob 的 DDK/固件接口几乎肯定不匹配，风险极高，本次未尝试。

---

## 5. 给 Lead 的下一步建议

1. **就 3D 目标做决策**：A 不可行已被证据钉死；要么立项 B（Vortek 式 IPC，先做 1–2 天最小 POC：JNI 起 bionic server → `vkEnumeratePhysicalDevices` 拿到 "Mali-G925" 经 socket 回传，容器内 glibc 程序打印；不实现完整 Vk 代理），要么按 `dxvk-feasibility.md` 的建议**暂时不做 3D、保持 llvmpipe**。
2. 若近期不投 3D：把 DXVK 从默认/推荐依赖里摘掉（避免用户以为有硬件加速），并在 UI/文档里明确 "3D = CPU 软件渲染"。
3. 若愿意重新评估产品架构：可研究 **C 路线（bionic 用户态）**——它能直接复用本 wrapper，且本机 bionic Vulkan 已验证可用；代价是放弃 Debian 生态。
4. 顺手修正团队共享事实：`/dev/mali0` 在容器内可见可开；bionic 侧 Mali Vulkan 正常；"没有 /dev/mali" 不是瓶颈。
5. 不要重复投入"把 bionic .so 拷进容器"这类尝试（本 notes §3 已做完，结论是加载器层失败）。

---

## 6. 复现方式

```bash
# 1) 下载 bionic 产物（5.2MB）
wget -O /tmp/libvulkan_wrapper_bionic.so \
  https://github.com/leegao/bionic-vulkan-wrapper/releases/download/v0.0.4/libvulkan_wrapper.so
# 2) 一手采集（设备信息 + ELF 静态分析 + 容器内实测），输出即 artifacts 里的 log
bash docs/notes/artifacts/vulkan-wrapper-probe-collect.sh
```

关键手工步骤见脚本内 `inner_vkprobe.sh`：拷 `.so` 到 rootfs `/root/vkwrap/` + 写 ICD json +
`VK_ICD_FILENAMES=... vulkaninfo --summary`。

## 7. 没做/边界

- 没在设备上运行 bionic wrapper（需要在 Termux/bionic 环境装 deb，或 NDK 编译测试程序；超出"只读验证"范围，且对 A 结论无影响）。间接证据已足够：`cmd gpu vkjson` 证明 bionic 侧可枚举 Mali；wrapper 在 bionic Termux/Winlator Bionic 可用的报告见 issue #185 评论。
- 没实现 B 路线 POC（本任务只做可行性判定）。
- 设备端 probe 用 `adb root(su)` + `run-as`（app 可调试）；未走真实 App 启动链，但 app 域打开 `/dev/mali0` 已实测 OK。
- 容器内 `/root/vkwrap/` 的 5MB 测试 .so 已清理，复现时按 §6 重新拷贝。
