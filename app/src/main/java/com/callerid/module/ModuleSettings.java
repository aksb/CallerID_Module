package com.callerid.module;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;
import android.util.TypedValue;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;

/**
 * 模块设置持久化。
 *
 * - 是否强制使用蜂窝数据流量查询（即使已连接 WiFi）。默认关闭。
 * - 悬浮窗背景样式：0=半透明黑（默认），1=全透明，2=半透明白。
 * - 悬浮窗内打开网页查询功能（v1.8 新增）：
 *   总开关默认关闭；查询来源 搜狗/360/自定义 三选一（默认搜狗）；
 *   网页字体档位 跟随系统/80%/100%/120%/150%（默认跟随系统）；
 *   顶部裁剪像素（v1.9 新增，默认 250）；
 *   深色模式 跟随系统/浅色/深色（v1.10 新增，默认跟随系统）。
 *
 * v3.17 改动：
 *  - 移除"悬浮窗字体大小"这一独立设置项（FONT_SIZE_* 全部删除）：字号档位
 *    改由"悬浮窗宽度"直接决定（见 FloatWindowService 的拖角缩放逻辑），
 *    不再需要用户单独设置字号，也不用管理"档位 × 宽度"两套来源打架的问题。
 *  - "网页底部裁剪像素"（原 KEY_WEB_BOTTOM_CROP_PX，语义是"从基础高度里减掉多少"）
 *    改为"网页展示高度像素"（KEY_WEB_HEIGHT_PX，语义是直接存绝对展示高度），
 *    因为拖角缩放需要把展示高度放宽到屏幕高度 60%，超出了原来"裁剪"算法
 *    能表达的范围（裁剪值不能是负数，原算法上限被封死在 28% 屏幕高度）。
 *    没有旧用户需要迁移，直接切换语义。
 *  - 新增"悬浮窗宽度"持久化（KEY_FLOAT_WIDTH_CUSTOMIZED + KEY_FLOAT_WIDTH_PX），
 *    用法仿照悬浮窗拖动位置的存储方式：没拖过 = 未自定义（悬浮窗维持
 *    WRAP_CONTENT 的自然宽度）；拖过一次 = 之后固定使用保存的绝对像素宽。
 *
 * v3.19 改动：
 *  - 新增"是否可拖角缩放"（KEY_FLOAT_RESIZABLE，默认开启），跟"是否可移动"是
 *    完全正交的独立开关，见 isFloatResizable()/setFloatResizable()。
 *  - 重新引入"悬浮窗字体大小"档位设置，但改成"二选一模式开关"而不是叠加：
 *    KEY_FONT_SIZE_MODE（跟随窗口自动/固定大小，默认自动＝维持 v3.17 起的
 *    行为不变）+ KEY_FONT_SIZE_LEVEL（小/中/大/特大，仅"固定大小"模式下生效），
 *    避免重蹈 v3.17 之前"档位 × 宽度"两套来源打架的覆辙。
 */
public class ModuleSettings {

    private static final String TAG  = "CallerID_ModuleSettings";
    private static final String PREF = "module_settings";
    private static final String KEY_FORCE_CELLULAR  = "force_cellular_query";
    private static final String KEY_FLOAT_BG_STYLE  = "float_bg_style";
    private static final String KEY_FLOAT_WIDTH_CUSTOMIZED = "float_width_customized";
    private static final String KEY_FLOAT_WIDTH_PX  = "float_width_px";
    private static final String KEY_WEB_QUERY_ENABLED   = "web_query_enabled";
    private static final String KEY_WEB_QUERY_SOURCE    = "web_query_source";
    private static final String KEY_WEB_QUERY_CUSTOM_URL = "web_query_custom_url";
    private static final String KEY_WEB_FONT_ZOOM       = "web_font_zoom";
    private static final String KEY_WEB_HEIGHT_PX       = "web_height_px"; // v3.17：原 web_bottom_crop_px 改为直接存绝对高度
    private static final String KEY_WEB_DARK_MODE       = "web_dark_mode";
    private static final String KEY_SHOW_CALLER_NUMBER  = "show_caller_number";
    private static final String KEY_SHOW_QUERY_RESULT   = "show_query_result";
    private static final String KEY_QUERY_RESULT_LINES  = "query_result_lines"; // v3.19 新增
    private static final String KEY_WEB_QUERY_DEFAULT_EXPANDED = "web_query_default_expanded";
    private static final String KEY_BAIDU_SILENT_QUERY  = "baidu_silent_query_enabled";
    private static final String KEY_FLOAT_MOVABLE       = "float_movable"; // v3.18 新增
    private static final String KEY_FLOAT_RESIZABLE     = "float_resizable"; // v3.19 新增
    private static final String KEY_FONT_SIZE_MODE      = "font_size_mode";  // v3.19 新增
    private static final String KEY_FONT_SIZE_LEVEL     = "font_size_level"; // v3.19 新增

    // SpamBlocker 联动（v3.19 新增，见下方专属分区注释）
    private static final String KEY_SB_AUTOSTART       = "sb_autostart";
    private static final String KEY_SB_PORT            = "sb_port";
    private static final String KEY_SB_TOKEN           = "sb_token";
    private static final String KEY_SB_TIMEOUT_MS      = "sb_timeout_ms";
    private static final String KEY_SB_KEYWORDS        = "sb_keywords";

