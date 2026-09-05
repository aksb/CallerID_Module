package com.callerid.module;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 接收 HookEntry 广播，转发给 FloatWindowService
 *
 * ✅ 使用 startService()（非 startForegroundService()）
 *    与 FloatWindowService 已改为普通 Service 保持一致，
 *    避免 5 秒内未调 startForeground 导致的 ANR。
 */
public class CallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        Intent svc = new Intent(ctx, FloatWindowService.class);
        svc.setAction(intent.getAction());
        svc.putExtra("number", intent.getStringExtra("number"));
        ctx.startService(svc);   // ✅ 普通启动
    }
}
