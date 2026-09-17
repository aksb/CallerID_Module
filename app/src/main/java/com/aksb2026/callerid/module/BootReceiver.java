package com.aksb2026.callerid.module;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启 FloatWindowService（普通启动），以及按设置决定是否自启 QueryServerService（v3.19 新增） */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            ctx.startService(new Intent(ctx, FloatWindowService.class));
            if (ModuleSettings.isSbAutostart(ctx)) {
                ctx.startService(new Intent(ctx, QueryServerService.class));
            }

            // v4.9 新增："⑤ Root 高级选项"开启时，开机自动跑一遍保活命令，
            // 效果对应 KeepAlive-Magisk 模块的 service.sh（开机自动执行）。
            // Shell.cmd().exec() 是阻塞调用，BroadcastReceiver.onReceive()
            // 不能长时间阻塞（否则会被系统判定 ANR），所以丢到后台线程执行，
            // 不等结果、不影响开机流程；执行结果这里不展示给用户，用户想看
            // 效果可以回到 App 里手动点一次"⑤"重新执行。
            if (ModuleSettings.isRootAdvancedEnabled(ctx)) {
                Context appCtx = ctx.getApplicationContext();
                new Thread(() -> {
                    try { RootAdvancedHelper.runOnce(appCtx); } catch (Exception ignored) {}
                }).start();
            }
        }
    }
}
