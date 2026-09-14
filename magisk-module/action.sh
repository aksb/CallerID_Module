#!/system/bin/sh
# 有这个文件，Magisk App 里这个模块条目会多一个"Action"按钮。
#
# 重要：这个按钮只有在模块刷入并重启过一次、模块被 Magisk 正式挂载生效
# 之后才会出现，这是 Magisk 本身的机制（模块要先"激活"才有 Action 入口），
# 首次刷入、还没重启之前看不到这个按钮属于正常现象，不是本模块的问题。
#
# 按钮出现之后，点一下就用 root 立即重新执行一次保活命令，不需要再重启
# 手机，而且每一项结果都会直接用中文显示在这里。

MODDIR=${0%/*}
. "$MODDIR/common/keepalive.sh"

LOG="$MODDIR/keepalive.log"
run_keepalive "$LOG"

echo ""
echo "完整日志见：$LOG"
