# PocketLinux 第二轮代码审查（bug-hunt-2）

- 审查时间：2026-11-27 01:2x
- 审查基线：**commit `db4d775`**（"渲染器与 box64 预设接入 wine 启动环境"，01:23:30；工作区源码干净）
- 方式：**只读源码** + 用 Node 在本地把 `index.html` 的 RFB/plTouch 代码跑在 stub DOM/WebSocket 上做协议与触控状态机仿真。
  未碰手机、未用 adb、未装 APK、未改任何源码（只写本报告）。
- 上轮已修项（Wine 引号、websockify 绑定、launchMode 单例、双指误点、RFB 消息分发、长按右键失效、CANCEL、缓存旧页面）**不在本报告重复**；
  文末 P1-4、P1-3 分别标注了它们与上轮遗留/回归的关系，避免误判为重复项。

---

## 一、结论速览（按优先级）

| # | 优先级 | 位置 | 问题 | 触发概率 |
|---|---|---|---|---|
| P0-1 | **P0** | `MainActivity.java:51,52,54,531-534` | 重新进入桌面时误判「残留进程」，**杀掉仍在运行的桌面**（窗口/Wine 游戏全丢） | 每次「返回后继续桌面」必现 |
| P1-1 | **P1** | `AndroidManifest.xml:27` + `MainActivity.java`（无 `onNewIntent`） | singleTask 后 `EXTRA_RESTART`/`EXTRA_EXE` 被静默丢弃：桌面正在运行时「保存并重启桌面」「运行 notepad」无任何反应 | 桌面日志浮层→设置，必现 |
| P1-2 | **P1** | `MainActivity.java:606-607,638-663`（`db4d775` 新引入） | `wineRenderEnv()` 的 `WINEDLLOVERRIDES` 覆盖了 `disableMono` 的 `mscoree,mshtml=`，**Wine Mono/Gecko 开关完全失效** | 每次启动 exe 必现 |
| P1-3 | **P1** | `index.html:219,250-259,298-320` | 长按右键后 `longPressed` 不复位：**之后所有轻点都变成右键**（左键坏掉） | 长按一次后必现（Node 仿真已复现） |
| P1-4 | **P1** | `MainActivity.java:379-382` | rootfs 解压无完成标记/无原子性：首次安装被中断后 **`bin/sh` 已存在→永远跳过解压，环境永久损坏**（上轮 fix-plan #7 未落地） | 首次安装窗口内被杀/闪退 |
| P2-1 | P2 | `MainActivity.java`（无 `onDestroy`）、`KeepAliveService.java` | WebView 不 destroy、Service 永不 stop → 后台继续解码/重连、进程常驻 | 每次退出桌面 |
| P2-2 | P2 | `MainActivity.java:156,164,314-344` | `startDesktop`/`installEnv` 无并发保护：按钮 + 自动流程可同时启动两份桌面/解压 | 首次启动时点按钮 |
| P2-3 | P2 | `MainActivity.java:633` | `runExeInWine` catch 在**后台线程直接 Toast** → 触发时必崩（`Can't toast on a thread...`） | 启动脚本抛异常时 |
| P2-4 | P2 | `MainActivity.java:42` vs `index.html:216` | `WEB_VER="pl6"` 但前端 `PL_VER='pl5'`，版本自检永远对不上 | 每次加载 |
| P2-5 | P2 | `SettingsActivity.java:241,268,302,328,526`、`Settings.java:20,26` | 文案仍称渲染器/box64「待接入/未实现」，但 `db4d775` 已接入 → 误导测试 | 每次看设置页 |
| P2-6 | P2 | `GameStore.java:75-80`、`SteamStore.java:82-87` | JSON 非原子写，写到一半被杀 → 下次加载静默失败，**整个库“消失”** | 低 |
| P2-7 | P2 | `SteamActivity.java:63,68-71,314-366` | `findSteamExe()` 在 **UI 线程递归扫 Program Files**，且首屏 onCreate+onResume 各扫一次 | Wine 前缀大/文件多时卡顿 |
| P2-8 | P2 | `index.html:71-80` | 断线重连无退避/无上限（800ms 永久重试），重连也不重置触控状态 | 桌面挂掉时 |

---

