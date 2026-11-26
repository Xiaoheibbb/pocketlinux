#!/system/bin/sh
# 补装 dbus-x11 并重新无头启动 XFCE + 截图
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
export DEBIAN_FRONTEND=noninteractive
if ! command -v dbus-launch >/dev/null 2>&1; then
  echo "--- 安装 dbus-x11 ---"
  apt-get install -y --no-install-recommends dbus-x11 2>&1 | tail -3
fi
echo "dbus-launch: $(command -v dbus-launch)"
export DISPLAY=:99
Xvfb :99 -screen 0 1280x800x24 > /tmp/xvfb.log 2>&1 &
sleep 4
echo "--- 启动 xfce4 ---"
dbus-launch --exit-with-session startxfce4 > /tmp/xfce.log 2>&1 &
sleep 15
echo "--- xfce.log ---"; tail -10 /tmp/xfce.log 2>&1
echo "--- 进程 ---"; ps aux 2>/dev/null | grep -E "xfce|Xvfb|xfwm" | grep -v grep | head -8
echo "--- 窗口 ---"; DISPLAY=:99 xwininfo -root -children 2>&1 | head -8
echo "--- 截图 ---"
import -window root /sdcard/Download/xfce-desktop.png 2>&1
ls -l /sdcard/Download/xfce-desktop.png 2>&1
'
echo "TEST_DONE"
