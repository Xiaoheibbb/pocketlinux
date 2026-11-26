# 拖拽失效代码审计（task-1 / rfb-auditor）

- 时间：2026-11-27 01:10
- 范围：`pocketlinux/app/src/main/assets/novnc/index.html`（RFB 客户端 + plTouch）、`pocketlinux/app/src/main/java/com/pocketlinux/probe/MainActivity.java`（CaptureWebView → evaluateJavascript 桥）
- 方式：只读代码 + 规范/源码查证 + 本地 node 模拟 + 读取 x11-prober 已采集的真机 xev 日志（`.probe-tmp/xevwin.log`）。**未碰手机、未跑 adb、未装 APK、未改源码。**

---

## 一、结论（先看）

**协议层没有 bug：mask 位、坐标连续性、Xvnc「先 warp 后按下」、evaluateJavascript 顺序、DPR 换算、是否需要补发同坐标 move —— 这 5 项逐条排除（见第二节）。**

真机上已能证明整条链路把「按住移动」正确送到了 X：

> `.probe-tmp/xevwin.log`（x11-prober 01:06 采集，xev 全屏窗口挂在同一 Xvnc :99 上）共 621 个事件：
> - **21 组** `ButtonPress(button 1) → MotionNotify… → ButtonRelease`；
> - 576 个 MotionNotify 中 **555 个 state=0x100（Button1Mask 置位）**，间隔 8–16ms，坐标在每组手势内连续；
> - 日志里没有 XTEST 控制测试的特征坐标（400,300），说明这些事件全是经 **App → JS → RFB → Xvnc** 进来的；
> - 也就是说 xfwm4/xfdesktop 收到的就是「左键按住 + 连续移动 + 抬起」，与鼠标拖拽语义完全一致。

由此，「拖不动」不在 RFB 客户端写法上。可解释「图标只被选中、不跟随」的**真实客户端缺陷出现在历史版本的长按逻辑**：

1. **旧包（HEAD / 已装 00:40 APK，canvas 触摸版）**：拖拽走 WebView 的 `touchstart/touchmove`，WebView 自身手势识别会打断触摸流（旧 JS 里 `touchcancle` 计数 c1 有实测记录，见 00:52 截图 `/tmp/drag3.png` 状态栏），而且旧逻辑 `onDown` 只发 `mask=0` 移动、位移超过阈值才补 `mask=1` 按下 —— 按下点和 DOWN 点脱节，拖拽容易变成「点一下」。
2. **过渡包（00:51–00:55 的 c5/c6/c7 版，见 `/tmp/c5.js`…`/tmp/c7.js`、`snap/index.new.html`）**：已改原生命中，但长按定时器在 600ms 时执行
   `pressed=false; suppressUp=true; sendPointer(0,…); click(…,4)` —— **按住期间把左键放掉，并把 `pressed` 置 false**；之后所有 ACTION_MOVE 命中 `if(pressed)` 被直接丢弃 → **按下后图标只被选中、指针不再跟随**，100% 对应用户描述。测试注入脚本 `slowdrag.sh` 是 down 后 `sleep 1` 再移动，必然触发。
3. **当前工作区（00:55 后，含 Lead 01:05 的 80ms 延迟按下改动）**：长按只置 `longPressed` 标记、不发任何鼠标包；MOVE 恒发 `mask=1`；快速拖动前先补发「按下点在起点」的 press —— 这是正确写法，与 xev 证据相符。**结论：当前代码不应再复现「只选中不跟随」；需要装新包实测确认。**

仍有两个真实 bug 会影响回归/触控（见第三节 P1/P2）：**长按右键在当前版本被 `pressedSent` 分支吃掉（永远发不出右键）**；**WebView 无缓存头，升级后可能继续跑旧 index.html（`plTouch` 未定义 → 触摸完全无反应）**。

与 x11-prober 的真机报告（`docs/notes/drag-device-measure.md`）互相印证：
- 他们同样测到 X 端 22/22 组 `press → state 0x100 的 motion → release`，并额外用 XTEST 在 X 层拖动 xterm 标题栏成功（+35+729 → +285+729），**证明 Xvnc + xfwm4 处理"按住-移动"完全正常**；
- 他们拟合出的 view→fb 比例 `a≈0.56–0.57` 与画布缩放 0.573 一致 → 坐标标定没有系统性偏移（按下点偏差至多几个像素，不会导致标题栏/图标完全拖不动）；
- 因此断点只可能在 App 侧状态机：历史版本的「长按 release 掉左键 / `pressed=false` 丢弃 MOVE」（本文件第一节 2）就是最贴合"选中但不跟随"的机制；当前版本已修，需装包回归。
- 另注意 `action===5`（第二根手指落下）会主动 `sendPointer(0,lastX,lastY)` 结束单指拖拽（为双指滚动服务）；若用户拖拽时手掌/第二根手指碰到屏幕，拖动会被中断。这是设计取舍，不是 bug，但可作为"偶尔拖一半断掉"的解释之一。

---

## 二、五项重点怀疑逐条排除

