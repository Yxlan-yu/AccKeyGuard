#!/system/bin/sh
# AccKeyGuard customize.sh - 安装时执行
# KernelSU / Magisk 安装环境提供 $MODPATH 指向模块安装目录

MODPATH=${MODPATH:-/data/adb/modules/acckeyguard}

VER=$(grep '^version=' "$MODPATH/module.prop" 2>/dev/null | cut -d= -f2)

ui_print "- AccKeyGuard（无障碍保活）正在安装 ${VER}"
ui_print "- 守护列表与主题保存在 KernelSU module config，升级不会丢失"

mkdir -p "$MODPATH/data" "$MODPATH/webroot" 2>/dev/null
chmod 755 "$MODPATH/service.sh" "$MODPATH/customize.sh" 2>/dev/null
chmod 755 "$MODPATH/data/accd.sh" "$MODPATH/data/control.sh" 2>/dev/null

ui_print "- 安装完成，重启后守护自动运行"
exit 0
