#!/system/bin/sh
# Magisk 卸载这个模块时执行（下次重启前）。
#
# 这里同样不做任何自动化的"卸载/恢复"操作，原因和 v1.0 一样：uninstall.sh
# 跑在开机极早期阶段，这时候贸然调用 pm 相关命令去"帮用户处理"风险比让
# 用户自己在系统正常启动后手动确认要高。
#
# 老实说明一点：模块移除后，系统分区里的占位 APK 会消失。如果你当时正式
# APK 是以"系统应用的更新"这种形式存在（即之前系统分区一直有这份占位在），
# 移除后重启，系统能不能正确把它"降级"处理成一个普通用户应用、还是会
# 把整个安装记录一起清掉，这个具体行为跟安卓版本/ROM 有关，我们没有把握
# 打包票，所以这里只留一份提示，不替你做决定。

echo "Aksb2026CallerID 系统化增强模块已被移除。" > /data/local/tmp/callerid_systemize_uninstalled.txt
echo "重启后请检查「来电识别」App 是否还能正常打开。" >> /data/local/tmp/callerid_systemize_uninstalled.txt
echo "如果打不开或者从桌面消失了，用 adb shell pm uninstall com.aksb2026.callerid.module" >> /data/local/tmp/callerid_systemize_uninstalled.txt
echo "彻底清一遍，再重新安装一遍正式 APK 即可恢复。" >> /data/local/tmp/callerid_systemize_uninstalled.txt
echo "App 数据（设置/SpamBlocker联动配置等）是否会一并被清掉，取决于具体" >> /data/local/tmp/callerid_systemize_uninstalled.txt
echo "安卓版本的包管理清理逻辑，如果丢了，重新配置一遍即可，不是什么大问题。" >> /data/local/tmp/callerid_systemize_uninstalled.txt
