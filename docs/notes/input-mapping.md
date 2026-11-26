# 输入外设子系统（物理键盘 / 手柄 + 按键映射）

- 状态：**M1 已实现**（2026-11-27），M2（虚拟手柄 / 陀螺仪 / 每游戏 profile）为设计稿，未实现
- 代码：`MainActivity.java`（输入捕获 + 映射）、`assets/novnc/index.html`（新增 `plKeyDown/plKeyUp/plPointerButton/plWheel/plStick`）
- 映射文件：App 私有目录 `files/input-map.json`（可选，存在则按同名项覆盖内置默认）
- 版本：`window.PL_VER='pl9'`；日志 TAG=`PLPROBE`，启动时会打印
  `输入映射: 键盘 N / 手柄键 M / 轴 K / hat J（内置默认 | 已读取 input-map.json 覆盖）`

---

## 1. 架构（数据流）

```
物理键盘 KeyEvent ──> MainActivity.dispatchKeyEvent ──InputMap.keyboard(KeyCode→keysym)──>
        evaluateJavascript("plKeyDown/plKeyUp(<keysym>)") ──> index.html sendKey ──> RFB KeyEvent ──> Xvnc/XFCE

手柄按键 KeyEvent ──> 同上，但先查 InputMap.gamepadButtons(KeyCode→action)
        action: mouse_left/middle/right → JS plPointerButton(1|2|3, down) → RFB Pointer(mask 1/2/4)
                wheel_up/down        → JS plWheel(up)                     → RFB Pointer(mask 8/16)
                keyboard             → JS plShowKeyboard()
                key:<keysym>         → JS plKeyDown/plKeyUp

手柄摇杆/摇杆帽 GenericMotionEvent(ACTION_MOVE) ──> MainActivity.dispatchGenericMotionEvent
        InputMap.gamepadAxes: AXIS_X/AXIS_Y → 死区(deadzone)+缩放(stickSpeed) → JS plStick(lx,ly)
        JS 用 requestAnimationFrame 按 dt 积分：ptr += stick * STICK_SPEED(820 fb px/s) * trackpadSens() * dt
        → RFB Pointer(mask 0 或当前按住的鼠标键)  （复用触控板灵敏度：设置页调触控板灵敏度，手柄一起变）
        InputMap.hatAxes: AXIS_HAT_X/Y → 方向键按下/抬起边沿（key:Left/Right/Up/Down）
```

要点：

- 按键**按下/抬起分离**（`plKeyDown/plKeyUp`），X 端自己维护修饰键状态，所以 Ctrl+C、Shift+字母、Alt+Tab 这类组合键成立；
  长按的重复交给 X 端 auto-repeat（KeyEvent 的 repeat 不再重复发，避免双倍连发）。
- 鼠标按钮在 JS 侧有一个位掩码 `puBtnMask`（1 左 / 2 中 / 4 右），按住 A 时摇杆移动的帧也带 mask=1，
  所以 **按住 A + 左摇杆 = 按住左键拖拽**（可拖窗口/图标/选择文本）。
- 手柄摇杆/摇杆帽只在值变化时以约 30Hz 推给 JS，由 JS 的 rAF 做平滑积分，避免每帧 evaluateJavascript 过重。

## 2. 支持列表（M1）

### 物理键盘（KeyEvent）

| 类别 | 支持 |
|---|---|
| 字母 | A–Z（发小写 keysym，配合 Shift 出大写） |
| 数字 | 0–9 |
| 功能键 | F1–F12 |
| 方向键 | ← ↑ → ↓ |
| 编辑键 | Esc、Tab、Enter、Backspace、Delete、Insert、Home、End、PageUp、PageDown |
| 修饰键 | Ctrl（左右）、Shift（左右）、Alt（左右）、Meta（左右）、CapsLock |
| 符号 | `- = [ ] \ ; ' , . / `` ` |
| 小键盘 | NumLock、0–9、`/ * - +`、`.`、Enter |
| 其他 | 未映射的键（如音量键、电源）原样交给系统 |

### 手柄（KeyEvent + GenericMotionEvent）

| 输入 | 是否读取 | 默认动作 |
|---|---|---|
| 左摇杆 AXIS_X / AXIS_Y | ✅ | 鼠标相对移动（复用触控板灵敏度） |
| 右摇杆 AXIS_Z / AXIS_RZ | ✅（可配置） | 默认不映射（JSON 里可改成 mouse_x/mouse_y） |
| 摇杆帽 AXIS_HAT_X / AXIS_HAT_Y | ✅ | 方向键 ←→↑↓ |
| A / B / X / Y | ✅ | 左键 / 右键 / 回车 / Esc |
| L1 / R1 | ✅ | 滚轮上 / 滚轮下（每次按下滚一格） |
| L2 / R2 | ✅（可配置） | 默认不映射（可改成 mouse_left 等） |
| START / SELECT | ✅ | START=弹键盘；SELECT 默认不映射 |
| 十字键（KeyEvent 上报型） | ✅ | 走键盘映射 → 方向键 |

