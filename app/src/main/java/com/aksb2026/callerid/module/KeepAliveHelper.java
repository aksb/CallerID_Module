package com.aksb2026.callerid.module;

import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 保活相关的状态查询（v4.1：去掉了 v4.0 里需要 root 的部分）。
 *
 * v4.0 曾经加过一个"一键保活"按钮，需要 App 自己申请 root 才能执行
 * `cmd appops set ...` 这类命令。但仔细想清楚后发现：Magisk 的 root 授权是
 * 全有或全无的，一旦同意，Aksb2026CallerID 拿到的不是"只能跑那几条命令"的权限，
 * 而是任意 root 命令的权限——授权机制本身不看这个 App 打算拿 root 干嘛。
 * 相比之下，配套的 Magisk 保活模块（service.sh / action.sh）压根不用走
 * "授权某个 App root"这条路，脚本本身就跑在 Magisk 自己的可信执行环境里，
 * 效果完全一样，但不需要把 root 交给这个功能复杂得多的 App。所以 v4.1 把
 * App 里申请 root、执行命令的那部分整个去掉了（连带去掉了 libsu 依赖），
 * 保活命令改成三选一：ADB 命令 / 手机本地终端（Termux 等）/ Magisk 模块的
 * Action 按钮，见"Root保活教程"板块里的说明。
 *
 * 这个类现在只保留三项完全不需要 root、走公开 API 就能查到的状态，纯粹是
 * 免费的信息展示，没有任何安全代价：
 * - 悬浮窗权限
 * - 电池优化白名单
 * - 应用待机分桶
 *
 * 依旧不存"执行过"的标志位，每次都现查系统当前的真实状态。
 */
final class KeepAliveHelper {

    private KeepAliveHelper() {}

    /** 一次性查出来的状态快照，供界面展示用。 */
    static final class Status {
        boolean overlayGranted;  // 悬浮窗权限（公开 API）
        boolean batteryIgnored;  // 是否已在电池优化白名单里（公开 API）
        boolean standbyOk;       // 待机分桶是否为"不受限"档位（公开 API，Android 9+ 才有意义）

        /** 综合判定："已保活" = 这三项（App 自己能查到的部分）都过关。 */
        boolean allGood() {
            return overlayGranted && batteryIgnored && standbyOk;
        }
    }

    /**
     * 查一次状态。全部是轻量公开 API 调用，理论上放主线程也不会卡，但仍然建议
     * 放后台线程调用，跟调用方保持一致的写法习惯即可。
     */
    static Status check(Context ctx) {
        Status s = new Status();
        String pkg = ctx.getPackageName();

        s.overlayGranted = Settings.canDrawOverlays(ctx);

        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        s.batteryIgnored = pm != null && pm.isIgnoringBatteryOptimizations(pkg);

        s.standbyOk = isStandbyBucketOk(ctx);

        return s;
    }

    /**
     * 应用待机分桶（Android 9 / API 28+ 才有意义，本项目 minSdk 就是 28，
     * 不需要额外做版本判断兜底）。ACTIVE/WORKING_SET 视为健康；
     * FREQUENT/RARE/RESTRICTED 视为"系统认为你不常用，后台限制会更狠"。
     */
    private static boolean isStandbyBucketOk(Context ctx) {
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return false;
            int bucket = usm.getAppStandbyBucket();
            return bucket <= UsageStatsManager.STANDBY_BUCKET_WORKING_SET;
        } catch (Exception e) {
            return false;
        }
    }
}