## 二、P0

### P0-1 重新进入桌面会杀掉后台仍在运行的桌面（核心流程/数据丢失）

**位置**
- `pocketlinux/app/src/main/java/com/pocketlinux/probe/MainActivity.java:51,52,54`（句柄是 Activity 实例字段）
- `MainActivity.java:531-534`（误判清理分支）
- 佐证：`GameLibraryActivity.java:430-431`（首页显示「运行中 / 继续桌面」）、`ui/DesktopStatus.java:71-78`（按端口判定 RUNNING）

```java
// MainActivity.java:51-54
private Process desktopProcess;          // ← 实例字段，Activity 一销毁就丢
private volatile boolean desktopReady;
private int desktopFbW = -1, desktopFbH = -1;

// MainActivity.java:531-534  startDesktop() 里
// 上次进程被杀后残留的桌面（端口被占）先清掉，避免连到旧会话
if (desktopProcess == null && (portOpen(5900) || portOpen(6080))) {
    print("清理残留桌面进程…");
    killDesktop();
}
```

**症状**
桌面进程由 App 进程 fork 出来、并靠 `KeepAliveService`（`MainActivity.java:306`）保持常驻，返回键只是 finish 掉 Activity，**桌面 proot/XFCE/正在跑的游戏都还活着**（首页也据此显示「运行中」）。
但 Activity 重建后 `desktopProcess == null`，`startDesktop()` 把「端口已开」当成「上次进程被杀后的残留」，先 `killDesktop()` 再重开：所有窗口、Wine 游戏、Steam 登录态全丢，并额外等约 10s 重新启动。

**触发（必现）**
1. 进入桌面并等到就绪（可先跑一个 Wine 程序）；
2. 按系统返回键 → 回到首页（首页显示「运行中」）；
3. 点「继续桌面」→ 日志出现 `清理残留桌面进程…`，桌面被重启（旧窗口全没）。

**根因**：进程句柄的生存期应是「App 进程级」，而不是「Activity 实例级」。上轮加的 `singleTask` 只防同一时刻两个实例，**不防实例销毁重建**，所以不冲突、也不算重复项。

**最小 patch**：把桌面会话句柄提升为进程级 static（进程真被杀时 static 自然回到 null，此前的「残留清理」语义仍然正确）。

```diff
--- a/pocketlinux/app/src/main/java/com/pocketlinux/probe/MainActivity.java
+++ b/pocketlinux/app/src/main/java/com/pocketlinux/probe/MainActivity.java
-    private Process desktopProcess;
-    private volatile boolean desktopReady;
+    // 桌面会话是进程级的：Activity 重建（返回后再进入/被系统回收）不能丢句柄，
+    // 否则会误把仍在运行的桌面当成“残留进程”杀掉
+    private static Process desktopProcess;
+    private static volatile boolean desktopReady;
@@
-    private int desktopFbW = -1, desktopFbH = -1;
+    private static int desktopFbW = -1, desktopFbH = -1;
```

（可选加固：`print()` 里对已销毁的 Activity 不再 append；更彻底的做法是把 `desktopProcess` + reader 线程收进一个进程级 `DesktopSession` 单例，但上面的 diff 已能让「继续桌面」变成真正的重连。）

**验证**：重建后走 `desktopProcess.isAlive() && !EXTRA_RESTART && fbW==desktopFbW` 分支 → 直接 `loadVnc()` 重连，不再 kill。

---

## 三、P1

### P1-1 `singleTask` 已加、`onNewIntent` 没写：重启桌面 / 启动 exe 被静默丢弃

**位置**
- `AndroidManifest.xml:27`：`android:launchMode="singleTask"`
- `MainActivity.java`：全类**没有** `onNewIntent`（`grep -n onNewIntent` 无结果）
- 触发入口：
  - `MainActivity.java:174`（桌面日志浮层的「设置」按钮，仅在 MainActivity 存活时可达）
  - `SettingsActivity.java:344-353`（「运行 notepad 测试」→ `EXTRA_EXE="notepad"`）
  - `SettingsActivity.java:391,564-572`（「保存并重启桌面」→ `EXTRA_RESTART=true`）

