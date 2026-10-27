#!/system/bin/sh
# AccKeyGuard action.sh - KernelSU 模块「动作」按钮：立即同步一次
# 输出会直接显示在管理器的对话框里，所以只保留几行关键信息。

MODDIR=${0%/*}
case "$MODDIR" in
    "$0"|"") MODDIR=$(pwd) ;;
esac

echo "无障碍保活 · 立即同步"
echo "--------------------"
sh "$MODDIR/data/control.sh" once 2>&1
echo "--------------------"

if [ -f "$MODDIR/data/accd.log" ]; then
    tail -n 3 "$MODDIR/data/accd.log" 2>/dev/null
else
    echo "（还没有日志）"
fi
