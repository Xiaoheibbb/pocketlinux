#!/system/bin/sh
# ============================================================
# PocketLinux M0 设备验证脚本（P2/P3：X Server + XFCE）
# 前置：Termux 已安装并完成 bootstrap；无线调试已连接
# 用法：adb push 本脚本与 /root/artifacts 下的包到 /data/local/tmp/ 后执行
# ============================================================
set -e

T=/data/data/com.termux
P=$T/files/usr
export HOME=$T/files/home
export PREFIX=$P
export PATH=$P/bin:$PATH
export LD_LIBRARY_PATH=$P/lib
export TERM=xterm-256color

STAGE=/data/local/tmp/pl
ROOTFS=$P/var/lib/proot-distro/installed-rootfs/ubuntu

log() { echo "[pl] $*"; }

# 1) 安装 proot 及依赖（deb 已由宿主 push 到 $STAGE）
if [ -d "$STAGE/pkgs" ]; then
  log "安装 proot 依赖"
  $P/bin/dpkg -i "$STAGE"/pkgs/libandroid-shmem.deb "$STAGE"/pkgs/libtalloc.deb "$STAGE"/pkgs/proot.deb || true
fi

# 2) 准备 rootfs（ubuntu-base.tar.gz）
if [ ! -d "$ROOTFS/bin" ]; then
  log "解压 Ubuntu rootfs"
  mkdir -p "$ROOTFS"
  $P/bin/tar -xzf "$STAGE/ubuntu-base.tar.gz" -C "$ROOTFS"
  echo "nameserver 223.5.5.5" > "$ROOTFS/etc/resolv.conf"
  cat > "$ROOTFS/etc/apt/sources.list" <<'EOF'
deb https://mirrors.aliyun.com/ubuntu-ports noble main restricted universe multiverse
deb https://mirrors.aliyun.com/ubuntu-ports noble-updates main restricted universe multiverse
EOF
fi

# 3) proot 启动函数
run_in_rootfs() {
  $P/bin/proot --link2symlink -0 -r "$ROOTFS" \
    -b /dev -b /proc -b /sys \
    -b "$T/files/home:/root" \
    -b "$P/tmp:/tmp" \
    -b "$STAGE:/stage" \
    -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    TERM=xterm-256color LANG=C.UTF-8 DISPLAY=:0 \
    /bin/bash -c "$1"
}

log "proot 自检"
run_in_rootfs "echo ROOTFS_OK; uname -m; cat /etc/os-release | head -2"

# 4) 装 XFCE（首次较慢，约 400MB）
if [ ! -x "$ROOTFS/usr/bin/startxfce4" ]; then
  log "安装 xfce4（可能几分钟）"
  run_in_rootfs "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends xfce4 xfce4-terminal dbus-x11 xterm mesa-utils"
fi

# 5) 启动 X Server（Termux:X11）
log "启动 termux-x11"
$P/bin/termux-x11 :0 -ac >/dev/null 2>&1 &
sleep 2

# 6) 启动 XFCE 会话
log "启动 XFCE"
run_in_rootfs "dbus-launch --exit-with-session startxfce4" &
sleep 8

log "完成。若 Termux:X11 应用已打开，应能看到 XFCE 桌面。"