## 3. 默认映射表

### 键盘（Android KeyCode → X keysym 值，0x 十六进制）

| KeyCode | keysym | 键 | KeyCode | keysym |
|---|---|---|---|---|
| KEYCODE_A..Z | 0x61..0x7a | a..z | KEYCODE_0..9 | 0x30..0x39 |
| KEYCODE_F1..F12 | 0xFFBE..0xFFC9 | F1..F12 | KEYCODE_DPAD_LEFT/UP/RIGHT/DOWN | 0xFF51 / 0xFF52 / 0xFF53 / 0xFF54 |
| KEYCODE_ESCAPE | 0xFF1B | Esc | KEYCODE_TAB | 0xFF09 |
| KEYCODE_ENTER | 0xFF0D | Enter | KEYCODE_DEL | 0xFF08 (Backspace) |
| KEYCODE_FORWARD_DEL | 0xFFFF | Delete | KEYCODE_SPACE | 0x20 |
| KEYCODE_SHIFT_LEFT/RIGHT | 0xFFE1 / 0xFFE2 | Shift | KEYCODE_CTRL_LEFT/RIGHT | 0xFFE3 / 0xFFE4 |
| KEYCODE_ALT_LEFT/RIGHT | 0xFFE9 / 0xFFEA | Alt | KEYCODE_META_LEFT/RIGHT | 0xFFEB / 0xFFEC |
| KEYCODE_CAPS_LOCK | 0xFFE5 | CapsLock | KEYCODE_MOVE_HOME/END | 0xFF50 / 0xFF57 |
| KEYCODE_PAGE_UP/DOWN | 0xFF55 / 0xFF56 | PgUp/PgDn | KEYCODE_INSERT | 0xFF63 |
| KEYCODE_MINUS / EQUALS | 0x2D / 0x3D | `-` `=` | KEYCODE_LEFT/RIGHT_BRACKET | 0x5B / 0x5D |
| KEYCODE_BACKSLASH | 0x5C | `\` | KEYCODE_SEMICOLON / APOSTROPHE | 0x3B / 0x27 |
| KEYCODE_COMMA / PERIOD / SLASH | 0x2C / 0x2E / 0x2F | `, . /` | KEYCODE_GRAVE | 0x60 |
| KEYCODE_NUMPAD_0..9 | 0xFFB0..0xFFB9 | 小键盘数字 | KEYCODE_NUMPAD_* | 0xFFAF `/`、0xFFAA `*`、0xFFAD `-`、0xFFAB `+`、0xFFAE `.`、0xFF8D Enter |

### 手柄（输入 → 动作）

| 输入 | 动作 |
|---|---|
| 左摇杆 | `mouse_x` / `mouse_y`（鼠标相对移动） |
| 摇杆帽 | `key:Left` / `key:Right` / `key:Up` / `key:Down` |
| A | `mouse_left`（左键按下/抬起） |
| B | `mouse_right` |
| X | `key:Return` |
| Y | `key:Escape` |
| L1 | `wheel_up` |
| R1 | `wheel_down` |
| START | `keyboard`（弹出键盘） |
| 摇杆参数 | 死区 `0.15`，缩放 `1.0` |

## 4. 映射文件 `files/input-map.json`（可选）

App 私有目录：`/data/data/com.pocketlinux.probe/files/input-map.json`
（App 内即 `getFilesDir()/input-map.json`）。文件**不存在时全部用内置默认**；存在时按键名逐项覆盖，
没写的项继续用默认。JSON 解析失败会打日志并回退默认，不会崩。

完整示例见 [`input-map.example.json`](./input-map.example.json)：

```json
{
  "keyboard": {
    "KEYCODE_ESCAPE": "Escape",
    "KEYCODE_F1": "F1",
    "KEYCODE_CTRL_LEFT": "Control_L",
    "KEYCODE_A": "a",
    "KEYCODE_ENTER": "Return"
  },
  "gamepad": {
    "BUTTON_A": "mouse_left",
    "BUTTON_B": "mouse_right",
    "BUTTON_X": "key:Return",
    "BUTTON_Y": "key:Escape",
    "BUTTON_L1": "wheel_up",
    "BUTTON_R1": "wheel_down",
    "BUTTON_START": "keyboard",
    "BUTTON_L2": "mouse_left",
    "AXIS_X": "mouse_x",
    "AXIS_Y": "mouse_y",
    "AXIS_RZ": "mouse_x",
    "AXIS_HAT_X": ["key:Left", "key:Right"],
    "AXIS_HAT_Y": ["key:Up", "key:Down"],
    "stick": { "deadzone": 0.15, "speed": 1.0 }
  }
}
```

### 键名规则

- **keyboard 段**：键 = Android KeyCode 名（`KEYCODE_A`，也接受省略前缀的 `A` / `PANEL` 这类写法会失败并忽略），
  值 = 单字符（`"a"`、`"1"`、`";"`）/ `0x` 十六进制 keysym（`"0xFF0D"`）/ 常用 X keysym 名
  （`Escape`、`Return`/`Enter`、`Tab`、`BackSpace`、`Delete`、`Insert`、`Home`、`End`、`Page_Up`、`Page_Down`、
  `Left`、`Up`、`Right`、`Down`、`Shift_L`、`Control_L`、`Alt_L`、`Super_L`、`Caps_Lock`、`F1..F12`、
  `space`、`minus`、`equal`、`bracketleft`、`bracketright`、`backslash`、`semicolon`、`apostrophe`、
  `comma`、`period`、`slash`、`grave`、`KP_0..KP_9`、`KP_Enter`、`KP_Divide`、`KP_Multiply`、`KP_Subtract`、`KP_Add`、`KP_Decimal`）。
- **gamepad 段**：键 = `BUTTON_A`…`BUTTON_START`/`BUTTON_SELECT`/`BUTTON_L2`/`BUTTON_R2`/`BUTTON_THUMBL`…
  （Android `KEYCODE_BUTTON_*`）或 `AXIS_X`/`AXIS_Y`/`AXIS_Z`/`AXIS_RZ`/`AXIS_LTRIGGER`…（`MotionEvent.AXIS_*`）。
  - 轴值只能是 `mouse_x` / `mouse_y`，或写成 `["负方向 action", "正方向 action"]`（hat，如方向键）。
  - `"stick": {"deadzone": 0..0.8, "speed": 0.2..3.0}` 只影响摇杆。
  - 动作字符串：`mouse_left` / `mouse_middle` / `mouse_right` / `wheel_up` / `wheel_down` / `keyboard` / `key:<keysym 名或 0xhex>`。
  - 手柄上没映射的按键会回落到 keyboard 段（所以十字键默认就是方向键）；两段都没映射的交给系统。

### 推送 / 生效

```sh
# 1) 本地写好 JSON 后 push 到 /data/local/tmp（App 私有目录 shell 写不进去）
adb push input-map.json /data/local/tmp/input-map.json
# 2) 用 run-as 以 App 身份拷贝（避免 SELinux/MCS 问题，不要用 root cp）
adb shell "run-as com.pocketlinux.probe sh -c 'cat /data/local/tmp/input-map.json > files/input-map.json'"
# 3) 让映射重新加载：重新进入桌面 Activity 即可（onNewIntent 会 reload），或重启 App
```
> 注意：root 直接 `cp` 到 App 目录的文件 App 读不到（SELinux MCS 不匹配，HANDOFF 有记录），必须走 `run-as`。

## 5. 验证方法（M1 自测）

- **编译**：`gradle :app:assembleDebug` 通过；APK 里 `assets/novnc/index.html` 含 `plKeyDown/plKeyUp/plPointerButton/plWheel/plStick`。
- **JS 状态机 harness**（无 DOM/无设备，stub 浏览器 + 真实 RFB 握手）：
  `node /root/workspace/.probe-tmp/test-touch.js` → **46 项断言全过**，含：
  - 键盘：`plKeyDown/plKeyUp` 发出 RFB KeyEvent(按下/抬起)，keysym 编码正确，`plKey` 兼容（按下+30ms 抬起）；
  - 手柄按键：A 按下/松开 mask 1→0、左右同按 mask=5、L1/R1 滚轮 mask 8/16；
  - 摇杆：rAF 积分（右推 100ms ≈ 131px = 820×1.6×0.1）、移动帧 mask=0、归零停止、A 按住+摇杆产生 mask=1 移动帧（拖拽）；
  - `PL_VER='pl9'`、`plSetTouchThresholds` 可调与钳制（hold 250–800 / right 400–1000）。
- **真机**（装包后由 Lead 做）：外接键盘敲字母/F 键/方向键/Esc、Ctrl+C；手柄左摇杆移动指针、A 点击、
  按住 A 拖窗口、L1/R1 滚轮、START 弹键盘；`logcat -s PLPROBE` 应出现
  `输入映射: ...` 和（接入手柄时）无异常；`logcat -s PLTOUCH` 仍是触摸路径。
- 现有限制：右摇杆默认未映射；L2/R2 默认未映射（但都可在 JSON 里配）；组合键依赖 Xvnc 键位表（标准 X 键位，已覆盖）。

## 6. M2 设计（本轮不实现）

### 6.1 屏幕虚拟手柄（悬浮按键 + 布局编辑器）

- **数据模型**（每游戏 profile 一份，默认 `files/vpad-default.json`）：
  ```json
  { "buttons": [
      { "id":"a", "label":"A", "action":"mouse_left", "x":0.86, "y":0.78, "r":0.055, "opacity":0.6 },
      { "id":"stick", "type":"stick", "action":"mouse", "x":0.18, "y":0.75, "r":0.12,
        "deadzone":0.15, "speed":1.0 }
  ] }
  ```
  坐标用 0~1 相对屏幕（竖屏/横屏都适配）；`action` 复用本文 M1 的动作字符串（`mouse_left`/`key:...`/`wheel_up`…），
  摇杆类型用 `stick`（输出 plStick）。新增动作 `toggle_vpad` 用于显示/隐藏。
- **渲染**：`index.html` 加一层 `#vpad`（absolute 定位的圆钮/图标，`pointer-events:auto`，z-index 高于 canvas 低于设置浮层）；
  触摸由该层自己处理（不经过 `plTouch`），按下/抬起 → `plPointerButton`/`plKeyDown/Up`，摇杆 → `plStick`；
  透明度/尺寸随设置项变化。
