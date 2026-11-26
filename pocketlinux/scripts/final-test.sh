#!/system/bin/sh
# 干净重启：杀残留 -> 一个 proot 内启动 Xvfb + XFCE -> 截图 -> 清理退出
NATIVE=/data/local/tmp/pl-native
export LD_LIBRARY_PATH=$NATIVE
export PROOT_LOADER=$NATIVE/loader
export PROOT_TMP_DIR=/data/local/tmp/prot

echo "=== 清理残留 ==="
pkill -9 -f Xvfb 2>/dev/null
pkill -9 -f xfce4 2>/dev/null
pkill -9 -f xfwm4 2>/dev/null
pkill -9 -f xfdesktop 2>/dev/null
pkill -9 -f xfsettingsd 2>/dev/null
pkill -9 -f proot 2>/dev/null
sleep 2
rm -f /data/local/tmp/pl-rootfs/tmp/.X11-unix/X99 /data/local/tmp/pl-rootfs/tmp/.X99-lock 2>/dev/null
echo "清理完成: $(ps -A | grep -c Xvfb)"

echo "=== 启动会话 ==="
$NATIVE/proot --link2symlink -0 -r /data/local/tmp/pl-rootfs \
  -b /dev -b /proc -b /sys -b /sdcard -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DISPLAY=:99
export HOME=/root
Xvfb :99 -screen 0 1280x800x24 -ac > /tmp/xvfb.log 2>&1 &
sleep 5
echo "Xvfb 起来了: $(ps -A | grep -c Xvfb)"
dbus-launch --exit-with-session startxfce4 > /tmp/xfce2.log 2>&1 &
sleep 20
echo "--- xfce2.log ---"; tail -6 /tmp/xfce2.log 2>&1
echo "--- 进程 ---"; ps -A 2>/dev/null | grep -E "xfwm|xfdesk|xfce4-panel|xfce4-session|Xvfb" | head -6
echo "--- 截图 ---"
import -window root -display :99 /tmp/final.png 2>&1; echo "import exit=$?"
ls -l /tmp/final.png 2>&1
cp /tmp/final.png /sdcard/Download/final.png 2>&1
ls -l /sdcard/Download/final.png 2>&1
echo "--- xsetroot 对照 ---"
xsetroot -solid "#336699"
import -window root -display :99 /tmp/blue.png 2>&1
convert /tmp/blue.png -format "%[pixel:p{100,100}]" info: 2>&1
echo
'
echo "=== 收尾 ==="
echo FINAL_TEST_DONE
