# 拖拽断点实测（真机，2026-11-27 01:03–01:08）

## 一句话结论

1. **X 端正常**：通过 App→JS→RFB 链路送进 Xvnc 的真实多点触摸拖动，X 端完整收到
   `ButtonPress(button 1)` → 多次 `MotionNotify(state 0x100 = Button1Mask)` → `ButtonRelease(button 1)`，
   22/22 组手势全部格式正确，**不是“X 端没收到按键保持下的移动”**。
2. **窗口管理器也正常**：X 端用 XTEST 对 xterm 标题栏做按住拖动，窗口从 `+35+729` 移到 `+285+729`（+250px），截图对比一致。
3. **断点不在 X 层**：既然掩码/顺序/坐标都对、且 WM 会跟着 Correct 事件拖窗口，问题只能落在
   App 侧另一环节（画面↔触摸坐标标定、手势状态机、或“按下点与视觉位置”的偏差）。
   下一轮最该做的是：**按下点 vs X 指针落点的精确交叉标定**（方法见第 6 节，已有半自动脚本）。

> ⚠️ 同时修正前一轮的一个误判：**`xev -root` 在这种有 xfwm4/xfdesktop 的桌面上永远收不到事件**，
> 之前的空日志不能说明任何问题（原因见第 2 节）。本次改用“全屏普通 xev 窗口”才拿到数据。

---

## 1. 环境

- 设备：Redmi K80 Ultra，Android 16，1280×2772（density 520），adb `192.168.31.56:45337`
- App：`com.pocketlinux.probe`（设备上 00:55 安装的构建，commit `5bfda59` 同级）
- 容器桌面：DISPLAY=:99，Xvnc `680x1472 -depth 24 -SecurityTypes None -ac`，
  进程：Xvnc 17092、xfce4-session 17194、websockify/python3
- 全程只做：读日志/截图 + 注入触摸/XTEST；**未重装 APK、未 force-stop App、未动其它 App**

## 2. 为什么 `xev -root` 日志是空的（前一轮踩的坑）

`xev` 源码（x11-utils 7.7 xev.c，main()）：

```c
if (use_root) w = RootWindow(dpy, screen);
if (w) {
    XGetWindowAttributes(dpy, w, &wattr);
    if (wattr.all_event_masks & ButtonPressMask)   /* xfwm4 已在 root 上选了 ButtonPressMask */
        attr.event_mask &= ~ButtonPressMask;       /* → xev 自己把 ButtonPress 去掉 */
    attr.event_mask &= ~SubstructureRedirectMask;
    XSelectInput(dpy, w, attr.event_mask);
}
```

两个后果：
1. root 上 `ButtonPressMask` 已被窗口管理器（xfwm4、xfdesktop）选中，xev 会**主动去掉 ButtonPress**，永远看不到按下/抬起；
2. 指针位于 xfdesktop 等子窗口上，MotionNotify 也不会送到 root 选区。

所以 `xev -root` 在带 WM 的桌面上是无效探针，空日志 ≠ X 没收到事件。
另外 `xev` 每个事件后都有 `fflush(stdout)`（同一份源码），**不加 `stdbuf -oL` 事件日志也不会丢**，
只有启动横幅会缓冲；`stdbuf` 在这里不是关键。

## 3. 方法一：全屏普通 xev 窗口 + App 真实触摸链

### 启动探测窗口（容器内，root 跑 proot）

```sh
# /root/inner-setup.sh（经 /data/local/tmp/run-generic.sh 执行，proot 参数同 start-desktop.sh）
export DISPLAY=:99
xev -geometry 680x1472+0+0 -event mouse > /root/xevwin.log 2>&1 &   # 全屏普通窗口（非 -root）
xterm -geometry 80x24+30+700 -bg white -fg black -title XTERMTEST > /root/xterm.log 2>&1 &
xwininfo -root -tree > /root/xwininfo.txt
import -window root /root/desk0.png
```

窗口树确认：`0x1c00001 "Event Tester": () 680x1421+0+24 +0+51`（全屏、在 xfwm4 管理下）。

### 注入

```sh
# root 执行注入（触摸屏 /dev/input/event5，按下-停 1s-移动-抬起）
adb shell 'su -c "am start -n com.pocketlinux.probe/.MainActivity"'   # 确认 topResumedActivity 是 probe
adb shell 'su -c "sh /data/local/tmp/slowdrag.sh"'                    # 01:05:23–01:05:25
adb shell "su -c \"cat /data/.../rootfs/root/xevwin.log\""
```

### 关键日志（`/root/xevwin.log`，原始文件见 `docs/notes/artifacts/xev-window-2026-11-27.log`）

第一组（按下点，含 Xvnc 的 warp-then-press）：

```
MotionNotify  ... root:(34,165)  state 0x0  ...
ButtonPress   ... root:(34,165)  state 0x0, button 1
ButtonRelease ... root:(34,165)  state 0x100, button 1     ← 触摸状态机把这次按下收回（同屏第二根手指介入）
```

后续每一组真实拖动都完整（摘一组）：