    // 网页顶部裁剪像素（v3.18：由三来源共用一个 key 拆分成各自独立的 key）
    private static final String KEY_WEB_TOP_CROP_PX_SOGOU  = "web_top_crop_px_sogou";
    private static final String KEY_WEB_TOP_CROP_PX_360    = "web_top_crop_px_360";
    private static final String KEY_WEB_TOP_CROP_PX_CUSTOM = "web_top_crop_px_custom";

    // 设置页板块级折叠状态持久化前缀（v3.18 新增），实际 key = 前缀 + boardId，
    // boardId 例如 perm_service/sim_call/float_settings/baidu_silent/web_query/
    // cache_mgmt/user_mgmt/backup_import（英文稳定标识，不随文案改动而丢失历史状态）。
    private static final String KEY_BOARD_EXPANDED_PREFIX = "board_expanded_";

    public static final int BG_STYLE_TRANSLUCENT_BLACK = 0;
    public static final int BG_STYLE_TRANSPARENT       = 1;
    public static final int BG_STYLE_TRANSLUCENT_WHITE = 2;

    /** 网页顶部裁剪像素默认值（实测搜狗/360顶部固定区域高度都在此附近）。搜狗/360 默认沿用此值。 */
    public static final int WEB_TOP_CROP_DEFAULT_PX = 250;

    /** "自定义"来源的顶部裁剪默认值（v3.18 新增）：网址因用户而异，没有通用经验值，默认 0＝不裁剪，更安全。 */
    public static final int WEB_TOP_CROP_DEFAULT_PX_CUSTOM = 0;

    /**
     * 网页查询展开区域"基础高度"占屏幕高度的比例（v3.14 新增为公开常量，供底部裁剪
     * 默认值计算复用；原为 FloatWindowService 内部私有常量，取值不变）。
     * v3.17：现在只用来算未设置过时的默认展示高度，见 getWebHeightPx()。
     */
    public static final float WEB_HEIGHT_RATIO = 0.28f;

    /** 网页展示区域高度下限（dp），防止拖到看不清/裁没（v3.17 新增为公开常量）。 */
    public static final int WEB_HEIGHT_MIN_DP = 60;

    /** 网页展示区域高度上限，占屏幕高度的比例（v3.17 新增，拖角缩放的高度上限）。 */
    public static final float WEB_HEIGHT_MAX_RATIO = 0.60f;

    /** 悬浮窗宽度下限，占屏幕宽度的比例（v3.17 新增，拖角缩放的宽度下限）。 */
    public static final float FLOAT_WIDTH_MIN_RATIO = 0.45f;

    /** 悬浮窗宽度上限，占屏幕宽度的比例（v3.17 新增，沿用原 fitTextToScreen 的 90% 收缩基准）。 */
    public static final float FLOAT_WIDTH_MAX_RATIO = 0.90f;

    // 悬浮窗内网页查询：网页深色模式（v1.10 新增，v3.13 新增强制反色档位）
    public static final int WEB_DARK_MODE_SYSTEM       = 0; // 默认，跟随系统深色模式设置
    public static final int WEB_DARK_MODE_LIGHT        = 1; // 强制浅色（白底）
    public static final int WEB_DARK_MODE_DARK         = 2; // 强制深色（黑底）
    public static final int WEB_DARK_MODE_FORCE_INVERT = 3; // 强制反色（CSS filter，纯前端，100% 生效）

    // 悬浮窗内网页查询：来源三选一
    public static final int WEB_SOURCE_SOGOU  = 0; // 默认
    public static final int WEB_SOURCE_360    = 1;
    public static final int WEB_SOURCE_CUSTOM = 2;

    // 悬浮窗内网页查询：字体缩放档位
    public static final int WEB_FONT_ZOOM_SYSTEM = 0; // 默认，跟随系统 Configuration.fontScale
    public static final int WEB_FONT_ZOOM_80     = 1;
    public static final int WEB_FONT_ZOOM_100    = 2;
    public static final int WEB_FONT_ZOOM_120    = 3;
    public static final int WEB_FONT_ZOOM_150    = 4;

    // 悬浮窗"标签/号码"字体大小模式（v3.19 新增，重新引入独立字号设置，但用
    // "二选一模式开关"而不是叠加，避免重蹈 v3.17 之前"档位 × 宽度"两套来源打架的覆辙）
    public static final int FONT_SIZE_MODE_AUTO  = 0; // 默认：完全维持 v3.17 起的行为，字号跟随悬浮窗宽度联动
    public static final int FONT_SIZE_MODE_FIXED = 1; // 忽略宽度联动，字号只由下面的档位决定（宽度可以照常拖角调整，两者互不影响）

    // 固定大小模式下的字号档位。"大"对齐现在的默认基准（NUM_BASE_SP/TAG_BASE_SP，
    // 即 1.0x），不选这个新设置的人（默认"自动"模式）视觉上不会有任何变化。
    public static final int FONT_SIZE_LEVEL_SMALL  = 0; // 0.75x
    public static final int FONT_SIZE_LEVEL_MEDIUM = 1; // 0.875x
    public static final int FONT_SIZE_LEVEL_LARGE  = 2; // 1.0x（默认，等同现在的效果）
    public static final int FONT_SIZE_LEVEL_XLARGE = 3; // 1.25x

    // 内置查询网址（不可编辑）。占位词"来电号码"会在实际打开时替换为来电号码。
    // 均已去除非必需的来源统计参数（如 from=index）。
    public static final String URL_SOGOU = "https://wap.sogou.com/web/searchList.jsp?keyword=来电号码";
    public static final String URL_360   = "https://m.so.com/s?q=来电号码";

