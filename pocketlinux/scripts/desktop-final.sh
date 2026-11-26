#!/system/bin/sh
NATIVE=/data/local/tmp/pl-native
export LD_LIBRARY_PATH=$NATIVE
export PROOT_LOADER=$NATIVE/loader
export PROOT_TMP_DIR=/data/local/tmp/prot

$NATIVE/proot --link2symlink -0 -r /data/local/tmp/pl-rootfs \
  -b /dev -b /proc -b /sys -b /sdcard -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TMPDIR=/tmp
export DISPLAY=:99
export HOME=/root
pkill -9 -x xfce4-session 2>/dev/null
pkill -9 -x xfwm4 2>/dev/null
pkill -9 -x xfdesktop 2>/dev/null
pkill -9 -x xfce4-panel 2>/dev/null
pkill -9 -x xfsettingsd 2>/dev/null
pkill -9 -x Thunar 2>/dev/null
sleep 2
rm -rf /root/.config/xfce4 /root/.cache/xfce4 /root/.config/xfconf /root/.cache/xfconf
echo "--- 启动 XFCE（首次初始化） ---"
dbus-launch --exit-with-session startxfce4 > /tmp/xfce7.log 2>&1 &
sleep 25
echo "--- 窗口尺寸 ---"
xwininfo -root -tree 2>&1 | grep -E "xfdesktop|xfce4-panel|xfwm4" | head -5
echo "--- 日志 ---"
grep -iE "error|fail|cannot" /tmp/xfce7.log 2>/dev/null | head -5
echo "--- 截图 ---"
import -window root -display :99 /sdcard/Download/desktop-final.png 2>&1
ls -l /sdcard/Download/desktop-final.png 2>&1
'
echo FINAL_DONE