**症状**
桌面正在运行时（M1 存活，从浮层进设置），设置页点「保存并重启桌面」：Toast 说「已保存，正在重启桌面…」，然后 `finish()`；但 `startActivity(MainActivity)` 命中 singleTask，系统复用 M1 并调 `onNewIntent`，MainActivity 不处理 → `EXTRA_RESTART` 被丢掉，**桌面不会重启、新分辨率不生效**；「运行 notepad」同理，点完回到桌面，什么也没启动。

**触发（必现）**
桌面 → 点右下「日志」→ 浮层里「设置」→「保存并重启桌面」→ 回到**旧桌面**（`getIntent()` 仍是旧 intent）。

**最小 patch**（同时消费一次性 extra，避免 flag 残留导致后续按钮反复重启）

```diff
--- a/pocketlinux/app/src/main/java/com/pocketlinux/probe/MainActivity.java
+++ b/pocketlinux/app/src/main/java/com/pocketlinux/probe/MainActivity.java
+    @Override
+    protected void onNewIntent(Intent intent) {
+        super.onNewIntent(intent);
+        setIntent(intent);
+        final String exe = intent.getStringExtra(EXTRA_EXE);
+        final String wd  = intent.getStringExtra(EXTRA_WORKDIR);
+        final boolean restart = intent.getBooleanExtra(EXTRA_RESTART, false);
+        intent.removeExtra(EXTRA_EXE);      // 一次性 extra，消费掉避免同实例后续操作重复触发
+        intent.removeExtra(EXTRA_WORKDIR);
+        intent.removeExtra(EXTRA_RESTART);
+        if (exe != null) {
+            new Thread(() -> runExeInWine(exe, wd)).start();
+        } else if (restart) {
+            new Thread(() -> { killDesktop(); startDesktop(); }).start();
+        }
+    }
```

桌面「重启」走 `killDesktop()+startDesktop()` 可以复用现有逻辑（`startDesktop` 里已有重启分支）。`runExeInWine` 里用的是 `settings` 字段，如需让新设置立即生效可在那里 `settings = Settings.load(this)` 一次。

**为什么不是重复项**：上轮为防双实例加的是 `launchMode`（清单层面），但 singleTask 语义要求补 `onNewIntent`，这属于新暴露的契约缺口。

---

### P1-2 `wineRenderEnv()` 覆盖 `WINEDLLOVERRIDES`，Mono/Gecko 开关失效（`db4d775` 新引入）

**位置**
- `MainActivity.java:606-607`：先按设置 export `WINEDLLOVERRIDES="mscoree,mshtml="`
- `MainActivity.java:638-663`：`wineRenderEnv()` 又无条件 append 一个 `export WINEDLLOVERRIDES=d3d11,d3d10core,dxgi=…`
- 三个分支都会覆盖：`653`（wined3d）、`656`（dxvk）、`662`（回退 wined3d）

```java
// runExeInWine(): inner
+ (settings.disableMono ? "export WINEDLLOVERRIDES=\"mscoree,mshtml=\"; " : "")
+ wineRenderEnv()          // ← 里面一定会再 export WINEDLLOVERRIDES=d3d11,...
```

**症状**
`设置 → Wine → 关闭 Mono / Gecko 首次安装弹窗`（默认开启）在**游戏启动路径**上完全不起作用：
`run-wine.sh` 里后一次 export 覆盖前一次，Wine 首次运行仍会弹 Mono/Gecko 安装器，正好是设置页承诺要消除的弹窗。
（`desktopScript` 那条路径没有被覆盖，但游戏启动走的是 `runExeInWine`，所以默认路径全中。）

**触发**：任意「导入 exe → 启动」或「运行 notepad 测试」，首次 Wine 初始化即弹窗。

**最小 patch**：只 export 一次，把两个 override 合并；删掉 `runExeInWine` 里那行单独 export。

