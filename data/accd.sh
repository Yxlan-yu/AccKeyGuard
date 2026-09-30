#!/system/bin/sh
# AccKeyGuard accd.sh - 无障碍服务守护进程
# 功能：从用户配置读取要保活的无障碍服务，定期检查系统已启用列表，
#       仅在缺失时补写，避免频繁覆盖导致的 UI 抽搐。

MOD_ID="acckeyguard"
MODDIR="/data/adb/modules/$MOD_ID"
CONFIG_DIR="/data/adb/ksu/module_configs/$MOD_ID"
LOGFILE="$MODDIR/data/accd.log"
PIDFILE="$MODDIR/data/accd.pid"
CHECK_INTERVAL=20

# 获取当前已启用的无障碍服务（component 冒号分隔），set 命令输出为空则持久
get_enabled() {
    settings get secure enabled_accessibility_services 2>/dev/null
}

# 从配置读取要保活的服务列表，二次剔除空项、只保留唯一值
get_wanted() {
    KSU_MODULE=$MOD_ID /data/adb/ksu/bin/ksud module config get enabled_services 2>/dev/null \
        | tr ':' '\n' | sed 's/[[:space:]]//g' | grep -v '^$' | sort -u | tr '\n' ':'
}

log() {
    echo "$(date '+%m-%d %H:%M:%S') $1" >> "$LOGFILE"
}

# 防止多实例
if [ -f "$PIDFILE" ]; then
    old=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
        log "已有守护进程 PID=$old，退出"
        exit 0
    fi
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"; exit 0' INT TERM

log "守护启动 PID=$$"

while true; do
    wanted=$(get_wanted)
    enabled=$(get_enabled)

    if [ -z "$wanted" ]; then
        # 配置为空：首次运行，把当前已启用的服务接管为保活列表
        if [ -n "$enabled" ] && [ "$enabled" != "null" ]; then
            clean=$(echo "$enabled" | tr ':' '\n' | sed 's/[[:space:]]//g' | grep -v '^$' | sort -u | paste -sd: -)
            KSU_MODULE=$MOD_ID /data/adb/ksu/bin/ksud module config set enabled_services "$clean" 2>/dev/null
            log "配置为空，接管现有服务: $clean"
            wanted="$clean:"
        else
            sleep "$CHECK_INTERVAL"
            continue
        fi
    fi

    # 逐个检查想要的服务是否已在已启用列表中，收集缺失项
    missing=""
    echo "$wanted" | tr ':' '\n' | sed 's/[[:space:]]//g' | grep -v '^$' | sort -u | while read -r svc; do
        [ -z "$svc" ] && continue
        if echo "$enabled" | grep -qF "$svc"; then
            :
        else
            echo "$svc"
        fi
    done > "$MODDIR/data/.missing"

    missing=$(cat "$MODDIR/data/.missing" 2>/dev/null)
    rm -f "$MODDIR/data/.missing"

    if [ -n "$missing" ]; then
        # 有缺失，规范化合并后写回（不去重覆盖，保留系统其他服务）
        merged="$enabled"
        for svc in $missing; do
            case ":$merged:" in
                *":$svc:"*) ;;
                *) merged="${merged:+$merged:}$svc" ;;
            esac
        done
        merged=$(echo "$merged" | tr ':' '\n' | sed 's/[[:space:]]//g' | grep -v '^$' | sort -u | paste -sd: -)
        settings put secure enabled_accessibility_services "$merged"
        log "补写缺失服务: $missing"
    fi

    # 确保无障碍总开关打开
    en=$(settings get secure accessibility_enabled 2>/dev/null)
    if [ "$en" != "1" ]; then
        settings put secure accessibility_enabled 1
        log "开启 accessibility_enabled"
    fi

    sleep "$CHECK_INTERVAL"
done
