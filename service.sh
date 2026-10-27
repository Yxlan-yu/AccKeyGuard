#!/system/bin/sh
# AccKeyGuard service.sh - 开机启动无障碍守护

MODDIR=${0%/*}
case "$MODDIR" in
    "$0"|"") MODDIR=/data/adb/modules/acckeyguard ;;
esac

# 清理上次运行残留的 PID 文件，避免重启后旧 PID 被复用造成误判
rm -f "$MODDIR/data/accd.pid"

# 等系统就绪（最多 10 分钟，避免异常情况下卡住）
i=0
while [ "$i" -lt 300 ]; do
    [ "$(getprop sys.boot_completed)" = "1" ] && break
    sleep 2
    i=$((i + 1))
done

chmod 755 "$MODDIR/data/accd.sh" "$MODDIR/data/control.sh" 2>/dev/null

# 交给 control.sh 统一启动（含 setsid / nohup 兜底与 PID 校验）
sh "$MODDIR/data/control.sh" start >/dev/null 2>&1

exit 0