```diff
--- a/pocketlinux/app/src/main/java/com/pocketlinux/probe/MainActivity.java
+++ b/pocketlinux/app/src/main/java/com/pocketlinux/probe/MainActivity.java
-                    + (settings.disableMono ? "export WINEDLLOVERRIDES=\"mscoree,mshtml=\"; " : "")
                     + wineRenderEnv()
@@  private String wineRenderEnv()
-        // 渲染器
-        String r = settings.renderer;
-        boolean dxvkPresent = new File(rootfsDir, "opt/dxvk/x64/d3d11.dll").exists();
-        if (Settings.RENDERER_WINED3D.equals(r)) {
-            sb.append("export WINEDLLOVERRIDES=d3d11,d3d10core,dxgi=b; ");
-            print("渲染器：wined3d（内置）");
-        } else if (dxvkPresent) {
-            sb.append("export WINEDLLOVERRIDES=d3d11,d3d10core,dxgi=n; ")
-              .append("export DXVK_HUD=0; ");
-            if (Settings.RENDERER_DXVK_VORTEK.equals(r)) sb.append("export GALLIUM_DRIVER=zink; ");
-            print("渲染器：" + settings.rendererName());
-        } else {
-            print("渲染器：" + settings.rendererName() + " 未安装，回退 wined3d");
-            sb.append("export WINEDLLOVERRIDES=d3d11,d3d10core,dxgi=b; ");
-        }
+        // 渲染器：WINEDLLOVERRIDES 只 export 一次，把 Mono 关闭项和 d3d 覆盖合并
+        String r = settings.renderer;
+        boolean dxvkPresent = new File(rootfsDir, "opt/dxvk/x64/d3d11.dll").exists();
+        boolean useDxvk = !Settings.RENDERER_WINED3D.equals(r) && dxvkPresent;
+        sb.append("export WINEDLLOVERRIDES=\"")
+          .append(settings.disableMono ? "mscoree,mshtml=;" : "")
+          .append("d3d11,d3d10core,dxgi=").append(useDxvk ? "n" : "b").append("\"; ");
+        if (Settings.RENDERER_WINED3D.equals(r)) {
+            print("渲染器：wined3d（内置）");
+        } else if (dxvkPresent) {
+            sb.append("export DXVK_HUD=0; ");
+            if (Settings.RENDERER_DXVK_VORTEK.equals(r)) sb.append("export GALLIUM_DRIVER=zink; ");
+            print("渲染器：" + settings.rendererName());
+        } else {
+            print("渲染器：" + settings.rendererName() + " 未安装，回退 wined3d");
+        }
         return sb.toString();
     }
```

（核心就是：`disableMono` 的 `mscoree,mshtml=` 与 d3d 的覆盖串必须**在同一个 export 里**，后 export 覆盖前一个的写法一律不行。）

---

### P1-3 长按右键后 `longPressed` 不复位：之后所有轻点都变右键

**位置**：`index.html:219`（声明）、`index.html:250-259`（ACTION_DOWN 分支）、`index.html:298-320`（ACTION_UP 分支）

```js
// index.html:250-259
if(action===0){
    ...
    moved=false; suppressUp=false;      // ← 没有 longPressed=false
    pressed=true; pressedSent=false;
    clearTimeout(armTimer); clearTimeout(longTimer);
    ...
    longTimer=setTimeout(()=>{ longPressed = !moved; }, 600);
    return;
}
// index.html:310-317 抬手
if(pressedSent){
    pressedSent=false; sendPointer(0,x,y);
    if(!moved && longPressed){ setTimeout(()=>click(x,y,4),50); log('右键'); }  // ← 用的是上一轮残留的 longPressed
    else if(!moved) log('左键');
}
```

**症状**
`longPressed` 只会在 600ms 定时器里被赋值；短按（<600ms）时定时器被 `clearTimeout` 清掉，**标志保持上一次手势的值**。于是：长按一次产生右键后再做任何短按 → 又发一次右键（并且前面还有一次左键 down/up），桌面里表现为「点一下弹出右键菜单 / 左键失灵」。这是上轮「长按右键」修复引入的复位遗漏（不是重复上轮问题，上轮修的是右键根本发不出来）。

**Node 仿真证据**（`/tmp/plharness/harness.js`，临时脚本，未提交）：
把 `index.html` 的脚本原样跑在 stub WebSocket/canvas 上，抓 RFB PointerEvent 字节：

```
after long press: 5,0 (left up) → 5,4 (right down) → 5,0 (right up)   ← 正确
T2 下一次短按：   5,1 → 5,0 → 5,4 → 5,0   ← 错：短按变左键+右键
T3 再下一次短按： 5,4 → 5,0              ← 错：只剩右键
加上 longPressed=false 后：T2/T3 都是 5,1 → 5,0（纯左键） ✅
```

