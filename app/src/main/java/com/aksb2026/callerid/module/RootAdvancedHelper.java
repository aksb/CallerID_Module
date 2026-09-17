package com.aksb2026.callerid.module;

import android.content.Context;

import com.topjohnwu.superuser.Shell;

/**
 * v4.9 新增："⑤ Root 高级选项"隐藏功能——教程板块里明确标注"高级/有风险"的
 * 一个可选开关，默认关闭，需要用户看完风险声明并主动确认才能开启。
 *
 * 开启后做的事情，效果上等价于同时具备 KeepAlive-Magisk 保活模块（五条
 * appops/deviceidle/am 命令）+ 理论上把本进程 oom_score_adj 调到 -1000
 * （跟系统内存回收机制的优先级判断对着干，详见 App 内弹窗风险声明），全部
 * 在 App 进程内部直接用 root 执行，不需要再单独刷 KeepAlive / Systemize
 * 两个 Magisk 模块。
 *
 * 重要背景（务必先看 KeepAliveHelper.java 顶部注释）：v4.1 出于"Magisk 的
 * root 授权是全有或全无，不应该交给功能复杂的 App"这个理由，把类似功能连同
 * libsu 依赖整个移除过。这次重新引入，前提是：① 默认关闭，绝不是推荐路径；
 * ② 教程里用醒目文案说清楚具体风险（不是泛泛的"有风险"三个字）；③ 用户需要
 * 主动勾选"已知悉风险"才能打开。开发者认为这几条前提下，把这个选项交给
 * 愿意自己承担后果的高级用户，是可以接受的取舍。
 */
final class RootAdvancedHelper {

    private RootAdvancedHelper() {}

    /** 五条保活命令 + oom_score_adj 调整，逐条执行、逐条判断，返回中文结果（每行一条，供弹窗/日志展示）。 */
    static String runOnce(Context ctx) {
        String pkg = ctx.getPackageName();
        StringBuilder sb = new StringBuilder();

        if (!Shell.getShell().isRoot()) {
            return "未获取到 root 权限，命令未执行。请检查 Magisk/KernelSU/APatch 的 Superuser 授权列表里是否已经允许本 App。";
        }

        sb.append(setAndCheck("悬浮窗权限",
                "cmd appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
                "cmd appops get " + pkg + " SYSTEM_ALERT_WINDOW", "allow"));
        sb.append('\n');
        sb.append(setAndCheck("后台运行权限",
                "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow",
                "cmd appops get " + pkg + " RUN_IN_BACKGROUND", "allow"));
        sb.append('\n');
        sb.append(setAndCheck("任意后台运行权限",
                "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow",
                "cmd appops get " + pkg + " RUN_ANY_IN_BACKGROUND", "allow"));
        sb.append('\n');
        sb.append(setAndCheck("电池优化白名单",
                "dumpsys deviceidle whitelist +" + pkg,
                "dumpsys deviceidle whitelist", pkg));
        sb.append('\n');
        sb.append(setAndCheck("待机分桶限制已解除",
                "am set-inactive " + pkg + " false",
                "am get-inactive " + pkg, "Idle=false"));
        sb.append('\n');

        // oom_score_adj：把本进程的这个值改到 -1000，跟 system_server 同级，
        // 意图是让系统在内存紧张时尽量最后才回收本进程。这是一步"跟系统内存
        // 管理机制对着干"的操作，具体风险见弹窗里的免责声明文字，这里只负责
        // 执行和报告结果，不做二次确认（二次确认在 UI 层已经做过）。
        int pid = android.os.Process.myPid();
        Shell.Result r = Shell.cmd("echo -1000 > /proc/" + pid + "/oom_score_adj").exec();
        boolean oomOk = r.isSuccess();
        sb.append("进程内存回收优先级（oom_score_adj=-1000）：").append(oomOk ? "已设置 ✔" : "设置失败 ✘（部分设备的 SELinux 策略会拦截，不代表其他几项也失败）");

        return sb.toString();
    }

    private static String setAndCheck(String desc, String setCmd, String checkCmd, String expect) {
        Shell.cmd(setCmd).exec();
        Shell.Result r = Shell.cmd(checkCmd).exec();
        String out = String.join("\n", r.getOut());
        boolean ok = out.contains(expect);
        return desc + "：" + (ok ? "已启用 ✔" : "未启用 ✘");
    }
}
