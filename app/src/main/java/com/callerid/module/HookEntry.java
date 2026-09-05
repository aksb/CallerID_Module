package com.callerid.module;

import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口
 * Hook 系统 TelephonyRegistry，绕过 Android 10+ 号码隐私屏蔽
 * 作用域：android（系统框架进程）
 *
 * 兼容策略：按版本依次尝试三种方法签名：
 *   Android 9-  : notifyCallState(int state, String number)
 *   Android 10+ : notifyCallStateForAllSubs(int state, String number)
 *   Android 12+ : notifyCallState(int subId, int state, String number)
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "CallerID_Hook";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": injected into android framework");

        boolean hooked = false;

        // 尝试 1：Android 9- 签名
        if (!hooked) hooked = tryHook(lpparam, "notifyCallState",
                0, 1, int.class, String.class);

        // 尝试 2：Android 10+ 签名
        if (!hooked) hooked = tryHook(lpparam, "notifyCallStateForAllSubs",
                0, 1, int.class, String.class);

        // 尝试 3：Android 12+ 签名（subId, state, number）
        if (!hooked) hooked = tryHook(lpparam, "notifyCallState",
                1, 2, int.class, int.class, String.class);

        if (!hooked) {
            XposedBridge.log(TAG + ": WARNING all hook attempts failed, "
                    + "check Android version signature");
        }
    }

    /**
     * @param stateArgIdx  state 在 args[] 中的下标
     * @param numberArgIdx number 在 args[] 中的下标
     */
    private boolean tryHook(XC_LoadPackage.LoadPackageParam lpparam,
                             String methodName,
                             int stateArgIdx, int numberArgIdx,
                             Class<?>... paramTypes) {
        try {
            Object[] args = new Object[paramTypes.length + 1];
            System.arraycopy(paramTypes, 0, args, 0, paramTypes.length);
            args[paramTypes.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int    state  = (int)    param.args[stateArgIdx];
                    String number = (String) param.args[numberArgIdx];
                    dispatch(state, number);
                }
            };
            XposedHelpers.findAndHookMethod(
                    "com.android.server.TelephonyRegistry",
                    lpparam.classLoader,
                    methodName,
                    args);
            XposedBridge.log(TAG + ": hooked " + methodName
                    + "(stateIdx=" + stateArgIdx + ",numIdx=" + numberArgIdx + ")");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 广播来电/挂断事件给主 App 进程 */
    private static void dispatch(int state, String number) {
        Context ctx = getSystemContext();
        if (ctx == null) return;

        if (state == TelephonyManager.CALL_STATE_RINGING
                && number != null && !number.isEmpty()) {
            Intent i = new Intent("com.callerid.ACTION_INCOMING");
            i.setPackage("com.callerid.module"); // 只发给自己，安全
            i.putExtra("number", number);
            ctx.sendBroadcast(i);
            XposedBridge.log(TAG + ": RINGING " + number);

        } else if (state == TelephonyManager.CALL_STATE_IDLE) {
            Intent i = new Intent("com.callerid.ACTION_IDLE");
            i.setPackage("com.callerid.module");
            ctx.sendBroadcast(i);
            XposedBridge.log(TAG + ": IDLE");
        }
    }

    private static Context getSystemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object instance = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            return (Context) XposedHelpers.callMethod(instance, "getSystemContext");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getSystemContext failed: " + t);
            return null;
        }
    }
}
