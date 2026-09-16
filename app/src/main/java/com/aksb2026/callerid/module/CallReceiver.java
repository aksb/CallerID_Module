package com.aksb2026.callerid.module;

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

        // 自愈（v3.20 新增）：这条广播是 HookEntry 从 system_server 进程里
        // 发出的、指定了本 App 包名的显式广播——即使本 App 进程之前已经被系统
        // 按后台策略杀掉，Android 也会重新拉起这个进程来处理它，不需要 root、
        // 不需要额外权限，是系统本身的保证。顺手借这个"进程反正都会被拉起"的
        // 时机，检查一下"SpamBlocker 联动"用的本地查询服务是不是也该活着但被
        // 杀了，是的话一并重启，不用等用户手动重开 App 才发现联动失效。
        //
        // 只在用户勾选过"开机自动启动本地查询服务"时才做这个自愈，尊重那些
        // 压根没打开过这个功能的用户；服务本来就在运行时再调一次 startService()
        // 是幂等操作（只是重新走一遍 onStartCommand），没有副作用。
        // 注意：这救不回"正在发生的这一通"电话——它和 SpamBlocker 自己那次
        // 呼叫屏蔽判定几乎同时触发，谁先谁后没有保证；救的是"下一通"。
        if (ModuleSettings.isSbAutostart(ctx)) {
            ctx.startService(new Intent(ctx, QueryServerService.class));
        }
    }
}
