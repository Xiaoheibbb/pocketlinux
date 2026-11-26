# DXVK / GPU 可行性探针（2026-11-27 01:35）

## 结论
**当前架构（proot 内 glibc 进程）拿不到 Mali GPU，DXVK 硬件加速不可行。**
软件 Vulkan（llvmpipe）可用，DXVK 理论上能跑但只有 CPU 速度。

## 证据
| 探针 | 结果 |
|---|---|
| `/dev/mali*` | 不存在 |
| `/dev/kgsl*` | 不存在 |
| `/dev/dri/` | 只有 `card0`（1000:1000，0660） |
| `card0` 驱动 | `mediatek-drm`（显示控制器，不是 GPU） |
| App 内打开 card0 | Permission denied |
| 容器内 `vulkaninfo` | 可用，adapter = `llvmpipe (LLVM 20.1.2)`，Vulkan 1.4.318，`PHYSICAL_DEVICE_TYPE_CPU` |
| panfrost ICD | 无设备可枚举（空） |

## 为什么
- Android 的 GPU 驱动是 **bionic** 库（`/system/lib64/libvulkan.so` + Mali blob），glibc 进程不能 dlopen。
- proot 里没有 `/dev/mali0`，且 SELinux/权限不允许 app 直接开 DRM 设备。
- 所以 Wine + DXVK 能拿到的只有 llvmpipe（CPU 光栅）。

## 三条路
1. **Winlator 式 bionic Vulkan 桥（vortek）**：Android 侧起 JNI 进程/线程加载系统 Vulkan，guest 的 Vulkan 调用经 socket+共享内存转发。这是新子系统，工作量大（2–3 周级别），但这是唯一能吃到 Mali 的路。
2. **软件 Vulkan + DXVK**：能跑通链路、能跑 2D/老游戏，CPU 占用高、帧率低；对 3D 没意义。
3. **放弃 3D，把精力放到易用性**（首次安装、画面流畅度、Steam/程序库、输入法）。

## 建议
先不投入 (1)；用 (3) 把产品体验拉满，等有 3D 硬需求再立项 (1)。