**最小 patch**

```diff
--- a/pocketlinux/app/src/main/assets/novnc/index.html
+++ b/pocketlinux/app/src/main/assets/novnc/index.html
@@  action===0
     mode='single'; downX=lastX=x; downY=lastY=y; downTime=Date.now();
     moved=false; suppressUp=false;
+    longPressed=false;                 // 每次按下都复位，否则上一次长按会污染后续所有轻点
     pressed=true; pressedSent=false;
```

建议顺手在 `connect()` 里也复位一次（断线重连时旧手势状态不应残留）：
`pressed=false; pressedSent=false; longPressed=false; mode='idle'; clearTimeout(armTimer); clearTimeout(longTimer);`

---

### P1-4 rootfs 解压没有完成标记（上轮 fix-plan #7 未落地）：首次安装中断=永久损坏

**位置**
- `MainActivity.java:379-382`：唯一完成判据是 `rootfs/bin/sh` 是否存在
- `MainActivity.java:366-378`：native 的判据只有 `exists() && length()>0`
- `TarExtractor.extractRaw(...)`（`TarExtractor.java:34-45`）**直接**往最终 `rootfsDir` 写入，无临时目录/无 `.pl-ok`/无 md5

```java
// MainActivity.java:379-382
if (!new File(rootfsDir, "bin/sh").exists()) {
    try (InputStream is = getAssets().open("rootfs.tar")) {
        TarExtractor.extractRaw(is, rootfsDir.toPath(), 0, null);   // 中断 => 半成品目录
    }
} else {
    print("rootfs 已存在，跳过解压");
}
```

**症状**
首次启动解压 100MB rootfs（约 1-2 分钟）或 apt 安装 XFCE（5-10 分钟）期间，如果 App 进程被杀/闪退/被系统回收，`bin/sh` 往往已经先解出来 → 之后每次启动都走「rootfs 已存在，跳过解压」，环境永远是半成品；UI 上**没有任何重置/重装入口**，只能清 App 数据。native 库同理：写到一半的 `proot` 只要 `length()>0` 就被当成完好。

**触发**：首次启动流程中强杀 App（或解压时磁盘满）→ 重新打开，日志 `rootfs 已存在，跳过解压` 然后运行失败。

**最小 patch**（临时目录 + 标记后 rename，保持原子性；native 也加一个长度/版本清单）

```diff
--- a/pocketlinux/app/src/main/java/com/pocketlinux/probe/MainActivity.java
+++ b/pocketlinux/app/src/main/java/com/pocketlinux/probe/MainActivity.java
-            if (!new File(rootfsDir, "bin/sh").exists()) {
+            File ok = new File(rootfsDir, ".pl-ok");
+            if (!ok.exists()) {
                 print("== 解压 rootfs ==");
+                File tmpRootfs = new File(getFilesDir(), "rootfs.tmp");
+                deleteTree(tmpRootfs);
                 try (InputStream is = getAssets().open("rootfs.tar")) {
-                    TarExtractor.extractRaw(is, rootfsDir.toPath(), 0, null);
+                    TarExtractor.extractRaw(is, tmpRootfs.toPath(), 0, null);
                 }
+                Files.write(new File(tmpRootfs, ".pl-ok").toPath(), "1".getBytes("UTF-8"));
+                deleteTree(rootfsDir);
+                if (!tmpRootfs.renameTo(rootfsDir))
+                    throw new java.io.IOException("rootfs rename 失败");
             } else {
                 print("rootfs 已存在，跳过解压");
             }
```

注意：`renameTo` 需同一文件系统（都在 `files/` 下，成立）；失败时下次启动依旧无 `.pl-ok` → 会重试。native 校验至少补 md5 或 `length` 清单（`assets/native/*.md5`），避免半截 `proot` 被当成好文件。

---

## 四、P2

### P2-1 WebView 不销毁 + KeepAliveService 永不停止（后台耗电/泄漏）