    private static SharedPreferences sp;

    public static synchronized void init(Context ctx) {
        if (sp == null) {
            sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }
    }

    /** 是否强制使用蜂窝数据流量查询。默认 false（使用系统默认联网方式）。 */
    public static boolean isForceCellular(Context ctx) {
        init(ctx);
        return sp.getBoolean(KEY_FORCE_CELLULAR, false);
    }

    public static void setForceCellular(Context ctx, boolean enabled) {
        init(ctx);
        sp.edit().putBoolean(KEY_FORCE_CELLULAR, enabled).apply();
        Log.d(TAG, "setForceCellular = " + enabled);
    }

    /** 悬浮窗背景样式：BG_STYLE_TRANSLUCENT_BLACK / BG_STYLE_TRANSPARENT。默认半透明黑。 */
    public static int getFloatBgStyle(Context ctx) {
        init(ctx);
        return sp.getInt(KEY_FLOAT_BG_STYLE, BG_STYLE_TRANSLUCENT_BLACK);
    }

    public static void setFloatBgStyle(Context ctx, int style) {
        init(ctx);
        sp.edit().putInt(KEY_FLOAT_BG_STYLE, style).apply();
        Log.d(TAG, "setFloatBgStyle = " + style);
    }

    // ── 悬浮窗宽度（v3.17 新增，配合拖角缩放） ──────────────────────────

    private static int clampFloatWidthPx(int px) {
        int screenWidthPx = Resources.getSystem().getDisplayMetrics().widthPixels;
        int min = Math.round(screenWidthPx * FLOAT_WIDTH_MIN_RATIO);
        int max = Math.round(screenWidthPx * FLOAT_WIDTH_MAX_RATIO);
        if (max < min) max = min;
        return Math.max(min, Math.min(max, px));
    }

    /** 悬浮窗宽度是否已被用户拖角自定义过。false 时悬浮窗维持原来的 WRAP_CONTENT 自然宽度。 */
    public static boolean isFloatWidthCustomized(Context ctx) {
        init(ctx);
        return sp.getBoolean(KEY_FLOAT_WIDTH_CUSTOMIZED, false);
    }

    /**
     * 用户拖角自定义的悬浮窗宽度（像素），已按 [FLOAT_WIDTH_MIN_RATIO, FLOAT_WIDTH_MAX_RATIO]
     * 相对当前屏幕宽度做过钳制。只有 isFloatWidthCustomized() 为 true 时这个值才有意义。
     */
    public static int getFloatWidthPx(Context ctx) {
        init(ctx);
        return clampFloatWidthPx(sp.getInt(KEY_FLOAT_WIDTH_PX, 0));
    }

    public static void setFloatWidthPx(Context ctx, int px) {
        init(ctx);
        int clamped = clampFloatWidthPx(px);
        sp.edit().putBoolean(KEY_FLOAT_WIDTH_CUSTOMIZED, true)
                .putInt(KEY_FLOAT_WIDTH_PX, clamped).apply();
        Log.d(TAG, "setFloatWidthPx = " + clamped);
    }

    // ── 悬浮窗内打开网页查询功能（v1.8 新增） ──────────────────────────

    /** 总开关：悬浮窗内是否显示"打开/关闭网页查询"按钮。默认关闭。 */
    public static boolean isWebQueryEnabled(Context ctx) {
        init(ctx);
        return sp.getBoolean(KEY_WEB_QUERY_ENABLED, false);
    }

    public static void setWebQueryEnabled(Context ctx, boolean enabled) {
        init(ctx);
        sp.edit().putBoolean(KEY_WEB_QUERY_ENABLED, enabled).apply();
        Log.d(TAG, "setWebQueryEnabled = " + enabled);
    }

    /** 查询来源：WEB_SOURCE_SOGOU / WEB_SOURCE_360 / WEB_SOURCE_CUSTOM。默认搜狗。 */
    public static int getWebQuerySource(Context ctx) {
        init(ctx);
        return sp.getInt(KEY_WEB_QUERY_SOURCE, WEB_SOURCE_SOGOU);
    }

    public static void setWebQuerySource(Context ctx, int source) {
        init(ctx);
        sp.edit().putInt(KEY_WEB_QUERY_SOURCE, source).apply();
        Log.d(TAG, "setWebQuerySource = " + source);
    }

    /** 自定义查询网址（仅当来源选择"自定义"时生效）。默认空字符串。 */
    public static String getWebQueryCustomUrl(Context ctx) {
        init(ctx);
        return sp.getString(KEY_WEB_QUERY_CUSTOM_URL, "");
    }

    public static void setWebQueryCustomUrl(Context ctx, String url) {
        init(ctx);
        sp.edit().putString(KEY_WEB_QUERY_CUSTOM_URL, url == null ? "" : url).apply();
        Log.d(TAG, "setWebQueryCustomUrl = " + url);
    }

    /** 网页字体缩放档位：WEB_FONT_ZOOM_SYSTEM/80/100/120/150。默认跟随系统。 */
    public static int getWebFontZoom(Context ctx) {
        init(ctx);
        return sp.getInt(KEY_WEB_FONT_ZOOM, WEB_FONT_ZOOM_SYSTEM);
    }

    public static void setWebFontZoom(Context ctx, int tier) {
        init(ctx);
        sp.edit().putInt(KEY_WEB_FONT_ZOOM, tier).apply();
        Log.d(TAG, "setWebFontZoom = " + tier);
    }

