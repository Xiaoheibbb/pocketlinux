#!/system/bin/sh
# 在 /data/local/tmp 的 rootfs 副本里安装 XFCE（root 直接跑，不受 App 生命周期影响）
set -x
ROOTFS=/data/local/tmp/pl-rootfs
NATIVE=/data/local/tmp/pl-native
export LD_LIBRARY_PATH=$NATIVE
export PROOT_LOADER=$NATIVE/loader
export PROOT_TMP_DIR=/data/local/tmp/prot
mkdir -p $PROOT_TMP_DIR

echo "=== proot 自检 $(date) ==="
$NATIVE/proot --link2symlink -0 -r $ROOTFS -b /dev -b /proc -b /sys -w /root /bin/bash -c 'echo ROOT_OK; uname -m'

echo "=== 修复 dpkg ==="
$NATIVE/proot --link2symlink -0 -r $ROOTFS -b /dev -b /proc -b /sys -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
dpkg --configure -a 2>&1 | tail -3
apt-get -f install -y 2>&1 | tail -3
'

echo "=== apt update ==="
$NATIVE/proot --link2symlink -0 -r $ROOTFS -b /dev -b /proc -b /sys -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq 2>&1 | tail -3
'

echo "=== 安装 xfce4（大包，请耐心） ==="
$NATIVE/proot --link2symlink -0 -r $ROOTFS -b /dev -b /proc -b /sys -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
apt-get install -y --no-install-recommends xfce4 xvfb x11-apps imagemagick x11vnc websockify 2>&1 | tail -6
'

echo "=== 校验 ==="
$NATIVE/proot --link2symlink -0 -r $ROOTFS -b /dev -b /proc -b /sys -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
for c in startxfce4 Xvfb import x11vnc websockify; do printf "%-12s " $c; command -v $c || echo NONE; done
'
echo "=== ALL_DONE $(date) ==="
