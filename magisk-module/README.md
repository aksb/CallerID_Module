# Aksb2026CallerID 保活助手（Magisk 模块）

不含任何 APK，纯脚本模块。参考 RecordYou 的思路，但反过来设计：
**装了「来电识别」(`com.aksb2026.callerid.module`) 这个包名的 App，这个模块才生效；
不装这个 App、或者卸载了这个 App，模块本身什么都不做，不会有任何副作用。**

## 这个模块做了什么

开机（`service.sh`，late_start service 阶段）时检测 `com.aksb2026.callerid.module`
是否已安装，是的话执行：

```
cmd appops set com.aksb2026.callerid.module SYSTEM_ALERT_WINDOW allow
cmd appops set com.aksb2026.callerid.module RUN_IN_BACKGROUND allow
cmd appops set com.aksb2026.callerid.module RUN_ANY_IN_BACKGROUND allow
dumpsys deviceidle whitelist +com.aksb2026.callerid.module
am set-inactive com.aksb2026.callerid.module false
```

这套命令和 App 里"Root保活教程"板块列出的 ADB 命令、手机本地终端（Termux 等）
命令完全一样，三种方式效果等价，选哪个纯粹看你手头方便用什么：

| 方式 | 需要什么 | 需要设备本身 root 吗 |
|---|---|---|
| ADB 命令 | 电脑 + 数据线 | 不需要 |
| 手机本地终端（Termux 等） | 手机已 root | 需要 |
| 本 Magisk 模块 | 手机已 root、装了 Magisk | 需要（但脚本本身跑在 Magisk 环境里，不是"给某个 App 申请 root"） |

## 验证是否生效

v4.3 起，本模块每次执行（开机自动执行，或者点 Action 按钮手动执行）都会
直接输出中文结果，不需要你再自己去跑命令肉眼比对英文输出。如果还是想在
Termux / ADB shell 里手动跑一遍确认，下面每条都是"设置 + 判断 + 中文
输出"合并成的单行命令，可以逐条整行复制粘贴执行（特意写成单行，避免
Termux 粘贴多行命令有时候会出错的问题）：

```
cmd appops set com.aksb2026.callerid.module SYSTEM_ALERT_WINDOW allow; cmd appops get com.aksb2026.callerid.module SYSTEM_ALERT_WINDOW | grep -q allow && echo "悬浮窗权限：已启用 ✔" || echo "悬浮窗权限：未启用 ✘"
cmd appops set com.aksb2026.callerid.module RUN_IN_BACKGROUND allow; cmd appops get com.aksb2026.callerid.module RUN_IN_BACKGROUND | grep -q allow && echo "后台运行权限：已启用 ✔" || echo "后台运行权限：未启用 ✘"
cmd appops set com.aksb2026.callerid.module RUN_ANY_IN_BACKGROUND allow; cmd appops get com.aksb2026.callerid.module RUN_ANY_IN_BACKGROUND | grep -q allow && echo "任意后台运行权限：已启用 ✔" || echo "任意后台运行权限：未启用 ✘"
dumpsys deviceidle whitelist +com.aksb2026.callerid.module; dumpsys deviceidle whitelist | grep -q com.aksb2026.callerid.module && echo "电池优化白名单：已启用 ✔" || echo "电池优化白名单：未启用 ✘"
am set-inactive com.aksb2026.callerid.module false; am get-inactive com.aksb2026.callerid.module | grep -q "Idle=false" && echo "待机分桶限制：已解除 ✔" || echo "待机分桶限制：未解除 ✘"
```

需要用 adb 从电脑上敲的话，整条包一层引号即可，例如：

```
adb shell 'cmd appops set com.aksb2026.callerid.module SYSTEM_ALERT_WINDOW allow; cmd appops get com.aksb2026.callerid.module SYSTEM_ALERT_WINDOW | grep -q allow && echo "悬浮窗权限：已启用 ✔" || echo "悬浮窗权限：未启用 ✘"'
```

## 安装

1. 手机上先装好「来电识别」App（这个模块不含 APK）。
2. Magisk App → 模块 → 从本地安装 → 选这个模块的 zip → 重启。
3. 重启后即生效。想不重启验证效果，见下面"不重启手动触发"。

模块和 App 谁先装都可以，模块本身只在乎"开机那一刻这个包名在不在"。

## 不重启手动触发

Magisk App 里这个模块条目会有一个 **Action** 按钮，点一下就用 root
立即重新跑一次上面那几条命令、并直接用中文显示每一项结果，不用重启
手机——原理是模块自带一个 `action.sh`，这是 Magisk 从 v24+ 开始支持的
标准功能。

**注意：这个按钮只有在模块刷入并重启过一次之后才会出现**，这是 Magisk
本身的机制（模块要先被挂载生效，Magisk App 才会显示它的 Action 入口），
刚刷完还没重启时看不到这个按钮属于正常现象。等按钮出现之后，点击执行
本身就不需要再重启了。

## 卸载

Magisk App 里正常移除这个模块、重启即可。卸载时会跑一次 `uninstall.sh`，
只把 `com.aksb2026.callerid.module` 从 deviceidle 白名单里撤出来（appops 的
allow 状态刻意不撤销，避免撤销瞬间正在使用中的 App 突然被系统限制这种
意料之外的副作用）。卸载模块**不会**卸载/影响「来电识别」这个 App 本身。

## 日志

`service.sh`/`action.sh` 每次执行都会往模块目录下的 `keepalive.log`
追加一行，可以用 Magisk App 自带的文件管理器，或者 adb/终端看：

```
/data/adb/modules/callerid_keepalive/keepalive.log
```

## 已知限制（老实说）

- 这几条命令碰的都是 AOSP 原生的电池优化/Doze 机制。小米/华为/OPPO/vivo
  等定制系统自己的"自启动管理"/"神隐模式"这类后台管理，跟这套机制是独立的
  两回事，这个模块管不到——遇到这类系统还是建议去系统设置里手动给
  「来电识别」加后台运行白名单，命中率通常比任何脚本都高。
- appops 的 `RUN_IN_BACKGROUND`/`RUN_ANY_IN_BACKGROUND` 用的是安卓内部没有
  正式对外承诺的隐藏接口，不是正式 SDK API，理论上未来某个安卓大版本升级
  有极小概率失效（目前各版本仍然可用）。
- `service.sh` 里对"系统服务是否就绪"的等待只是简单轮询最多 60 秒，
  极端情况下（比如系统启动特别慢）到 60 秒时 `pm path` 仍可能查不到，
  这时命令会被跳过——不会报错崩溃，但那次开机不会生效，等下次开机通常
  就正常了；也可以直接用上面的 Action 按钮手动补一次。
