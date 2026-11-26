# 修复计划（2026-11-27 00:59）

来源：第三方审查（8×P1 + P2）。下表按「影响功能 → 安全 → 体验」排序。

## 归属
- Lead（本会话）：MainActivity.java、assets/novnc/index.html、AndroidManifest.xml、TarExtractor.java
- ui-designer（team task-3）：GameLibraryActivity.java、SettingsActivity.java、Settings.java、新增 ui 包

## P1
| # | 问题 | 影响 | 修法 | 归属 |
|---|---|---|---|---|
| 1 | Wine 启动脚本单引号嵌套，路径含空格/引号时静默不启动 | 核心功能（跑 exe）基本不可用 | 路径改由环境变量传入（PL_EXE/PL_WD），内层用 "$PL_EXE"；外层 sh 文件里做转义 | Lead |
| 2 | websockify 绑 0.0.0.0:6080 + 无密码 | 同 Wi-Fi 可看/操作桌面 | 绑 127.0.0.1:6080 | Lead |
| 3 | 桌面不是真全屏（ActionBar + 状态栏占位 → 7% 黑边） | 观感/触控区域 | MainActivity 用 NoActionBar.Fullscreen 主题 + immersive 隐藏系统栏 | Lead |
| 4 | 桌面会被误杀（新实例发现端口占用就 kill，且 pkill libproot） | 游戏中重启、丢状态 | MainActivity 加 launchMode=singleTask；只有显式重启或「端口被陌生进程占用」才清理 | Lead |
| 5 | 双指手势前先发一次左键单击 | 滚动时误点 | 按下先延迟 80ms 再发左键；第二指在此前落下则取消 | Lead |
| 6 | RFB 帧循环不校验消息类型（ServerCutText/Bell 导致错位冻结） | 画面永久卡住 | 按消息类型分发：0=更新，1=ColourMap 跳过，2=Bell 忽略，3=ServerCutText 跳过 | Lead |
| 7 | rootfs 解压无完成标记，native 不校验版本 | 中断后永久坏、升级后用旧 proot | 解压到临时目录 + `.pl-ok` 标记后 rename；native 加 md5/长度校验 | Lead |
| 8 | 键盘退格/回车失效（\n 当字符、无 DEL） | 桌面内无法正常输入 | 原生 EditText onKeyListener 处理 DEL/ENTER，发 0xFF08/0xFF0D | Lead |

## P2（选重要的）
9. 设置页高度输入写错字段（s.width）→ ui-designer
10. 导入文件名用 lastPathSegment（得到 msid 而不是文件名）→ ui-designer
11. TarExtractor 跳过硬链接、不设权限 → Lead
12. ACTION_CANCEL 当抬手、MOVE 日志刷屏、8ms evaluateJavascript → Lead（降噪 + 节流 16ms）
13. DESKTOP_READY 跨块检不到 → Lead（累积缓冲）
14. 仓库卫生：app/build/.gradle 入库、.gitignore → 收尾统一处理

## 验证
- 编译：`gradle :app:assembleDebug`
- 真机：桌面启动 ≤15s；点 XFCE 菜单能开；双指滚动不误点；「测试 Wine」→ notepad 窗口出现；桌面内复制文本后画面不冻结。
