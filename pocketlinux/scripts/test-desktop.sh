#!/system/bin/sh
# 无头验证：Xvfb + XFCE + 截图（不依赖 App）
ROOTFS=/data/local/tmp/pl-rootfs
NATIVE=/data/local/tmp/pl-native
export LD_LIBRARY_PATH=$NATIVE
export PROOT_LOADER=$NATIVE/loader
export PROOT_TMP_DIR=/data/local/tmp/prot
mkdir -p $PROOT_TMP_DIR

$NATIVE/proot --link2symlink -0 -r $ROOTFS \
  -b /dev -b /proc -b /sys -b /sdcard \
  -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DISPLAY=:99
echo "--- 启动 Xvfb ---"
Xvfb :99 -screen 0 1280x800x24 > /tmp/xvfb.log 2>&1 &
sleep 4
echo "--- xvfb.log ---"; tail -4 /tmp/xvfb.log 2>&1
echo "--- 启动 XFCE ---"
dbus-launch --exit-with-session startxfce4 > /tmp/xfce.log 2>&1 &
sleep 12
echo "--- xfce.log ---"; tail -6 /tmp/xfce.log 2>&1
echo "--- 进程 ---"; ps aux 2>/dev/null | grep -E "xfce|Xvfb" | grep -v grep | head -8
echo "--- 截图 ---"
import -window root /sdcard/Download/xfce-desktop.png 2>&1
ls -l /sdcard/Download/xfce-desktop.png 2>&1
echo "--- 窗口列表 ---"
DISPLAY=:99 xwininfo -root -children 2>&1 | head -8
'
echo "TEST_DONE"