    /**
     * 网页顶部裁剪像素（v1.9 新增，v3.18 拆分为三来源各自独立）：把 WebView 顶部固定的
     * 搜索框/标签栏区域裁掉不显示。搜狗/360/自定义三个来源各自一份独立存储的值，
     * 互不影响。0 = 不裁剪。搜狗/360 默认 250，自定义默认 0（网址因用户而异，没有
     * 通用经验值，默认不裁剪更安全）。
     * 有效性校验（非数字/负数 → 0）在设置界面（MainActivity）保存时完成，
     * 这里读到的值始终是已校验过的合法值（>= 0）。
     * @param source WEB_SOURCE_SOGOU / WEB_SOURCE_360 / WEB_SOURCE_CUSTOM
     */
    public static int getWebTopCropPx(Context ctx, int source) {
        init(ctx);
        String key = webTopCropKey(source);
        int def = (source == WEB_SOURCE_CUSTOM) ? WEB_TOP_CROP_DEFAULT_PX_CUSTOM : WEB_TOP_CROP_DEFAULT_PX;
        return sp.getInt(key, def);
    }

    public static void setWebTopCropPx(Context ctx, int source, int px) {
        init(ctx);
        sp.edit().putInt(webTopCropKey(source), Math.max(0, px)).apply();
        Log.d(TAG, "setWebTopCropPx[" + source + "] = " + px);
    }

    private static String webTopCropKey(int source) {
        if (source == WEB_SOURCE_360) return KEY_WEB_TOP_CROP_PX_360;
        if (source == WEB_SOURCE_CUSTOM) return KEY_WEB_TOP_CROP_PX_CUSTOM;
        return KEY_WEB_TOP_CROP_PX_SOGOU;
    }

    // ── 网页展示高度（v3.17 重做：原"底部裁剪像素"改为直接存绝对高度） ──────

