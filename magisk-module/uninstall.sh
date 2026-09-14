#!/system/bin/sh
# 卸载这个模块时执行一次（Magisk 在下次重启应用卸载前跑这个脚本）。
#
# 只撤销 deviceidle 白名单这一项——appops 的 allow 状态本来就是"放宽限制"的
# 方向，撤不撤对系统本身没有风险；如果这里连 RUN_IN_BACKGROUND 也一并撤销，
# 而用户当时还在正常使用这个 App，撤销的瞬间可能立刻被系统重新限制，属于
# 用户没预料到的副作用。所以这里选择偏保守的收尾方式：只退出白名单，
# appops 的设置保持原样，用户以后想恢复原状的话去 App 里"一键保活"按钮或
# 手动敲命令都能自己控制。

PKG="com.callerid.module"
if pm path "$PKG" >/dev/null 2>&1; then
    dumpsys deviceidle whitelist -"$PKG" >/dev/null 2>&1
fi
