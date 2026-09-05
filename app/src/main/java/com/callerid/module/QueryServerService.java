package com.callerid.module;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * 托管 LocalQueryServer 的普通 Service（v3.19 新增，"结合 SpamBlocker 拦截器"功能）。
 *
 * 沿用项目里 FloatWindowService 的风格：不用前台服务，规避 Android 14+ 对前台服务
 * 类型的强校验（本模块不是默认拨号 App，拿不到 phoneCall 类型授权）。代价是可能被
 * 系统后台省电策略清理掉，用户需要按需给这个 App 加省电白名单。
 *
 * exported=false（见 AndroidManifest）：外部 App 不能直接 startService 拉起它，
 * 只有本 App 自己（MainActivity 手动启动 / BootReceiver 开机自启）能启动，
 * 联动的实际鉴权在 HTTP 层由 token 完成，这里只是多一层"不让外部乱启动"的保护。
 */
public class QueryServerService extends Service {

    private static final String TAG = "CallerID_QueryServerService";

    private LocalQueryServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        server = new LocalQueryServer(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int port = ModuleSettings.getSbPort(this);
        if (server == null) server = new LocalQueryServer(this);
        if (!server.isRunning()) {
            server.start(port);
        }
        Log.d(TAG, "onStartCommand, port=" + port + ", running=" + server.isRunning());
        // START_STICKY：进程被系统杀掉后尽量由系统重建（不重放最后一个 intent），
        // 和 FloatWindowService 保持一致的存活策略。
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 不支持绑定，只用 startService/stopService 控制生命周期
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }
}
