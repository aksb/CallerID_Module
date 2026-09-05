package com.callerid.module;

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
        }
    }
}