### 1) RFB mask 语义 / 是否要发 mask=1 / 坐标连续性 / 会不会每 move 先 mask=0 再 mask=1
- RFC 6143 §7.5.5：PointerEvent = `[5, button-mask, x(u16), y(u16)]`，**bit0 = button 1**。按下后保持移动必须**一直发 mask=1**，只抬起时发 mask=0。当前代码正确：DOWN `sendPointer(1,…)`，MOVE `sendPointer(1,x,y)`，UP `sendPointer(0,…)`。
- 当前 MOVE 分支没有「先发 mask=0 再发 mask=1」的写法（那种写法会被 X 理解为 抬手→再按下，拖拽必坏）。`click()/wheel()` 里的延时 mask=0 **只在 UP/滚轮手势里用**，不会插进拖拽序列。
- 坐标连续性：当前代码在第一次 MOVE 时先 `sendPointer(1,downX,downY)`（把按下点补在起点），再发当前点，轨迹连续；`lastX/Y` 只用于手势取消。

### 2) Xvnc 对 PointerEvent 的处理：先 warp 还是先按下
查 TigerVNC v1.13.1（Ubuntu 24.04 `tigervnc-standalone-server` 对应版本）源码：

- `unix/xserver/hw/vnc/XserverDesktop.cc`：
  ```cpp
  void XserverDesktop::pointerEvent(const Point& pos, int buttonMask)
  {
    vncPointerMove(pos.x + vncGetScreenX(screenIndex), pos.y + vncGetScreenY(screenIndex));
    vncPointerButtonAction(buttonMask);   // ← 先移动，再处理按键变化
  }
  ```
- `unix/xserver/hw/vnc/vncInput.c`：`vncPointerMove()` 只在坐标变化时 queue `MotionNotify`；`vncPointerButtonAction()` 只对 `buttonMask ^ oldButtonMask` 的位 queue Press/Release。
  → **同一个 PointerEvent 内是最新坐标先落地、再按键**；mask 不变的后续事件只产生 Motion。因此按下点不会落到旧坐标，也**不需要**客户端额外发「按下后同坐标 move」。

来源：
- https://raw.githubusercontent.com/TigerVNC/tigervnc/v1.13.1/unix/xserver/hw/vnc/XserverDesktop.cc
- https://raw.githubusercontent.com/TigerVNC/tigervnc/v1.13.1/unix/xserver/hw/vnc/vncInput.c
- https://raw.githubusercontent.com/TigerVNC/tigervnc/v1.13.1/common/rfb/VNCSConnectionST.cxx（pointerEvent 直通，无过滤）

### 3) evaluateJavascript 会不会乱序/合并
- `onTouchEvent` 在 UI 线程；`evaluateJavascript` 也把脚本投递到 WebView/渲染进程主线程，**同一来源 FIFO**：DOWN → MOVE* → UP 顺序不变。
- 原生 16ms 节流只对 `ACTION_MOVE` `return true`（丢帧，不丢 DOWN/UP），不会造成乱序或 UP 提前。
- 风险只可能是**延迟**（JS 主线程在 `process()` 解码 Raw 帧时，触摸脚本排队），但延迟不会让「按住」失效，只影响跟手度。
- 本地用 node 复核了 plTouch 逻辑：一次拖动输出正是 `[5,1,down] [5,1,move…] [5,0,up]`，无中间 mask=0。

### 4) vToFb 的 DPR 换算
- `MotionEvent.getX()` = View 物理像素；WebView 在本工程没有设置 zoom/`useWideViewPort`，页面 `initial-scale=1,width=device-width`，所以 CSS px = 物理像素 / `devicePixelRatio`，且 `devicePixelRatio == density`（520dpi → 3.25）。
- `canvas.getBoundingClientRect()` 返回 CSS px；`(px/DPR - r.left)/r.width*fbW` 量纲自洽，最后 clamp 到 `[0,fbW-1]`。
- 真机反证：xev 里拖拽轨迹连续、方向正确（见第一节统计），说明映射没有整体偏移/缩放错误。
- 唯一前提风险：若以后开启 WebView 缩放/“宽视图”，DPR 与 density 脱钩需重算——当前不成立。

### 5) 是否缺少「按下后先发一次同坐标 move 再开始移动」
- 对 Xvnc：不需要（见 2）。
- 真正需要的是**第一次移动前把 press 补在起点**，避免延迟按下窗口内按下点漂移 —— 当前代码已做（`if(!pressedSent){ sendPointer(1,downX,downY); … }`）。
- 若以后换 x11vnc/libvncserver 系 server，可再考虑 DOWN 前补一次 mask=0 的 move，属兼容性加固，不是本次根因。

---

## 三、最小 patch（未落地；Lead 拥有这两个文件，按需取用）

### P1-a `index.html`：修「长按右键失效」回归（当前 `pressedSent` 分支永远到不了右键）
现在放手时若 `pressedSent` 为真（按住 >80ms 都成立），只发左键 release 就结束，`longPressed` 永远不会变成右键。

