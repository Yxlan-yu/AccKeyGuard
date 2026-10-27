#!/system/bin/sh
# AccKeyGuard accd.sh - 无障碍服务守护进程
#
# 只做一件事：把「守护列表」里的服务保持在系统已启用列表中。
# - 不主动停用任何服务（取消守护只表示不再自动恢复）
# - 只在缺失时写回，避免每轮覆盖造成无障碍反复重建
# - settings 返回 null（从未设置）按空列表处理，绝不把 null 当组件写回
# - 组件名统一归一化成 pkg/pkg.Class 后按整项精确比较，避免子串误命中
# - 每轮重读 check_interval，WebUI 改完即时生效，不用重启守护
# - 日志超过 256KB 自动截断到尾部 128KB
#
# 用法：
#   accd.sh           常驻循环
#   accd.sh --once    只补回一次后退出（WebUI「立即恢复」）

MOD_ID="acckeyguard"
MODDIR=${AKG_MODDIR:-/data/adb/modules/$MOD_ID}
KS=${AKG_KS:-/data/adb/ksu/bin/ksud}
LOGFILE="$MODDIR/data/accd.log"
PIDFILE="$MODDIR/data/accd.pid"

DEF_INTERVAL=20
MIN_INTERVAL=5
MAX_INTERVAL=300
LOG_MAX=262144
LOG_KEEP=131072

mkdir -p "$MODDIR/data" 2>/dev/null

ONCE=0
[ "$1" = "--once" ] && ONCE=1

log() {
    if [ -f "$LOGFILE" ]; then
        size=$(wc -c < "$LOGFILE" 2>/dev/null | tr -d ' ')
        case "$size" in
            ''|*[!0-9]*) size=0 ;;
        esac
        if [ "$size" -ge "$LOG_MAX" ]; then
            tail -c "$LOG_KEEP" "$LOGFILE" > "$LOGFILE.tmp" 2>/dev/null &&
                mv -f "$LOGFILE.tmp" "$LOGFILE"
        fi
    fi
    echo "$(date '+%m-%d %H:%M:%S') $1" >> "$LOGFILE"
}

cfg_get() {
    KSU_MODULE=$MOD_ID "$KS" module config get "$1" 2>/dev/null
}

# stdin: 冒号分隔的组件串（可为 null / 空）
# stdout: 每行一个归一化组件 pkg/pkg.Class，去重由调用方排序完成
normalize_stream() {
    tr ':' '\n' | sed 's/[[:space:]]//g' | while IFS= read -r line || [ -n "$line" ]; do
        [ -z "$line" ] && continue
        [ "$line" = "null" ] && continue
        case "$line" in
            */*)
                pkg=${line%%/*}
                cls=${line#*/}
                case "$cls" in
                    .*) echo "$pkg/$pkg$cls" ;;
                    *)  echo "$pkg/$cls" ;;
                esac
                ;;
            *) ;; # 没有 / 的裸包名不是合法组件，丢弃避免写坏设置
        esac
    done
}

# 已启用列表，冒号结尾便于整项精确匹配；读取失败返回非 0
read_enabled() {
    raw=$(settings get secure enabled_accessibility_services 2>/dev/null) || return 1
    [ -z "$raw" ] && raw=null
    printf '%s' "$raw" | normalize_stream | sort -u | tr '\n' ':'
}

read_wanted() {
    cfg_get enabled_services | normalize_stream | sort -u | tr '\n' ':'
}

read_interval() {
    v=$(cfg_get check_interval | tr -cd '0-9')
    case "$v" in
        ''|*[!0-9]*) v=$DEF_INTERVAL ;;
    esac
    [ "$v" -lt "$MIN_INTERVAL" ] && v=$MIN_INTERVAL
    [ "$v" -gt "$MAX_INTERVAL" ] && v=$MAX_INTERVAL
    echo "$v"
}

# 一次同步：$1=already_enabled（冒号结尾），$2=wanted（冒号结尾）
# stdout: 变更摘要（空 = 无需变更）
sync_once() {
    enabled="$1"
    wanted="$2"

    if [ -z "$wanted" ]; then
        echo ""
        return 0
    fi

    missing=""
    old_ifs=$IFS
    IFS=':'
    for svc in $wanted; do
        [ -z "$svc" ] && continue
        case ":$enabled" in
            *":$svc:"*) ;;
            *) missing="${missing}${svc} " ;;
        esac
    done
    IFS=$old_ifs

    [ -z "$missing" ] && { echo ""; return 0; }

    merged="$enabled"
    old_ifs=$IFS
    IFS=' '
    for svc in $missing; do
        [ -z "$svc" ] && continue
        merged="${merged}${svc}:"
    done
    IFS=$old_ifs

    final=$(printf '%s' "$merged" | tr ':' '\n' | sed 's/[[:space:]]//g' | grep -v '^$' | sort -u | tr '\n' ':')
    final=${final%:}

    [ -z "$final" ] && { echo ""; return 0; }

    if ! settings put secure enabled_accessibility_services "$final" 2>/dev/null; then
        echo "写入失败"
        return 1
    fi

    en=$(settings get secure accessibility_enabled 2>/dev/null)
    if [ "$en" != "1" ]; then
        settings put secure accessibility_enabled 1 2>/dev/null
    fi

    echo "${missing% }"
    return 0
}

# ---------------------------------------------------------------- 单次模式

if [ "$ONCE" = "1" ]; then
    wanted=$(read_wanted)
    if [ -z "$wanted" ]; then
        echo "守护列表为空，无需同步"
        exit 0
    fi
    if ! enabled=$(read_enabled); then
        echo "读取无障碍设置失败"
        exit 1
    fi
    changed=$(sync_once "$enabled" "$wanted")
    if [ -n "$changed" ]; then
        log "手动同步，补回 $(echo "$changed" | wc -w | tr -d ' ') 个服务"
        echo "已补回：$(echo "$changed" | tr ' ' '\n' | grep -v '^$' | tr '\n' ' ')"
    else
        echo "全部正常，无需变更"
    fi
    exit 0
fi

# ---------------------------------------------------------------- 常驻模式

# 防止多实例：kill -0 只能证明 PID 存活，重启后 PID 会被无关进程复用，
# 必须校验 /proc/<pid>/cmdline 确实是本脚本，否则误判「守护仍在」导致永久缺席。
if [ -f "$PIDFILE" ]; then
    old=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null \
        && grep -q "accd.sh" "/proc/$old/cmdline" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"; exit 0' INT TERM HUP

log "守护启动 PID=$$"

while true; do
    interval=$(read_interval)

    wanted=$(read_wanted)

    if [ -z "$wanted" ]; then
        # 配置为空：首次运行，把当前已启用的服务接管为守护列表
        if enabled=$(read_enabled) && [ -n "$enabled" ]; then
            clean=${enabled%:}
            KSU_MODULE=$MOD_ID "$KS" module config set enabled_services "$clean" 2>/dev/null
            log "配置为空，已接管现有服务: $clean"
            wanted="$enabled"
        else
            sleep "$interval"
            continue
        fi
    fi

    if ! enabled=$(read_enabled); then
        log "读取无障碍设置失败，跳过本轮"
        sleep "$interval"
        continue
    fi

    changed=$(sync_once "$enabled" "$wanted")
    if [ -n "$changed" ]; then
        log "补写缺失服务: $(echo "$changed" | tr ' ' '\n' | grep -v '^$' | tr '\n' ' ')"
    fi

    sleep "$interval"
done
