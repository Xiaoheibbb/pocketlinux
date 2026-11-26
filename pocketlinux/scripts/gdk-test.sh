#!/system/bin/sh
NATIVE=/data/local/tmp/pl-native
export LD_LIBRARY_PATH=$NATIVE
export PROOT_LOADER=$NATIVE/loader
export PROOT_TMP_DIR=/data/local/tmp/prot

$NATIVE/proot --link2symlink -0 -r /data/local/tmp/pl-rootfs \
  -b /dev -b /proc -b /sys -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DISPLAY=:99
export DEBIAN_FRONTEND=noninteractive
echo "--- X 扩展 ---"
xdpyinfo 2>/dev/null | sed -n "/number of extensions/,/^default screen/p" | head -20
echo "--- 安装 python3-gi ---"
apt-get install -y --no-install-recommends python3-gi 2>&1 | tail -1
echo "--- GDK 认为的屏幕 ---"
python3 - <<PYEOF
import gi
gi.require_version("Gtk", "3.0")
from gi.repository import Gtk
w = Gtk.Window()
s = w.get_screen()
print("GDK screen:", s.get_width(), "x", s.get_height())
print("monitors:", s.get_n_monitors())
try:
    m = s.get_monitor_geometry(0)
    print("monitor0:", m.width, "x", m.height)
except Exception as e:
    print("monitor err:", e)
PYEOF
'
echo GDK_DONE
