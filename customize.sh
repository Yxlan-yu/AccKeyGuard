#!/system/bin/sh
# AccKeyGuard customize.sh - 安装时执行
# KernelSU 安装环境提供 $MODPATH 指向模块安装目标
MODPATH=${MODPATH:-/data/adb/modules/acckeyguard}

ui_print "- AccKeyGuard 正在安装..."

# 确保 data 目录存在并设置脚本执行位
mkdir -p "$MODPATH/data"
chmod 755 "$MODPATH/data/accd.sh" "$MODPATH/service.sh"

ui_print "- 安装完成"
exit 0