```
MotionNotify  ... root:(290,1165)  state 0x0,  is_hint 0      ← RFB 先 warp 指针
ButtonPress   ... root:(290,1165)  state 0x0,  button 1
MotionNotify  ... root:(286,1164)  state 0x100, is_hint 0     ← 按住移动，Button1Mask=0x100 在
MotionNotify  ... root:(275,1163)  state 0x100, is_hint 0
MotionNotify  ... root:(34,165)    state 0x100, is_hint 0
ButtonRelease ... root:(34,165)    state 0x100, button 1
```

本次 salvage 到的 621 个事件统计：

| 事件 | 数量 | 说明 |
|---|---|---|
| ButtonPress (button 1) | 22 | 全部 state 0x0 |
| MotionNotify | 576 | 其中 **555 条 `state 0x100`**（按住移动），20 条 `state 0x0`（按下前的指针 warp） |
| ButtonRelease (button 1) | 22 | 其中 21 条 `state 0x100`（释放瞬间按键位仍在，正常） |

**每一组都是 press → mask=0x100 的 motion → release，没有“丢了 mask”或“只 warp 不按下”的情况。**

> 注：01:05:23 之后用户本人开始在手机上真实拖拽（PLTOUCH 同期持续记录 DOWN/MOVE/UP，无 ACTION_CANCEL），
> 这 22 组就是**真实手指走完整条 App→JS→RFB→Xvnc 链**留下的 X 事件，比 sendevent 合成更可信。

## 4. 方法二：xterm 标题栏拖拽（X 层基准）

App 链路的“拖标题栏”因用户中途抢设备没来得及做完整版，改为先在 X 层做基准：

```sh
# 容器内：起 xterm（frame 原点 +30+700，title 高度约 29px）
xterm -geometry 80x24+30+700 -bg white -fg black -title XTERMTEST &
# XTEST 在 (150,715) 按下，每次 +25px 移动 10 次，抬起（python3 ctypes libXtst）
python3 /root/inner-xtest-drag.py
```

`xwininfo -root -tree` 前后对比：

```
before: 0x1c00012 "XTERMTEST"  484x316+5+29  +35+729
after : 0x1c00012 "XTERMTEST"  484x316+5+29  +285+729     ← 窗口被拖动了 250px
```

截图：`docs/images/drag-xterm-xtest-before.png` / `drag-xterm-xtest-after.png`。
**说明 Xvnc + xfwm4 对“按住-移动-释放”输入的处理完全正常。**

## 5. 现象与推断

- X 层（第 3、4 节）：输入事件格式、Button1Mask、坐标连续性、WM 响应全部正常。
- 那么「拖不动窗口/图标只被选中」的断点不在 X，只能是：
  1. **按下点与视觉位置不一致**（canvas 的 fit/居中与 `vToFb()` 之间的小偏差；标题栏只有 ~24–27px 高，偏差几十像素就会按到窗口内容/桌面）；
  2. JS 手势状态机在特定时序下提前 `release`（本次日志里确实看到“按下后 927ms 在同一点 release”，来源是第二根手指介入时 `plTouch(action=5)` 直接 release 旧指针——正常单指拖动不会触发，但值得复查）；
  3. X 之后的渲染/回传链路（画面里窗口没动，但 X 实际动了）。
- 第 6 节的交叉标定可以直接把 1 定量，是下一个最省时间的验证。

## 6. 下一轮建议：按下点 vs 指针落点交叉标定（已有半自动流程）

1. 容器里起全屏 `xev -geometry 680x1472+0+0 -event mouse`（**不要用 -root**）；
2. App 前台，用 sendevent 在已知 raw 坐标连续点 N 个点（raw≈屏幕像素×100：`(x_raw,y_raw)=(x_screen*100, y_screen*100)`）；
3. 同一时刻读 `logcat -s PLTOUCH:I`（native 收到的 view 坐标）和 `xevwin.log`（X 收到 fb 坐标）；
4. 用本次的脚本对齐两组序列，拟合 `fb = a*view + b`。本次粗拟合 `a≈0.56–0.57`（与画布缩放 0.573 一致），
   截距噪声来自序列错位；精确标定后即可判断是否存在系统性偏移。

本次用过的辅助文件（设备上留着，可直接复用）：
`/data/local/tmp/run-generic.sh`（root 跑容器脚本）、`inner-setup.sh`、`inner-xev-reset.sh`、
`inner-xtest.py`、`inner-xtest-drag.py`、`inner-cleanup.sh`（都在 `rootfs/root/` 有一份）。

## 7. 现场清理（已做）

- 已 kill 全屏 xev、xterm、以及测试用的临时 proot；`xwininfo -root -tree` 确认无 `Event Tester`/`XTERMTEST`；
- XFCE 桌面（Xvnc 17092）+ 用户原有 App 均未受影响；`docs/images/drag-test-desk-cleaned.png` 现场截图；
- App 停在后台，未 force-stop；未重装。

## 8. 复现命令速查

```sh
adb connect 192.168.31.56:45337
adb shell 'su -c "am start -n com.pocketlinux.probe/.MainActivity"'
adb shell 'su -c "setsid sh /data/local/tmp/run-generic.sh /root/inner-setup.sh >/dev/null 2>&1 &"'
adb shell 'su -c "sh /data/local/tmp/slowdrag.sh"'
adb shell 'su -c "cat /data/data/com.pocketlinux.probe/files/rootfs/root/xevwin.log"'
```
