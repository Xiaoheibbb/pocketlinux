#!/system/bin/sh
ND=$(pm path com.pocketlinux.probe | head -1 | sed "s|package:||; s|/base.apk||")/lib/arm64
FD=/data/user/0/com.pocketlinux.probe/files
export LD_LIBRARY_PATH=$FD/native:$ND
export PROOT_LOADER=$ND/libproot_loader.so
export PROOT_TMP_DIR=$FD/tmp
exec $ND/libproot.so --link2symlink -0 -r $FD/rootfs -b /dev -b /proc -b /sys -w / /usr/bin/bash "$1"