- **编辑器**：长按"编辑"进入编辑态：拖动按钮改位置、双指缩放改半径、点选改 action/标签、删除/添加；
  保存写回 profile JSON（通过 native 的 `saveProfile` JS 桥或提供文本编辑面板，M2 先做文本面板 + 拖拽）。
  编辑态禁用游戏输入转发，避免误触。
- **入口**：MainActivity 增加一个悬浮小按钮（可复用右下"日志"按钮旁）切换虚拟手柄显示；
  设置页（ui-designer）加"虚拟手柄：无/默认/自定义"选项。

### 6.2 陀螺仪（GYROSCOPE → 视角/鼠标移动）

- `SensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)`，`SENSOR_DELAY_GAME`（~50Hz）。
- 角速度（rad/s）积分成角度增量：`yaw += gz*dt`（水平转手机）→ 映射到鼠标相对移动；
  `pitch += gx*dt`（前后仰）→ 可映射纵向或忽略。
- 参数：`deadzone`（默认 0.05 rad/s）、`sensitivity`（默认 1.0，0.2–3.0）、`invert_x/invert_y`、`mode`：
  - `mouse`：像摇杆一样输出 `plStick`/新增 `plAim(dx,dy)` → 桌面指针移动（适合 FPS 的操作）；
  - `disabled`：默认关闭（避免桌面使用误动）。
