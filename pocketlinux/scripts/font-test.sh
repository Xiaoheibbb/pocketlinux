#!/system/bin/sh
# 装字体后重起 XFCE，验证窗口尺寸
NATIVE=/data/local/tmp/pl-native
export LD_LIBRARY_PATH=$NATIVE
export PROOT_LOADER=$NATIVE/loader
export PROOT_TMP_DIR=/data/local/tmp/prot

$NATIVE/proot --link2symlink -0 -r /data/local/tmp/pl-rootfs \
  -b /dev -b /proc -b /sys -b /sdcard -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
export DISPLAY=:99
export HOME=/root
echo "--- 安装字体 ---"
apt-get install -y --no-install-recommends fonts-dejavu-core xfonts-base fonts-liberation 2>&1 | tail -3
echo "--- 字体列表 ---"; fc-list 2>/dev/null | head -3; xlsfonts 2>/dev/null | head -3
echo "--- 重启 XFCE ---"
pkill -9 -f xfce4-session 2>/dev/null; pkill -9 -f xfwm4 2>/dev/null; pkill -9 -f xfdesktop 2>/dev/null; pkill -9 -f xfce4-panel 2>/dev/null
sleep 2
dbus-launch --exit-with-session startxfce4 > /tmp/xfce4.log 2>&1 &
sleep 15
echo "--- 窗口尺寸 ---"
xwininfo -root -tree 2>&1 | grep -E "xfdesktop|xfce4-panel|Thunar|xfwm4" | head -6
echo "--- 截图 ---"
import -window root -display :99 /sdcard/Download/font-test.png 2>&1
ls -l /sdcard/Download/font-test.png 2>&1
'
echo FONT_TEST_DONE
