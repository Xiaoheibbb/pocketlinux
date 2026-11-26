#!/system/bin/sh
# 装 TigerVNC（Xvnc 自带 X server + VNC，RANDR 正常）
NATIVE=/data/local/tmp/pl-native
export LD_LIBRARY_PATH=$NATIVE
export PROOT_LOADER=$NATIVE/loader
export PROOT_TMP_DIR=/data/local/tmp/prot

$NATIVE/proot --link2symlink -0 -r /data/local/tmp/pl-rootfs \
  -b /dev -b /proc -b /sys -b /sdcard -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
echo "--- 修复 dpkg ---"
dpkg --configure -a 2>&1 | tail -2
apt-get -f install -y 2>&1 | tail -2
echo "--- 安装 tigervnc ---"
apt-get install -y --no-install-recommends tigervnc-standalone-server tigervnc-common 2>&1 | tail -4
echo "--- 校验 ---"
for c in Xvnc vncserver xrandr; do printf "%-12s " $c; command -v $c || echo NONE; done
'
echo SETUP_VNC_DONE