- `MainActivity.java` 全类没有 `onDestroy`，`web`（WebView）也没有 `web.destroy()`；`KeepAliveService` 由 `MainActivity.java:306` 启动，全工程**没有任何 `stopService/stopSelf`**。
- 返回键退出桌面后：WebView 页面与 websocket 仍在后台继续收帧、`putImageData`；`index.html:79` 的 onclose 还会每 800ms 重连。再进桌面又 new 一个 WebView → 旧实例堆积。
- 用户从最近任务划掉 App 后，前台服务 + proot 桌面仍在跑，通知栏常驻且无法手动结束。
- 最小 patch：

```java
@Override
protected void onDestroy() {
    if (web != null) { web.loadUrl("about:blank"); web.destroy(); web = null; }
    super.onDestroy();
}
```
若产品上要求「退出即关桌面」，再在 `onDestroy` 里按需 `killDesktop()+stopService`；否则至少销毁 WebView、给 Service 加可结束入口。

### P2-2 `startDesktop` / `installEnv` 无并发保护（可双开 proot / 双解压）

- 入口：`MainActivity.java:156`（准备环境按钮 `new Thread(this::installEnv)`）、`164`（启动桌面按钮 `new Thread(this::startDesktop)`）、`314-344`（boot 线程自动 `installEnv()`/`startDesktop()`）。
- 首次启动时浮层按钮可用：boot 正在解压/装 XFCE 时用户点「准备环境」= 两个 TarExtractor 同时写同一 rootfs；点「启动桌面」= 在 XFCE 未装完时起桌面。重复点击「启动桌面」也可能两个 proot 同时抢 `:99`/`6080`，`desktopProcess` 被后者覆盖，前者变孤儿。
- 最小 patch：加 `private static final AtomicBoolean INSTALLING/STARTING`，进入时 CAS 失败直接 `print("正在进行")` 返回；或 `synchronized` 方法 + 按钮 setEnabled(false)。

### P2-3 `runExeInWine` catch 在后台线程 Toast → 必崩

- `MainActivity.java:633`：`Toast.makeText(...).show()` 直接在 catch 里；该方法运行在 boot/onNewIntent 的后台线程，没有 Looper → 一旦走到 catch 就抛 `RuntimeException: Can't toast on a thread that has not called Looper.prepare()`，异常发生在 catch 区块内会直接带崩进程。
- 最小 patch：`runOnUiThread(() -> Toast.makeText(this, "Wine 启动失败：" + t, Toast.LENGTH_LONG).show());`

### P2-4 缓存自检版本号对不上

- `MainActivity.java:42` `WEB_VER="pl6"`，`index.html:216` `window.PL_VER='pl5'`；`onPageFinished` 打印 `jsver=` 是为了确认没有跑旧页面，现在永远显示 pl5，起不到「版本必须等于当前前端」的自检作用。
- 最小 patch：把 `index.html` 改成 `window.PL_VER='pl6'`（以后两个一起改，或只从 URL 参数带版本号）。

### P2-5 设置页文案还停留在「未接入」

- `SettingsActivity.java:241`「待接入」、`268`「后端 Wine 启动脚本还没读取」、`302`「未实现」、`328`「占位项」、`526`「（未实现）」；`Settings.java:20,26` 注释同样过期。
- `db4d775` 已经在 `runExeInWine` 里读取 `renderer`/`box64Preset` 并拼 env（即使 DXVK 缺失会回退 wined3d）。现在的文案会让测试以为「切了没效果」，掩盖 P1-2 这类真实问题。
- 建议：渲染器保留「DXVK 未安装时回退 wined3d」说明，box64 改成「平衡/性能/兼容」真实含义；同时说明「只对从首页/Steam 启动的游戏生效，桌面内自己开的程序不受影响」。

### P2-6 `games.json` / `steam-games.json` 非原子写

- `GameStore.java:75-80`、`SteamStore.java:82-87` 都是直接 `Files.write` 覆盖。写一半进程被杀 → 文件截断；`load()` 里 `catch (Throwable)` 把异常吞掉，界面表现为**整个程序库/Steam 库清空**（文件还在但解析不了，且不会自动修复）。
- 最小 patch：写 `*.json.tmp` 后 `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`；`load()` 失败时尝试读同目录 `.bak`。

