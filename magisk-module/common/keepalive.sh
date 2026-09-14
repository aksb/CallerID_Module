#!/system/bin/sh
# 共享逻辑，被 service.sh（开机自动执行）和 action.sh（Magisk App 里手动点
# Action 按钮触发，不用重启手机）两边一起 source，避免同一套命令写两份。
#
# 只认包名，不关心 APK 是哪个版本、什么时候装的——这也是这个模块的设计初衷：
# 模块本身不带 APK，装没装、装的哪个版本跟这个模块完全无关，模块只负责
# "这个包名在，就把保活开关打开"这一件事。
#
# v4.3 起：每一条命令执行完都立刻用对应的只读命令验证一遍，直接给中文
# 结果（已启用 ✔ / 未启用 ✘），不用再另外跑一遍"验证命令"去肉眼比对
# 英文输出里有没有 allow 字样。

PKG="com.callerid.module"

run_keepalive() {
    LOG="$1"

    log() {
        echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >> "$LOG"
    }

    if ! pm path "$PKG" >/dev/null 2>&1; then
        log "未检测到 $PKG，跳过（没装这个 App，或者 App 还没装完），模块本身不做任何事"
        echo "未检测到「来电识别」App，跳过，模块本身不做任何事"
        return 0
    fi

    log "检测到 $PKG，开始执行保活命令"

    # $1 中文项目名  $2 设置命令  $3 验证命令  $4 验证命令输出里代表"已生效"
    # 要匹配到的关键字。设置命令和验证命令都用 eval 执行，方便把带参数、
    # 带管道的完整命令当字符串传进来。
    set_and_check() {
        DESC="$1"
        eval "$2" >> "$LOG" 2>&1
        if eval "$3" 2>/dev/null | grep -q "$4"; then
            RESULT="已启用 ✔"
        else
            RESULT="未启用 ✘"
        fi
        log "$DESC：$RESULT"
        echo "$DESC：$RESULT"
    }

    set_and_check "悬浮窗权限" \
        "cmd appops set $PKG SYSTEM_ALERT_WINDOW allow" \
        "cmd appops get $PKG SYSTEM_ALERT_WINDOW" "allow"

    # appops 里没有公开对外承诺的读取 API，App 自己（用公开 SDK）查不到这
    # 两项，但这里是用 root/shell 身份跑 cmd 工具本身去查，跟"App 自己能不
    # 能查"是两回事，可以正常拿到结果。
    set_and_check "后台运行权限" \
        "cmd appops set $PKG RUN_IN_BACKGROUND allow" \
        "cmd appops get $PKG RUN_IN_BACKGROUND" "allow"

    set_and_check "任意后台运行权限" \
        "cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow" \
        "cmd appops get $PKG RUN_ANY_IN_BACKGROUND" "allow"

    set_and_check "电池优化白名单" \
        "dumpsys deviceidle whitelist +$PKG" \
        "dumpsys deviceidle whitelist" "$PKG"

    # 把 App 从"待机分桶限制"里摘出来，是对上面 deviceidle 白名单的补充，
    # 不是每个 ROM 都严格遵守，但无害，可以放心跑。
    set_and_check "待机分桶限制已解除" \
        "am set-inactive $PKG false" \
        "am get-inactive $PKG" "Idle=false"

    log "保活命令执行完毕"

    # ── 下面是可选、默认不启用的更激进选项 ──────────────────────────────
    # 这些命令要么影响的是整台手机（不是只针对这一个 App），要么在不同 ROM/
    # 系统版本上设置项名称不一定存在，谨慎起见默认注释掉，不替你做这个决定；
    # 如果确认自己的机型需要、且知道自己在做什么，可以手动去掉行首的 "# " 再
    # 重新安装这个模块。
    #
    # 关闭"自适应电池"（影响整台手机所有 App 的省电策略，不是本 App 独有）：
    # settings put global adaptive_battery_management_enabled 0
    #
    # 小米/华为/OPPO/vivo 各家自己的"自启动管理"/"神隐模式"数据库结构没有
    # 官方文档、随系统版本变化，这里不提供直接写库的命令——命中率最高的做法
    # 还是去系统设置里手动给这个 App 加白名单，参考 App 内"Root保活教程"板块
    # 的说明。
}
