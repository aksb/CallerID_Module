#!/system/bin/sh
# 有这个文件，Magisk App 里这个模块条目会多一个"Action"按钮，点一下就用 root
# 立即重新执行一次保活命令，不需要重启手机——跟 App 里"一键保活"按钮做的事完全
# 一样，只是触发入口换成了 Magisk App。

MODDIR=${0%/*}
. "$MODDIR/common/keepalive.sh"

LOG="$MODDIR/keepalive.log"
run_keepalive "$LOG"

echo "已执行，日志见：$LOG"
