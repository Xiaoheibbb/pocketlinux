#!/system/bin/sh
NATIVE=/data/local/tmp/pl-native
export LD_LIBRARY_PATH=$NATIVE
export PROOT_LOADER=$NATIVE/loader
export PROOT_TMP_DIR=/data/local/tmp/prot
$NATIVE/proot --link2symlink -0 -r /data/local/tmp/pl-rootfs \
  -b /dev -b /proc -b /sys -b /sdcard -w /root /bin/bash -c '
export PATH=/usr/local/bin:/usr/bin:/bin
export DISPLAY=:99
echo "--- xsetroot 蓝色背景 ---"
xsetroot -solid "#336699" 2>&1; echo "xsetroot exit=$?"
echo "--- xwd 抓 root ---"
xwd -root -out /tmp/s.xwd 2>&1; echo "xwd exit=$?"
ls -l /tmp/s.xwd 2>&1
echo "--- 转 png ---"
convert /tmp/s.xwd /sdcard/Download/xwd-shot.png 2>&1; echo "convert exit=$?"
ls -l /sdcard/Download/xwd-shot.png 2>&1
echo "--- 列窗口 ---"
xwininfo -root -tree 2>&1 | head -12
'
echo SHOT_DONE
