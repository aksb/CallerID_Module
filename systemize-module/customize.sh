#!/system/bin/sh
##########################################################
# Aksb2026CallerID 系统化增强 - customize.sh（v4.3 重写版）
#
# 【这一版和 v1.0 的核心区别，务必看一下】
# v1.0 的做法：刷机时现场用 `pm path` 找到手机上已装的 APK，复制到系统
# 分区，再执行 `pm uninstall -k` 卸载当前的普通安装版本。实测中这个
# "现场卸载再指望系统重启后重新识别"的过程，在部分设备上会导致系统
# 重新扫描 /system 分区、给这个包重新分配 UID 时跟原来 -k 保留下来的
# 数据目录对不上，表现为：桌面图标变成默认安卓机器人、App 一打开就闪退、
# 而且这之后连重新安装 APK 都会失败（不是签名问题，是这个包名当时已经
# 处于一种自相矛盾的残缺注册状态，装不进去）。
#
# v4.3 改成参照 RecordYou 的思路：模块 zip 里内置一个跟正式 APK 用
# 同一把签名 key 签的"占位 APK"（没有任何真实功能、也没有桌面图标），
# 构建时就已经打包好，刷机时原样复制进系统分区，**不做任何"卸载现有
# 安装"的操作**。之后不管你是先装的正式 APK 还是先刷的这个模块：
#   - 先刷模块、后装 APK：系统分区已经有一个"基础版本"占位在那，装
#     正式 APK 时，Android 认这是"给这个系统应用发的更新"，走的是
#     系统给预装应用推送更新的原生机制。
#   - 先装 APK、后刷模块：重启后系统重新扫描 /system，发现这个包在
#     系统分区也有一份（占位版本），而数据分区已经有一份签名相同、
#     版本号更高的正式版——同样是安卓原生识别"系统应用 + 已被用户
#     更新覆盖"这套机制来处理，不需要本脚本插手做任何卸载动作。
# 两种顺序走的都是安卓自己每天都在用的标准逻辑，不再依赖本脚本去做
# 有状态的现场手术，这也是这一版没有了"先装 App 再刷模块"这条顺序
# 限制的原因。
##########################################################

PKG="com.aksb2026.callerid.module"
STUB_SRC="$MODPATH/Aksb2026CallerID-stub.apk"
TARGET_DIR="$MODPATH/system/priv-app/Aksb2026CallerID"

ui_print " "
ui_print "======================================"
ui_print " Aksb2026CallerID 系统化增强（可选模块，v4.3）"
ui_print "======================================"
ui_print "- 跟保活脚本模块（callerid_keepalive）完全独立，装不装、先刷哪个"
ui_print "  都互不影响"
ui_print "- 这一版不再要求「先装 App 再刷模块」，顺序随意"

if [ ! -f "$STUB_SRC" ]; then
    abort "! 模块包内缺少占位 APK（Aksb2026CallerID-stub.apk），说明这份模块 zip 打包不完整，请重新下载完整的模块 zip，不要只解压部分文件手动重新打包"
fi

mkdir -p "$TARGET_DIR"
cp -f "$STUB_SRC" "$TARGET_DIR/Aksb2026CallerID.apk"

if [ ! -f "$TARGET_DIR/Aksb2026CallerID.apk" ]; then
    abort "! 复制占位 APK 到系统分区暂存目录失败，安装中止（如果你手机上已经装了正式 APK，这个失败不会对它产生任何影响，因为本脚本全程没有触碰你已装的那份）"
fi

set_perm_recursive "$MODPATH/system" 0 0 0755 0644

# 目前这个 App 不需要任何特权权限，这份空白名单文件纯粹是让它以标准
# priv-app 姿态存在，避免个别 ROM 的权限扫描流程对"没有对应白名单文件的
# priv-app"报警告。
mkdir -p "$MODPATH/system/etc/permissions"
cat > "$MODPATH/system/etc/permissions/privapp-permissions-callerid.xml" << EOF
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="$PKG">
    </privapp-permissions>
</permissions>
EOF
set_perm "$MODPATH/system/etc/permissions/privapp-permissions-callerid.xml" 0 0 0644

if pm path "$PKG" >/dev/null 2>&1; then
    ui_print "- 检测到手机上已经装了 $PKG（不会做任何改动，只是提示一下）"
    ui_print "- 重启后系统会自动把它识别成系统应用 + 已被你现有版本更新覆盖，"
    ui_print "  正常情况下不需要你做任何额外操作"
else
    ui_print "- 当前还没有安装 $PKG，重启后请正常安装一遍正式 APK 即可（会被系统"
    ui_print "  当成给这个系统应用做更新，前提是这份 APK 跟本模块内置占位 APK"
    ui_print "  用的是同一套签名——官方正式发布的 APK 都满足这一点）"
fi

ui_print "- 完成，请重启手机"
ui_print "- 如果重启后出现异常（比如提示安装包无效、App 打不开），不要慌，"
ui_print "  先移除本模块重启，再用 adb shell pm uninstall $PKG 彻底清一遍，"
ui_print "  重新正常安装 APK 确认恢复正常即可"