```diff
@@ window.plTouch action===1 分支
     if(pressed){
       pressed=false;
-      if(pressedSent){ pressedSent=false; sendPointer(0,x,y); if(!moved) log('左键'); }
+      if(pressedSent){
+        pressedSent=false; sendPointer(0,x,y);              // 先抬左键，拖拽/点击收尾
+        if(!moved && longPressed){                          // 按住不动再抬手 = 右键
+          setTimeout(()=>click(x,y,4),40); log('右键');
+        } else if(!moved) log('左键');
+      }
       else if(!moved){
         if(longPressed){ click(downX,downY,4); log('右键'); }
         else { click(downX,downY,1); log('左键'); }
       }
     }
```

### P1-b `index.html`：加脚本版本号（排查「装了新包但仍跑旧 JS」）
```diff
+window.PLVER='pl4';   // 触摸脚本版本；原生加载后写 logcat，确认不是缓存旧页
```

### P1-c `MainActivity.java`：禁止 WebView 缓存旧页面 + 启动打印 JS 版本
```diff
@@ shouldInterceptRequest
-                        return new android.webkit.WebResourceResponse(mime, "UTF-8", is);
+                        android.webkit.WebResourceResponse resp =
+                                new android.webkit.WebResourceResponse(mime, "UTF-8", is);
+                        resp.getResponseHeaders().put("Cache-Control", "no-store");
+                        return resp;
@@ loadVnc()
             web.loadUrl("http://appassets.local/index.html");
             print("已加载 VNC 页面");
+            web.postDelayed(() -> web.evaluateJavascript("window.PLVER||'none'",
+                    v -> Log.i("PLTOUCH", "jsver=" + v)), 1500);
```
> 依据：升级 APK 后若 WebView 仍用旧 index.html，而新 Java 已在调 `plTouch(...)` → ReferenceError → **任何触摸都无反应**，与用户「现在触摸直接没反应了」的症状一致。

### P2 `index.html` + `MainActivity.java`：ACTION_CANCEL 不能当抬手（防误点击/悬空按键）
```diff
@@ index.html plTouch 开头
+  if(action===3){                        // 系统取消：只释放，绝不发 click
+    clearTimeout(armTimer); clearTimeout(longTimer);
+    if(pressed && pressedSent) sendPointer(0,lastX,lastY);
+    pressed=false; pressedSent=false; mode='idle';
+    return;
+  }
```
```diff
@@ MainActivity CaptureWebView.onTouchEvent
-            else if (action == android.view.MotionEvent.ACTION_CANCEL) a = 1;
+            else if (action == android.view.MotionEvent.ACTION_CANCEL) a = 3;
```

**不要改**的地方：mask 写法、坐标映射、Xvnc 相关（warp 顺序 / 补发同坐标 move）。当前这些都是对的，改动反而引入风险。

---

## 四、依据清单（可复核）

| 依据 | 位置 |
|---|---|
| RFC 6143 §7.5.5 PointerEvent（mask bit0=Button1） | https://datatracker.ietf.org/doc/html/rfc6143#section-7.5.5 |
| Xvnc 先 move 后 button | TigerVNC v1.13.1 `XserverDesktop.cc::pointerEvent`、`vncInput.c`（URL 见上） |
| 真机 X 端收到按住移动（555×state=0x100） | `.probe-tmp/xevwin.log`（x11-prober 采集；21 组 press→move→release，8–16ms/帧） |
| 旧版长按放掉左键导致 MOVE 被丢 | `/tmp/c5.js`、`/tmp/c6.js`、`/tmp/c7.js`（各时间点从设备拉下来的 JS）、`snap/index.new.html`；`if(pressed)` 在 MOVE 分支 |
| 旧 canvas 版观测到 touchcancel | `/tmp/drag3.png` 状态栏 `… c1 …`（c1=cancel 计数） |
| 当前 01:05 版逻辑 | `index.html:236-299`（armTimer/pressedSent/MOVE/UP） |
| plTouch 输出序列模拟 | 本地 node 模拟：`[5,1,315,988] [5,1,334,994] … [5,0,409,1019]` |

---

## 五、装新包后的验证步骤（给 Lead 回归用）

1. `gradle :app:assembleDebug` → 安装 → 进桌面；`logcat -s PLTOUCH:I | grep jsver` 应打印 `jsver="pl4"`（补丁落地后）。
2. XFCE 桌面：按住 Home 图标拖 300ms 以上再动 → 图标应跟随；松手落位。
3. 拖窗口标题栏 → 窗口应移动。
4. 长按桌面 0.8s 不动再抬手 → 应出右键菜单（回归 P1-a）。
5. 双指上下滑滚轮、双指轻点中键、三指上滑键盘 → 不回归。
6. 若 2/3 仍失败：xev 已证明输入到 X 是对的 → 转查桌面层（`pgrep -a xfwm4`、`xfdesktop` 图标是否 auto-arrange），而不是再改 RFB 客户端。

## 六、审计遗留/说明
- 我不能在真机上按/拖（任务限定只读代码），所以「当前版本真机是否已能拖动」需装包实测；但 X 端证据已排除客户端协议问题。
- 本文件所有对照使用工作区当前源码（`index.html` 01:05 版 / `MainActivity.java` 01:0x 版）；Lead 若继续改这两个文件，patch 需重新 rebase。
