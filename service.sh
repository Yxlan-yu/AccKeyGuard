#!/system/bin/sh
# AccKeyGuard service.sh - 开机启动无障碍守护
MODDIR=${0%/*}

# 等待系统就绪
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done

# 启动守护进程（后台）
nohup "$MODDIR/data/accd.sh" >/dev/null 2>&1 &

exit 0
