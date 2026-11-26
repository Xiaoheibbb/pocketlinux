#!/bin/bash
# PocketLinux Vulkan wrapper probe - reproducible collection script
# Run from a host with adb connected (192.168.31.56:45337). Saves raw evidence as stdout.
set -u
ADB=${ADB:-adb}
DEV=$($ADB devices | awk 'NR==2{print $1}')
echo "[env] adb device: $DEV"
$ADB connect 192.168.31.56:45337 >/dev/null 2>&1 || true
DEV=$($ADB devices | awk 'NR==2{print $1}')

mkdir -p /tmp/gh
W=/tmp/gh/libvulkan_wrapper_bionic.so
if [ ! -s "$W" ]; then
  echo "[env] downloading leegao v0.0.4 libvulkan_wrapper.so (bionic)"
  wget -q --timeout=90 --tries=1 -O "$W" \
    "https://github.com/leegao/bionic-vulkan-wrapper/releases/download/v0.0.4/libvulkan_wrapper.so" || echo "!! download failed"
fi

echo "================ A. 目标机（Android, bionic 侧）================"
echo '--- props'
$ADB shell 'su -c "getprop ro.hardware.vulkan; getprop ro.soc.model; getprop ro.board.platform; getenforce"'
echo '--- GPU 内核设备节点（root 视角）'
$ADB shell 'su -c "ls -laZ /dev/mali0 /dev/dri/card0 2>&1"'
echo '--- kbase 驱动标识'
$ADB shell 'su -c "cat /sys/class/misc/mali0/device/uevent 2>/dev/null | head -8"'
echo '--- 系统 bionic Vulkan 枚举结果（由 Android GPU 服务进程执行，bionic）'
$ADB shell 'su -c "cmd gpu vkjson"' 2>/dev/null > /tmp/vkjson_$$.json
grep -E '"(deviceName|driverID|driverVersion|apiVersion|vendorID)"' /tmp/vkjson_$$.json | head -8
echo '--- vendor Vulkan UMD（bionic）'
$ADB shell 'su -c "ls -la /vendor/lib64/hw/vulkan.mali.so; readlink -f /vendor/lib64/hw/vulkan.mali.so"' 2>/dev/null || true
$ADB shell 'su -c "cat /vendor/lib64/hw/mt6991/vulkan.mali.so"' > /tmp/vulkan_mali_$$.so 2>/dev/null
readelf -d /tmp/vulkan_mali_$$.so 2>/dev/null | grep -E 'NEEDED|RUNPATH|SONAME'
echo '--- 应用进程域（runas_app）能否打开 /dev/mali0'
$ADB shell 'su -c "run-as com.pocketlinux.probe id; run-as com.pocketlinux.probe sh -c \"ls -la /dev/mali0; (exec 3<>/dev/mali0 && echo APP_OPEN_OK) || echo APP_OPEN_FAIL\""'

echo
echo "================ B. wrapper 产物静态分析 ================"
echo '--- v0.0.4 libvulkan_wrapper.so (Termux/bionic 构建) ELF NEEDED'
readelf -d "$W" | grep -E 'NEEDED|RPATH|RUNPATH|SONAME'
echo '--- 关键内嵌字符串（运行期依赖）'
strings -n 8 "$W" | grep -E 'ADRENOTOOLS|/system/lib64/libvulkan|com\.termux|android_stub|libgraphicsenv' | head -12
echo '--- 在 glibc 容器里 ldd 该 bionic .so（预期直接失败）'
ldd "$W" 2>&1 | head -4
echo '--- /system/lib64/libvulkan.so 也是 bionic 链接，glibc 里 ldd 失败'
$ADB shell 'su -c "cat /system/lib64/libvulkan.so"' > /tmp/libvulkan_sys_$$.so 2>/dev/null
readelf -d /tmp/libvulkan_sys_$$.so 2>/dev/null | grep -E 'NEEDED' | head -10
ldd /tmp/libvulkan_sys_$$.so 2>&1 | head -3
echo '--- glibc 的 libc.so / libm.so 是 ld script（与 bionic soname 冲突）'
head -2 /lib/aarch64-linux-gnu/libc.so; head -2 /lib/aarch64-linux-gnu/libm.so

echo
echo "================ C. glibc proot 容器内实测 ================"
cat > /tmp/inner_vkprobe.sh <<'EOS'
#!/system/bin/sh
FD=/data/data/com.pocketlinux.probe/files
mkdir -p $FD/rootfs/root/vkwrap
cp /data/local/tmp/libvulkan_wrapper_bionic.so $FD/rootfs/root/vkwrap/libvulkan_wrapper.so
chmod 644 $FD/rootfs/root/vkwrap/libvulkan_wrapper.so
cat > $FD/rootfs/root/vkwrap/wrapper_icd.aarch64.json <<'EOF'
{
    "ICD": {
        "api_version": "1.3.289",
        "library_path": "/root/vkwrap/libvulkan_wrapper.so"
    },
    "file_format_version": "1.0.0"
}
EOF
ND=$(pm path com.pocketlinux.probe | head -1 | sed "s|package:||; s|/base.apk||")/lib/arm64
LD=/data/local/tmp/vklibs
mkdir -p $LD
ln -sf $ND/libtalloc.so $LD/libtalloc.so.2
ln -sf $ND/libandroid-shmem.so $LD/libandroid-shmem.so
export LD_LIBRARY_PATH=$LD:$ND
export PROOT_LOADER=$ND/libproot_loader.so
export PROOT_TMP_DIR=$FD/tmp
exec $ND/libproot.so --link2symlink -0 -r $FD/rootfs -b /dev -b /proc -b /sys -w / /usr/bin/bash -c '
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
echo "--- container distro: $(. /etc/os-release 2>/dev/null; echo $PRETTY_NAME)"
echo "--- GPU 节点在容器内可见?"; ls -la /dev/mali0 /dev/dri/ 2>&1
echo "--- 容器内能否 open /dev/mali0:"; ( exec 9<>/dev/mali0 && echo OPEN_OK || echo OPEN_FAIL ) 2>&1
echo "--- 容器已安装 mesa/vulkan-tools:"; dpkg -l 2>/dev/null | grep -E "mesa-vulkan-drivers|vulkan-tools" | awk "{print \$2, \$3}"
echo "--- 基线 vulkaninfo（llvmpipe）:"
timeout 60 vulkaninfo --summary 2>/dev/null | grep -E "deviceName|driverName|deviceType|apiVersion" | head -6
echo "--- 把 bionic wrapper 作为 ICD 交给 glibc 加载:"
export VK_ICD_FILENAMES=/root/vkwrap/wrapper_icd.aarch64.json
timeout 60 vulkaninfo --summary 2>&1 | grep -E "ERROR|Failed|no drivers|deviceName" | head -8
'
EOS
chmod 755 /tmp/inner_vkprobe.sh
$ADB push "$W" /data/local/tmp/libvulkan_wrapper_bionic.so >/dev/null 2>&1
$ADB push /tmp/inner_vkprobe.sh /data/local/tmp/ >/dev/null 2>&1
$ADB shell su -c "sh /data/local/tmp/inner_vkprobe.sh" 2>&1

echo
echo "================ D. 结论 ================"
echo "见 docs/notes/vulkan-wrapper-probe.md"
rm -f /tmp/vkjson_$$.json /tmp/vulkan_mali_$$.so /tmp/libvulkan_sys_$$.so
