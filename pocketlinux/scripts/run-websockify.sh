#!/system/bin/sh
# 前台跑 websockify（由 nohup 后台托管），把 VNC 5900 转成 WebSocket 6080
NATIVE=/data/local/tmp/pl-native
export LD_LIBRARY_PATH=$NATIVE
export PROOT_LOADER=$NATIVE/loader
export PROOT_TMP_DIR=/data/local/tmp/prot
exec $NATIVE/proot --link2symlink -0 -r /data/local/tmp/pl-rootfs \
  -b /dev -b /proc -b /sys -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TMPDIR=/tmp
echo "websockify 启动 $(date)"
exec websockify 0.0.0.0:6080 127.0.0.1:5900
'