### P2-7 SteamActivity 在 UI 线程递归扫 Wine 前缀

- `SteamActivity.java:63`（onCreate）、`68-71`（onResume）都调 `refreshSteamDetect()` → `315` `findSteamExe()` → `332-366` 递归 `Program Files (x86)`/`Program Files`（深度 3，跟着目录走）。
- Wine 前缀里装了 Steam 后目录几千项，首屏和每次返回本页都同步扫一遍，弱机容易掉帧甚至 ANR；`findNamed` 也会跟进目录符号链接。
- 最小 patch：把 `findSteamExe()` 丢到 `new Thread(...)`，`runOnUiThread` 回填；并删掉 onCreate 里的重复调用（onResume 已经会调）。

### P2-8 RFB 断线重连无退避/无上限，且不重置触控状态

- `index.html:79`：`ws.onclose=()=>{ ...; setTimeout(connect,800); }`，桌面没起来时会每 800ms 重连到天荒地老（页面在后台也跑）；`connect()` 只重置了 RFB 状态，没有重置 `pressed/longPressed/mode/armTimer/longTimer`。
- 最小 patch：指数退避 + 上限（如 8 次后停在「桌面已断开，点按重试」），并在 `connect()` 开头复位触摸状态机（与 P1-3 的补丁一起做）。

---

## 五、本轮已核查、判定「不是 bug / 不重复报」的点

- **RFB 分块/半包处理**：`process()` 的 `available()`/`end-p` 边界核对过，Node 仿真把 ServerInit 后的 FramebufferUpdate 拆成 3 块喂入：前两块不误消费、第三块完整后只发一次增量请求，无错位。✅
- **16bpp Raw 解码**：仿真像素 `0x001F→(0,0,255)`、`0x07E0→(0,255,0)`，RGB565 位移/大小端与 SetPixelFormat 一致。✅
- **ServerCutText/ColourMap/Bell 分派**：类型 1/2/3 的跳过长度计算正确（上轮已修，未回归）。✅
- **Wine 路径含空格/中文/单引号**：`shq()` + `export PL_EXE=…` + 内层 `"$PL_EXE"` 组合是正确写法；games 目录映射 `/games` 也在 `run-wine.sh` 里 bind。✅
- **Steam `C:\` → `/home/.wine/drive_c` 映射**：`normalizeExePath` → `resolveHostFile` → `hostToGuest` → `runExeInWine("$PL_EXE")` 链路自洽（Wine 前缀在 `files/home/.wine`，与 desktopScript 的 `-b home:/home` + `WINEPREFIX=/home/.wine` 一致）。✅
- **外部 Intent 注入**：`MainActivity` 是 `exported=false`（Manifest:26），第三方 App 无法直接塞 `EXTRA_EXE`；`GameLibraryActivity` 虽然 exported 但不转发 extras。✅
- **websockify/Xvnc 绑定**：已是 `127.0.0.1:6080` + `-localhost`（上轮修复，未回归）。⚠️ 残余风险：本机任意有 INTERNET 权限的 App 仍可连 `127.0.0.1:6080`（无认证）看到/操作桌面，属于方案本身限制，需要 VNC 密码或 Unix socket 才能根治，不在本轮给 patch。
- **上轮项**：Wine 引号、websockify 绑定、launchMode、双指误点、RFB 消息分发、长按右键失效、CANCEL、缓存旧页面，本轮均未重复报告。

---

## 六、复现/验证工具说明

- 触控与 RFB 仿真是临时脚本，未写入仓库：`/tmp/plharness/harness.js`（读取 `index.html` 内联脚本，用 stub `document`/`canvas`/`WebSocket` 跑真实代码）：
  - 握手：喂 `RFB 003.008\n` → `[1,1]` → SecurityResult → ServerInit，核对客户端发出的 SetPixelFormat/SetEncodings/FramebufferUpdateRequest 字节；
  - 分帧：FramebufferUpdate 拆 3 段喂入，核对 `img.data`；
  - 触控：用真实 `setTimeout` 模拟长按→短按序列，抓 `PointerEvent`（类型 5）mask。
- 修复验证：把 P1-3 的 `longPressed=false` 打进副本后，仿真 T2/T3 由 `mask=4` 变回 `mask=1`。
