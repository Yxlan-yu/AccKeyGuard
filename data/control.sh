#!/system/bin/sh
# AccKeyGuard control.sh - 守护进程控制
#
#   control.sh start     启动（已在运行则什么都不做）
#   control.sh stop      停止
#   control.sh restart   重启（WebUI「重启守护」/「启动」）
#   control.sh status    打印运行状态
#   control.sh once      立即同步一次（WebUI「立即同步」/ KSU 动作按钮）
#
# 供 WebUI 与 action.sh 调用；全部输出走 stdout，便于界面直接展示。

DIR=${0%/*}
case "$DIR" in
    "$0"|"") DIR=$(pwd) ;;
esac
ACC="$DIR/accd.sh"
PIDFILE="$DIR/accd.pid"
LOGFILE="$DIR/accd.log"

alive_pid() {
    [ -f "$PIDFILE" ] || return 1
    pid=$(cat "$PIDFILE" 2>/dev/null)
    case "$pid" in
        ''|*[!0-9]*) return 1 ;;
    esac
    kill -0 "$pid" 2>/dev/null || return 1
    grep -q "accd.sh" "/proc/$pid/cmdline" 2>/dev/null || return 1
    echo "$pid"
}

do_start() {
    pid=$(alive_pid)
    if [ -n "$pid" ]; then
        echo "守护已在运行 (PID $pid)"
        return 0
    fi

    [ -f "$ACC" ] || { echo "缺少 accd.sh，请重新安装模块"; return 1; }
    chmod 755 "$ACC" 2>/dev/null
    mkdir -p "$DIR" 2>/dev/null
    rm -f "$PIDFILE"

    if command -v setsid >/dev/null 2>&1; then
        setsid sh "$ACC" </dev/null >/dev/null 2>&1 &
    else
        nohup sh "$ACC" </dev/null >/dev/null 2>&1 &
    fi

    # 给脚本一点时间写 PID 文件
    i=0
    while [ "$i" -lt 20 ]; do
        sleep 0.1 2>/dev/null || sleep 1
        pid=$(alive_pid)
        [ -n "$pid" ] && break
        i=$((i + 1))
    done

    pid=$(alive_pid)
    if [ -n "$pid" ]; then
        echo "守护已启动 (PID $pid)"
        return 0
    fi
    echo "守护启动失败，请查看日志"
    return 1
}

do_stop() {
    pid=$(alive_pid)
    if [ -z "$pid" ]; then
        rm -f "$PIDFILE"
        echo "守护未在运行"
        return 0
    fi

    kill "$pid" 2>/dev/null
    i=0
    while [ "$i" -lt 20 ]; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.1 2>/dev/null || sleep 1
        i=$((i + 1))
    done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null

    rm -f "$PIDFILE"
    echo "守护已停止"
    return 0
}

case "$1" in
    start)
        do_start
        ;;
    stop)
        do_stop
        ;;
    restart)
        do_stop >/dev/null 2>&1
        do_start
        ;;
    status)
        pid=$(alive_pid)
        if [ -n "$pid" ]; then
            echo "运行中 PID=$pid"
        else
            echo "未运行"
        fi
        ;;
    once)
        if [ ! -f "$ACC" ]; then
            echo "缺少 accd.sh，请重新安装模块"
            exit 1
        fi
        sh "$ACC" --once
        ;;
    *)
        echo "用法: control.sh {start|stop|restart|status|once}"
        exit 1
        ;;
esac
