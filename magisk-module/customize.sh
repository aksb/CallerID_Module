#!/system/bin/sh
# 装模块那一刻执行（不是开机时）。这个模块没有 system/ 目录、不需要挂载任何
# 系统文件，只在开机 service 阶段跑几条 appops/dumpsys 命令，所以这里把
# SKIPMOUNT 设成 true（配合模块根目录下的 skip_mount 空文件，双重保险）。

SKIPMOUNT=true

ui_print "- CallerIDModule 保活助手"
ui_print "  纯脚本模块，不含 APK，只有装了 com.callerid.module 这个包名的 App"
ui_print "  时才会在开机后自动生效；没装这个 App 完全没有影响。"
ui_print "  Magisk App 里这个模块的 Action 按钮可以不重启立即重新执行一次。"

if pm path com.callerid.module >/dev/null 2>&1; then
    ui_print "- 检测到已安装 com.callerid.module，重启后即会自动生效"
else
    ui_print "- 当前还没有安装 com.callerid.module，之后装了这个包名后重启一次即可生效"
fi