- 校准：启动时采样 300ms 作为零偏（gyro bias），并做一阶低通滤波。
- **权限/兼容**：需 `android.hardware.sensor.gyroscope`（`uses-feature required=false`）；无陀螺仪时自动隐藏该设置项。
- JS 端只新增 `plAim(dxPx,dyPx)`（不走 rAF 积分，直接相对位移），复用触控板灵敏度与 `#cur` 光标。

### 6.3 每游戏 profile

- `Game` 增加 `profile` 字段（profile 目录 `files/profiles/<gameId>.json`），内容：
  ```json
  { "inputMap": { "gamepad": {...} }, "vpad": {...}, "gyro": {...} }
  ```
- 启动 exe 时（`MainActivity.runExeInWine`）按 `Game.profile` 加载并合并到当前 InputMap；退出游戏后回落到默认 profile。
- 加载顺序：内置默认 → `files/input-map.json`（全局）→ profile 覆盖；每层只覆盖写了的键。
- Settings/GameLibrary 里每张游戏卡片加"输入配置"入口（ui-designer 负责 UI），Save 后写 profile 文件。
- 热切换：profile 切换时 reload InputMap 并注入 JS（`plSetTouchMode/plSetTouchThresholds` 已有；新增 `plSetVirtualPad(json)`）。

### 6.4 实施顺序建议

1. JS 虚拟手柄层（纯前端，可先用固定布局验证触摸→动作链路）→ 2. 编辑器与 profile 文件读写（native JSON 桥）→
3. 陀螺仪（native Sensor 监听 + plAim）→ 4. 每游戏 profile 合并与 UI 入口。

## 7. 需要 Lead 接的部分

1. **装包验证**：编译产物已就绪，外接键盘/手柄真机回归（按第 5 节清单）；需要 `Settings` 页的开关由 ui-designer 加（M2）。
2. **可选**：把默认 `input-map.example.json` 作为文档交付，不必预置到 APK；用户要改时按第 4 节 push。
3. M2 开始前确认：虚拟手柄编辑器的产品形态（文本面板 vs 拖拽编辑器）、陀螺仪默认开关（建议默认关）、profile 与 `Game` 字段的改动归属（Game.java 目前不在我写权限内）。
