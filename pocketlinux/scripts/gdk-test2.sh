#!/system/bin/sh
NATIVE=/data/local/tmp/pl-native
export LD_LIBRARY_PATH=$NATIVE
export PROOT_LOADER=$NATIVE/loader
export PROOT_TMP_DIR=/data/local/tmp/prot

$NATIVE/proot --link2symlink -0 -r /data/local/tmp/pl-rootfs \
  -b /dev -b /proc -b /sys -w /root /bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TMPDIR=/tmp
export DISPLAY=:99
export DEBIAN_FRONTEND=noninteractive
echo "--- 修复 dpkg (TMPDIR=/tmp) ---"
dpkg --configure -a 2>&1 | tail -4
apt-get -f install -y 2>&1 | tail -2
echo "--- 装 GTK 测试依赖 ---"
apt-get install -y --no-install-recommends python3-gi gir1.2-gtk-3.0 2>&1 | tail -2
echo "--- GDK 屏幕 ---"
python3 - <<PYEOF
try:
    import gi
    gi.require_version("Gtk", "3.0")
    from gi.repository import Gtk
    w = Gtk.Window()
    s = w.get_screen()
    print("GDK screen:", s.get_width(), "x", s.get_height())
    print("monitors:", s.get_n_monitors())
    m = s.get_monitor_geometry(0)
    print("monitor0:", m.width, "x", m.height)
except Exception as e:
    print("GDK err:", type(e).__name__, e)
PYEOF
'
echo GDK2_DONE
