#!/system/bin/sh
# 用 Xvnc 起桌面，验证窗口尺寸与画面
NATIVE=/data/local/tmp/pl-native
export LD_LIBRARY_PATH=$NATIVE
export PROOT_LOADER=$NATIVE/loader
export PROOT_TMP_DIR=/data/local/tmp/prot

pkill -9 -f Xvfb 2>/dev/null; pkill -9 -f Xvnc 2>/dev/null
pkill -9 -f xfce4 2>/dev/null; pkill -9 -f xfwm4 2>/dev/null; pkill -9 -f xfdesktop 2>/dev/null
sleep 2

$NATIVE/proot --link2symlink -0 -r /data/local/tmp/pl-rootfs \
  -b /dev -b /proc -b /sys -b /sdcard -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DISPLAY=:99
export HOME=/root
echo "--- 启动 Xvnc ---"
Xvnc :99 -geometry 1280x800 -depth 24 -SecurityTypes None -localhost -rfbport 5900 > /tmp/xvnc.log 2>&1 &
sleep 5
echo "--- xvnc.log ---"; tail -5 /tmp/xvnc.log 2>&1
echo "--- xrandr ---"; xrandr 2>&1 | head -6
echo "--- 启动 XFCE ---"
dbus-launch --exit-with-session startxfce4 > /tmp/xfce3.log 2>&1 &
sleep 18
echo "--- 窗口树 ---"; xwininfo -root -tree 2>&1 | head -14
echo "--- 截图 ---"
import -window root -display :99 /sdcard/Download/vnc-desktop.png 2>&1; echo "import=$?"
ls -l /sdcard/Download/vnc-desktop.png 2>&1
'
echo XVNC_TEST_DONE
