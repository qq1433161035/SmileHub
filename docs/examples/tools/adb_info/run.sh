#!/system/bin/sh
# adb_info：在设备本机采集信息，等价于常用 adb shell 查询。
# 参数：WB_ARG_SECTION（all/basic/display/battery/memory/storage/network）
#       WB_ARG_FILTER（可选关键字，只保留匹配行）

section="${WB_ARG_SECTION:-all}"
filter="${WB_ARG_FILTER:-}"

prop() {
  getprop "$1" 2>/dev/null
}

banner() {
  echo "===== $1 ====="
}

info_basic() {
  banner "设备"
  echo "brand: $(prop ro.product.brand)"
  echo "manufacturer: $(prop ro.product.manufacturer)"
  echo "model: $(prop ro.product.model)"
  echo "device: $(prop ro.product.device)"
  echo "product: $(prop ro.product.name)"
  echo "serial: $(prop ro.serialno)"

  banner "系统"
  echo "android: $(prop ro.build.version.release) (SDK $(prop ro.build.version.sdk))"
  echo "build: $(prop ro.build.display.id)"
  echo "security_patch: $(prop ro.build.version.security_patch)"
  echo "abi: $(prop ro.product.cpu.abi)"
  echo "abilist: $(prop ro.product.cpu.abilist)"
  echo "kernel: $(uname -r)"
  echo "selinux: $(getenforce 2>/dev/null)"
  echo "uptime: $(uptime 2>/dev/null | tr -s ' ')"

  banner "Root"
  if command -v su >/dev/null 2>&1; then
    echo "su: $(command -v su)"
    echo "root: 存在 su（未探测，避免弹出授权框）"
  else
    echo "root: 未发现 su"
  fi
}

info_display() {
  banner "屏幕"
  wm size 2>/dev/null
  wm density 2>/dev/null
}

info_battery() {
  banner "电池"
  dumpsys battery 2>/dev/null | sed -n '1,20p'
}

info_memory() {
  banner "内存"
  if [ -r /proc/meminfo ]; then
    sed -n '1,5p' /proc/meminfo
  else
    echo "无法读取 /proc/meminfo"
  fi
}

info_storage() {
  banner "存储"
  df -h /data /storage/emulated/0 2>/dev/null
}

info_network() {
  banner "网络"
  if command -v ip >/dev/null 2>&1; then
    ip addr show 2>/dev/null | sed -n '1,40p'
  else
    echo "未找到 ip 命令"
  fi
}

run_section() {
  case "$1" in
    basic) info_basic ;;
    display) info_display ;;
    battery) info_battery ;;
    memory) info_memory ;;
    storage) info_storage ;;
    network) info_network ;;
    all)
      info_basic
      info_display
      info_battery
      info_memory
      info_storage
      info_network
      ;;
    *)
      echo "unknown section: $1" >&2
      echo "可用: all basic display battery memory storage network" >&2
      return 2
      ;;
  esac
}

if [ -n "$filter" ]; then
  run_section "$section" 2>&1 | grep -i -e "$filter"
  # grep 没匹配时返回 1，不应让整次调用算失败
  true
else
  run_section "$section"
fi
