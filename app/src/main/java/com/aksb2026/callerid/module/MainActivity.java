package com.aksb2026.callerid.module;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    // "查询来源"三选一配色（v3.19 新增）：搜狗＝橙、360＝绿、自定义＝蓝，
    // 对应的保存按钮（顶部裁剪跟随当前选中来源动态切换；自定义网址固定用这个色，
    // 因为只在选中自定义时才显示）用同一套颜色；"展示高度"保存按钮不属于任何单一
    // 来源，不参与这套配色，固定改成紫色，避免和上面三色混淆。
    private static final int COLOR_SOURCE_SOGOU  = 0xFFEF6C00; // 橙
    private static final int COLOR_SOURCE_360    = 0xFF2E7D32; // 绿
    private static final int COLOR_SOURCE_CUSTOM = 0xFF1565C0; // 蓝
    private static final int COLOR_WEB_HEIGHT_BTN = 0xFF8E24AA; // 紫（展示高度保存按钮专用）

    private EditText etTestNumber;

    // 自定义号码：手动添加输入框
    private EditText etUserName;
    private EditText etUserNumber;

    // 两个通用可管理列表（缓存 / 自定义号码 共用同一套逻辑）
    private ManagedListSection cacheSection;
    private ManagedListSection userSection;

    // 板块展开触发器（v4.0 新增）：boardId -> "如果还没展开就展开它"的动作，
    // 供顶部"保活状态"那行的"去处理 →"链接跳转到下面对应板块使用。
    // 在 addCollapsibleBoard() 里注册，不需要每个板块手动维护自己的 header/content 引用。
    private final java.util.Map<String, Runnable> boardExpandTriggers = new java.util.LinkedHashMap<>();
    // 同上，配套记录每个板块 header 行的 View，方便"去处理 →"顺便把 ScrollView 滚动过去。
    private final java.util.Map<String, View> boardHeaderRows = new java.util.LinkedHashMap<>();

    // 保活相关的顶部状态刷新逻辑（v4.0 新增），onResume 里也要用，所以提到字段级别的 Runnable。
    private Runnable refreshKeepAliveStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CacheStore.init(this);
        CacheStore.clearEmpty();
        UserNumberStore.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));

        // 供顶部"保活状态"那行的"去处理 →"链接跳转/滚动到下面"Root保活教程"板块用
        // （v4.0 新增）：ScrollView 真正创建之后才会被赋值，但点击回调本身是在
        // 用户点击时才执行，那时候早就赋值完了，所以用数组包一层来绕开"局部变量
        // 必须先声明再使用"这条 Java 语法限制。
        final ScrollView[] scrollViewHolder = new ScrollView[1];

        add(root, title("📞 来电识别", 20, Color.WHITE), 0);
        add(root, title("v1.5  基于百度号码查询页解析", 12, 0xFF44CC44), 4);
        add(root, title(
                "1. LSPosed → 来电识别 → 启用 → 作用域选「系统框架」→ 重启后生效。\n"
              + "2. 授权悬浮窗 → 启动服务\n"
              + "3. 用「模拟来电」按钮测试，无需真来电\n"
              + "4. ROOT保活（防止 Service 被杀）见软件底部「ROOT保活教程」板块。",
                12, 0xFFFF9900), 4);

        // ── 来电识别服务：真正的功能开关（v4.9 重新设计）───────────────────
        // 之前"启动/停止来电识别服务"两个按钮 + 顶部状态镜像行，实际上完全不
        // 影响来电时会不会弹悬浮窗——CallReceiver 是独立注册的广播接收器，
        // 来电时系统会单独把它拉起来，跟这个按钮点没点过、这个常驻服务
        // 活没活着毫无关系，那一整套东西展示的只是一个对结果没有任何影响
        // 的中间状态，才会让"闲置"和"停止"看起来自相矛盾。
        //
        // 现在改成货真价实的开关：直接用 PackageManager 禁用/启用
        // CallReceiver 这个组件本身——选"关闭"之后，系统连广播都不会往这个
        // 组件投递，来电真的不会再弹出来；选"开启"立刻恢复。这个状态由
        // 系统本身持久化（存在 PackageManager 的组件状态记录里，跨重启
        // 有效），不需要我们自己再另外存一份，天然不会跟实际行为不一致。
        // 默认"开启"，跟"装完就能用、不用手动设置"的一贯设计保持一致。
        add(root, section("来电识别服务", 20), 8);
        RadioGroup rgCallService = new RadioGroup(this);
        rgCallService.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbCallServiceOn  = radioBtn("开启");
        RadioButton rbCallServiceOff = radioBtn("关闭");
        rgCallService.addView(rbCallServiceOn);
        rgCallService.addView(rbCallServiceOff);
        (isCallServiceEnabled() ? rbCallServiceOn : rbCallServiceOff).setChecked(true);
        rgCallService.setOnCheckedChangeListener((group, checkedId) -> {
            boolean enable = checkedId == rbCallServiceOn.getId();
            setCallServiceEnabled(enable);
            toast(enable ? "来电识别服务已开启" : "来电识别服务已关闭");
        });
        add(root, rgCallService, 8);

        TextView tvTopForceCellular = new TextView(this);
        tvTopForceCellular.setTextSize(13);
        tvTopForceCellular.setTextColor(0xFF888888);
        add(root, tvTopForceCellular, 4);

        TextView tvTopSbLinkage = new TextView(this);
        tvTopSbLinkage.setTextSize(13);
        add(root, tvTopSbLinkage, 4);

        // ── 保活状态（v4.0 新增）：第四行，接在上面三行下面 ────────────────
        // "已保活/未保活"这句话本身 + 默认收起的明细列表 + 跳转到下面
        // "Root保活教程"板块的"去处理 →"链接。这里只负责摆控件和挂刷新逻辑，
        // 真正的检查逻辑在 KeepAliveHelper 里，且都在后台线程跑，不卡启动。
        TextView tvTopKeepAlive = new TextView(this);
        tvTopKeepAlive.setTextSize(13);
        add(root, tvTopKeepAlive, 4);

        LinearLayout keepAliveDetailBox = new LinearLayout(this);
        keepAliveDetailBox.setOrientation(LinearLayout.VERTICAL);
        TextView tvKeepAliveDetail = title("", 12, 0xFF999999);
        tvKeepAliveDetail.setLineSpacing(0, 1.3f);
        add(keepAliveDetailBox, tvKeepAliveDetail, 4);
        addWarningFold(root, "keepalive_detail", "保活明细", keepAliveDetailBox, 2, false);

        TextView tvKeepAliveJump = new TextView(this);
        tvKeepAliveJump.setTextSize(12);
        tvKeepAliveJump.setMovementMethod(LinkMovementMethod.getInstance());
        add(root, tvKeepAliveJump, 4);

        refreshKeepAliveStatus = () -> new Thread(() -> {
            KeepAliveHelper.Status st = KeepAliveHelper.check(this);
            runOnUiThread(() -> {
                setStatusText(tvTopKeepAlive, "保活状态：", st.allGood(), "已保活", "未保活");

                StringBuilder detail = new StringBuilder();
                detail.append(st.overlayGranted ? "✅" : "❌").append(" 悬浮窗权限\n");
                detail.append(st.batteryIgnored ? "✅" : "❌").append(" 电池优化白名单\n");
                detail.append(st.standbyOk ? "✅" : "❌").append(" 应用待机分桶\n");
                detail.append("ℹ️ RUN_IN_BACKGROUND 等 appops 项没有公开读取 API，App 自己查不到，"
                        + "已执行请参考下方「Root保活教程」里的验证命令自行确认。");
                tvKeepAliveDetail.setText(detail.toString());

                SpannableString jump = new SpannableString("去处理 →");
                jump.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        Runnable trigger = boardExpandTriggers.get("root_keepalive");
                        if (trigger != null) trigger.run();
                        View target = boardHeaderRows.get("root_keepalive");
                        ScrollView scroller = scrollViewHolder[0];
                        if (target != null && scroller != null) {
                            scroller.post(() -> scroller.smoothScrollTo(0, Math.max(0, target.getTop() - dp(12))));
                        }
                    }

                    @Override
                    public void updateDrawState(TextPaint ds) {
                        ds.setColor(0xFF2196F3);
                        ds.setUnderlineText(false);
                    }
                }, 0, jump.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvKeepAliveJump.setText(jump);
            });
        }, "CallerID-KeepAliveCheck").start();
        refreshKeepAliveStatus.run();

        add(root, line(), 14);
        LinearLayout boardPermService = new LinearLayout(this);
        boardPermService.setOrientation(LinearLayout.VERTICAL);
        addCollapsibleBoard(root, "perm_service", "权限与服务", boardPermService);

        Button btnOverlay = btn("🔒 授权悬浮窗权限", 0xFF6A1B9A);
        btnOverlay.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        add(boardPermService, btnOverlay, 8);

        // v4.9：原来这里的"启动/停止来电识别服务"按钮 + 状态显示已经整体挪到
        // 顶部、改成了真正的功能开关（见 onCreate 顶部"来电识别服务"那一块），
        // 这里不再重复。

        LinearLayout boardForceCellularWarning = new LinearLayout(this);
        boardForceCellularWarning.setOrientation(LinearLayout.VERTICAL);
        addWarningFold(boardPermService, "force_cellular_warning", "⚠强制使用流量说明", boardForceCellularWarning, 14);
        add(boardForceCellularWarning, title(
                "如果使用数据流量正常查询，而WIFI无法查询(提示未知号码)，可以尝试开启下面的【强制使用流量查询】功能。\n\n"
              + "注意！此功能在即使连接WIFI也会强制使用流量查询，会额外消耗流量！虽然查询号码不会消耗多少流量，但如果你的手机流量特别少的话请谨慎开启，以免产生不必要的费用！\n\n"
              + "建议开启或关闭此功能后都开关 飞行模式 或 重启一次手机，以确保功能生效。另外手机系统设置里开发者选项——始终开启移动数据网络 要开启(默认就是开启状态)，否则连接WIFI后数据流量都无法使用，联网查询必定失败。",
                13, 0xFFFF9900), 6);

        Button btnForceCellularOn = btn("▶ 启动强制使用流量查询", 0xFF1565C0);
        add(boardPermService, btnForceCellularOn, 10);

        Button btnForceCellularOff = btn("⏹ 停止强制使用流量查询", 0xFFB71C1C);
        add(boardPermService, btnForceCellularOff, 6);

        // 状态显示：进入设置页时读取一次；点击"启动/停止"后本页面内也会立即刷新
        // （颜色保持原来的灰色即可，不需要变化；v3.13：字号改为 14sp，
        // 与"当前状态：已启动"一致，emoji 和文字之间加空格）
        TextView tvForceCellularStatus = title("", 14, 0xFF888888);
        add(boardPermService, tvForceCellularStatus, 6);
        Runnable refreshForceCellularStatus = () -> {
            String modeText = ModuleSettings.isForceCellular(this) ? "📶 强制流量" : "🛜 默认联网方式";
            tvForceCellularStatus.setText("当前状态：" + modeText);
            tvTopForceCellular.setText("联网查询方式：" + modeText);
        };
        refreshForceCellularStatus.run();

        btnForceCellularOn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("确认")
                .setMessage("确定启动强制流量吗？")
                .setPositiveButton("确定", (d, w) -> {
                    ModuleSettings.setForceCellular(this, true);
                    toast("已强制使用流量查询");
                    refreshForceCellularStatus.run();
                })
                .setNegativeButton("取消", null)
                .show());

        btnForceCellularOff.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("确认")
                .setMessage("确定停止强制流量吗？")
                .setPositiveButton("确定", (d, w) -> {
                    ModuleSettings.setForceCellular(this, false);
                    toast("已停止强制使用流量查询\n(已恢复默认联网方式)");
                    refreshForceCellularStatus.run();
                })
                .setNegativeButton("取消", null)
                .show());

        add(root, line(), 14);
        LinearLayout boardSimCall = new LinearLayout(this);
        boardSimCall.setOrientation(LinearLayout.VERTICAL);
        addCollapsibleBoard(root, "sim_call", "模拟来电测试", boardSimCall);

        etTestNumber = new EditText(this);
        etTestNumber.setHint("输入号码，如 17896436252");
        etTestNumber.setHintTextColor(0xFF555555);
        etTestNumber.setTextColor(Color.WHITE);
        etTestNumber.setTextSize(15);
        etTestNumber.setInputType(InputType.TYPE_CLASS_PHONE);
        etTestNumber.setText("17896436252");
        etTestNumber.setBackgroundColor(0xFF1E1E1E);
        etTestNumber.setPadding(dp(12), dp(10), dp(12), dp(10));
        add(boardSimCall, etTestNumber, 8);

        Button btnSim = btn("📲 模拟来电（弹悬浮窗）", 0xFF00897B);
        btnSim.setOnClickListener(v -> {
            String num = etTestNumber.getText().toString().trim();
            if (num.isEmpty()) { toast("请先输入号码"); return; }
            Intent i = new Intent(this, FloatWindowService.class);
            i.setAction("com.callerid.ACTION_INCOMING");
            i.putExtra("number", num);
            startService(i);
            toast("已触发：" + num);
        });
        add(boardSimCall, btnSim, 6);

        Button btnIdle = btn("⏹ 模拟挂断（移除悬浮窗）", 0xFF455A64);
        btnIdle.setOnClickListener(v -> {
            Intent i = new Intent(this, FloatWindowService.class);
            i.setAction("com.callerid.ACTION_IDLE");
            startService(i);
        });
        add(boardSimCall, btnIdle, 6);

        // ── 悬浮窗设置（v3.18 新增独立板块）：归纳"是否显示来电号码/是否显示查询
        // 结果/悬浮窗背景"这几项原有设置，加上新增的"是否可移动"，位置维持不变
        // （原来就夹在"模拟来电测试"和"百度号码解析"之间，现在只是从"模拟来电
        // 测试"板块里拆出来单独成一个可折叠板块，位置本身没有挪动）。
        add(root, line(), 14);
        LinearLayout boardFloatSettings = new LinearLayout(this);
        boardFloatSettings.setOrientation(LinearLayout.VERTICAL);
        addCollapsibleBoard(root, "float_settings", "悬浮窗设置", boardFloatSettings);

        // 是否显示来电号码（v3.13 新增，默认"是"）：控制悬浮窗第二行（号码）显隐
        add(boardFloatSettings, title("是否显示来电号码：", 13, 0xFFAAAAAA), 14);
        RadioGroup rgShowNumber = new RadioGroup(this);
        rgShowNumber.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbShowNumYes = radioBtn("是");
        RadioButton rbShowNumNo  = radioBtn("否");
        rgShowNumber.addView(rbShowNumYes);
        rgShowNumber.addView(rbShowNumNo);
        (ModuleSettings.isShowCallerNumber(this) ? rbShowNumYes : rbShowNumNo).setChecked(true);
        boolean[] suppressShowNumber = {false};
        rgShowNumber.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressShowNumber[0]) { suppressShowNumber[0] = false; return; }
            boolean newVal = checkedId == rbShowNumYes.getId();
            if (!newVal && wouldHideEverything(false,
                    ModuleSettings.isShowQueryResult(this), ModuleSettings.isWebQueryEnabled(this))) {
                confirmHideAllThenApply(
                        () -> { ModuleSettings.setShowCallerNumber(this, false); notifySettingsChanged(); },
                        () -> { suppressShowNumber[0] = true; rbShowNumYes.setChecked(true); });
                return;
            }
            ModuleSettings.setShowCallerNumber(this, newVal);
            notifySettingsChanged(); // v3.17：设置实时生效
        });
        add(boardFloatSettings, rgShowNumber, 4);

        // 是否显示查询结果（v3.15 新增，默认"是"）：控制悬浮窗第一行（标签/查询结果）显隐
        add(boardFloatSettings, title("是否显示查询结果：", 13, 0xFFAAAAAA), 14);
        RadioGroup rgShowQueryResult = new RadioGroup(this);
        rgShowQueryResult.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbShowResultYes = radioBtn("是");
        RadioButton rbShowResultNo  = radioBtn("否");
        rgShowQueryResult.addView(rbShowResultYes);
        rgShowQueryResult.addView(rbShowResultNo);
        (ModuleSettings.isShowQueryResult(this) ? rbShowResultYes : rbShowResultNo).setChecked(true);
        boolean[] suppressShowResult = {false};
        rgShowQueryResult.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressShowResult[0]) { suppressShowResult[0] = false; return; }
            boolean newVal = checkedId == rbShowResultYes.getId();
            if (!newVal && wouldHideEverything(
                    ModuleSettings.isShowCallerNumber(this), false, ModuleSettings.isWebQueryEnabled(this))) {
                confirmHideAllThenApply(
                        () -> { ModuleSettings.setShowQueryResult(this, false); notifySettingsChanged(); },
                        () -> { suppressShowResult[0] = true; rbShowResultYes.setChecked(true); });
                return;
            }
            ModuleSettings.setShowQueryResult(this, newVal);
            notifySettingsChanged(); // v3.17：设置实时生效
        });
        add(boardFloatSettings, rgShowQueryResult, 4);

        // 查询结果显示行数（v3.19 新增，默认"一行"）：控制悬浮窗第一行（查询结果/标签）
        // 是显示一行截断，还是允许换到两行再截断
        add(boardFloatSettings, title(
                "查询结果显示：选择「一行」时，查询结果超过显示的部分会被截断。"
              + "选择「两行」时，查询结果超过显示的部分会分成两行显示。",
                12, 0xFF777777), 14);
        RadioGroup rgQueryResultLines = new RadioGroup(this);
        rgQueryResultLines.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbLinesOne = radioBtn("一行");
        RadioButton rbLinesTwo = radioBtn("两行");
        rgQueryResultLines.addView(rbLinesOne);
        rgQueryResultLines.addView(rbLinesTwo);
        (ModuleSettings.getQueryResultLines(this) == 2 ? rbLinesTwo : rbLinesOne).setChecked(true);
        rgQueryResultLines.setOnCheckedChangeListener((group, checkedId) -> {
            ModuleSettings.setQueryResultLines(this, checkedId == rbLinesTwo.getId() ? 2 : 1);
            notifySettingsChanged(); // 设置实时生效
        });
        add(boardFloatSettings, rgQueryResultLines, 4);

        // ── 悬浮窗背景 ────────────────────────────────────────────────
        // v3.17：字体大小不再单独设置，改为直接跟随悬浮窗宽度联动（拖角缩放），
        // 原来这里的"悬浮窗字体大小"四档单选整块移除。
        add(boardFloatSettings, title("悬浮窗背景：", 13, 0xFFAAAAAA), 14);
        RadioGroup rgBg = new RadioGroup(this);
        rgBg.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbBgTranslucent = radioBtn("半透明黑");
        RadioButton rbBgWhite       = radioBtn("半透明白");
        RadioButton rbBgTransparent = radioBtn("全透明");
        rgBg.addView(rbBgTranslucent);
        rgBg.addView(rbBgWhite);
        rgBg.addView(rbBgTransparent);
        int savedBgStyle = ModuleSettings.getFloatBgStyle(this);
        RadioButton checkedBg = (savedBgStyle == ModuleSettings.BG_STYLE_TRANSPARENT) ? rbBgTransparent
                : (savedBgStyle == ModuleSettings.BG_STYLE_TRANSLUCENT_WHITE) ? rbBgWhite
                : rbBgTranslucent;
        checkedBg.setChecked(true);
        rgBg.setOnCheckedChangeListener((group, checkedId) -> {
            int style = (checkedId == rbBgTransparent.getId()) ? ModuleSettings.BG_STYLE_TRANSPARENT
                    : (checkedId == rbBgWhite.getId())          ? ModuleSettings.BG_STYLE_TRANSLUCENT_WHITE
                    : ModuleSettings.BG_STYLE_TRANSLUCENT_BLACK;
            ModuleSettings.setFloatBgStyle(this, style);
            notifySettingsChanged(); // v3.17：设置实时生效
        });
        add(boardFloatSettings, rgBg, 4);

        // ── 悬浮窗字体大小模式（v3.19 新增，重新引入独立字号设置） ────────────
        // "跟随窗口自动"（默认）：完全维持 v3.17 起的行为，字号跟随悬浮窗宽度
        // 联动，下面的档位选择不生效。
        // "固定大小"：忽略宽度联动，字号只由档位（小/中/大/特大）决定，宽度
        // 改变（拖角缩放或自然撑开）不会再影响字号。"大"档对齐现在的默认
        // 字号，选"自动"（默认）的人视觉上不会有任何变化。
        // 两者是"二选一模式开关"而不是叠加，从根源上避免"档位 × 宽度"打架。
        add(boardFloatSettings, title("字体大小模式：", 13, 0xFFAAAAAA), 14);
        RadioGroup rgFontSizeMode = new RadioGroup(this);
        rgFontSizeMode.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbFontModeAuto  = radioBtn("跟随窗口自动");
        RadioButton rbFontModeFixed = radioBtn("固定大小");
        rgFontSizeMode.addView(rbFontModeAuto);
        rgFontSizeMode.addView(rbFontModeFixed);
        (ModuleSettings.getFontSizeMode(this) == ModuleSettings.FONT_SIZE_MODE_FIXED
                ? rbFontModeFixed : rbFontModeAuto).setChecked(true);
        add(boardFloatSettings, rgFontSizeMode, 4);

        RadioGroup rgFontSizeLevel = new RadioGroup(this);
        rgFontSizeLevel.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbFontLevelSmall  = radioBtn("小");
        RadioButton rbFontLevelMedium = radioBtn("中");
        RadioButton rbFontLevelLarge  = radioBtn("大");
        RadioButton rbFontLevelXLarge = radioBtn("特大");
        rgFontSizeLevel.addView(rbFontLevelSmall);
        rgFontSizeLevel.addView(rbFontLevelMedium);
        rgFontSizeLevel.addView(rbFontLevelLarge);
        rgFontSizeLevel.addView(rbFontLevelXLarge);
        int savedFontLevel = ModuleSettings.getFontSizeLevel(this);
        RadioButton checkedFontLevel =
                (savedFontLevel == ModuleSettings.FONT_SIZE_LEVEL_SMALL)  ? rbFontLevelSmall
              : (savedFontLevel == ModuleSettings.FONT_SIZE_LEVEL_MEDIUM) ? rbFontLevelMedium
              : (savedFontLevel == ModuleSettings.FONT_SIZE_LEVEL_XLARGE) ? rbFontLevelXLarge
              : rbFontLevelLarge;
        checkedFontLevel.setChecked(true);
        rgFontSizeLevel.setVisibility(rbFontModeFixed.isChecked() ? View.VISIBLE : View.GONE);
        rgFontSizeLevel.setOnCheckedChangeListener((group, checkedId) -> {
            int level = (checkedId == rbFontLevelSmall.getId())  ? ModuleSettings.FONT_SIZE_LEVEL_SMALL
                      : (checkedId == rbFontLevelMedium.getId()) ? ModuleSettings.FONT_SIZE_LEVEL_MEDIUM
                      : (checkedId == rbFontLevelXLarge.getId()) ? ModuleSettings.FONT_SIZE_LEVEL_XLARGE
                      : ModuleSettings.FONT_SIZE_LEVEL_LARGE;
            ModuleSettings.setFontSizeLevel(this, level);
            notifySettingsChanged(); // 设置实时生效
        });
        add(boardFloatSettings, rgFontSizeLevel, 4);

        rgFontSizeMode.setOnCheckedChangeListener((group, checkedId) -> {
            boolean fixed = checkedId == rbFontModeFixed.getId();
            ModuleSettings.setFontSizeMode(this,
                    fixed ? ModuleSettings.FONT_SIZE_MODE_FIXED : ModuleSettings.FONT_SIZE_MODE_AUTO);
            rgFontSizeLevel.setVisibility(fixed ? View.VISIBLE : View.GONE);
            notifySettingsChanged(); // 设置实时生效
        });

        // 外观类设置（悬浮窗背景/字体大小模式）与下面行为类设置（是否可移动/
        // 是否可拖角缩放）之间加一条分割线，便于区分（v3.19 新增）
        add(boardFloatSettings, line(), 14);

        // ── 是否可移动（v3.18 新增） ──────────────────────────────────
        // 可移动=true（默认）：维持原有拖动+双击折叠逻辑不变。
        // 可移动=false：悬浮窗本体不可拖动，单击/双击"悬浮窗空白区域"都能折叠成球；
        // 小球本身始终可拖动，四角拖角缩放热区始终可用，两者都不受此开关影响。
        // 设置实时生效（FloatWindowService 每次触摸时直接读取，见
        // attachDragBehavior()），不需要额外广播通知。
        add(boardFloatSettings, title("是否可移动：", 13, 0xFFAAAAAA), 14);
        RadioGroup rgFloatMovable = new RadioGroup(this);
        rgFloatMovable.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbMovableYes = radioBtn("是");
        RadioButton rbMovableNo  = radioBtn("否");
        rgFloatMovable.addView(rbMovableYes);
        rgFloatMovable.addView(rbMovableNo);
        (ModuleSettings.isFloatMovable(this) ? rbMovableYes : rbMovableNo).setChecked(true);
        rgFloatMovable.setOnCheckedChangeListener((group, checkedId) ->
                ModuleSettings.setFloatMovable(this, checkedId == rbMovableYes.getId()));
        add(boardFloatSettings, rgFloatMovable, 4);

        add(boardFloatSettings, title(
                "当悬浮窗不能移动时，单击和双击「悬浮窗空白区域」区域，悬浮窗折叠成小球。\n"
              + "当悬浮窗可以移动时，双击「悬浮窗空白区域」，悬浮窗折叠成小球。\n"
              + "点击小球可再次展开悬浮窗。\n"
              + "建议先把悬浮窗设置好不会遮挡的位置，再设置成不可移动，单击就可折叠。",
                12, 0xFF777777), 6);

        // ── 是否可拖动四个角缩放悬浮窗（v3.19 新增） ──────────────────────
        // 可缩放=true（默认）：维持现有拖角缩放逻辑不变。
        // 可缩放=false：四个角的拖拽热区整个隐藏并停用，已经调好的宽度/高度
        // 不受影响、不会被重置；和"是否可移动"是完全正交的两个开关，互不干扰，
        // 四种组合都能正常成立。设置实时生效（FloatWindowService 每次触摸四角
        // 热区时直接读取），不需要额外广播通知。
        add(boardFloatSettings, title("是否可拖动四个角缩放悬浮窗：", 13, 0xFFAAAAAA), 14);
        RadioGroup rgFloatResizable = new RadioGroup(this);
        rgFloatResizable.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbResizableYes = radioBtn("是");
        RadioButton rbResizableNo  = radioBtn("否");
        rgFloatResizable.addView(rbResizableYes);
        rgFloatResizable.addView(rbResizableNo);
        (ModuleSettings.isFloatResizable(this) ? rbResizableYes : rbResizableNo).setChecked(true);
        rgFloatResizable.setOnCheckedChangeListener((group, checkedId) ->
                ModuleSettings.setFloatResizable(this, checkedId == rbResizableYes.getId()));
        add(boardFloatSettings, rgFloatResizable, 4);

        add(boardFloatSettings, title(
                "建议先拖角把悬浮窗大小调好，再关闭这里锁死，防止手滑碰到误改大小。",
                12, 0xFF777777), 6);

        // ── 百度号码解析开关（v3.13 新增，v3.19 板块改名："百度静默查询"→"百度号码解析"） ──
        // 只影响查询链路最后一步（联网查询 mhaoma.baidu.com）：关闭后本地
        // 自定义库/缓存/白名单/内置库命中的号码完全不受影响，仅当本地未命中
        // 时不再联网查询，直接显示"未知号码"。
        add(root, line(), 14);
        LinearLayout boardBaiduSilent = new LinearLayout(this);
        boardBaiduSilent.setOrientation(LinearLayout.VERTICAL);
        addCollapsibleBoard(root, "baidu_silent", "百度号码解析", boardBaiduSilent);
        add(boardBaiduSilent, title(
                "关闭后：自定义号码/联网缓存/特殊号码白名单/内置企业库命中的号码正常显示，"
              + "不受影响；仅当以上都未命中时，不再联网查询百度，直接显示「未知号码」。",
                12, 0xFF777777), 6);
        RadioGroup rgBaiduSilent = new RadioGroup(this);
        rgBaiduSilent.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbBaiduOn  = radioBtn("启用");
        RadioButton rbBaiduOff = radioBtn("关闭");
        rgBaiduSilent.addView(rbBaiduOn);
        rgBaiduSilent.addView(rbBaiduOff);
        (ModuleSettings.isBaiduSilentQueryEnabled(this) ? rbBaiduOn : rbBaiduOff).setChecked(true);
        rgBaiduSilent.setOnCheckedChangeListener((group, checkedId) ->
                ModuleSettings.setBaiduSilentQueryEnabled(this, checkedId == rbBaiduOn.getId()));
        add(boardBaiduSilent, rgBaiduSilent, 4);

        // ── SpamBlocker 联动（v3.19 新增） ──────────────────────────────
        // 本板块只负责起停一个只监听 127.0.0.1 的本地 HTTP 查询服务（见
        // QueryServerService / LocalQueryServer），本身不会主动联系 SpamBlocker。
        // 真正联动需要在 SpamBlocker 那边配置「即时查询」，见下方示例 URL 说明。
        add(root, line(), 14);
        LinearLayout boardSbLinkage = new LinearLayout(this);
        boardSbLinkage.setOrientation(LinearLayout.VERTICAL);
        addCollapsibleBoard(root, "sb_linkage", "SpamBlocker 联动", boardSbLinkage);

        LinearLayout boardSbPrinciple = new LinearLayout(this);
        boardSbPrinciple.setOrientation(LinearLayout.VERTICAL);
        add(boardSbPrinciple, title(
                "原理：SpamBlocker 来电时主动向本机发起 HTTP 请求，本模块复用查询链路\n"
              + "（自定义库/缓存/白名单/内置库/联网查询）拿到号码标签，返回给 SpamBlocker\n"
              + "做拦截判断。方向是 SpamBlocker 主动来问，不是本模块主动推送。\n"
              + "需要两边各配置一次：① 下面先启动本地查询服务器；② 复制示例 URL，\n"
              + "去 SpamBlocker「即时查询」新增一条 API，把 URL 填进去即可。",
                13, 0xFFFF9900), 0);
        addWarningFold(boardSbLinkage, "sb_linkage_principle", "SpamBlocker拦截原理", boardSbPrinciple, 6);

        Button btnSbStart = btn("▶ 启动本地查询服务器", 0xFF2E7D32);
        add(boardSbLinkage, btnSbStart, 10);
        Button btnSbStop = btn("⏹ 停止本地查询服务器", 0xFFB71C1C);
        add(boardSbLinkage, btnSbStop, 6);

        TextView tvSbStatus = new TextView(this);
        tvSbStatus.setTextSize(14);
        add(boardSbLinkage, tvSbStatus, 6);
        Runnable refreshSbStatus = () -> {
            boolean running = isQueryServerRunning();
            setStatusText(tvSbStatus, running, "运行中", "已停止");
            setStatusText(tvTopSbLinkage, "SpamBlocker联动：", running, "运行中", "已停止");
        };
        refreshSbStatus.run();

        btnSbStart.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("确认")
                .setMessage("确定启动本地查询服务器吗？")
                .setPositiveButton("确定", (d, w) -> {
                    startService(new Intent(this, QueryServerService.class));
                    toast("本地查询服务已启动");
                    new Handler(Looper.getMainLooper()).postDelayed(refreshSbStatus, 200);
                })
                .setNegativeButton("取消", null)
                .show());
        btnSbStop.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("确认")
                .setMessage("确定停止本地查询服务器吗？")
                .setPositiveButton("确定", (d, w) -> {
                    stopService(new Intent(this, QueryServerService.class));
                    toast("本地查询服务已停止");
                    new Handler(Looper.getMainLooper()).postDelayed(refreshSbStatus, 200);
                })
                .setNegativeButton("取消", null)
                .show());

        add(boardSbLinkage, title("开机是否自动启动本地查询服务器：", 13, 0xFFAAAAAA), 14);
        RadioGroup rgSbAutostart = new RadioGroup(this);
        rgSbAutostart.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbSbAutoYes = radioBtn("是");
        RadioButton rbSbAutoNo  = radioBtn("否");
        rgSbAutostart.addView(rbSbAutoYes);
        rgSbAutostart.addView(rbSbAutoNo);
        (ModuleSettings.isSbAutostart(this) ? rbSbAutoYes : rbSbAutoNo).setChecked(true);
        rgSbAutostart.setOnCheckedChangeListener((group, checkedId) ->
                ModuleSettings.setSbAutostart(this, checkedId == rbSbAutoYes.getId()));
        add(boardSbLinkage, rgSbAutostart, 4);

        add(boardSbLinkage, title(
                "很多定制系统（小米/华为/OPPO等）会清理这类普通后台 Service，\n"
              + "建议给本 App 加「无限制后台/自启动」白名单，否则可能过一段时间自动停止。",
                12, 0xFF777777), 10);

        // 端口
        add(boardSbLinkage, title("本地服务端口（改动后需要重启服务才生效）：", 13, 0xFFAAAAAA), 14);
        EditText etSbPort = new EditText(this);
        etSbPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etSbPort.setText(String.valueOf(ModuleSettings.getSbPort(this)));
        etSbPort.setTextColor(Color.WHITE);
        etSbPort.setBackgroundColor(0xFF1E1E1E);
        etSbPort.setPadding(dp(12), dp(10), dp(12), dp(10));
        add(boardSbLinkage, etSbPort, 6);
        Button btnSbPortSave = btn("💾 保存端口并重启服务", 0xFF1565C0);
        add(boardSbLinkage, btnSbPortSave, 6);

        // token
        add(boardSbLinkage, title(
                "鉴权 token，防止本机其它 App 冒充查询。注意！此项无需填入SpamBlocker，"
              + "下面那串蓝色的“http://127.......”才需要整个填入SpamBlocker：",
                13, 0xFFAAAAAA), 14);
        TextView tvSbToken = title(ModuleSettings.getSbToken(this), 14, 0xFF44CC44);
        add(boardSbLinkage, tvSbToken, 6);
        Button btnSbTokenCopy = btn("📋 复制 token", 0xFF455A64);
        add(boardSbLinkage, btnSbTokenCopy, 6);
        Button btnSbTokenRegen = btn("🔄 重新生成 token", 0xFFB71C1C);
        add(boardSbLinkage, btnSbTokenRegen, 6);
        btnSbTokenCopy.setOnClickListener(v -> {
            copyText("token", tvSbToken.getText().toString());
            toast("token 已复制");
        });

        // 示例 URL（依赖端口/token，两者任一变化都要重算，所以抽成 Runnable）
        TextView tvSbUrl = title("", 13, 0xFF2196F3);
        add(boardSbLinkage, title("SpamBlocker「即时查询」HTTP 请求 URL 填这个（长按可选中复制）：", 13, 0xFFAAAAAA), 14);
        add(boardSbLinkage, tvSbUrl, 6);
        Runnable[] refreshSbUrlHolder = new Runnable[1];
        refreshSbUrlHolder[0] = () -> tvSbUrl.setText(
                "http://127.0.0.1:" + ModuleSettings.getSbPort(this)
                        + "/query?number={raw_number}&token=" + ModuleSettings.getSbToken(this));
        refreshSbUrlHolder[0].run();
        Button btnSbUrlCopy = btn("📋 复制示例 URL", 0xFF455A64);
        add(boardSbLinkage, btnSbUrlCopy, 6);
        btnSbUrlCopy.setOnClickListener(v -> {
            copyText("url", tvSbUrl.getText().toString());
            toast("URL 已复制");
        });
        add(boardSbLinkage, buildSbConfigStepsText(), 10);

        btnSbTokenRegen.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("确认")
                .setMessage("重新生成后，旧 token 立即失效，需要去 SpamBlocker 那边同步更新 URL，确定继续吗？")
                .setPositiveButton("确定", (d, w) -> {
                    String newToken = ModuleSettings.regenerateSbToken(this);
                    tvSbToken.setText(newToken);
                    refreshSbUrlHolder[0].run();
                    toast("token 已重新生成");
                })
                .setNegativeButton("取消", null)
                .show());

        btnSbPortSave.setOnClickListener(v -> {
            String portStr = etSbPort.getText().toString().trim();
            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                toast("端口必须是数字");
                return;
            }
            if (port < 1024 || port > 65535) {
                toast("端口范围需在 1024~65535 之间");
                return;
            }
            ModuleSettings.setSbPort(this, port);
            etSbPort.setText(String.valueOf(port));
            refreshSbUrlHolder[0].run();
            boolean wasRunning = isQueryServerRunning();
            if (wasRunning) {
                stopService(new Intent(this, QueryServerService.class));
                startService(new Intent(this, QueryServerService.class));
            }
            toast(wasRunning ? "端口已保存，服务已重启" : "端口已保存");
            new Handler(Looper.getMainLooper()).postDelayed(refreshSbStatus, 200);
        });

        // 超时
        add(boardSbLinkage, title("单次查询超时（毫秒，建议略小于 SpamBlocker 决策窗口 4~5 秒）：", 13, 0xFFAAAAAA), 14);
        EditText etSbTimeout = new EditText(this);
        etSbTimeout.setInputType(InputType.TYPE_CLASS_NUMBER);
        etSbTimeout.setText(String.valueOf(ModuleSettings.getSbTimeoutMs(this)));
        etSbTimeout.setTextColor(Color.WHITE);
        etSbTimeout.setBackgroundColor(0xFF1E1E1E);
        etSbTimeout.setPadding(dp(12), dp(10), dp(12), dp(10));
        add(boardSbLinkage, etSbTimeout, 6);
        Button btnSbTimeoutSave = btn("💾 保存超时设置", 0xFF1565C0);
        add(boardSbLinkage, btnSbTimeoutSave, 6);
        btnSbTimeoutSave.setOnClickListener(v -> {
            int timeoutMs;
            try {
                timeoutMs = Integer.parseInt(etSbTimeout.getText().toString().trim());
            } catch (NumberFormatException e) {
                toast("超时必须是数字");
                return;
            }
            ModuleSettings.setSbTimeoutMs(this, timeoutMs);
            etSbTimeout.setText(String.valueOf(ModuleSettings.getSbTimeoutMs(this)));
            toast("超时设置已保存（立即生效，无需重启服务）");
        });

        // 骚扰关键词
        add(boardSbLinkage, title(
                "命中以下任意关键词就判定为 is_spam=true（逗号分隔，可自行增删）：",
                13, 0xFFAAAAAA), 14);
        EditText etSbKeywords = new EditText(this);
        etSbKeywords.setText(ModuleSettings.getSbKeywords(this));
        etSbKeywords.setTextColor(Color.WHITE);
        etSbKeywords.setBackgroundColor(0xFF1E1E1E);
        etSbKeywords.setPadding(dp(12), dp(10), dp(12), dp(10));
        etSbKeywords.setMinLines(2);
        add(boardSbLinkage, etSbKeywords, 6);
        Button btnSbKeywordsSave = btn("💾 保存关键词", 0xFF1565C0);
        add(boardSbLinkage, btnSbKeywordsSave, 6);
        btnSbKeywordsSave.setOnClickListener(v -> {
            ModuleSettings.setSbKeywords(this, etSbKeywords.getText().toString());
            toast("关键词已保存（立即生效，无需重启服务）");
        });

        // 测试查询：不依赖 SpamBlocker，直接调本地服务验证联动通不通
        add(boardSbLinkage, title("测试查询（直接调本地服务，跳过 SpamBlocker，用来验证联动本身通不通）：", 13, 0xFFAAAAAA), 14);
        EditText etSbTestNumber = new EditText(this);
        etSbTestNumber.setHint("输入号码，如 17896436252");
        etSbTestNumber.setHintTextColor(0xFF555555);
        etSbTestNumber.setTextColor(Color.WHITE);
        etSbTestNumber.setInputType(InputType.TYPE_CLASS_PHONE);
        etSbTestNumber.setBackgroundColor(0xFF1E1E1E);
        etSbTestNumber.setPadding(dp(12), dp(10), dp(12), dp(10));
        etSbTestNumber.setText("17896436252"); // v4.5 新增：跟「模拟来电测试」保持一致，默认填入示例号码
        add(boardSbLinkage, etSbTestNumber, 6);
        Button btnSbTest = btn("🧪 发起测试查询", 0xFF00897B);
        add(boardSbLinkage, btnSbTest, 6);
        TextView tvSbTestResult = title("", 13, 0xFFCCCCCC);
        add(boardSbLinkage, tvSbTestResult, 6);
        btnSbTest.setOnClickListener(v -> {
            String num = etSbTestNumber.getText().toString().trim();
            if (num.isEmpty()) { toast("请先输入号码"); return; }
            if (!isQueryServerRunning()) {
                tvSbTestResult.setText("本地查询服务未运行，请先点上面的「启动本地查询服务器」");
                return;
            }
            tvSbTestResult.setText("查询中…");
            int port = ModuleSettings.getSbPort(this);
            String token = ModuleSettings.getSbToken(this);
            new Thread(() -> {
                String result = testQueryLocalServer(port, token, num);
                runOnUiThread(() -> tvSbTestResult.setText(result));
            }, "CallerID-SbTestQuery").start();
        });

        // ── 悬浮窗内打开网页查询功能（v1.8 新增） ──────────────────────────
        add(root, line(), 14);
        LinearLayout boardWebQuery = new LinearLayout(this);
        boardWebQuery.setOrientation(LinearLayout.VERTICAL);
        addCollapsibleBoard(root, "web_query", "悬浮窗网页查询", boardWebQuery);
        add(boardWebQuery, title(
                "开启后，悬浮窗「标签/号码」下方会新增一个按钮，点击可展开一个固定高度、"
              + "可正常滚动点击的真实网页（搜狗/360/自定义），查看更详细的搜索结果。\n"
              + "默认关闭，不影响现有悬浮窗效果；关闭时下面这些设置会整体收起。",
                12, 0xFF777777), 6);

        CheckBox cbWebQueryEnabled = new CheckBox(this);
        cbWebQueryEnabled.setText("悬浮窗内打开网页查询功能");
        cbWebQueryEnabled.setTextColor(Color.WHITE);
        cbWebQueryEnabled.setTextSize(14);
        cbWebQueryEnabled.setChecked(ModuleSettings.isWebQueryEnabled(this));
        add(boardWebQuery, cbWebQueryEnabled, 8);

        LinearLayout webSubContainer = new LinearLayout(this);
        webSubContainer.setOrientation(LinearLayout.VERTICAL);
        webSubContainer.setVisibility(cbWebQueryEnabled.isChecked() ? View.VISIBLE : View.GONE);

        boolean[] suppressWebQuery = {false};
        cbWebQueryEnabled.setOnCheckedChangeListener((btnView, isChecked) -> {
            if (suppressWebQuery[0]) { suppressWebQuery[0] = false; return; }
            if (!isChecked && wouldHideEverything(
                    ModuleSettings.isShowCallerNumber(this), ModuleSettings.isShowQueryResult(this), false)) {
                confirmHideAllThenApply(
                        () -> {
                            ModuleSettings.setWebQueryEnabled(MainActivity.this, false);
                            webSubContainer.setVisibility(View.GONE);
                        },
                        () -> { suppressWebQuery[0] = true; cbWebQueryEnabled.setChecked(true); });
                return;
            }
            ModuleSettings.setWebQueryEnabled(MainActivity.this, isChecked);
            webSubContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // "默认展开网页"选项（v3.15 新增）：勾选后悬浮窗一出现就自动展开网页区域，
        // 不需要再点一次按钮；默认不勾选（保持原行为）。归属在总开关下面，
        // 总开关关闭时随 webSubContainer 一起隐藏。
        CheckBox cbWebQueryDefaultExpanded = new CheckBox(this);
        cbWebQueryDefaultExpanded.setText("默认展开网页");
        cbWebQueryDefaultExpanded.setTextColor(Color.WHITE);
        cbWebQueryDefaultExpanded.setTextSize(14);
        cbWebQueryDefaultExpanded.setChecked(ModuleSettings.isWebQueryDefaultExpanded(this));
        cbWebQueryDefaultExpanded.setOnCheckedChangeListener((btnView, isChecked) ->
                ModuleSettings.setWebQueryDefaultExpanded(MainActivity.this, isChecked));
        add(webSubContainer, cbWebQueryDefaultExpanded, 8);

        // 网页字体大小：跟随系统 / 80% / 100% / 120% / 150%
        // 一行放不下会在部分手机上超出屏幕变形，改成每行两个、用 RadioButton 各自独立
        // 手动维护单选互斥（不再用单个 RadioGroup 横向排列）
        add(webSubContainer, title("网页字体大小：", 13, 0xFFAAAAAA), 4);
        String[] webFontLabels = {"跟随系统", "80%", "100%", "120%", "150%"};
        RadioButton[] webFontBtns = new RadioButton[webFontLabels.length];
        for (int i = 0; i < webFontLabels.length; i++) {
            webFontBtns[i] = radioBtn(webFontLabels[i]);
        }
        int savedWebFontTier = ModuleSettings.getWebFontZoom(this);
        if (savedWebFontTier < 0 || savedWebFontTier >= webFontBtns.length) {
            savedWebFontTier = ModuleSettings.WEB_FONT_ZOOM_SYSTEM;
        }
        webFontBtns[savedWebFontTier].setChecked(true);
        for (int i = 0; i < webFontBtns.length; i++) {
            final int tier = i;
            webFontBtns[i].setOnClickListener(v -> {
                for (RadioButton rb : webFontBtns) rb.setChecked(rb == webFontBtns[tier]);
                ModuleSettings.setWebFontZoom(this, tier);
            });
        }
        for (int i = 0; i < webFontBtns.length; i += 2) {
            LinearLayout rowWebFont = new LinearLayout(this);
            rowWebFont.setOrientation(LinearLayout.HORIZONTAL);
            rowWebFont.addView(webFontBtns[i]);
            if (i + 1 < webFontBtns.length) rowWebFont.addView(webFontBtns[i + 1]);
            add(webSubContainer, rowWebFont, 2);
        }

        // 网页深色模式（v1.10 新增，v3.13 新增"强制反色"第4档）：
        // 跟随系统 / 浅色 / 深色 / 强制反色，三个来源共用同一档位。
        // 排版复用"网页字体大小"那套手动两列 RadioButton 写法（一行两个，避免横排挤爆）。
        add(webSubContainer, title("网页深色模式：", 13, 0xFFAAAAAA), 14);
        String[] darkLabels = {"跟随系统", "浅色", "深色", "强制反色"};
        int[] darkValues = {
                ModuleSettings.WEB_DARK_MODE_SYSTEM,
                ModuleSettings.WEB_DARK_MODE_LIGHT,
                ModuleSettings.WEB_DARK_MODE_DARK,
                ModuleSettings.WEB_DARK_MODE_FORCE_INVERT
        };
        RadioButton[] darkBtns = new RadioButton[darkLabels.length];
        for (int i = 0; i < darkLabels.length; i++) {
            darkBtns[i] = radioBtn(darkLabels[i]);
        }
        int savedDarkMode = ModuleSettings.getWebDarkMode(this);
        int savedDarkIdx = 0;
        for (int i = 0; i < darkValues.length; i++) {
            if (darkValues[i] == savedDarkMode) { savedDarkIdx = i; break; }
        }
        darkBtns[savedDarkIdx].setChecked(true);
        for (int i = 0; i < darkBtns.length; i++) {
            final int idx = i;
            darkBtns[i].setOnClickListener(v -> {
                for (RadioButton rb : darkBtns) rb.setChecked(rb == darkBtns[idx]);
                ModuleSettings.setWebDarkMode(this, darkValues[idx]);
            });
        }
        for (int i = 0; i < darkBtns.length; i += 2) {
            LinearLayout rowDark = new LinearLayout(this);
            rowDark.setOrientation(LinearLayout.HORIZONTAL);
            rowDark.addView(darkBtns[i]);
            if (i + 1 < darkBtns.length) rowDark.addView(darkBtns[i + 1]);
            add(webSubContainer, rowDark, 2);
        }

        // 查询来源：搜狗 / 360 / 自定义（三选一）
        add(webSubContainer, title("查询来源：", 13, 0xFFAAAAAA), 14);
        RadioGroup rgWebSource = new RadioGroup(this);
        rgWebSource.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbSogou  = radioBtn("搜狗", COLOR_SOURCE_SOGOU);
        RadioButton rb360    = radioBtn("360", COLOR_SOURCE_360);
        RadioButton rbCustom = radioBtn("自定义", COLOR_SOURCE_CUSTOM);
        rgWebSource.addView(rbSogou);
        rgWebSource.addView(rb360);
        rgWebSource.addView(rbCustom);
        RadioButton[] sourceBtns = {rbSogou, rb360, rbCustom};
        int savedSource = ModuleSettings.getWebQuerySource(this);
        if (savedSource < 0 || savedSource >= sourceBtns.length) {
            savedSource = ModuleSettings.WEB_SOURCE_SOGOU;
        }
        sourceBtns[savedSource].setChecked(true);
        add(webSubContainer, rgWebSource, 4);

        add(webSubContainer, title(
                "搜狗：" + ModuleSettings.URL_SOGOU + "\n"
              + "360：" + ModuleSettings.URL_360 + "\n"
              + "（以上两个网址固定内置，不可编辑）",
                11, 0xFF555555), 6);

        // 自定义网址：仅当上面选择"自定义"时生效；只有选中"自定义"才显示这一整块
        // （v3.15 新增：选搜狗/360 时隐藏，避免误以为需要额外配置。
        //  v3.18：位置从"网页展示高度"下方挪到这里——紧跟在"两个内置网址不可编辑"
        //  说明之后、"网页顶部裁剪"之前，三块内容（内置网址说明/自定义网址/顶部裁剪）
        //  按"查询来源相关设置放一起"的顺序排列，更符合阅读逻辑）
        LinearLayout customUrlSection = new LinearLayout(this);
        customUrlSection.setOrientation(LinearLayout.VERTICAL);
        customUrlSection.setVisibility(rbCustom.isChecked() ? View.VISIBLE : View.GONE);

        add(customUrlSection, title(
                "自定义网址（选中「自定义」时生效，网址中用「来电号码」这4个字作为占位词，"
              + "程序会自动替换成实际来电号码）：",
                13, 0xFFAAAAAA), 14);

        LinearLayout rowCustomUrl = new LinearLayout(this);
        rowCustomUrl.setOrientation(LinearLayout.HORIZONTAL);

        EditText etCustomUrl = new EditText(this);
        etCustomUrl.setHint("例如：https://example.com/s?q=来电号码");
        etCustomUrl.setHintTextColor(0xFF555555);
        etCustomUrl.setTextColor(Color.WHITE);
        etCustomUrl.setTextSize(13);
        etCustomUrl.setText(ModuleSettings.getWebQueryCustomUrl(this));
        etCustomUrl.setBackgroundColor(0xFF1E1E1E);
        etCustomUrl.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lpCustomUrl = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rowCustomUrl.addView(etCustomUrl, lpCustomUrl);

        Button btnSaveCustomUrl = btn("保存", COLOR_SOURCE_CUSTOM); // 只在选中"自定义"时可见，固定用自定义的蓝色
        LinearLayout.LayoutParams lpSaveBtn = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpSaveBtn.leftMargin = dp(8);
        btnSaveCustomUrl.setOnClickListener(v -> {
            String url = etCustomUrl.getText().toString().trim();
            ModuleSettings.setWebQueryCustomUrl(this, url);
            if (!url.isEmpty() && !url.contains("来电号码")) {
                toast("已保存（提醒：网址中未找到「来电号码」占位词，可能无法正确替换成来电号码）");
            } else {
                toast("已保存");
            }
        });
        rowCustomUrl.addView(btnSaveCustomUrl, lpSaveBtn);

        add(customUrlSection, rowCustomUrl, 6);
        add(webSubContainer, customUrlSection, 0);

        // 网页顶部裁剪（v1.9 新增，v3.18 拆分为三来源各自独立值）：把网页顶部固定的
        // 搜索框/标签栏区域裁掉不显示。三个来源（搜狗/360/自定义）现在各自一份独立
        // 存储的值；UI 上只保留一个输入框，跟随上面"查询来源"单选框联动——选中哪个
        // 来源，这个输入框就显示/编辑哪个来源的值，切换来源时输入框内容跟着换
        // （和"自定义网址"块"只有选中自定义才显示"是同一种模式）。0 或留空 = 不裁剪。
        add(webSubContainer, title(
                "网页顶部裁剪（像素）：把网页最上方固定不动的搜索框/标签栏区域裁掉不显示，"
              + "搜狗/360/自定义三个来源各自独立设置，此输入框跟随上面选中的来源切换，"
              + "填 0 或留空则不裁剪。搜狗/360 默认 250，自定义默认 0。",
                13, 0xFFAAAAAA), 14);

        LinearLayout rowCropPx = new LinearLayout(this);
        rowCropPx.setOrientation(LinearLayout.HORIZONTAL);

        EditText etCropPx = new EditText(this);
        etCropPx.setHint("例如：250");
        etCropPx.setHintTextColor(0xFF555555);
        etCropPx.setTextColor(Color.WHITE);
        etCropPx.setTextSize(13);
        etCropPx.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        etCropPx.setText(String.valueOf(ModuleSettings.getWebTopCropPx(this, savedSource)));
        etCropPx.setBackgroundColor(0xFF1E1E1E);
        etCropPx.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lpCropPx = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rowCropPx.addView(etCropPx, lpCropPx);

        Button btnSaveCropPx = btn("保存", colorForSource(savedSource)); // 跟随当前选中来源动态变色（见下方 rgWebSource 的切换监听器）
        LinearLayout.LayoutParams lpSaveCropBtn = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpSaveCropBtn.leftMargin = dp(8);
        btnSaveCropPx.setOnClickListener(v -> {
            int currentSource = ModuleSettings.getWebQuerySource(this); // 保存到"当前选中"的来源
            String text = etCropPx.getText().toString().trim();
            if (text.isEmpty()) {
                ModuleSettings.setWebTopCropPx(this, currentSource, 0);
                toast("已保存");
                return;
            }
            int val;
            try {
                val = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                val = -1;
            }
            if (val < 0) {
                ModuleSettings.setWebTopCropPx(this, currentSource, 0);
                etCropPx.setText("0");
                toast("输入无效值，画面不会裁切");
            } else {
                ModuleSettings.setWebTopCropPx(this, currentSource, val);
                toast("已保存");
            }
        });
        rowCropPx.addView(btnSaveCropPx, lpSaveCropBtn);

        add(webSubContainer, rowCropPx, 6);

        // 前面的"来源/自定义网址/顶部裁剪"和下面的"网页展示高度"之间加一条
        // 分割线，便于区分（v3.19 新增）
        add(webSubContainer, line(), 14);

        // 网页展示高度（v3.17 重做：原"网页底部裁剪"改为直接填绝对展示高度）：
        // 这个值和"拖角缩放"的纵向拖动是同一个存储值的两种录入方式——没有真实
        // 来电、想提前精确设置好的场景，可以直接在这里填数字；有真实来电时，
        // 也可以直接拖悬浮窗四个角来调，效果实时可见。允许范围
        // [WEB_HEIGHT_MIN_DP, 屏幕高度 × WEB_HEIGHT_MAX_RATIO]，超出范围会被自动
        // 钳制，不会报错。
        add(webSubContainer, title(
                "网页展示高度（像素）：网页查询区域展开后的高度，可以直接在这里填，"
              + "也可以在悬浮窗上拖角缩放调整（同一个值）。超出允许范围会自动调整到"
              + "范围边界，留空则不修改。",
                13, 0xFFAAAAAA), 14);

        LinearLayout rowWebHeightPx = new LinearLayout(this);
        rowWebHeightPx.setOrientation(LinearLayout.HORIZONTAL);

        EditText etWebHeightPx = new EditText(this);
        etWebHeightPx.setHint("例如：500");
        etWebHeightPx.setHintTextColor(0xFF555555);
        etWebHeightPx.setTextColor(Color.WHITE);
        etWebHeightPx.setTextSize(13);
        etWebHeightPx.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        etWebHeightPx.setText(String.valueOf(ModuleSettings.getWebHeightPx(this)));
        etWebHeightPx.setBackgroundColor(0xFF1E1E1E);
        etWebHeightPx.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lpWebHeightPx = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rowWebHeightPx.addView(etWebHeightPx, lpWebHeightPx);

        Button btnSaveWebHeightPx = btn("保存", COLOR_WEB_HEIGHT_BTN); // 三来源共用的全局值，不参与查询来源那套配色，固定紫色以示区分
        LinearLayout.LayoutParams lpSaveWebHeightBtn = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpSaveWebHeightBtn.leftMargin = dp(8);
        btnSaveWebHeightPx.setOnClickListener(v -> {
            String text = etWebHeightPx.getText().toString().trim();
            if (text.isEmpty()) {
                etWebHeightPx.setText(String.valueOf(ModuleSettings.getWebHeightPx(this)));
                toast("留空，未修改");
                return;
            }
            int val;
            try {
                val = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                val = -1;
            }
            if (val < 0) {
                etWebHeightPx.setText(String.valueOf(ModuleSettings.getWebHeightPx(this)));
                toast("输入无效值，已还原");
                return;
            }
            ModuleSettings.setWebHeightPx(this, val); // 内部会自动钳制到允许范围
            int actual = ModuleSettings.getWebHeightPx(this);
            etWebHeightPx.setText(String.valueOf(actual));
            toast(actual == val ? "已保存" : "已保存（超出范围，已自动调整为 " + actual + "）");
        });
        rowWebHeightPx.addView(btnSaveWebHeightPx, lpSaveWebHeightBtn);

        add(webSubContainer, rowWebHeightPx, 6);

        // 查询来源切换：更新自定义网址块显隐 + 顶部裁剪输入框显示对应来源的值/按钮变色
        // （v3.18：顶部裁剪按来源拆分独立值后，输入框内容要跟随来源联动切换；
        //  v3.19：保存按钮颜色也跟着换成当前选中来源对应的颜色）
        rgWebSource.setOnCheckedChangeListener((group, checkedId) -> {
            int source = (checkedId == rb360.getId())    ? ModuleSettings.WEB_SOURCE_360
                       : (checkedId == rbCustom.getId())  ? ModuleSettings.WEB_SOURCE_CUSTOM
                       : ModuleSettings.WEB_SOURCE_SOGOU;
            ModuleSettings.setWebQuerySource(this, source);
            customUrlSection.setVisibility(checkedId == rbCustom.getId() ? View.VISIBLE : View.GONE);
            etCropPx.setText(String.valueOf(ModuleSettings.getWebTopCropPx(this, source)));
            btnSaveCropPx.setBackgroundColor(colorForSource(source));
        });

        add(boardWebQuery, webSubContainer, 4);

        // ── 联网查询缓存管理 ──────────────────────────────────────────────
        add(root, line(), 14);
        LinearLayout boardCacheMgmt = new LinearLayout(this);
        boardCacheMgmt.setOrientation(LinearLayout.VERTICAL);
        addCollapsibleBoard(root, "cache_mgmt", "联网查询缓存管理", boardCacheMgmt);

        Button btnClearOne = btn("🔄 清除当前号码缓存（重新查询）", 0xFF1565C0);
        btnClearOne.setOnClickListener(v -> {
            String num = etTestNumber.getText().toString().trim();
            if (num.isEmpty()) { toast("请先在上方输入号码"); return; }
            new AlertDialog.Builder(this)
                    .setTitle("确认清除")
                    .setMessage("确定清除号码 " + num + " 的缓存吗？清除后下次来电将重新联网查询")
                    .setPositiveButton("清除", (d, w) -> {
                        CacheStore.remove(num);
                        toast("已清除 " + num + " 的缓存，下次将重新查询");
                        if (cacheSection != null) cacheSection.refresh();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        add(boardCacheMgmt, btnClearOne, 8);

        Button btnClearEmpty = btn("🗑 清除无效缓存（未知号码重新查询）", 0xFF37474F);
        btnClearEmpty.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("确认清除")
                .setMessage("确定清除无效缓存吗？清除后这些未知号码下次来电将重新联网查询")
                .setPositiveButton("清除", (d, w) -> {
                    CacheStore.clearEmpty();
                    toast("已清除无效缓存");
                    if (cacheSection != null) cacheSection.refresh();
                })
                .setNegativeButton("取消", null)
                .show());
        add(boardCacheMgmt, btnClearEmpty, 6);

        Button btnClearAll = btn("⚠ 清除全部联网查询缓存", 0xFF4E342E);
        btnClearAll.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("确认清除")
                .setMessage("确定清除全部联网查询缓存吗？此操作不可恢复，清除后所有号码下次来电都将重新联网查询")
                .setPositiveButton("清除", (d, w) -> {
                    CacheStore.clearAll();
                    toast("已清除全部联网查询缓存");
                    if (cacheSection != null) cacheSection.refresh();
                })
                .setNegativeButton("取消", null)
                .show());
        add(boardCacheMgmt, btnClearAll, 6);

        cacheSection = new ManagedListSection(
                boardCacheMgmt,
                "联网查询记录列表",
                new KVStore() {
                    @Override public Map<String, String> getAll() { return CacheStore.getAll(); }
                    @Override public void put(String key, String value) { CacheStore.put(key, value); }
                    @Override public void removeAll(Collection<String> keys) { CacheStore.removeAll(keys); }
                },
                (key, value) -> key + "   " + value, // 号码 + 查询结果
                "确定删除选中的 %d 条缓存记录吗？删除后下次来电会重新联网查询",
                "编辑查询结果"
        );
        cacheSection.build();

        // ── 自定义号码管理 ────────────────────────────────────────────────
        add(root, line(), 14);
        LinearLayout boardUserMgmt = new LinearLayout(this);
        boardUserMgmt.setOrientation(LinearLayout.VERTICAL);
        addCollapsibleBoard(root, "user_mgmt", "自定义号码管理", boardUserMgmt);

        add(boardUserMgmt, title(
                "(优先级最高，覆盖软件内置库/联网结果)\n"
              + "支持剪贴板批量导入，每行一个，名字和号码用英文,号隔开，如：\n"
              + "    张三,15555555555\n"
              + "    李四,16666666666\n"
              + "    王五,17777777777",
                12, 0xFF777777), 6);

        LinearLayout rowInput = new LinearLayout(this);
        rowInput.setOrientation(LinearLayout.HORIZONTAL);

        etUserName = new EditText(this);
        etUserName.setHint("姓名/标签");
        etUserName.setHintTextColor(0xFF555555);
        etUserName.setTextColor(Color.WHITE);
        etUserName.setTextSize(15);
        etUserName.setBackgroundColor(0xFF1E1E1E);
        etUserName.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lpName = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rowInput.addView(etUserName, lpName);

        etUserNumber = new EditText(this);
        etUserNumber.setHint("号码");
        etUserNumber.setHintTextColor(0xFF555555);
        etUserNumber.setTextColor(Color.WHITE);
        etUserNumber.setTextSize(15);
        etUserNumber.setInputType(InputType.TYPE_CLASS_PHONE);
        etUserNumber.setBackgroundColor(0xFF1E1E1E);
        etUserNumber.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lpNum = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpNum.leftMargin = dp(8);
        rowInput.addView(etUserNumber, lpNum);

        add(boardUserMgmt, rowInput, 8);

        Button btnAddUser = btn("➕ 添加/覆盖 一条自定义号码", 0xFF00695C);
        btnAddUser.setOnClickListener(v -> {
            String name = etUserName.getText().toString().trim();
            String num = etUserNumber.getText().toString().trim();
            if (name.isEmpty() || num.isEmpty()) { toast("姓名和号码都要填"); return; }
            boolean exists = UserNumberStore.get(num) != null;
            String msg = exists
                    ? "确定导入1条号码吗？其中有1条重复会被覆盖"
                    : "确定导入1条号码吗？";
            new AlertDialog.Builder(this)
                    .setTitle("确认添加")
                    .setMessage(msg)
                    .setPositiveButton("确定", (d, w) -> {
                        UserNumberStore.put(num, name);
                        etUserName.setText("");
                        etUserNumber.setText("");
                        toast("已添加：" + name + " (" + num + ")");
                        if (userSection != null) userSection.refresh();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        add(boardUserMgmt, btnAddUser, 6);

        Button btnImportClipboard = btn("📋 从剪贴板导入（合并追加）", 0xFF283593);
        btnImportClipboard.setOnClickListener(v -> importFromClipboard());
        add(boardUserMgmt, btnImportClipboard, 6);

        userSection = new ManagedListSection(
                boardUserMgmt,
                "自定义号码列表",
                new KVStore() {
                    @Override public Map<String, String> getAll() { return UserNumberStore.getAll(); }
                    @Override public void put(String key, String value) { UserNumberStore.put(key, value); }
                    @Override public void removeAll(Collection<String> keys) { UserNumberStore.removeAll(keys); }
                },
                (key, value) -> value + "   " + key, // 姓名 + 号码
                "确定删除选中的 %d 条自定义号码吗？",
                "编辑姓名/标签"
        );
        userSection.build();

        // ── 备份和导入（v3.13：文件名带时间戳，支持多版本，导入时可选择版本） ──
        add(root, line(), 14);
        LinearLayout boardBackupImport = new LinearLayout(this);
        boardBackupImport.setOrientation(LinearLayout.VERTICAL);
        addCollapsibleBoard(root, "backup_import", "备份和导入", boardBackupImport);
        add(boardBackupImport, title(
                "文件保存路径（可长按复制）：\n"
              + "/storage/emulated/0/Android/data/com.aksb2026.callerid.module/files/backup\n"
              + "其中\n"
              + "query_cache_backup_xxx.json是联网查询号码\n"
              + "custom_numbers_backup_xxx.json是自定义号码\n"
              + "full_backup_xxx.json是全部备份数据\n"
              + "每次备份会生成一个带时间戳的新文件，不会覆盖旧备份，可保留多个历史版本。\n"
              + "卸载本软件这些备份文件会自动删除，记得卸载前把备份文件复制到安全的地方。",
                12, 0xFF777777), 6);

        Button btnBackupCache = btn("💾 备份联网缓存到文件", 0xFF6D4C41);
        btnBackupCache.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("确认备份")
                .setMessage("确定要备份联网缓存到文件吗？")
                .setPositiveButton("备份", (d, w) -> backupCacheToFile())
                .setNegativeButton("取消", null)
                .show());
        add(boardBackupImport, btnBackupCache, 8);

        Button btnImportCache = btn("📂 从文件导入联网缓存（合并，选版本）", 0xFF4527A0);
        btnImportCache.setOnClickListener(v -> confirmAndImportCacheFromFile());
        add(boardBackupImport, btnImportCache, 6);

        Button btnBackupUser = btn("💾 备份自定义号码到文件", 0xFF6D4C41);
        btnBackupUser.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("确认备份")
                .setMessage("确定要备份自定义号码到文件吗？")
                .setPositiveButton("备份", (d, w) -> backupUserNumbersToFile())
                .setNegativeButton("取消", null)
                .show());
        add(boardBackupImport, btnBackupUser, 10);

        Button btnImportUser = btn("📂 从文件导入自定义号码（合并，选版本）", 0xFF4527A0);
        btnImportUser.setOnClickListener(v -> confirmAndImportUserNumbersFromFile());
        add(boardBackupImport, btnImportUser, 6);

        // 备份全部数据（v3.13 新增）：联网缓存 + 自定义号码 + 全部设置项打包成一个文件
        Button btnBackupAll = btn("🗄 备份全部数据（缓存+号码+全部设置）", 0xFF795548);
        btnBackupAll.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("确认备份")
                .setMessage("确定要备份全部数据（联网缓存 + 自定义号码 + 全部设置项）到文件吗？")
                .setPositiveButton("备份", (d, w) -> backupAllToFile())
                .setNegativeButton("取消", null)
                .show());
        add(boardBackupImport, btnBackupAll, 14);

        Button btnImportAll = btn("📂 从文件导入全部数据（选版本）", 0xFFAD1457);
        btnImportAll.setOnClickListener(v -> confirmAndImportAllFromFile());
        add(boardBackupImport, btnImportAll, 6);

        add(boardBackupImport, title(
                "注意：「备份全部数据」不包含 LSPosed 模块启用状态、悬浮窗权限授权、Root 保活命令这几项"
              + "系统/框架层配置，换新机或刷机后这几步仍需手动重新走一遍。",
                11, 0xFF555555), 8);

        add(root, line(), 14);
        LinearLayout boardRootKeepAlive = new LinearLayout(this);
        boardRootKeepAlive.setOrientation(LinearLayout.VERTICAL);
        addCollapsibleBoard(root, "root_keepalive", "Root保活教程", boardRootKeepAlive);

        // ── v4.5 重新设计：①②③➃四个入口按钮 + 弹窗展示具体命令/说明 ──────────
        // 之前①②③三条命令整段铺在页面上太长了，改成"按钮 + 弹窗"，弹窗里的文字
        // 沿用 title() 生成，本身就支持长按选中复制，弹窗关闭逻辑复用系统
        // AlertDialog 自带的"确定"按钮，不需要额外维护展开/收起状态。
        //
        // 免 Root 电池优化申请按钮和对应说明文字（v4.0 曾经加过）这次一并去掉：
        // 这个 App 的定位就是配合 LSPosed（本身要求设备已 root），走到这个教程
        // 板块的人手上大概率已经有 root，这个"不需要 root"的分支价值不大。
        add(boardRootKeepAlive, title(
                "下面①②③三种方式效果完全一样（本质上跑的是同一套命令），任选其一，"
              + "哪个方便用哪个。\n\n"
              + "➃是有风险的可选项，如果①/②/③操作后效果不理想再考虑使用：\n\n"
              + "推荐顺序：①或②操作完成后，如果没有出现掉后台、掉服务现象，就不用"
              + "后续操作。如果还是有问题，就执行③（因为③每次开机都会执行一次命令）。"
              + "如果③还是不行，可以考虑➃把本 App 安装成「系统应用」。",
                12, 0xFF44CC44), 8);

        final String adbCommands =
                "ADB 命令（电脑上敲，手机数据线连接电脑，不需要设备本身 root）\n\n"
              + "共 5 条，可长按复制，逐条整行复制粘贴，每次粘贴后按回车执行都会返回"
              + "中文结果（一条一行，最好不要复制多条一起粘贴，部分终端粘贴多行命令"
              + "有时会出错）\n\n"
              + "以下命令不会对系统造成影响，不用了正常卸载本 App 即可，无需额外操作。\n\n"
              + "adb shell 'cmd appops set com.aksb2026.callerid.module SYSTEM_ALERT_WINDOW allow; cmd appops get com.aksb2026.callerid.module SYSTEM_ALERT_WINDOW | grep -q allow && echo \"悬浮窗权限：已启用 ✔\" || echo \"悬浮窗权限：未启用 ✘\"'\n\n"
              + "adb shell 'cmd appops set com.aksb2026.callerid.module RUN_IN_BACKGROUND allow; cmd appops get com.aksb2026.callerid.module RUN_IN_BACKGROUND | grep -q allow && echo \"后台运行权限：已启用 ✔\" || echo \"后台运行权限：未启用 ✘\"'\n\n"
              + "adb shell 'cmd appops set com.aksb2026.callerid.module RUN_ANY_IN_BACKGROUND allow; cmd appops get com.aksb2026.callerid.module RUN_ANY_IN_BACKGROUND | grep -q allow && echo \"任意后台运行权限：已启用 ✔\" || echo \"任意后台运行权限：未启用 ✘\"'\n\n"
              + "adb shell 'dumpsys deviceidle whitelist +com.aksb2026.callerid.module; dumpsys deviceidle whitelist | grep -q com.aksb2026.callerid.module && echo \"电池优化白名单：已启用 ✔\" || echo \"电池优化白名单：未启用 ✘\"'\n\n"
              + "adb shell 'am set-inactive com.aksb2026.callerid.module false; am get-inactive com.aksb2026.callerid.module | grep -q \"Idle=false\" && echo \"待机分桶限制：已解除 ✔\" || echo \"待机分桶限制：未解除 ✘\"'";

        final String localCommands =
                "在 Termux / MT管理器等终端里\n\n"
              + "先输入 su 回车，授权 root 权限，窗口出现 # 标识后，\n\n"
              + "逐条整行复制粘贴，共 5 条，可长按复制，每次粘贴后按回车执行都会返回"
              + "中文结果（一条一行，最好不要把多行一起粘贴，部分终端粘贴多行命令"
              + "有时会出错）\n\n"
              + "以下命令不会对系统造成影响，不用了正常卸载本 App 即可，无需额外操作。\n\n"
              + "cmd appops set com.aksb2026.callerid.module SYSTEM_ALERT_WINDOW allow; cmd appops get com.aksb2026.callerid.module SYSTEM_ALERT_WINDOW | grep -q allow && echo \"悬浮窗权限：已启用 ✔\" || echo \"悬浮窗权限：未启用 ✘\"\n\n"
              + "cmd appops set com.aksb2026.callerid.module RUN_IN_BACKGROUND allow; cmd appops get com.aksb2026.callerid.module RUN_IN_BACKGROUND | grep -q allow && echo \"后台运行权限：已启用 ✔\" || echo \"后台运行权限：未启用 ✘\"\n\n"
              + "cmd appops set com.aksb2026.callerid.module RUN_ANY_IN_BACKGROUND allow; cmd appops get com.aksb2026.callerid.module RUN_ANY_IN_BACKGROUND | grep -q allow && echo \"任意后台运行权限：已启用 ✔\" || echo \"任意后台运行权限：未启用 ✘\"\n\n"
              + "dumpsys deviceidle whitelist +com.aksb2026.callerid.module; dumpsys deviceidle whitelist | grep -q com.aksb2026.callerid.module && echo \"电池优化白名单：已启用 ✔\" || echo \"电池优化白名单：未启用 ✘\"\n\n"
              + "am set-inactive com.aksb2026.callerid.module false; am get-inactive com.aksb2026.callerid.module | grep -q \"Idle=false\" && echo \"待机分桶限制：已解除 ✔\" || echo \"待机分桶限制：未解除 ✘\"";

        final String keepAliveModuleInfo =
                "KeepAlive-Magisk 保活模块本身不含 APK，只要装了这个包名的 App 就自动生效。\n\n"
              + "安装好模块后重启手机，在 Magisk App 里找到这个模块，点它的 Action 按钮，"
              + "就是立即执行一次「② 本地终端命令」里的五项、并直接用中文显示每一项结果，"
              + "执行后不用重启手机；也可以什么都不做，模块本身开机时会自动执行一次"
              + "（同样会把结果记进模块目录下的日志）。\n\n"
              + "注意：这个 Action 按钮要刷入模块并重启过一次之后才会出现，是 Magisk 本身的"
              + "机制，首次刷入还没重启时看不到属于正常现象，不是模块有问题。";

        final String systemizeModuleInfo =
                "这个模块包含一个空的 APK 文件，用来把本「来电识别」App 安装成「系统应用」，"
              + "具体能带来多少额外的抗杀效果目前没有实测数据（理论上可能有点用）。\n\n"
              + "使用方法：\n"
              + "刷入这个模块后重启一次，再安装本「来电识别」APK 覆盖即可。\n"
              + "（如果顺序反过来，先安装 APK、再刷 Systemize-Magisk 模块，刷完同样需要"
              + "重启一次，也可以，没有影响）\n\n"
              + "如果前面的①/②/③操作已经能稳定使用，不建议再刷这个模块。\n\n"
              + "注意：如果要自己使用源码编译的话，占位 APK 和正式 APK 必须同一套签名。\n\n"
              + "免责声明：\n"
              + "这个模块会修改你的系统文件。请自行承担风险。开发者不对使用该模块所造成的"
              + "任何损害负责。";

        Button btnShowAdb = btn("① ADB 命令（点击查看）", 0xFF1565C0);
        add(boardRootKeepAlive, btnShowAdb, 10);
        btnShowAdb.setOnClickListener(v -> showInfoDialog("① ADB 命令", adbCommands));

        Button btnShowLocal = btn("② 本地终端命令，需 root（点击查看）", 0xFF1565C0);
        add(boardRootKeepAlive, btnShowLocal, 6);
        btnShowLocal.setOnClickListener(v -> showInfoDialog("② 本地终端命令", localCommands));

        Button btnShowKeepAliveModule = btn("③ KeepAlive-Magisk 保活模块（点击查看）", 0xFF1565C0);
        add(boardRootKeepAlive, btnShowKeepAliveModule, 6);
        btnShowKeepAliveModule.setOnClickListener(v ->
                showInfoDialog("③ KeepAlive-Magisk 保活模块", keepAliveModuleInfo));

        Button btnShowSystemizeModule = btn("➃ Systemize-Magisk 模块（可选，有风险）", 0xFFB71C1C);
        add(boardRootKeepAlive, btnShowSystemizeModule, 6);
        btnShowSystemizeModule.setOnClickListener(v ->
                showInfoDialog("➃ Systemize-Magisk 模块（可选，有风险）", systemizeModuleInfo));

        // ── ⑤ Root 高级选项（v4.9 新增，隐藏功能） ──────────────────────────
        // 默认关闭、教程里刻意不显眼；效果上相当于把①②③➃全部合并到 App 自己
        // 内部用 root 执行，不需要再单独装 KeepAlive/Systemize 两个 Magisk
        // 模块。这是"高级用户自己承担后果"的选项，不是推荐路径，具体风险声明
        // 见下面 rootAdvancedDisclaimer 文案，开启前必须勾选"已知悉风险"。
        Button btnRootAdvanced = btn("⑤ Root 高级选项（隐藏功能，谨慎开启）", 0xFF7F0000);
        add(boardRootKeepAlive, btnRootAdvanced, 6);

        final String rootAdvancedDisclaimer =
                "这个功能会让 App 自己申请并长期持有 root 权限，用来在内部直接执行"
              + "保活命令、并尝试把本进程的内存回收优先级调到接近系统核心进程的级别。\n\n"
              + "这是一个纯 AI 编写的项目，没有经过人工代码审查，也没有经过大量实机测试。\n\n"
              + "授权 root 启用此功能可能造成无限重启、卡屏死机、系统损坏（变砖）乃至硬件"
              + "受损。在尝试开启前，请务必提前备份好个人所有重要数据。如果您缺乏刷机"
              + "救砖经验，或不知道如何在系统崩溃时进行自救，请绝对不要尝试开启。\n\n"
              + "一旦您选择开启本功能，即视为您已完全理解并自愿接受上述所有风险，由此"
              + "产生的数据丢失、设备损坏等一切后果均由您本人自行承担。\n\n"
              + "一旦系统出现不稳定，请立即关闭此功能，并撤销本 App 的 root 授权。\n\n"
              + "具体的技术风险：调整内存回收优先级这一步，本质上是在跟系统自己的内存"
              + "管理机制对着干——内存紧张时，系统可能被迫牺牲手机其他部分（比如别的"
              + "App 更频繁被杀、系统界面卡顿，极端情况下可能导致整机不稳定），不是只对"
              + "这个 App 自己有风险。\n\n"
              + "开启后，前面①②③➃提到的 Magisk 模块就都不再需要了，可以卸载（同时装着"
              + "也不冲突，只是没必要）。";

        btnRootAdvanced.setOnClickListener(v -> {
            if (ModuleSettings.isRootAdvancedEnabled(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("⑤ Root 高级选项（当前已开启）")
                        .setMessage("现在开机会自动用 root 执行一次保活命令。")
                        .setPositiveButton("立即重新执行一次", (d, w) -> runRootAdvancedNow())
                        .setNegativeButton("关闭此功能", (d, w) -> {
                            ModuleSettings.setRootAdvancedEnabled(this, false);
                            toast("已关闭。注意：本次开机期间已经改过的内存回收优先级不会自动"
                                    + "恢复，重启一次手机即可彻底恢复默认状态。");
                        })
                        .setNeutralButton("取消", null)
                        .show();
                return;
            }

            CheckBox cbAck = new CheckBox(this);
            cbAck.setText("我已经完整看完并知悉、接受以上所有风险");
            cbAck.setTextColor(Color.WHITE);
            cbAck.setPadding(dp(20), dp(4), dp(20), dp(16));

            TextView tvMsg = title(rootAdvancedDisclaimer, 13, Color.WHITE);
            tvMsg.setPadding(dp(20), dp(16), dp(20), dp(4));

            LinearLayout dialogContent = new LinearLayout(this);
            dialogContent.setOrientation(LinearLayout.VERTICAL);
            dialogContent.setBackgroundColor(0xFF2B2B2B);
            dialogContent.addView(tvMsg);
            dialogContent.addView(cbAck);
            ScrollView sv = new ScrollView(this);
            sv.addView(dialogContent);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("⑤ Root 高级选项 —— 开启前必读")
                    .setView(sv)
                    .setPositiveButton("确定开启", (d, w) -> {
                        ModuleSettings.setRootAdvancedEnabled(this, true);
                        toast("正在申请 root 权限……");
                        runRootAdvancedNow();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            cbAck.setOnCheckedChangeListener((btn2, checked) ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(checked));
        });

        // ── 关于本软件（v3.20 新增，v3.20-2 补全真实地址，v3.21 标题居中+整体下移+新增更新地址） ──
        // 不参与折叠，永远展开、标题和正文都居中；四条地址（项目主页/更新地址/
        // 作者主页/SpamBlocker）都是真实地址，均可点击跳外部浏览器。
        add(root, line(), 14);
        TextView aboutHeader = section("关于本软件", 20);
        aboutHeader.setGravity(Gravity.CENTER);
        add(root, aboutHeader, 22); // 原来是 10，+12 让整个板块相对上面再往下挪一点
        add(root, buildAboutSectionText(), 8);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF121212);
        sv.addView(root);
        scrollViewHolder[0] = sv;
        setContentView(sv);
    }

    // ── 缓存实时刷新（v1.7 新增，第4条：模拟来电/真实来电产生新查询结果后自动刷新列表） ──

    @Override
    protected void onResume() {
        super.onResume();
        CacheStore.setOnCacheUpdatedListener(() -> runOnUiThread(() -> {
            if (cacheSection != null) cacheSection.refresh();
        }));
        // 每次真正回到前台都现查一次保活状态（v4.0 新增）——不存历史 flag，
        // 系统设置随时可能在用户不知情的情况下变化（被 ROM 升级重置、被手动
        // 关掉电池白名单等），onResume 比只在 onCreate 里查一次更贴近"每次
        // 打开 App 都看到真实状态"的要求（同一个 Activity 实例被切回前台时
        // onCreate 不会重新跑，但 onResume 会）。
        if (refreshKeepAliveStatus != null) refreshKeepAliveStatus.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CacheStore.setOnCacheUpdatedListener(null);
    }

    // ── 剪贴板导入（自定义号码管理板块用，高频快速批量加号码） ────────────

    /**
     * 从剪贴板导入，自动识别两种格式：
     * ① 以 "{" 开头 → 视为旧版「备份到剪贴板」生成的合并 JSON 数据；
     * ② 其它 → 视为逐行 "姓名,号码" 格式，按行解析，跳过格式不对的行。
     * 两种情况均先解析统计、弹窗确认后再写入（合并追加，不会清空已有数据）。
     */
    private void importFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
            toast("剪贴板为空");
            return;
        }
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
        String content = cs == null ? "" : cs.toString().trim();
        if (content.isEmpty()) { toast("剪贴板为空"); return; }

        if (content.startsWith("{")) {
            confirmAndImportJsonBackup(content);
        } else {
            confirmAndImportPlainList(content);
        }
    }

    private static class ParsedEntry {
        final String name, number;
        ParsedEntry(String name, String number) { this.name = name; this.number = number; }
    }

    /** 纯解析，不写入。返回按行解析出的条目；skipCountOut[0] 回传跳过的行数 */
    private List<ParsedEntry> parsePlainList(String content, int[] skipCountOut) {
        List<ParsedEntry> result = new ArrayList<>();
        String[] lines = content.split("\\r?\\n");
        int skip = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int idx = trimmed.indexOf(',');
            if (idx < 0) idx = trimmed.indexOf('，'); // 兼容中文逗号
            if (idx <= 0 || idx == trimmed.length() - 1) { skip++; continue; }
            String name = trimmed.substring(0, idx).trim();
            String number = trimmed.substring(idx + 1).trim();
            if (name.isEmpty() || number.isEmpty()) { skip++; continue; }
            result.add(new ParsedEntry(name, number));
        }
        if (skipCountOut != null && skipCountOut.length > 0) skipCountOut[0] = skip;
        return result;
    }

    private void confirmAndImportPlainList(String content) {
        int[] skipHolder = new int[1];
        List<ParsedEntry> entries = parsePlainList(content, skipHolder);
        int skip = skipHolder[0];
        if (entries.isEmpty()) {
            toast("没有解析到有效的号码，已跳过 " + skip + " 行");
            return;
        }
        // 同一批次内号码重复时，后面的覆盖前面的（与原有写入顺序保持一致）
        LinkedHashMap<String, String> dedup = new LinkedHashMap<>();
        for (ParsedEntry e : entries) dedup.put(e.number, e.name);

        int x = dedup.size();
        int y = 0;
        for (String number : dedup.keySet()) {
            if (UserNumberStore.get(number) != null) y++;
        }

        String msg = (y > 0)
                ? ("确定导入" + x + "条号码吗？其中有" + y + "条重复会被覆盖")
                : ("确定导入" + x + "条号码吗？");
        if (skip > 0) msg += "\n（另有 " + skip + " 行格式不正确，将被跳过）";

        new AlertDialog.Builder(this)
                .setTitle("确认导入")
                .setMessage(msg)
                .setPositiveButton("导入", (d, w) -> {
                    for (Map.Entry<String, String> e : dedup.entrySet()) {
                        UserNumberStore.put(e.getKey(), e.getValue());
                    }
                    toast("导入完成：成功 " + x + " 条，跳过 " + skip + " 条");
                    if (userSection != null) userSection.refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmAndImportJsonBackup(String jsonStr) {
        JSONObject root;
        try {
            root = new JSONObject(jsonStr);
        } catch (JSONException e) {
            toast("备份数据格式解析失败，请确认剪贴板内容完整");
            return;
        }

        LinkedHashMap<String, String> users = new LinkedHashMap<>();
        LinkedHashMap<String, String> cache = new LinkedHashMap<>();
        try {
            if (root.has("user_numbers")) {
                JSONObject u = root.getJSONObject("user_numbers");
                Iterator<String> keys = u.keys();
                while (keys.hasNext()) {
                    String number = keys.next();
                    users.put(number, u.getString(number));
                }
            }
            if (root.has("query_cache")) {
                JSONObject c = root.getJSONObject("query_cache");
                Iterator<String> keys = c.keys();
                while (keys.hasNext()) {
                    String number = keys.next();
                    cache.put(number, c.getString(number));
                }
            }
        } catch (JSONException e) {
            toast("备份数据格式解析失败，请确认剪贴板内容完整");
            return;
        }

        if (users.isEmpty() && cache.isEmpty()) {
            toast("没有解析到可导入的数据");
            return;
        }

        int userY = 0;
        for (String number : users.keySet()) if (UserNumberStore.get(number) != null) userY++;
        int cacheY = 0;
        for (String number : cache.keySet()) if (CacheStore.get(number) != null) cacheY++;

        StringBuilder msg = new StringBuilder("确定导入自定义号码 " + users.size() + " 条");
        if (userY > 0) msg.append("（其中 ").append(userY).append(" 条重复会被覆盖）");
        msg.append("，联网缓存 ").append(cache.size()).append(" 条");
        if (cacheY > 0) msg.append("（其中 ").append(cacheY).append(" 条重复会被覆盖）");
        msg.append(" 吗？");

        new AlertDialog.Builder(this)
                .setTitle("确认导入")
                .setMessage(msg.toString())
                .setPositiveButton("导入", (d, w) -> {
                    for (Map.Entry<String, String> e : users.entrySet()) UserNumberStore.put(e.getKey(), e.getValue());
                    for (Map.Entry<String, String> e : cache.entrySet()) CacheStore.put(e.getKey(), e.getValue());
                    toast("恢复完成：自定义号码 " + users.size() + " 条，联网缓存 " + cache.size() + " 条");
                    if (userSection != null) userSection.refresh();
                    if (cacheSection != null) cacheSection.refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 文件备份 / 导入（备份和导入板块用） ──────────────────────────────
    // v3.13：备份文件名带时间戳，支持保留多个历史版本；导入时弹版本选择列表
    // （不需要额外权限、不跳转系统文件管理器）；新增"备份全部数据"打包
    // 联网缓存 + 自定义号码 + 全部设置项。

    private static final String CACHE_BACKUP_PREFIX = "query_cache_backup_";
    private static final String USER_BACKUP_PREFIX  = "custom_numbers_backup_";
    private static final String FULL_BACKUP_PREFIX  = "full_backup_";
    private static final String LEGACY_CACHE_BACKUP = "query_cache_backup.json";
    private static final String LEGACY_USER_BACKUP  = "custom_numbers_backup.json";

    /** App 专属外部存储下的备份目录：/sdcard/Android/data/<包名>/files/backup/ 不需要任何权限 */
    private File backupDir() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir(); // 极端情况下外部存储不可用，退回内部存储
        File dir = new File(base, "backup");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String nowString() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }

    /** 文件名用时间戳（区别于展示用的 nowString()，不含冒号等文件名非法字符） */
    private String fileTimestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
    }

    private void writeFile(File f, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f, false);
             OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8")) {
            w.write(content);
        }
    }

    private String readFile(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(f);
             InputStreamReader r = new InputStreamReader(fis, "UTF-8")) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private void backupToFile(String fileName, Map<String, String> data, String label) {
        try {
            JSONObject dataObj = new JSONObject();
            for (Map.Entry<String, String> e : data.entrySet()) {
                dataObj.put(e.getKey(), e.getValue());
            }
            JSONObject root = new JSONObject();
            root.put("count", data.size());
            root.put("backup_time", nowString());
            root.put("data", dataObj);

            File f = new File(backupDir(), fileName);
            writeFile(f, root.toString());
            toast("已备份 " + data.size() + " 条" + label + " 到：\n" + f.getAbsolutePath());
        } catch (JSONException e) {
            toast("生成备份数据失败：" + e.getMessage());
        } catch (IOException e) {
            toast("写入备份文件失败：" + e.getMessage());
        }
    }

    private void backupUserNumbersToFile() {
        backupToFile(USER_BACKUP_PREFIX + fileTimestamp() + ".json", UserNumberStore.getAll(), "自定义号码");
    }

    private void backupCacheToFile() {
        backupToFile(CACHE_BACKUP_PREFIX + fileTimestamp() + ".json", CacheStore.getAll(), "联网缓存");
    }

    /**
     * 备份全部数据：联网缓存 + 自定义号码 + 全部设置项打包成一个带时间戳的文件。
     * 不含 LSPosed 启用状态 / 悬浮窗权限 / Root 保活命令等系统层配置（见按钮下方说明）。
     */
    private void backupAllToFile() {
        try {
            Map<String, String> users = UserNumberStore.getAll();
            Map<String, String> cache = CacheStore.getAll();

            JSONObject userObj = new JSONObject();
            for (Map.Entry<String, String> e : users.entrySet()) userObj.put(e.getKey(), e.getValue());
            JSONObject cacheObj = new JSONObject();
            for (Map.Entry<String, String> e : cache.entrySet()) cacheObj.put(e.getKey(), e.getValue());

            JSONObject root = new JSONObject();
            root.put("type", "full_backup");
            root.put("backup_time", nowString());
            root.put("user_numbers", userObj);
            root.put("query_cache", cacheObj);
            root.put("settings", ModuleSettings.exportAll(this));

            File f = new File(backupDir(), FULL_BACKUP_PREFIX + fileTimestamp() + ".json");
            writeFile(f, root.toString());
            toast("已备份全部数据（自定义号码 " + users.size() + " 条，联网缓存 " + cache.size()
                    + " 条，含全部设置项）到：\n" + f.getAbsolutePath());
        } catch (JSONException e) {
            toast("生成备份数据失败：" + e.getMessage());
        } catch (IOException e) {
            toast("写入备份文件失败：" + e.getMessage());
        }
    }

    private interface ImportPutter { void put(String key, String value); }
    private interface FileChosenCallback { void onChosen(File f); }

    /** 扫描备份目录，收集匹配前缀的带时间戳文件 + 旧版固定文件名（如存在），按时间倒序排列 */
    private List<File> listBackupCandidates(String prefix, String legacyFileName) {
        List<File> result = new ArrayList<>();
        File dir = backupDir();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith(prefix) && name.endsWith(".json")) result.add(f);
            }
        }
        if (legacyFileName != null) {
            File legacy = new File(dir, legacyFileName);
            if (legacy.exists()) result.add(legacy);
        }
        Collections.sort(result, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return result;
    }

    /** 弹出版本选择列表（无需权限/不跳系统文件管理器），选中后回调对应文件 */
    private void pickBackupFileThen(String prefix, String legacyFileName, String label, FileChosenCallback cb) {
        List<File> files = listBackupCandidates(prefix, legacyFileName);
        if (files.isEmpty()) { toast("还没有" + label + "备份文件"); return; }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        String[] display = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            boolean legacy = f.getName().equals(legacyFileName);
            display[i] = (legacy ? "[旧版本备份] " : "") + sdf.format(new Date(f.lastModified()));
        }

        new AlertDialog.Builder(this)
                .setTitle("选择要导入的" + label + "版本（共 " + files.size() + " 个）")
                .setItems(display, (d, which) -> cb.onChosen(files.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 从指定文件解析出待导入条目，统计总数(x)与已存在会被覆盖的条目数(y)，
     * 弹窗确认后再真正写入（合并追加，不会清空已有数据）。
     */
    private void confirmAndImportFromFile(File f, String label,
                                           Map<String, String> existingSnapshot,
                                           ImportPutter putter, Runnable afterRefresh) {
        if (!f.exists()) { toast(label + "备份文件不存在"); return; }

        String content;
        try {
            content = readFile(f);
        } catch (IOException e) {
            toast("读取备份文件失败：" + e.getMessage());
            return;
        }

        JSONObject root, data;
        try {
            root = new JSONObject(content);
            data = root.getJSONObject("data");
        } catch (JSONException e) {
            toast("备份文件格式解析失败：" + e.getMessage());
            return;
        }

        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        try {
            Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                entries.put(key, data.getString(key));
            }
        } catch (JSONException e) {
            toast("备份文件格式解析失败：" + e.getMessage());
            return;
        }

        if (entries.isEmpty()) { toast("备份文件内没有可导入的" + label); return; }

        int x = entries.size();
        int y = 0;
        for (String key : entries.keySet()) {
            if (existingSnapshot.containsKey(key)) y++;
        }
        String backupTime = root.optString("backup_time", "未知时间");

        String msg = (y > 0)
                ? ("确定导入" + x + "条" + label + "吗？其中有" + y + "条重复会被覆盖\n（备份时间：" + backupTime + "）")
                : ("确定导入" + x + "条" + label + "吗？\n（备份时间：" + backupTime + "）");

        new AlertDialog.Builder(this)
                .setTitle("确认导入")
                .setMessage(msg)
                .setPositiveButton("导入", (d, w) -> {
                    for (Map.Entry<String, String> e : entries.entrySet()) putter.put(e.getKey(), e.getValue());
                    toast("已从备份导入 " + x + " 条" + label + "（合并追加）");
                    if (afterRefresh != null) afterRefresh.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmAndImportUserNumbersFromFile() {
        pickBackupFileThen(USER_BACKUP_PREFIX, LEGACY_USER_BACKUP, "自定义号码备份", f ->
                confirmAndImportFromFile(f, "自定义号码", UserNumberStore.getAll(), UserNumberStore::put,
                        () -> { if (userSection != null) userSection.refresh(); }));
    }

    private void confirmAndImportCacheFromFile() {
        pickBackupFileThen(CACHE_BACKUP_PREFIX, LEGACY_CACHE_BACKUP, "联网缓存备份", f ->
                confirmAndImportFromFile(f, "联网缓存", CacheStore.getAll(), CacheStore::put,
                        () -> { if (cacheSection != null) cacheSection.refresh(); }));
    }

    private void confirmAndImportAllFromFile() {
        pickBackupFileThen(FULL_BACKUP_PREFIX, null, "全部数据备份", this::confirmImportFullBackupFile);
    }

    /**
     * 导入"全部数据"备份文件：联网缓存 / 自定义号码 合并追加（同其他导入按钮行为）；
     * 设置项直接整体覆盖当前设置（单值没有"合并"概念），导入前会明确提示会覆盖设置。
     * 导入设置成功后自动 recreate() 本页面，让所有单选框/复选框状态立刻刷新。
     */
    private void confirmImportFullBackupFile(File f) {
        if (!f.exists()) { toast("备份文件不存在"); return; }

        String content;
        try {
            content = readFile(f);
        } catch (IOException e) {
            toast("读取备份文件失败：" + e.getMessage());
            return;
        }

        JSONObject root;
        try {
            root = new JSONObject(content);
        } catch (JSONException e) {
            toast("备份文件格式解析失败：" + e.getMessage());
            return;
        }

        JSONObject userObj = root.optJSONObject("user_numbers");
        JSONObject cacheObj = root.optJSONObject("query_cache");
        JSONObject settingsObj = root.optJSONObject("settings");
        String backupTime = root.optString("backup_time", "未知时间");

        int userCount = userObj != null ? userObj.length() : 0;
        int cacheCount = cacheObj != null ? cacheObj.length() : 0;
        boolean hasSettings = settingsObj != null;

        String msg = "备份时间：" + backupTime + "\n"
                + "将合并追加 自定义号码 " + userCount + " 条、联网缓存 " + cacheCount + " 条（重复的会被覆盖）。\n"
                + (hasSettings ? "导入后会覆盖当前所有设置项，确定继续吗？" : "（此备份不含设置项）");

        new AlertDialog.Builder(this)
                .setTitle("确认导入全部数据")
                .setMessage(msg)
                .setPositiveButton("导入", (d, w) -> {
                    try {
                        if (userObj != null) {
                            Iterator<String> keys = userObj.keys();
                            while (keys.hasNext()) {
                                String k = keys.next();
                                UserNumberStore.put(k, userObj.getString(k));
                            }
                        }
                        if (cacheObj != null) {
                            Iterator<String> keys = cacheObj.keys();
                            while (keys.hasNext()) {
                                String k = keys.next();
                                CacheStore.put(k, cacheObj.getString(k));
                            }
                        }
                        if (settingsObj != null) {
                            ModuleSettings.importAll(this, settingsObj);
                        }
                    } catch (JSONException e) {
                        toast("导入过程中数据解析出错：" + e.getMessage());
                        return;
                    }
                    toast("导入完成：自定义号码 " + userCount + " 条，联网缓存 " + cacheCount + " 条"
                            + (hasSettings ? "，设置项已覆盖" : ""));
                    if (hasSettings) {
                        // 设置项已变化：recreate() 重建本页面，单选框/复选框状态立即刷新
                        recreate();
                    } else {
                        if (userSection != null) userSection.refresh();
                        if (cacheSection != null) cacheSection.refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 通用可展开 / 多选 / 编辑 列表组件（缓存管理 + 自定义号码管理 共用） ──

    private interface KVStore {
        Map<String, String> getAll();
        void put(String key, String value);
        void removeAll(Collection<String> keys);
    }

    private interface RowFormatter {
        String format(String key, String value);
    }

    /**
     * 一个可折叠、可多选（全选/反选/删除选中）、可单条编辑的列表区块。
     * 缓存管理 和 自定义号码管理 两个板块共用这一套逻辑，只是传入的
     * 数据源（KVStore）、显示格式（RowFormatter）、文案不同。
     *
     * 懒加载：初始只显示"共 X 条"的统计数字，不构建任何行视图；
     * 第一次展开时才真正把列表画出来，避免数据量大时拖慢主界面打开速度。
     */
    private class ManagedListSection {

        private final KVStore store;
        private final RowFormatter formatter;
        private final String deleteConfirmTemplate; // 含 %d 占位符
        private final String editDialogTitle;

        private boolean expanded = false;
        private boolean built = false;

        private TextView tvHeader;   // "▶ 展开 联网查询记录列表" 可点击
        private String sectionTitle;
        private TextView tvTotal;    // "共 X 条"
        private TextView tvSelected; // "已选择 X 项"（仅展开时显示）
        private LinearLayout toolbarRow; // 全选/反选/删除选中（仅展开时显示）
        private LinearLayout container;  // 具体行，仅展开时可见

        private final Map<String, CheckBox> checkBoxes = new LinkedHashMap<>();
        private Map<String, String> currentData = new LinkedHashMap<>();

        ManagedListSection(LinearLayout root, String titleText, KVStore store,
                            RowFormatter formatter, String deleteConfirmTemplate, String editDialogTitle) {
            this.store = store;
            this.formatter = formatter;
            this.deleteConfirmTemplate = deleteConfirmTemplate;
            this.editDialogTitle = editDialogTitle;
            buildSkeleton(root, titleText);
        }

        private void buildSkeleton(LinearLayout root, String titleText) {
            this.sectionTitle = titleText;
            // header：点击展开/收起
            LinearLayout headerRow = new LinearLayout(MainActivity.this);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            headerRow.setClickable(true);
            headerRow.setOnClickListener(v -> toggleExpand());

            tvHeader = new TextView(MainActivity.this);
            tvHeader.setText("▶ 展开 " + titleText);
            tvHeader.setTextColor(0xFF80CBC4);
            tvHeader.setTextSize(14);
            headerRow.addView(tvHeader);

            tvTotal = new TextView(MainActivity.this);
            tvTotal.setTextColor(0xFF777777);
            tvTotal.setTextSize(12);
            LinearLayout.LayoutParams lpTotal = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lpTotal.leftMargin = dp(10);
            headerRow.addView(tvTotal, lpTotal);

            add(root, headerRow, 10);

            // 工具栏：全选 / 反选 / 删除选中 / 已选择 X 项（默认隐藏，展开才显示）
            toolbarRow = new LinearLayout(MainActivity.this);
            toolbarRow.setOrientation(LinearLayout.HORIZONTAL);
            toolbarRow.setGravity(Gravity.CENTER_VERTICAL);
            toolbarRow.setVisibility(View.GONE);

            CheckBox cbSelectAll = new CheckBox(MainActivity.this);
            cbSelectAll.setText("全选");
            cbSelectAll.setTextColor(Color.WHITE);
            cbSelectAll.setOnClickListener(v -> selectAll(cbSelectAll.isChecked()));
            toolbarRow.addView(cbSelectAll);

            Button btnInvert = btn("反选", 0xFF37474F);
            LinearLayout.LayoutParams lpInvert = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lpInvert.leftMargin = dp(10);
            btnInvert.setOnClickListener(v -> {
                invertSelection();
                cbSelectAll.setChecked(false); // 反选后"全选"框状态不确定，直接取消勾选态
            });
            toolbarRow.addView(btnInvert, lpInvert);

            Button btnDelete = btn("🗑 删除选中", 0xFFB71C1C);
            LinearLayout.LayoutParams lpDelete = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lpDelete.leftMargin = dp(10);
            btnDelete.setOnClickListener(v -> deleteSelectedWithConfirm());
            toolbarRow.addView(btnDelete, lpDelete);

            add(root, toolbarRow, 8);

            tvSelected = new TextView(MainActivity.this);
            tvSelected.setTextColor(0xFF777777);
            tvSelected.setTextSize(12);
            tvSelected.setVisibility(View.GONE);
            add(root, tvSelected, 4);

            // 具体行容器（默认隐藏、未构建）
            container = new LinearLayout(MainActivity.this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setVisibility(View.GONE);
            add(root, container, 2);
        }

        void build() {
            refresh();
        }

        /** 数据发生变化（增/删/改/导入）后调用；始终刷新统计数字，展开状态下同时重建行 */
        void refresh() {
            Map<String, String> all = store.getAll();
            currentData = new LinkedHashMap<>(all);
            tvTotal.setText("共 " + all.size() + " 条");
            if (built) {
                rebuildRows(all);
            }
        }

        private void toggleExpand() {
            expanded = !expanded;
            if (expanded && !built) {
                rebuildRows(currentData);
                built = true;
            }
            container.setVisibility(expanded ? View.VISIBLE : View.GONE);
            toolbarRow.setVisibility(expanded ? View.VISIBLE : View.GONE);
            tvSelected.setVisibility(expanded ? View.VISIBLE : View.GONE);
            tvHeader.setText((expanded ? "▼ 收起 " : "▶ 展开 ") + sectionTitle);
        }

        private void rebuildRows(Map<String, String> data) {
            container.removeAllViews();
            checkBoxes.clear();
            for (Map.Entry<String, String> entry : data.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(4), 0, dp(4));

                CheckBox cb = new CheckBox(MainActivity.this);
                cb.setOnClickListener(v -> updateSelectedCount());
                row.addView(cb);
                checkBoxes.put(key, cb);

                TextView tv = new TextView(MainActivity.this);
                tv.setText(formatter.format(key, value));
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(14);
                tv.setOnClickListener(v -> openEditDialog(key));
                LinearLayout.LayoutParams lpTv = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lpTv.leftMargin = dp(6);
                row.addView(tv, lpTv);

                container.addView(row);
            }
            updateSelectedCount();
        }

        private void updateSelectedCount() {
            int n = 0;
            for (CheckBox cb : checkBoxes.values()) if (cb.isChecked()) n++;
            tvSelected.setText("已选择 " + n + " 项");
        }

        private void selectAll(boolean checked) {
            for (CheckBox cb : checkBoxes.values()) cb.setChecked(checked);
            updateSelectedCount();
        }

        private void invertSelection() {
            for (CheckBox cb : checkBoxes.values()) cb.setChecked(!cb.isChecked());
            updateSelectedCount();
        }

        private void deleteSelectedWithConfirm() {
            List<String> selected = new ArrayList<>();
            for (Map.Entry<String, CheckBox> e : checkBoxes.entrySet()) {
                if (e.getValue().isChecked()) selected.add(e.getKey());
            }
            if (selected.isEmpty()) { toast("没有勾选任何条目"); return; }

            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("确认删除")
                    .setMessage(String.format(deleteConfirmTemplate, selected.size()))
                    .setPositiveButton("删除", (d, w) -> {
                        store.removeAll(selected);
                        toast("已删除 " + selected.size() + " 条");
                        refresh();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }

        private void openEditDialog(String key) {
            String oldValue = currentData.get(key);

            EditText et = new EditText(MainActivity.this);
            et.setText(oldValue);
            et.setTextColor(Color.WHITE);
            et.setBackgroundColor(0xFF1E1E1E);
            et.setPadding(dp(12), dp(10), dp(12), dp(10));
            et.setSelection(et.getText().length());

            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(editDialogTitle + "（" + key + "）")
                    .setView(et)
                    .setPositiveButton("保存", (d, w) -> {
                        String v = et.getText().toString().trim();
                        if (v.isEmpty()) {
                            toast("内容不能为空，如需删除请用「删除选中」");
                            return;
                        }
                        store.put(key, v);
                        toast("已更新");
                        refresh();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    // ── 通用小工具 ────────────────────────────────────────────────────────

    private void add(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        parent.addView(v, lp);
    }

    private int dp(int val) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, val,
                getResources().getDisplayMetrics()));
    }

    /** 说明/提示类文字：支持长按选中复制（比如 Root 保活命令） */
    private TextView title(String text, float sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setLineSpacing(0, 1.4f);
        tv.setTextIsSelectable(true);
        return tv;
    }

    /**
     * v4.5 新增："Root保活教程"①②③➃四个入口用的弹窗——标题 + 可长按选中复制的
     * 正文 + 一个"确定"按钮。正文用 title() 生成（本身就支持长按选中），外面套一层
     * ScrollView 防止内容超长时对话框顶到屏幕外。
     *
     * v4.9 修正：不再用系统默认主题的 AlertDialog（有些机型/系统主题下默认是
     * 白底，配我们写死的浅灰字完全看不清）。改成自己套一层深灰底容器，跟主
     * 界面的纯黑背景做区分，同时保证文字在任何系统主题下都看得清楚。
     */
    private void showInfoDialog(String dialogTitle, String message) {
        TextView tv = title(message, 13, Color.WHITE);
        tv.setPadding(dp(20), dp(16), dp(20), dp(16));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF2B2B2B); // 深灰底，跟主界面纯黑背景 (0xFF121212) 区分开
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(dialogTitle)
                .setView(sv)
                .setPositiveButton("确定", null)
                .show();
    }

    /**
     * v4.9 新增："⑤ Root 高级选项"实际执行入口。Shell.cmd().exec() 是阻塞调用
     * （包括第一次调用时弹出的 Superuser 授权确认框，也会阻塞在这里等用户点
     * 允许/拒绝），必须放子线程跑，不能卡主线程；执行完切回主线程弹结果。
     */
    private void runRootAdvancedNow() {
        new Thread(() -> {
            String result = RootAdvancedHelper.runOnce(getApplicationContext());
            runOnUiThread(() -> showInfoDialog("⑤ Root 高级选项 —— 执行结果", result));
        }).start();
    }

    /**
     * SpamBlocker「即时查询」配置步骤说明（v3.20 新增）：整体是灰色说明文字，
     * 但其中 "is_spam":true 这一小段要单独标成蓝色（跟上面示例 URL 的蓝色呼应，
     * 提示这两处都是要填进 SpamBlocker 里的关键字段），所以不能用普通 title()，
     * 要用 SpannableString 局部上色。
     */
    private TextView buildSbConfigStepsText() {
        String isSpamField = "\"is_spam\":true";
        String full =
                "SpamBlocker 那边：即时查询(查询API) → 新建 → 自定义→HTTP请求→URL→填上面蓝色那一串地址 →\n"
              + "解析结果→负向标识符填入 " + isSpamField + " (蓝色字体)保存即可，其他全部保持默认。\n"
              + "测试方法：SpamBlocker可以点击「试管」按钮测试，无需真来电。\n"
              + "第一次设置比较繁琐，如不理解可以到软件底部访问主页查看详细教程。";

        SpannableString ss = new SpannableString(full);
        int start = full.indexOf(isSpamField);
        int end = start + isSpamField.length();
        ss.setSpan(new ForegroundColorSpan(0xFF2196F3), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextView tv = title(full, 12, 0xFF777777);
        tv.setText(ss);
        return tv;
    }

    /**
     * "关于本软件"板块正文（v3.20 新增，v3.20-2 补全真实地址，v3.21 新增更新地址/
     * 字号改回 13sp/精简 SpamBlocker 那行文案）：居中显示，@he007209 和四条
     * 地址标蓝，且都可点击跳外部浏览器。
     */
    private TextView buildAboutSectionText() {
        final String projectUrl = "https://github.com/aksb/CallerID_Module";
        final String releasesUrl = "https://github.com/aksb/CallerID_Module/releases";
        final String authorUrl = "https://github.com/aksb";
        final String spamBlockerUrl = "https://github.com/aj3423/SpamBlocker";

        // 用 StringBuilder 逐段拼接，同时记录每一段蓝色文字的起止下标，
        // 而不是事后用 String.indexOf() 去找——releasesUrl 本身就是拿
        // projectUrl 加了个 "/releases" 后缀，indexOf(projectUrl) 会误
        // 命中 releasesUrl 前面那一段，必须在拼接时就精确记下每段位置。
        StringBuilder sb = new StringBuilder();

        sb.append("感谢酷安 ");
        int atStart = sb.length();
        sb.append("@he007209");
        int atEnd = sb.length();
        sb.append(" 大佬分享的软件源码，本项目在大佬源码的基础上新增/调整多项功能，修复一些BUG。\n\n");

        sb.append("欢迎访问本项目GitHub主页，里面有更详细的使用教程\n");
        int projectStart = sb.length();
        sb.append(projectUrl);
        int projectEnd = sb.length();
        sb.append("\n\n");

        sb.append("软件源码、更新地址\n");
        int releasesStart = sb.length();
        sb.append(releasesUrl);
        int releasesEnd = sb.length();
        sb.append("\n\n");

        sb.append("作者主页\n");
        int authorStart = sb.length();
        sb.append(authorUrl);
        int authorEnd = sb.length();
        sb.append("\n里面有更多实用小工具。\n\n");

        sb.append("SpamBlocker骚扰电话/短信 拦截器\n");
        int sbUrlStart = sb.length();
        sb.append(spamBlockerUrl);
        int sbUrlEnd = sb.length();
        sb.append("\n\n");

        sb.append("本项目与 SpamBlocker 官方无任何关联，仅通过其「即时查询」接口实现单向联动，"
                + "具体功能与使用条款请以 SpamBlocker 官方项目为准。");

        String full = sb.toString();
        SpannableString ss = new SpannableString(full);

        ss.setSpan(new ForegroundColorSpan(0xFF2196F3), atStart, atEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        setLinkSpan(ss, projectUrl, projectStart, projectEnd);
        setLinkSpan(ss, releasesUrl, releasesStart, releasesEnd);
        setLinkSpan(ss, authorUrl, authorStart, authorEnd);
        setLinkSpan(ss, spamBlockerUrl, sbUrlStart, sbUrlEnd);

        TextView tv = new TextView(this);
        tv.setText(ss);
        tv.setTextSize(13); // 上一版放到 15sp 之后反馈偏大，改回 13sp
        tv.setTextColor(0xFF777777);
        tv.setLineSpacing(0, 1.4f);
        tv.setGravity(Gravity.CENTER);
        // 注意：这里不设 setTextIsSelectable(true)——一旦开启文字选中，
        // 下面几条链接的 ClickableSpan 就点不动了（触摸事件会被选中逻辑
        // 吃掉），两者二选一，这里优先保证链接可点击。
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        return tv;
    }

    /** 给 [start, end) 这段文字同时上蓝色 + 点击跳外部浏览器打开 url（"关于本软件"三条地址复用） */
    private void setLinkSpan(SpannableString ss, String url, int start, int end) {
        ss.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    toast("无法打开链接，请检查是否安装了浏览器");
                }
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                ds.setColor(0xFF2196F3);
                ds.setUnderlineText(false);
            }
        }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 板块标题，默认 11sp（"Root保活教程："沿用此默认大小） */
    private TextView section(String text) {
        return section(text, 11);
    }

    /** 板块标题，指定字号（本次改动中5个板块标题放大到 20sp，与顶部"来电识别"一致） */
    private TextView section(String text, float sp) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase());
        tv.setTextSize(sp);
        tv.setTextColor(0xFF777777);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.1f);
        return tv;
    }

    /** 按钮文字不设为可选中，避免长按选中干扰点击 */
    private Button btn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setBackgroundColor(color);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        return b;
    }

    /** 悬浮窗样式设置用的单选按钮（深色主题，白字） */
    private RadioButton radioBtn(String text) {
        return radioBtn(text, Color.WHITE);
    }

    /** 指定文字颜色的单选按钮重载（v3.19 新增，供"查询来源"三选一的橙/绿/蓝配色使用） */
    private RadioButton radioBtn(String text, int textColor) {
        RadioButton rb = new RadioButton(this);
        rb.setId(View.generateViewId());
        rb.setText(text);
        rb.setTextColor(textColor);
        rb.setTextSize(13);
        rb.setPadding(dp(4), dp(4), dp(16), dp(4));
        return rb;
    }

    /**
     * 板块级可折叠 header（v3.18 新增）：整行可点击，展开/折叠标题下方的 content 容器，
     * 并把新状态持久化到 ModuleSettings。这是"板块折叠"这一层的持久化——和
     * ManagedListSection 内部"▶ 展开 XX列表"那层折叠是完全独立的两层语义：外层
     * 板块折叠/展开时只是把整个 content 设成 GONE/VISIBLE，不会重建 ManagedListSection，
     * 所以内层列表本身的展开/收起状态天然保持不受影响，两层折叠可以同时展开。
     *
     * 这个方法只负责"整行可点击 + 切换 content 显隐 + 写入持久化状态"这一层 UI 逻辑，
     * 不管懒加载——ManagedListSection 内部折叠继续自己维护"首次展开才建行"的逻辑，
     * 板块级折叠里装的都是 RadioGroup/EditText 这种少量控件，onCreate 时一次性建好，
     * 用 setVisibility 切显隐即可，没必要懒加载。
     *
     * 板块折叠只收起标题下方的 content，分割线（调用方自己 add(root, line())）和
     * 标题本身（这个 header 行）永远露出。
     *
     * @param root      外层容器，header 和 content 都会被 add 进去
     * @param boardId   稳定英文标识，用于持久化 key，不能用中文标题当 key（以后改
     *                  文案会丢历史状态），例如 perm_service/sim_call/float_settings/
     *                  baidu_silent/web_query/cache_mgmt/user_mgmt/backup_import
     * @param titleText 板块中文标题，显示在 ▶/▼ 三角形后面
     * @param content   这个板块的内容容器（空的 VERTICAL LinearLayout），调用方后续
     *                  把这个板块的所有控件 add 到这个 content 里，而不是 add 到 root 里
     */
    private void addCollapsibleBoard(LinearLayout root, String boardId, String titleText, LinearLayout content) {
        boolean expanded = ModuleSettings.isBoardExpanded(this, boardId); // 首次安装（没有存储值）默认展开

        TextView tvHeader = section(titleText, 20); // 复用现有板块标题样式：20sp、加粗、灰色、字间距
        tvHeader.setText((expanded ? "▼ " : "▶ ") + titleText); // section() 内部会 toUpperCase()，这里覆盖成带三角形前缀的最终文字（对中文无影响）

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setClickable(true);
        headerRow.setFocusable(true);
        // 三角形后面留够内边距，整行都能点开合，不会出现"点空白区域点不到"
        headerRow.setPadding(0, dp(6), 0, dp(6));
        headerRow.addView(tvHeader);

        content.setVisibility(expanded ? View.VISIBLE : View.GONE);

        headerRow.setOnClickListener(v -> {
            boolean newExpanded = content.getVisibility() != View.VISIBLE;
            content.setVisibility(newExpanded ? View.VISIBLE : View.GONE);
            tvHeader.setText((newExpanded ? "▼ " : "▶ ") + titleText);
            ModuleSettings.setBoardExpanded(this, boardId, newExpanded);
        });

        // v4.0 新增：登记这个板块的"展开"动作和 header 行本身，供其它地方（比如顶部
        // "保活状态"的"去处理 →"链接）以 boardId 找到并触发展开/滚动过去，不用每个
        // 调用点都手动维护自己的 headerRow/content 引用。
        boardExpandTriggers.put(boardId, () -> {
            if (content.getVisibility() != View.VISIBLE) headerRow.performClick();
        });
        boardHeaderRows.put(boardId, headerRow);

        add(root, headerRow, 10);
        add(root, content, 0);
    }

    /**
     * 小折叠 header（v3.21 新增，"强制流量说明"专用）：视觉上借用
     * ManagedListSection 的"▶ 展开 X"/"▼ 收起 X"文案风格（箭头和"展开/收起"
     * 两个字都跟着状态切换，跟 addCollapsibleBoard 那种"箭头单独切换、标题
     * 本身不变"的大板块风格不是一回事），颜色/字号可以传参自定义。
     *
     * 持久化机制跟 addCollapsibleBoard 完全一样，复用同一套
     * ModuleSettings.isBoardExpanded/setBoardExpanded（首次打开默认展开、
     * 跨重启记忆状态）——不是 ManagedListSection 那种每次开 App 都重置成
     * 收起的临时状态，两者只是外观像，持久化行为不一样。
     *
     * @param root      外层容器（这里传的是某个大板块的 content，不是顶层 root）
     * @param boardId   持久化 key，需要跟其他板块的 key 不重复
     * @param titleText 显示在"▶ 展开 "/"▼ 收起 "后面的文字
     * @param content   这个小折叠的内容容器
     * @param topDp     header 行相对上一个控件的顶部间距（dp）
     */
    private void addWarningFold(LinearLayout root, String boardId, String titleText, LinearLayout content, int topDp) {
        addWarningFold(root, boardId, titleText, content, topDp, true);
    }

    /** 同上，但可以指定"从没展开/收起过时"的默认状态（v4.0 新增，"保活明细"要求默认收起）。 */
    private void addWarningFold(LinearLayout root, String boardId, String titleText, LinearLayout content, int topDp, boolean defaultExpanded) {
        boolean expanded = ModuleSettings.isBoardExpanded(this, boardId, defaultExpanded);

        TextView tvHeader = new TextView(this);
        tvHeader.setText((expanded ? "▼ 收起 " : "▶ 展开 ") + titleText);
        tvHeader.setTextColor(0xFFFF9900);
        tvHeader.setTextSize(13);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setClickable(true);
        headerRow.setFocusable(true);
        headerRow.setPadding(0, dp(6), 0, dp(6));
        headerRow.addView(tvHeader);

        content.setVisibility(expanded ? View.VISIBLE : View.GONE);

        headerRow.setOnClickListener(v -> {
            boolean newExpanded = content.getVisibility() != View.VISIBLE;
            content.setVisibility(newExpanded ? View.VISIBLE : View.GONE);
            tvHeader.setText((newExpanded ? "▼ 收起 " : "▶ 展开 ") + titleText);
            ModuleSettings.setBoardExpanded(this, boardId, newExpanded);
        });

        add(root, headerRow, topDp);
        add(root, content, 0);
    }

    /** "查询来源"配色映射（v3.19 新增）：搜狗＝橙、360＝绿、自定义＝蓝 */
    private int colorForSource(int source) {
        if (source == ModuleSettings.WEB_SOURCE_360) return COLOR_SOURCE_360;
        if (source == ModuleSettings.WEB_SOURCE_CUSTOM) return COLOR_SOURCE_CUSTOM;
        return COLOR_SOURCE_SOGOU;
    }

    private View line() {
        View v = new View(this);
        v.setBackgroundColor(0xFF2A2A2A);
        v.setMinimumHeight(dp(1));
        return v;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * 通知 FloatWindowService 做一次"设置实时生效"局部刷新（v3.17 新增）。
     * 只用于背景样式 / 是否显示来电号码 / 是否显示查询结果这三项——它们在设置页
     * 里本来就没有独立的"保存"按钮，是 RadioGroup 选中后立刻持久化，所以这里
     * 也在改值的同一个监听器回调里立刻发通知，不等待任何"保存"动作。
     * 如果当前没有真实来电、悬浮窗还没创建，FloatWindowService 那边会因为
     * floatView == null 直接忽略，不会有任何副作用。
     */
    private void notifySettingsChanged() {
        Intent i = new Intent(this, FloatWindowService.class);
        i.setAction("com.callerid.ACTION_SETTINGS_CHANGED");
        startService(i);
    }

    /**
     * 判断"是否显示来电号码"/"是否显示查询结果"/"悬浮窗网页查询"这三项如果按给定
     * 的新状态生效，会不会导致悬浮窗上三项全部关闭（悬浮窗形同不显示）。
     * v3.15 新增：三个开关各自的 OnCheckedChange 回调在"用户把某一项关掉"时，
     * 会用这个方法检查一下"关掉这一项之后，另外两项是不是也已经是关闭状态"，
     * 只有在会导致三项全部关闭时才弹确认框，和用户关闭的先后顺序无关。
     */
    private boolean wouldHideEverything(boolean showNumber, boolean showQueryResult, boolean webQueryEnabled) {
        return !showNumber && !showQueryResult && !webQueryEnabled;
    }

    /** 弹出"即将关闭悬浮窗所有项目"确认框；确认后执行 onConfirm，取消后执行 onCancel（用于把控件视觉状态复原）。 */
    private void confirmHideAllThenApply(Runnable onConfirm, Runnable onCancel) {
        new AlertDialog.Builder(this)
                .setTitle("确认关闭")
                .setMessage("即将关闭悬浮窗所有项目，悬浮窗将不显示，是否继续？")
                .setCancelable(false)
                .setPositiveButton("继续", (d, w) -> onConfirm.run())
                .setNegativeButton("取消", (d, w) -> onCancel.run())
                .show();
    }

    /**
     * 给状态 TextView 设置"当前状态：xxx"文案，"当前状态："前缀保持灰色，
     * 后面的状态词按 isOn 显示绿色（onLabel）或红色（offLabel）。
     */
    private void setStatusText(TextView tv, boolean isOn, String onLabel, String offLabel) {
        setStatusText(tv, "当前状态：", isOn, onLabel, offLabel);
    }

    /**
     * 同上，但前缀文案可自定义（v3.21 新增，供顶部状态总览三行复用同一套
     * 灰色标签 + 绿/红状态值的配色逻辑，只是标签文字不是"当前状态："）。
     */
    private void setStatusText(TextView tv, String label, boolean isOn, String onLabel, String offLabel) {
        String value = isOn ? onLabel : offLabel;
        int color = isOn ? 0xFF44CC44 : 0xFFFF3333;
        setStatusText(tv, label, value, color);
    }

    /**
     * 同上，但不是简单的"好/坏"二元状态，而是给一个中性、不报警的颜色和自定义文案
     * （v4.2 新增，"来电识别服务"这一行专用——常驻实例是否存在，跟"来电时弹不弹得出
     * 悬浮窗"其实是两件不完全相关的事：常驻实例被系统按内存压力回收掉是正常现象，
     * 每次真实来电都会由 CallReceiver 独立重新拉起一个新实例，不依赖常驻实例是否
     * 还活着。用刺眼的红色"已停止"容易让人误以为功能坏了，这里改成中性的橙色
     * "闲置"，并在旁边点一句"不影响来电弹窗"）。
     */
    private void setStatusText(TextView tv, String label, String value, int valueColor) {
        SpannableString ss = new SpannableString(label + value);
        ss.setSpan(new ForegroundColorSpan(0xFF888888), 0, label.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new ForegroundColorSpan(valueColor), label.length(), ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(ss);
    }

    /**
     * v4.9 新增：查询/设置 CallReceiver 组件本身的启用状态——这是"来电识别服务"
     * 开关真正的数据来源，直接读写 PackageManager 的组件状态记录，系统自己
     * 持久化、跨重启有效，不需要我们另外存一份 SharedPreferences。
     * DONT_KILL_APP：切换这个开关不需要、也不应该杀掉整个 App 进程重启。
     */
    private boolean isCallServiceEnabled() {
        int state = getPackageManager().getComponentEnabledSetting(
                new ComponentName(this, CallReceiver.class));
        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setCallServiceEnabled(boolean enabled) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, CallReceiver.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    /**
     * 查询 QueryServerService（"SpamBlocker 联动"本地查询服务）是否正在运行（v3.19 新增）。
     * 用 ActivityManager 查真实运行状态，不用进程内静态标志位——原因和这个服务
     * 本身的道理一样：普通 Service 常被系统按后台限制杀掉重启进程，静态字段在
     * 新进程里会被重置，导致显示和真实状态不一致。
     */
    private boolean isQueryServerRunning() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            for (ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
                if (QueryServerService.class.getName().equals(info.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 把文本写入系统剪贴板（v3.19 新增，供"SpamBlocker 联动"板块的 token/URL 复制按钮使用）。 */
    private void copyText(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    /**
     * 测试查询：跳过 SpamBlocker，在后台线程直接用 HttpURLConnection 打本地服务的
     * /query 接口，用来验证"服务是否真的在监听 + 查询链路是否正常"，和真实联动
     * 走的是同一条路径，只是发起方从 SpamBlocker 换成了这个测试按钮本身（v3.19 新增）。
     * 必须在后台线程调用——网络请求不能跑在主线程。
     */
    private String testQueryLocalServer(int port, String token, String number) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "http://127.0.0.1:" + port + "/query?number="
                    + java.net.URLEncoder.encode(number, "UTF-8")
                    + "&token=" + java.net.URLEncoder.encode(token, "UTF-8");
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            return "HTTP " + code + "\n" + sb;
        } catch (Exception e) {
            return "请求失败：" + e;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