    private static int webHeightMinPx() {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, WEB_HEIGHT_MIN_DP,
                Resources.getSystem().getDisplayMetrics()));
    }

    private static int webHeightMaxPx() {
        int screenHeightPx = Resources.getSystem().getDisplayMetrics().heightPixels;
        return Math.round(screenHeightPx * WEB_HEIGHT_MAX_RATIO);
    }

    private static int clampWebHeightPx(int px) {
        int min = webHeightMinPx();
        int max = webHeightMaxPx();
        if (max < min) max = min; // 极端小屏兜底，避免 min > max 导致钳制区间为空
        return Math.max(min, Math.min(max, px));
    }

    /**
     * 网页查询展示区域的高度（像素），下限 WEB_HEIGHT_MIN_DP，上限屏幕高度的
     * WEB_HEIGHT_MAX_RATIO（60%）。可通过拖角缩放或设置页数字输入框两种方式编辑，
     * 是同一个存储值的两种录入方式。
     * 从未保存过时的默认值：沿用 v3.16 之前的默认观感（基础高度的 75%，
     * 基础高度 = 屏幕高度 × WEB_HEIGHT_RATIO）。
     */
    public static int getWebHeightPx(Context ctx) {
        init(ctx);
        if (sp.contains(KEY_WEB_HEIGHT_PX)) {
            return clampWebHeightPx(sp.getInt(KEY_WEB_HEIGHT_PX, 0));
        }
        int screenHeightPx = Resources.getSystem().getDisplayMetrics().heightPixels;
        int baseHeightPx = Math.round(screenHeightPx * WEB_HEIGHT_RATIO);
        int defaultPx = Math.round(baseHeightPx * 0.75f);
        return clampWebHeightPx(defaultPx);
    }

    public static void setWebHeightPx(Context ctx, int px) {
        init(ctx);
        int clamped = clampWebHeightPx(px);
        sp.edit().putInt(KEY_WEB_HEIGHT_PX, clamped).apply();
        Log.d(TAG, "setWebHeightPx = " + clamped);
    }

    /** 网页深色模式：WEB_DARK_MODE_SYSTEM/LIGHT/DARK/FORCE_INVERT。默认跟随系统。 */
    public static int getWebDarkMode(Context ctx) {
        init(ctx);
        return sp.getInt(KEY_WEB_DARK_MODE, WEB_DARK_MODE_SYSTEM);
    }

    public static void setWebDarkMode(Context ctx, int mode) {
        init(ctx);
        sp.edit().putInt(KEY_WEB_DARK_MODE, mode).apply();
        Log.d(TAG, "setWebDarkMode = " + mode);
    }

    // ── 悬浮窗第二行来电号码显隐（v3.13 新增） ──────────────────────────

    /** 悬浮窗第二行（号码）是否显示。默认显示。 */
    public static boolean isShowCallerNumber(Context ctx) {
        init(ctx);
        return sp.getBoolean(KEY_SHOW_CALLER_NUMBER, true);
    }

    public static void setShowCallerNumber(Context ctx, boolean show) {
        init(ctx);
        sp.edit().putBoolean(KEY_SHOW_CALLER_NUMBER, show).apply();
        Log.d(TAG, "setShowCallerNumber = " + show);
    }

    /** 悬浮窗"查询结果/标签"行是否显示（v3.15 新增）。默认显示。 */
    public static boolean isShowQueryResult(Context ctx) {
        init(ctx);
        return sp.getBoolean(KEY_SHOW_QUERY_RESULT, true);
    }

    public static void setShowQueryResult(Context ctx, boolean show) {
        init(ctx);
        sp.edit().putBoolean(KEY_SHOW_QUERY_RESULT, show).apply();
        Log.d(TAG, "setShowQueryResult = " + show);
    }

    /**
     * 查询结果显示行数（v3.19 新增）：1＝一行（超出截断），2＝两行（超出的部分换到
     * 第二行，再超出才截断）。默认 1，与现有行为一致。
     */
    public static int getQueryResultLines(Context ctx) {
        init(ctx);
        return sp.getInt(KEY_QUERY_RESULT_LINES, 1);
    }

    public static void setQueryResultLines(Context ctx, int lines) {
        init(ctx);
        sp.edit().putInt(KEY_QUERY_RESULT_LINES, (lines == 2) ? 2 : 1).apply();
        Log.d(TAG, "setQueryResultLines = " + lines);
    }

    /**
     * 网页查询是否"默认展开"（v3.15 新增）：勾选后，悬浮窗一出现（且网页查询总开关
     * 已启用）就自动展开网页区域，不需要用户再点一次按钮。默认不勾选（保持原行为）。
     * 依附于"悬浮窗内打开网页查询功能"总开关：总开关关闭时这一项本身也是隐藏状态，
     * 不会生效。
     */
    public static boolean isWebQueryDefaultExpanded(Context ctx) {
        init(ctx);
        return sp.getBoolean(KEY_WEB_QUERY_DEFAULT_EXPANDED, false);
    }

    public static void setWebQueryDefaultExpanded(Context ctx, boolean expanded) {
        init(ctx);
        sp.edit().putBoolean(KEY_WEB_QUERY_DEFAULT_EXPANDED, expanded).apply();
        Log.d(TAG, "setWebQueryDefaultExpanded = " + expanded);
    }

    // ── 百度静默查询开关（v3.13 新增） ────────────────────────────────
    // 只影响查询链路第4步（WebView 联网查询 mhaoma.baidu.com）：
    // 关闭后，本地自定义库/缓存/白名单/内置库命中的号码完全不受影响，
    // 仅当本地未命中时不再联网查询，直接回落到"未知号码"。默认启用（原有行为）。

    public static boolean isBaiduSilentQueryEnabled(Context ctx) {
        init(ctx);
        return sp.getBoolean(KEY_BAIDU_SILENT_QUERY, true);
    }

    public static void setBaiduSilentQueryEnabled(Context ctx, boolean enabled) {
        init(ctx);
        sp.edit().putBoolean(KEY_BAIDU_SILENT_QUERY, enabled).apply();
        Log.d(TAG, "setBaiduSilentQueryEnabled = " + enabled);
    }

    // ── 悬浮窗是否可移动（v3.18 新增） ────────────────────────────────
    // true（默认）：完全维持原有拖动+双击折叠逻辑。
    // false：悬浮窗本体（标签/号码区域、按钮行两侧空白）不可拖动，单击/双击均可折叠成球；
    // 小球本身始终可拖动、拖角缩放始终可用，不受此开关影响。

    public static boolean isFloatMovable(Context ctx) {
        init(ctx);
        return sp.getBoolean(KEY_FLOAT_MOVABLE, true);
    }

    public static void setFloatMovable(Context ctx, boolean movable) {
        init(ctx);
        sp.edit().putBoolean(KEY_FLOAT_MOVABLE, movable).apply();
        Log.d(TAG, "setFloatMovable = " + movable);
    }

    // ── 悬浮窗是否可拖角缩放（v3.19 新增） ────────────────────────────────
    // true（默认）：完全维持现状，四个角可以拖拽改变悬浮窗宽度/网页展示高度。
    // false：四个角的拖拽热区整个隐藏并停用（不只是触摸不生效），已经调好的
    // 宽度/高度不受影响，不会被重置——跟"是否可移动"那边"先摆好位置再设置
    // 不可移动"是同一个思路：先把大小调好，再关掉这个开关锁死，防止手滑碰到。
    // 与"是否可移动"是完全正交的两个开关，两者控制不同的手势区域，互不干扰。

    public static boolean isFloatResizable(Context ctx) {
        init(ctx);
        return sp.getBoolean(KEY_FLOAT_RESIZABLE, true);
    }

    public static void setFloatResizable(Context ctx, boolean resizable) {
        init(ctx);
        sp.edit().putBoolean(KEY_FLOAT_RESIZABLE, resizable).apply();
        Log.d(TAG, "setFloatResizable = " + resizable);
    }

    // ── 悬浮窗字体大小模式（v3.19 新增） ──────────────────────────────────
    // "跟随窗口自动"（默认）：维持 v3.17 起的行为，字号由悬浮窗宽度直接决定。
    // "固定大小"：忽略宽度联动，字号只由档位（小/中/大/特大）决定，宽度改变
    // （不管是拖角缩放还是自然撑开）都不会再影响字号。两个来源永远只有一个
    // 在生效，从根源上避免"档位 × 宽度"打架。
    // "防止文字溢出的自动收缩兜底逻辑"（最小 12sp）在两种模式下都继续保留，
    // 固定大小严格说是"目标字号"，极端情况下仍可能被再压小。

    public static int getFontSizeMode(Context ctx) {
        init(ctx);
        return sp.getInt(KEY_FONT_SIZE_MODE, FONT_SIZE_MODE_AUTO);
    }

    public static void setFontSizeMode(Context ctx, int mode) {
        init(ctx);
        int clamped = (mode == FONT_SIZE_MODE_FIXED) ? FONT_SIZE_MODE_FIXED : FONT_SIZE_MODE_AUTO;
        sp.edit().putInt(KEY_FONT_SIZE_MODE, clamped).apply();
        Log.d(TAG, "setFontSizeMode = " + clamped);
    }

    /** 固定大小模式下使用的字号档位：FONT_SIZE_LEVEL_SMALL/MEDIUM/LARGE/XLARGE。默认"大"。 */
    public static int getFontSizeLevel(Context ctx) {
        init(ctx);
        int level = sp.getInt(KEY_FONT_SIZE_LEVEL, FONT_SIZE_LEVEL_LARGE);
        if (level < FONT_SIZE_LEVEL_SMALL || level > FONT_SIZE_LEVEL_XLARGE) return FONT_SIZE_LEVEL_LARGE;
        return level;
    }

    public static void setFontSizeLevel(Context ctx, int level) {
        init(ctx);
        if (level < FONT_SIZE_LEVEL_SMALL || level > FONT_SIZE_LEVEL_XLARGE) level = FONT_SIZE_LEVEL_LARGE;
        sp.edit().putInt(KEY_FONT_SIZE_LEVEL, level).apply();
        Log.d(TAG, "setFontSizeLevel = " + level);
    }

    /** 档位 → 缩放系数。"大"＝1.0x，对齐现在的默认字号（NUM_BASE_SP/TAG_BASE_SP）。 */
    public static float fontSizeLevelScale(int level) {
        switch (level) {
            case FONT_SIZE_LEVEL_SMALL:  return 0.75f;
            case FONT_SIZE_LEVEL_MEDIUM: return 0.875f;
            case FONT_SIZE_LEVEL_XLARGE: return 1.25f;
            case FONT_SIZE_LEVEL_LARGE:
            default:                     return 1.0f;
        }
    }

    // ── SpamBlocker 联动（v3.19 新增） ──────────────────────────────────
    // 本模块开一个只监听 127.0.0.1 的极简本地 HTTP 查询服务（QueryServerService /
    // LocalQueryServer），SpamBlocker 的「即时查询」功能配置指向这个地址后，来电时
    // 会主动发 HTTP 请求过来，本模块复用 WebQueryHelper 的查询链路（自定义库/缓存/
    // 白名单/内置库/联网查询）拿到号码标签，再按下面的关键词表判断 is_spam，返回
    // JSON 给 SpamBlocker 做拦截决策。方向是 SpamBlocker 主动来问、本模块被动回答，
    // 不是本模块主动推送。

    private static final int SB_PORT_DEFAULT = 18831;
    private static final int SB_TIMEOUT_MS_DEFAULT = 4000;
    private static final String SB_KEYWORDS_DEFAULT = "营销,广告,推销,骚扰,诈骗,保险,贷款,理财";

    /**
     * 开机是否自动启动本地查询服务。默认关闭。
     * 运行状态本身不额外存一个"总开关"字段——和 FloatWindowService 保持同样的模式，
     * 服务是否在跑，实时用 ActivityManager 查询真实状态即可（见 MainActivity
     * 的 isQueryServerRunning()），避免"持久化开关"和"服务真实存活状态"不同步。
     */
    public static boolean isSbAutostart(Context ctx) {
        init(ctx);
        return sp.getBoolean(KEY_SB_AUTOSTART, false);
    }

    public static void setSbAutostart(Context ctx, boolean autostart) {
        init(ctx);
        sp.edit().putBoolean(KEY_SB_AUTOSTART, autostart).apply();
        Log.d(TAG, "setSbAutostart = " + autostart);
    }

    /** 本地查询服务监听端口。默认 18831。改动后需要重启服务才生效（由调用方负责重启）。 */
    public static int getSbPort(Context ctx) {
        init(ctx);
        int port = sp.getInt(KEY_SB_PORT, SB_PORT_DEFAULT);
        if (port < 1024 || port > 65535) return SB_PORT_DEFAULT;
        return port;
    }

    public static void setSbPort(Context ctx, int port) {
        init(ctx);
        if (port < 1024 || port > 65535) port = SB_PORT_DEFAULT;
        sp.edit().putInt(KEY_SB_PORT, port).apply();
        Log.d(TAG, "setSbPort = " + port);
    }

    /** 鉴权 token，首次访问时自动生成并持久化（32 位十六进制）。 */
    public static synchronized String getSbToken(Context ctx) {
        init(ctx);
        String token = sp.getString(KEY_SB_TOKEN, null);
        if (token == null || token.isEmpty()) {
            token = generateToken();
            sp.edit().putString(KEY_SB_TOKEN, token).apply();
        }
        return token;
    }

    /** 重新生成一个新 token（旧 token 立即失效，需要在 SpamBlocker 那边同步更新）。 */
    public static synchronized String regenerateSbToken(Context ctx) {
        init(ctx);
        String token = generateToken();
        sp.edit().putString(KEY_SB_TOKEN, token).apply();
        Log.d(TAG, "regenerateSbToken");
        return token;
    }

    private static String generateToken() {
        byte[] buf = new byte[16];
        new SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 单次查询超时（毫秒），超过后本地服务直接回"未识别"，不阻塞 SpamBlocker 的决策窗口。默认 4000ms。 */
    public static int getSbTimeoutMs(Context ctx) {
        init(ctx);
        int t = sp.getInt(KEY_SB_TIMEOUT_MS, SB_TIMEOUT_MS_DEFAULT);
        return (t < 500 || t > 10000) ? SB_TIMEOUT_MS_DEFAULT : t;
    }

    public static void setSbTimeoutMs(Context ctx, int timeoutMs) {
        init(ctx);
        if (timeoutMs < 500 || timeoutMs > 10000) timeoutMs = SB_TIMEOUT_MS_DEFAULT;
        sp.edit().putInt(KEY_SB_TIMEOUT_MS, timeoutMs).apply();
        Log.d(TAG, "setSbTimeoutMs = " + timeoutMs);
    }

    /** 判定 is_spam 用的关键词表，逗号分隔。只要查到的标签文本命中其中任意一个词就判定为骚扰。 */
    public static String getSbKeywords(Context ctx) {
        init(ctx);
        return sp.getString(KEY_SB_KEYWORDS, SB_KEYWORDS_DEFAULT);
    }

    public static void setSbKeywords(Context ctx, String keywords) {
        init(ctx);
        sp.edit().putString(KEY_SB_KEYWORDS, keywords == null ? "" : keywords.trim()).apply();
        Log.d(TAG, "setSbKeywords = " + keywords);
    }

    /** 按逗号切分关键词表，去掉空项，供 LocalQueryServer 判定 is_spam 使用。 */
    public static String[] getSbKeywordsArray(Context ctx) {
        String raw = getSbKeywords(ctx);
        if (raw == null || raw.trim().isEmpty()) return new String[0];
        String[] parts = raw.split("[,，]");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    // ── 设置页板块级折叠状态（v3.18 新增） ──────────────────────────────
    // 首次安装（未存储过值）默认展开；boardId 用稳定英文标识，不用中文标题，
    // 避免以后改文案导致历史展开/折叠状态丢失。

    public static boolean isBoardExpanded(Context ctx, String boardId) {
        init(ctx);
        return sp.getBoolean(KEY_BOARD_EXPANDED_PREFIX + boardId, true);
    }

    public static void setBoardExpanded(Context ctx, String boardId, boolean expanded) {
        init(ctx);
        sp.edit().putBoolean(KEY_BOARD_EXPANDED_PREFIX + boardId, expanded).apply();
    }

    // ── 全部设置项导出 / 导入（v3.13 新增，供"备份全部数据"功能使用） ──────

    /** 导出全部设置项为 JSON 对象。 */
    public static synchronized JSONObject exportAll(Context ctx) throws JSONException {
        init(ctx);
        JSONObject o = new JSONObject();
        o.put(KEY_FORCE_CELLULAR, isForceCellular(ctx));
        o.put(KEY_FLOAT_BG_STYLE, getFloatBgStyle(ctx));
        o.put(KEY_FLOAT_WIDTH_CUSTOMIZED, isFloatWidthCustomized(ctx));
        o.put(KEY_FLOAT_WIDTH_PX, getFloatWidthPx(ctx));
        o.put(KEY_SHOW_CALLER_NUMBER, isShowCallerNumber(ctx));
        o.put(KEY_SHOW_QUERY_RESULT, isShowQueryResult(ctx));
        o.put(KEY_QUERY_RESULT_LINES, getQueryResultLines(ctx));
        o.put(KEY_WEB_QUERY_DEFAULT_EXPANDED, isWebQueryDefaultExpanded(ctx));
        o.put(KEY_BAIDU_SILENT_QUERY, isBaiduSilentQueryEnabled(ctx));
        o.put(KEY_WEB_QUERY_ENABLED, isWebQueryEnabled(ctx));
        o.put(KEY_WEB_QUERY_SOURCE, getWebQuerySource(ctx));
        o.put(KEY_WEB_QUERY_CUSTOM_URL, getWebQueryCustomUrl(ctx));
        o.put(KEY_WEB_FONT_ZOOM, getWebFontZoom(ctx));
        o.put(KEY_WEB_TOP_CROP_PX_SOGOU, getWebTopCropPx(ctx, WEB_SOURCE_SOGOU));
        o.put(KEY_WEB_TOP_CROP_PX_360, getWebTopCropPx(ctx, WEB_SOURCE_360));
        o.put(KEY_WEB_TOP_CROP_PX_CUSTOM, getWebTopCropPx(ctx, WEB_SOURCE_CUSTOM));
        o.put(KEY_WEB_HEIGHT_PX, getWebHeightPx(ctx));
        o.put(KEY_WEB_DARK_MODE, getWebDarkMode(ctx));
        o.put(KEY_FLOAT_MOVABLE, isFloatMovable(ctx));
        o.put(KEY_FLOAT_RESIZABLE, isFloatResizable(ctx));
        o.put(KEY_FONT_SIZE_MODE, getFontSizeMode(ctx));
        o.put(KEY_FONT_SIZE_LEVEL, getFontSizeLevel(ctx));
        // SpamBlocker 联动：token 故意不导出，避免恢复备份时把已经在 SpamBlocker
        // 那边配置好的 token 覆盖成另一台设备/另一次备份的旧值，导致联动失效。
        o.put(KEY_SB_AUTOSTART, isSbAutostart(ctx));
        o.put(KEY_SB_PORT, getSbPort(ctx));
        o.put(KEY_SB_TIMEOUT_MS, getSbTimeoutMs(ctx));
        o.put(KEY_SB_KEYWORDS, getSbKeywords(ctx));
        return o;
    }

    /**
     * 从 JSON 对象逐项覆盖恢复设置项。缺失的字段保持原值不动（兼容旧版/不完整备份）。
     * 调用方负责在导入前向用户确认"会覆盖当前所有设置项"。
     */
    public static synchronized void importAll(Context ctx, JSONObject o) throws JSONException {
        init(ctx);
        if (o.has(KEY_FORCE_CELLULAR))       setForceCellular(ctx, o.getBoolean(KEY_FORCE_CELLULAR));
        if (o.has(KEY_FLOAT_BG_STYLE))        setFloatBgStyle(ctx, o.getInt(KEY_FLOAT_BG_STYLE));
        if (o.has(KEY_FLOAT_WIDTH_PX))         setFloatWidthPx(ctx, o.getInt(KEY_FLOAT_WIDTH_PX));
        if (o.has(KEY_SHOW_CALLER_NUMBER))    setShowCallerNumber(ctx, o.getBoolean(KEY_SHOW_CALLER_NUMBER));
        if (o.has(KEY_SHOW_QUERY_RESULT))     setShowQueryResult(ctx, o.getBoolean(KEY_SHOW_QUERY_RESULT));
        if (o.has(KEY_QUERY_RESULT_LINES))    setQueryResultLines(ctx, o.getInt(KEY_QUERY_RESULT_LINES));
        if (o.has(KEY_WEB_QUERY_DEFAULT_EXPANDED))
            setWebQueryDefaultExpanded(ctx, o.getBoolean(KEY_WEB_QUERY_DEFAULT_EXPANDED));
        if (o.has(KEY_BAIDU_SILENT_QUERY))    setBaiduSilentQueryEnabled(ctx, o.getBoolean(KEY_BAIDU_SILENT_QUERY));
        if (o.has(KEY_WEB_QUERY_ENABLED))     setWebQueryEnabled(ctx, o.getBoolean(KEY_WEB_QUERY_ENABLED));
        if (o.has(KEY_WEB_QUERY_SOURCE))      setWebQuerySource(ctx, o.getInt(KEY_WEB_QUERY_SOURCE));
        if (o.has(KEY_WEB_QUERY_CUSTOM_URL))  setWebQueryCustomUrl(ctx, o.getString(KEY_WEB_QUERY_CUSTOM_URL));
        if (o.has(KEY_WEB_FONT_ZOOM))         setWebFontZoom(ctx, o.getInt(KEY_WEB_FONT_ZOOM));
        if (o.has(KEY_WEB_TOP_CROP_PX_SOGOU)) setWebTopCropPx(ctx, WEB_SOURCE_SOGOU, o.getInt(KEY_WEB_TOP_CROP_PX_SOGOU));
        if (o.has(KEY_WEB_TOP_CROP_PX_360))   setWebTopCropPx(ctx, WEB_SOURCE_360, o.getInt(KEY_WEB_TOP_CROP_PX_360));
        if (o.has(KEY_WEB_TOP_CROP_PX_CUSTOM)) setWebTopCropPx(ctx, WEB_SOURCE_CUSTOM, o.getInt(KEY_WEB_TOP_CROP_PX_CUSTOM));
        if (o.has(KEY_WEB_HEIGHT_PX))         setWebHeightPx(ctx, o.getInt(KEY_WEB_HEIGHT_PX));
        if (o.has(KEY_WEB_DARK_MODE))         setWebDarkMode(ctx, o.getInt(KEY_WEB_DARK_MODE));
        if (o.has(KEY_FLOAT_MOVABLE))         setFloatMovable(ctx, o.getBoolean(KEY_FLOAT_MOVABLE));
        if (o.has(KEY_FLOAT_RESIZABLE))       setFloatResizable(ctx, o.getBoolean(KEY_FLOAT_RESIZABLE));
        if (o.has(KEY_FONT_SIZE_MODE))        setFontSizeMode(ctx, o.getInt(KEY_FONT_SIZE_MODE));
        if (o.has(KEY_FONT_SIZE_LEVEL))       setFontSizeLevel(ctx, o.getInt(KEY_FONT_SIZE_LEVEL));
        if (o.has(KEY_SB_AUTOSTART))          setSbAutostart(ctx, o.getBoolean(KEY_SB_AUTOSTART));
        if (o.has(KEY_SB_PORT))               setSbPort(ctx, o.getInt(KEY_SB_PORT));
        if (o.has(KEY_SB_TIMEOUT_MS))         setSbTimeoutMs(ctx, o.getInt(KEY_SB_TIMEOUT_MS));
        if (o.has(KEY_SB_KEYWORDS))           setSbKeywords(ctx, o.getString(KEY_SB_KEYWORDS));
        // v3.17 之前备份里的 KEY_FLOAT_FONT_SIZE / 旧 web_bottom_crop_px 字段，
        // 以及 v3.18 之前的共用 web_top_crop_px 字段，已随功能移除/拆分，
        // 即使旧备份文件里还带着这些字段，这里也不再读取。
        if (o.has(KEY_FLOAT_WIDTH_CUSTOMIZED) && !o.has(KEY_FLOAT_WIDTH_PX)
                && o.optBoolean(KEY_FLOAT_WIDTH_CUSTOMIZED, false)) {
            // 极端情况：备份里勾了"已自定义"但没有宽度值，忽略，保持当前状态不动
            Log.d(TAG, "importAll: float width customized flag without px value, ignored");
        }
    }
}
