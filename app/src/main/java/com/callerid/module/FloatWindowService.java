package com.callerid.module;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

/**
 * 悬浮窗 Service（普通 Service，非前台服务）
 *
 * v1.4 改动：
 *  - 移除官方短号白名单硬判断（已移入 WebQueryHelper.SPECIAL_NUMBERS）
 *  - 锁屏/全屏来电支持：窗口 type 保持 TYPE_APPLICATION_OVERLAY，
 *    同时在 LayoutParams 加 FLAG_SHOW_WHEN_LOCKED（API 27+，普通 flag 无需权限）
 *  - 加入 currentNumber 字段追踪，避免异步回调覆盖新来电结果
 *  - 保留 Root 保活逻辑说明（见 README）
 *
 * v1.5 改动：
 *  - 悬浮窗支持拖动（去掉 FLAG_NOT_TOUCHABLE，加触摸监听实现移动）
 *  - 背景改为圆角、30% 不透明度的黑色
 *
 * v1.6 改动：
 *  - 拖动结束后的位置会保存到 SharedPreferences，下次来电悬浮窗直接
 *    从上次的位置显示（首次使用/从未拖动过时，仍是默认底部居中）
 *  - 文字放大到原来的 1.5 倍，同时加自适应缩小：如果文字实际宽度超过
 *    屏幕宽度的 90%，会按比例逐步缩小字号，直到不超过屏幕（最小不低于 12sp）
 *
 * v1.7 改动：
 *  - 【定位锚点修复】原先保存的拖动位置是“左上角绝对坐标”（TOP|START），
 *    但悬浮窗宽度是 WRAP_CONTENT，标签文字长度变化会导致窗口宽度变化，
 *    左上角不变但视觉中心却跟着偏移。现改为“以屏幕中心为基准的相对偏移”
 *    （Gravity.CENTER + x/y offset）：这种定位方式下系统始终按视图自身的
 *    中心点去对齐偏移量，与视图宽高无关，因此无论标签文字变长变短（甚至
 *    “查询中…”变成最终结果导致的宽度变化），视觉中心点都会保持不动。
 *    注意：这是坐标语义的变更，SharedPreferences 文件名同步改为
 *    callerid_float_pos_v2，旧版本保存的坐标不会被误用（用户升级后需要
 *    重新拖动一次悬浮窗，此后新的坐标语义即可长期正确生效）。
 *  - 悬浮窗背景新增“全透明”可选样式（原半透明黑效果保留为默认），
 *    见 ModuleSettings.getFloatBgStyle()。
 *  - 悬浮窗字体新增 小/中/大/超大 四档大小（原字号 = “大”），
 *    见 ModuleSettings.getFloatFontSize()；原有的自适应缩小防溢出逻辑
 *    （最小 12sp 兜底）在四档基础上继续生效。
 *
 * v1.8 改动：
 *  - 悬浮窗新增"网页查询"入口（受 ModuleSettings.isWebQueryEnabled() 总开关控制，
 *    默认关闭，关闭时悬浮窗与之前完全一样）：标签/号码下方新增一个按钮，
 *    文案在"🔍 打开网页查询"/"✕ 关闭网页查询"之间切换；点击后在按钮下方
 *    展开一个固定高度（屏幕高度 28%）的可见、可交互 WebView，加载搜狗/360/
 *    自定义三选一的查询网址（占位词"来电号码"替换为实际来电号码）。
 *  - 拖动手势收窄：只绑定在"标签+号码"这个子容器（topRow）上，网页区域和
 *    按钮按各自正常触摸规则响应，不会和拖动冲突。
 *  - 点击"关闭"或悬浮窗整体销毁（挂断/来电切换到新号码）时，WebView 会被
 *    彻底 destroy() 释放，不做后台保留；下次"打开"会重新创建加载。
 *
 * v1.9 改动：
 *  - 悬浮窗背景新增"半透明白"可选样式（50% 不透明度白色），
 *    见 ModuleSettings.getFloatBgStyle() / BG_STYLE_TRANSLUCENT_WHITE。
 *  - 网页查询区域新增"顶部裁剪"：把 WebView 顶部固定的搜索框/标签栏区域
 *    整体裁掉不显示（纯几何裁剪，与网页 DOM 结构无关）。裁剪像素由用户
 *    在设置里填写，三个来源共用同一个值，见 ModuleSettings.getWebTopCropPx()。
 *
 * v1.10 改动：
 *  - 网页查询区域新增"深色模式"三选一（跟随系统/浅色/深色），见
 *    ModuleSettings.getWebDarkMode()。通过 androidx.webkit 的
 *    WebSettingsCompat.setAlgorithmicDarkeningAllowed()（新版 WebView）
 *    或退化到 setForceDark()（旧版 WebView）对网页做算法级强制深色渲染——
 *    搜狗/360 这类没有自带深色版式的网页也能被整体反色成暗色。
 *    "跟随系统"档位读取当前 Configuration.uiMode 的夜间模式标记决定明暗。
 *  - "来电识别服务"运行状态改为通过 ActivityManager.getRunningServices()
 *    实时查询（不再用进程内静态标志位）：因为普通 Service 常被系统按后台
 *    限制杀掉重启进程，静态字段在新进程里会被重置成初始值，导致设置页
 *    显示的状态和真实情况不一致；改用 ActivityManager 查询是跨进程都准确
 *    的权威数据源。
 *
 * v3.16 改动：
 *  - 【修复】"打开/关闭网页查询"按钮点击瞬间悬浮窗上下乱跳、且每次点击都会
 *    向上"爬"一点的问题。根因：旧版 adjustWindowKeepingTopEdge() 是"先让内容
 *    变化触发一次真实布局 -> 下一帧再测量位置差值 -> 补偿回 LayoutParams"，
 *    这个"先跳一下、下一帧再纠正"的两步过程本身就会有一帧可见的跳动，
 *    且测量得到的差值受状态栏/系统 inset、时序等因素影响，长期点击会累积
 *    出小误差，表现为悬浮窗持续向上爬。现改为 applyHeightDeltaKeepingTopEdge()：
 *    网页区域展开/收起造成的高度变化量是已知常量（webContainerHeightPx +
 *    固定间距），不需要靠测量去"事后发现"，直接算出补偿值，和内容变化在
 *    同一次 updateViewLayout() 里一起提交，不再有跨帧的可见闪动，也没有
 *    "测量-纠正"的误差累积来源。同时新增：如果悬浮窗贴近屏幕底部，展开网页
 *    会把窗口下边缘顶出屏幕时，整体再多向上让出这部分空间（原来没有这个
 *    边界处理）。
 *  - "打开/关闭网页查询"按钮所在行左右两侧的空白区域，现在和"标签+号码"
 *    区域（top_row）拥有完全相同的手势：可拖动悬浮窗、双击折叠成悬浮球。
 *    实现上把原来一整条 MATCH_PARENT 的按钮，拆成"左侧占位 + 居中按钮本体 +
 *    右侧占位"三段式布局，按钮本体保持原来的点击开关逻辑不变，两侧占位复用
 *    同一个 attachDragBehavior()。同时把 attachDragBehavior() 内部原来"每个
 *    View 各自一份 converted 标志位"改为直接判断 lp.gravity 是否已经是
 *    Gravity.CENTER 来决定要不要做首次换算，避免同一悬浮窗上现在有三个
 *    （top_row + 左右占位）独立触摸区域时，各自状态不同步的问题。
 *
 * v3.17 改动：
 *  - 新增"拖角缩放"：卡片四个角各放一个可拖拽小热区（addCornerHandles()），
 *    不区分是哪个角，统一效果：横向拖动距离 → 悬浮窗宽度（以水平中心对称
 *    缩放，[45%,90%] 屏幕宽度之间），纵向拖动距离 → 网页查询区域高度（顶部
 *    锚点不动，只网页区域往下长/往上收，[60dp, 60%屏幕高度] 之间）。折叠成
 *    悬浮球时不显示这些热区。第一次拖动会把原本 WRAP_CONTENT 的自然宽度
 *    原地转换成一个具体像素值（转换瞬间数值就是当前测量值，不会跳动），
 *    松手时把最终宽/高写回 ModuleSettings，下次悬浮窗重建（新来电）会
 *    读回上次拖过的大小。高度这一路复用了 v3.16 刚加的
 *    applyHeightDeltaKeepingTopEdge()，避免拖动时出现同款跳动问题。
 *  - 移除"悬浮窗字体大小"这个独立设置项：字号不再单独设置，改为直接跟随
 *    悬浮窗宽度联动——没拖动过宽度时完全维持原来的效果（固定用"大"档
 *    基准字号），拖动过一次之后，字号 = 基准字号 × (当前宽度 ÷ 90%屏幕宽度)，
 *    正好落在 50%~100% 之间。这样"宽度收缩基准"和"字号收缩基准"统一成
 *    了同一套计算（effectiveWidthFontScale() / effectiveMaxTextWidthPx()），
 *    不会再出现"卡片被拖窄了，但字体还按屏幕宽度收缩"的不一致。
 *  - tv_tag（标签行）从最多 1 行放开到最多 2 行，并加上省略号兜底
 *    （ellipsize END）：极窄宽度 + 长标签文字的极端情况下，以前是硬裁没有
 *    省略号，现在优先允许换行，实在装不下再截断出省略号。
 *  - "网页展示高度"改为直接存绝对像素值（原来是"从 28%屏幕高度基础值里
 *    减去底部裁剪像素"，上限被封死在这个基础值，无法支持拖角缩放要求的
 *    60% 屏幕高度上限），设置页对应的数字输入框文案同步从"网页底部裁剪
 *    （像素）"改成"网页展示高度（像素）"，见 ModuleSettings.getWebHeightPx()/
 *    setWebHeightPx()。
 *  - 新增"设置实时生效"：背景样式 / 是否显示来电号码 / 是否显示查询结果
 *    这三项（纯视觉、无副作用、互不依赖）在设置页修改后，如果当前正好有
 *    真实来电、悬浮窗正显示着，会立刻局部刷新（不整体重建），见
 *    applySettingsRealtime()，由 MainActivity 在这三项的监听器里通过
 *    ACTION_SETTINGS_CHANGED 广播触发。显示号码/显示查询结果这两项会改变
 *    悬浮窗高度，同样复用 applyHeightDeltaKeepingTopEdge() 做位置补偿，
 *    避免跳动。网页查询相关的整块设置（来源/深色模式/裁剪/默认展开/总开关）
 *    不做实时生效——分支太多，且拖角缩放已经天然解决了"所见即所得"的
 *    诉求；总开关被关掉时，当前正开着的网页区域会继续开着，直到下次悬浮窗
 *    重建（挂断/换号码）才会消失。
 *
 * v3.19 改动：
 *  - 新增"是否可拖角缩放"开关（ModuleSettings.isFloatResizable()，默认开启）：
 *    和"是否可移动"是完全正交的两个开关，控制不同的手势区域，互不干扰。
 *    每次触摸四角热区时实时读取这个设置，关闭后立刻生效，不需要广播/重建。
 *    关闭时四个角的拖拽热区整个隐藏并停用（setVisibility(GONE)，不只是
 *    触摸不生效），已经调好的宽度/高度不受影响，不会被重置，见
 *    setCornerHandlesVisible() / addCornerHandles() / attachCornerResizeBehavior()。
 *  - 重新引入"悬浮窗字体大小"档位设置（小/中/大/特大），但用"二选一模式
 *    开关"而不是叠加在宽度联动上面：ModuleSettings.getFontSizeMode() 为
 *    "跟随窗口自动"（默认）时完全维持 v3.17 起的行为不变；为"固定大小"时
 *    忽略宽度联动，字号只由档位决定，宽度改变（拖角缩放或自然撑开）不会
 *    再影响字号，两者独立互不影响，可以同时使用（比如固定字体 + 关闭拖角
 *    缩放＝宽度和字号都锁死）。两个字号来源永远只有一个在生效，从根源上
 *    避免"档位 × 宽度"打架，不会重蹈 v3.17 之前的覆辙。防止文字溢出的
 *    自动收缩兜底逻辑（最小 12sp）在两种模式下都继续保留，见
 *    effectiveWidthFontScale()。
 *  - 去掉 FLAG_KEEP_SCREEN_ON：以前只要悬浮窗还挂着就会一直阻止系统按
 *    正常超时时间熄屏，现在改为完全跟随系统设置的熄屏时间。FLAG_TURN_SCREEN_ON
 *    继续保留，来电时屏幕本来是灭的还是会照常点亮一次；熄屏之后悬浮窗本身
 *    不会消失，只是看不见，重新点亮屏幕后悬浮窗还在原来的状态。
 *  - 网页查询区域新增两项防骚扰处理（openWebQuery()）：
 *    ① 无条件注入一条 CSS，隐藏搜狗结果页"打开QQ浏览器，搜更多有用内容"
 *    这条自我推广横幅（class="qb-download-banner-non-share"，实测截图取得），
 *    不区分深色模式档位、不区分查询来源（其他来源匹配不到这个 class，
 *    等于空操作，无害），见 QueryWebViewClient.onPageFinished()
 *    里新增的 HIDE_QB_DOWNLOAD_BANNER_JS 注入。这是页面自己的 DOM 内容，
 *    不是真正的浏览器弹窗，所以只能这样针对性隐藏，且完全依赖这个具体
 *    class 名字——如果搜狗以后改版换了 class，这条规则会悄悄失效，需要
 *    重新抓取页面结构、更新选择器。
 *    ② 新增"防真弹窗"通用保险：setSupportMultipleWindows(false) +
 *    setJavaScriptCanOpenWindowsAutomatically(false) 明确禁止 window.open()
 *    弹出新窗口/新标签页；新增 QueryWebChromeClient，把 onJsAlert/
 *    onJsConfirm/onJsPrompt 全部自动"取消"，网页调用 alert/confirm/prompt
 *    不会弹出打断操作的原生对话框。这两条是通用防御，跟查询来源、深色
 *    模式无关，任何网站的类似骚扰都一并防住。
 */
public class FloatWindowService extends Service {

    private static final String PREFS_NAME = "callerid_float_pos_v2"; // v1.7：坐标语义变更，改用新文件名
    private static final String KEY_POS_SET = "pos_set";
    private static final String KEY_POS_X   = "pos_x";
    private static final String KEY_POS_Y   = "pos_y";

    // 折叠悬浮球的记忆位置（v3.14 新增）：与上面卡片的拖动位置完全独立的一套坐标，
    // 语义相同（Gravity.CENTER + 相对屏幕中心的偏移），互不影响
    private static final String KEY_BALL_POS_SET = "ball_pos_set";
    private static final String KEY_BALL_POS_X   = "ball_pos_x";
    private static final String KEY_BALL_POS_Y   = "ball_pos_y";

    // 基准字号（未拖动过卡片宽度时使用，等价于原来的"大"档）；拖动过宽度之后，
    // 实际字号 = 基准字号 × effectiveWidthFontScale()（v3.17：字号不再单独设置，
    // 改为直接跟随悬浮窗宽度联动，见 effectiveWidthFontScale()/refreshTextSizing()）
    private static final float NUM_BASE_SP = 21f;
    private static final float TAG_BASE_SP = 27f;
    private static final float MIN_SP      = 12f;   // 收缩下限，避免缩到看不清
    private static final float MAX_WIDTH_RATIO = 0.90f; // 未拖动过宽度时，允许占用屏幕宽度的比例

    // 折叠悬浮球的固定直径（v3.14 新增）
    private static final int BALL_SIZE_DP = 56;

    // 四角拖拽热区的边长（v3.17 新增，拖角缩放）
    private static final int CORNER_HANDLE_DP = 22;

    private WindowManager wm;
    private View          floatView;
    private String        currentNumber = "";
    private WindowManager.LayoutParams lp;

    // 网页查询：当前展开的 WebView 实例（未展开时为 null），以及它加载时对应的号码
    // （用于识别"来电切换到新号码但悬浮窗被复用"的情况，此时应自动关闭旧网页）
    private WebView webQueryView;
    private String  webQueryLoadedNumber;

    // 当前这个 WebView 实际是用哪个来源打开的（v3.18 新增，配合"顶部裁剪按来源拆分独立值"）：
    // 拖角缩放时如果直接实时读 ModuleSettings.getWebQuerySource()，遇到"打开网页查询时是
    // 搜狗，之后不关闭、跑去设置里把来源切到360"这种边界情况会算错——此时屏幕上实际显示的
    // 还是搜狗网页，裁剪值应该按打开时的来源算，不能按设置里当前选中的来源算。
    private int webQueryLoadedSource = ModuleSettings.WEB_SOURCE_SOGOU;

    // 网页展开区域的当前高度（像素）。buildView() 时从 ModuleSettings.getWebHeightPx() 读入，
    // 拖角缩放时实时更新，松手时写回 ModuleSettings（v3.17：语义从"裁剪像素"改为绝对高度）。
    private int webContainerHeightPx;

    // 悬浮窗卡片宽度是否已被拖角自定义过（v3.17 新增）。false = 维持 WRAP_CONTENT 自然宽度；
    // true = cardWidthPx 是当前生效的绝对像素宽度。buildView() 时从 ModuleSettings 读入。
    private boolean widthCustomized = false;
    private int cardWidthPx = -1; // -1 表示"未自定义，自然宽度"，仅当 widthCustomized 为 true 时才有意义

    // 悬浮窗是否处于"折叠成小球"状态（v3.14 新增）。floatView 被销毁重建（挂断/removeWindow）
    // 时会重置为 false，保证每次新来电默认都是展开的完整卡片。
    private boolean isCollapsed = false;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("com.callerid.ACTION_INCOMING".equals(action)) {
                String num = intent.getStringExtra("number");
                if (num != null && !num.isEmpty()) handleIncoming(num);
            } else if ("com.callerid.ACTION_IDLE".equals(action)) {
                handleIdle();
            } else if ("com.callerid.ACTION_SETTINGS_CHANGED".equals(action)) {
                applySettingsRealtime();
            }
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        removeWindow();
        super.onDestroy();
    }

    // ── 来电处理 ──────────────────────────────────────────────────────────────

    private void handleIncoming(String number) {
        // 只有真的换了新号码（而不是同一通电话异步查询结果回填）才会在折叠状态下
        // 自动展开——用户主动把小球留在折叠状态时，不应该被同一通电话的后续更新打断
        boolean isNewNumber = !number.equals(currentNumber);
        currentNumber = number;
        showOrUpdate(number, "查询中\u2026");
        if (isNewNumber && isCollapsed) {
            expandToCard();
        }

        new WebQueryHelper().query(this, number, result -> {
            // 防止异步结果覆盖更新的来电
            if (!number.equals(currentNumber)) return;
            String show = (result != null && !result.isEmpty()) ? result : "未知号码";
            new Handler(Looper.getMainLooper()).post(() -> showOrUpdate(number, show));
        });
    }

    private void handleIdle() {
        currentNumber = "";
        removeWindow();
    }

    // ── 悬浮窗操作 ────────────────────────────────────────────────────────────

    private void showOrUpdate(String number, String tag) {
        if (floatView == null) {
            floatView = buildView();
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            // 注意：去掉了 FLAG_NOT_TOUCHABLE，悬浮窗自身才能接收拖动手势；
                            // FLAG_NOT_TOUCH_MODAL 仍保留，窗口范围之外（比如接听/挂断按钮）
                            // 的触摸依旧穿透到下层，不受影响。
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            // v3.19：去掉了 FLAG_KEEP_SCREEN_ON——以前只要悬浮窗还挂着就会
                            // 一直阻止系统按正常超时时间熄屏，现在改为跟随系统设置的熄屏
                            // 时间正常熄屏。FLAG_TURN_SCREEN_ON 继续保留，来电时屏幕本来是
                            // 灭的还是会照常点亮一次；熄屏之后悬浮窗本身不会消失，只是看
                            // 不见，重新点亮屏幕后悬浮窗还在原来的状态。
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
            );

            // 优先使用上次拖动保存的位置（屏幕中心相对偏移）；
            // 如果从未拖动过，用默认的底部居中
            applySavedPosition(lp);

            // 拖动 + 双击折叠手势绑定在三块区域上：①"标签+号码"子容器（top_row）
            // ②③ 网页查询按钮行左右两侧的空白占位（v3.16 新增，行为与 top_row 完全
            // 一致）。网页区域本身/按钮本体按各自正常触摸规则响应（网页滚动、按钮
            // 点击），不受这套逻辑影响。
            Runnable collapseAction = () -> {
                View cardRoot = floatView.findViewWithTag("card_root");
                View ballView = floatView.findViewWithTag("ball_view");
                if (cardRoot != null && ballView != null) collapseToBall(cardRoot, ballView);
            };
            View topRow = floatView.findViewWithTag("top_row");
            attachDragBehavior(topRow != null ? topRow : floatView, lp, collapseAction);
            View btnRowSpacerLeft = floatView.findViewWithTag("btn_row_spacer_left");
            View btnRowSpacerRight = floatView.findViewWithTag("btn_row_spacer_right");
            if (btnRowSpacerLeft != null) attachDragBehavior(btnRowSpacerLeft, lp, collapseAction);
            if (btnRowSpacerRight != null) attachDragBehavior(btnRowSpacerRight, lp, collapseAction);
            try {
                wm.addView(floatView, lp);
            } catch (Exception e) {
                e.printStackTrace();
                floatView = null;
                return;
            }
        }
        updateTag(number, tag);
    }

    /**
     * 读取上次保存的拖动位置并写入 LayoutParams。
     * @return true 表示成功恢复了保存的“屏幕中心相对偏移”坐标（Gravity.CENTER）；
     *         false 表示没有保存过，使用默认的底部居中位置。
     */
    private boolean applySavedPosition(WindowManager.LayoutParams params) {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (sp.getBoolean(KEY_POS_SET, false)) {
            params.gravity = Gravity.CENTER;
            params.x = sp.getInt(KEY_POS_X, 0);
            params.y = sp.getInt(KEY_POS_Y, 0);
            return true;
        }
        // 默认：底部居中，距底部 120px，避开顶部来电卡片
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.x = 0;
        params.y = 120;
        return false;
    }

    private void savePosition(int x, int y) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_POS_SET, true)
                .putInt(KEY_POS_X, x)
                .putInt(KEY_POS_Y, y)
                .apply();
    }

    /**
     * 让悬浮窗可拖动：
     * 如果初始位置还是“底部居中 + 偏移”（从未拖动过，未采用中心相对定位），
     * 第一次按下时把它换算成“相对屏幕中心的偏移”（Gravity.CENTER），换算基准
     * 是当前视图的中心点，因此换算过程中视觉位置不会跳动；如果是从
     * SharedPreferences 恢复的位置，本身已经是中心相对偏移，不需要再换算。
     * 松手（ACTION_UP/CANCEL）时把当前偏移量保存下来，下次来电直接沿用——
     * 由于是相对视图自身中心的偏移，之后无论标签文字长短如何变化，
     * 视觉中心都会保持在用户拖动到的那个位置上。
     *
     * v3.14 新增：同时识别双击手势（双击"标签/号码"区域折叠成悬浮球），双击检测
     * 与拖动逻辑并行处理、互不影响——GestureDetector 只是"旁听"同一串触摸事件来
     * 判断是否构成双击，不会拦截或改变原有的按下/移动/松手流程。
     *
     * v3.16 改动：是否已经换算成 Gravity.CENTER 的相对偏移，不再用"每个 View 各自
     * 一份 converted 局部变量"来记录（因为现在 top_row、按钮行左侧占位、右侧占位
     * 三个独立触摸区域都会调用本方法，各自的局部变量互不知晓对方的状态，可能
     * 出现其中一个已经换算过、另一个还认为没换算过而重复换算的不一致）。改为
     * 直接读 params.gravity 是否已经是 Gravity.CENTER 来判断——这本身就是换算
     * 结果的唯一事实来源，三个触摸区域共享同一个 params 对象，天然保持同步。
     *
     * v3.18 新增"是否可移动"开关（ModuleSettings.isFloatMovable()）：
     * 不预先传一个固定参数进来，而是在每次触摸事件里直接实时读取——这样这个
     * 开关自动是"设置实时生效"的，不需要额外接广播、也不需要重建悬浮窗，下一次
     * 触摸悬浮窗时读到的就是最新值。
     * 可移动 = true（默认）：完全维持原有逻辑（拖动 + 双击折叠）。
     * 可移动 = false：ACTION_MOVE 直接忽略（不改变 lp.x/y，也不做首次换算，
     * 因为不移动就不需要换算成相对坐标）；GestureDetector 额外识别 onSingleTapUp
     * （单击立即响应，不用等待系统判断"是不是双击前半部分"的等待时间），和
     * onDoubleTap 一样都触发折叠——三个触摸区域（top_row + 按钮行左右占位）统一
     * 处理，行为一致。
     * 这个开关只管拖动，不影响四角拖拽缩放热区——缩放和移动位置是两件独立的事，
     * 缩放不受此开关影响，见 attachCornerResizeBehavior()。
     */
    private void attachDragBehavior(View view, WindowManager.LayoutParams params,
                                     Runnable onCollapse) {
        GestureDetector gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (onCollapse != null) onCollapse.run();
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        // 只有"不可移动"模式下单击才触发折叠；可移动模式维持原样，
                        // 单击不做任何事（只有双击才折叠），避免和拖动手势冲突/误触
                        if (!ModuleSettings.isFloatMovable(FloatWindowService.this)) {
                            if (onCollapse != null) onCollapse.run();
                            return true;
                        }
                        return false;
                    }
                });

        view.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureDetector.onTouchEvent(event);
                boolean movable = ModuleSettings.isFloatMovable(FloatWindowService.this);
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        if (!movable) {
                            // 不可移动模式：不做拖动相关的任何坐标换算/记录，
                            // 后续 ACTION_MOVE 会被直接忽略，单击/双击折叠交给 GestureDetector
                            return true;
                        }
                        if (params.gravity != Gravity.CENTER) {
                            int[] loc = new int[2];
                            v.getLocationOnScreen(loc);
                            int viewCenterX = loc[0] + v.getWidth() / 2;
                            int viewCenterY = loc[1] + v.getHeight() / 2;
                            DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
                            int screenCenterX = dm.widthPixels / 2;
                            int screenCenterY = dm.heightPixels / 2;
                            params.gravity = Gravity.CENTER;
                            params.x = viewCenterX - screenCenterX;
                            params.y = viewCenterY - screenCenterY;
                            try { wm.updateViewLayout(floatView, params); } catch (Exception ignored) {}
                        }
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (!movable) return true; // 不可移动：忽略移动，不改变 lp.x/y
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        params.x = initialX + dx;
                        params.y = initialY + dy;
                        try { wm.updateViewLayout(floatView, params); } catch (Exception ignored) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (movable) savePosition(params.x, params.y);
                        return true;
                }
                return false;
            }
        });
    }

    private void updateTag(String number, String tag) {
        if (floatView == null) return;
        floatView.post(() -> {
            // 悬浮窗被复用给新号码（而不是重新创建）时，如果网页查询区域还开着，
            // 里面显示的是旧号码的查询结果，直接自动关闭，避免误导
            if (webQueryView != null && webQueryLoadedNumber != null
                    && !webQueryLoadedNumber.equals(number)) {
                TextView btn = floatView.findViewWithTag("btn_web_toggle");
                FrameLayout container = floatView.findViewWithTag("web_container");
                if (btn != null && container != null) closeWebQuery(btn, container);
            }

            TextView tvNum = floatView.findViewWithTag("tv_num");
            TextView tvTag = floatView.findViewWithTag("tv_tag");
            if (tvNum != null) tvNum.setText(number);
            if (tvTag != null) {
                tvTag.setText(tag);
                if (tag.contains("诈骗"))
                    tvTag.setTextColor(Color.parseColor("#FF3333"));
                else if (tag.contains("骚扰") || tag.contains("广告")
                        || tag.contains("催收") || tag.contains("营销")
                        || tag.contains("推销"))
                    tvTag.setTextColor(Color.parseColor("#FF9900"));
                else if (tag.contains("查询中"))
                    tvTag.setTextColor(Color.parseColor("#AAAAAA"));
                else
                    tvTag.setTextColor(Color.parseColor("#44CC44"));
            }
            // 文字内容变化后，重新按当前卡片宽度做自适应缩放（v3.17：字号跟随宽度，不再有独立档位）
            refreshTextSizing();
        });
    }

    /**
     * 宽度→字号的缩放系数。
     * v3.19 新增：如果"字体大小模式"是固定大小，直接返回档位对应的固定系数，
     *   完全忽略下面的宽度联动——两个来源永远只有一个在生效，从根源上避免
     *   "档位 × 宽度"打架（v3.17 之前的老问题）。
     * 否则维持 v3.17 起的"跟随窗口自动"行为：
     *   没拖动过卡片宽度（widthCustomized == false）：完全维持原来的效果，固定返回 1.0，
     *   字号就是"大"档基准值，只有 fitTextToScreen 自身"超宽才收缩"的兜底逻辑会介入。
     *   拖动过一次之后：卡片宽度在 [45%,90%] 屏幕宽度区间内（ModuleSettings 已经钳过），
     *   系数按"当前宽度 ÷ 90%屏幕宽度"算，正好落在 [0.5, 1.0] 区间，两头都比较自然。
     */
    private float effectiveWidthFontScale() {
        if (ModuleSettings.getFontSizeMode(this) == ModuleSettings.FONT_SIZE_MODE_FIXED) {
            return ModuleSettings.fontSizeLevelScale(ModuleSettings.getFontSizeLevel(this));
        }
        if (!widthCustomized) return 1f;
        int screenWidthPx = Resources.getSystem().getDisplayMetrics().widthPixels;
        float fullWidthPx = screenWidthPx * MAX_WIDTH_RATIO;
        if (fullWidthPx <= 0) return 1f;
        return cardWidthPx / fullWidthPx;
    }

    /**
     * 文字可用的最大宽度（v3.17 新增，供 fitTextToScreen 收缩基准使用）。
     * 没拖动过宽度：跟原来一样，用"屏幕宽度 × 90%"（不减 padding，保持原有观感不变）。
     * 拖动过宽度：改用卡片当前实际宽度（减去左右 padding），否则卡片被拖窄之后，
     *   文字还是按屏幕宽度 90% 的老基准收缩，会超出更窄的卡片边界。
     */
    private int effectiveMaxTextWidthPx() {
        if (!widthCustomized) {
            int screenWidthPx = Resources.getSystem().getDisplayMetrics().widthPixels;
            return (int) (screenWidthPx * MAX_WIDTH_RATIO);
        }
        return Math.max(0, cardWidthPx - 28 * 2); // 28 = root.setPadding() 里的左右 padding 像素值
    }

    /** 用当前有效宽度/字号基准，重新给 tv_num / tv_tag 定字号（拖角缩放、内容变化、设置实时生效都调这个）。 */
    private void refreshTextSizing() {
        if (floatView == null) return;
        TextView tvNum = floatView.findViewWithTag("tv_num");
        TextView tvTag = floatView.findViewWithTag("tv_tag");
        float scale = effectiveWidthFontScale();
        int maxWidthPx = effectiveMaxTextWidthPx();
        if (tvNum != null) fitTextToScreen(tvNum, NUM_BASE_SP * scale, maxWidthPx, 1);
        if (tvTag != null) fitTextToScreen(tvTag, TAG_BASE_SP * scale, maxWidthPx, ModuleSettings.getQueryResultLines(this)); // v3.19：跟随"查询结果显示行数"设置
    }

    /** dp → px 换算（FloatWindowService 内新增控件用；原有控件的写死像素值不受影响） */
    private int dp(int val) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, val,
                Resources.getSystem().getDisplayMetrics()));
    }

    /**
     * 把 TextView 的字号先设为基准字号，如果按这个字号测量出来的单行文字宽度超过
     * maxWidthPx，就逐步缩小字号，直到能放下为止（最小不低于 MIN_SP）。
     * maxLines > 1 时（目前只有 tv_tag 传 2），允许放到 maxWidthPx 的 maxLines 倍宽度
     * 还在收缩（近似估算能否用 maxLines 行装下，不用 StaticLayout 做精确多行测量）；
     * 到了 MIN_SP 还装不下的极端情况，交给 TextView 自身的 ellipsize 兜底截断。
     */
    private void fitTextToScreen(TextView tv, float baseSp, int maxWidthPx, int maxLines) {
        String text = tv.getText() == null ? "" : tv.getText().toString();
        float sp = baseSp;
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (text.isEmpty() || maxWidthPx <= 0) return;

        Paint paint = tv.getPaint();
        float allowance = maxWidthPx * (float) Math.max(1, maxLines);
        while (sp > MIN_SP && paint.measureText(text) > allowance) {
            sp -= 1f;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }
    }

    private void removeWindow() {
        destroyWebQueryIfAny();
        if (floatView != null) {
            try { wm.removeView(floatView); } catch (Exception ignored) {}
            floatView = null;
            lp = null;
        }
        // 折叠状态只在当前这个悬浮窗实例的生命周期内有效，窗口销毁后重置，
        // 保证每次新来电重新创建悬浮窗时默认都是展开的完整卡片
        isCollapsed = false;
    }

    // ── 折叠悬浮球（v3.14 新增） ─────────────────────────────────────────────

    /**
     * 双击"标签/号码"区域触发：折叠成一个小球。
     * - 之前从未拖动过小球：缩到卡片当前所在的位置（视觉上原地缩小）。
     * - 之前拖动过小球：直接瞬间出现在上次记住的位置（不做过渡动画）。
     * 折叠前如果网页查询还开着，先关闭释放，避免小球状态下后台还留着 WebView。
     */
    private void collapseToBall(View cardRoot, View ballView) {
        if (floatView == null || lp == null) return;

        TextView btn = floatView.findViewWithTag("btn_web_toggle");
        FrameLayout container = floatView.findViewWithTag("web_container");
        if (webQueryView != null && btn != null && container != null) {
            closeWebQuery(btn, container);
        }

        int ballSizePx = dp(BALL_SIZE_DP);
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int targetX, targetY;
        if (sp.getBoolean(KEY_BALL_POS_SET, false)) {
            targetX = sp.getInt(KEY_BALL_POS_X, 0);
            targetY = sp.getInt(KEY_BALL_POS_Y, 0);
        } else {
            int[] loc = new int[2];
            floatView.getLocationOnScreen(loc);
            int viewCenterX = loc[0] + floatView.getWidth() / 2;
            int viewCenterY = loc[1] + floatView.getHeight() / 2;
            DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
            targetX = viewCenterX - dm.widthPixels / 2;
            targetY = viewCenterY - dm.heightPixels / 2;
        }
        int[] clamped = clampCenterOffset(ballSizePx, ballSizePx, targetX, targetY);

        cardRoot.setVisibility(View.GONE);
        ballView.setVisibility(View.VISIBLE);
        setCornerHandlesVisible(false);
        lp.gravity = Gravity.CENTER;
        lp.x = clamped[0];
        lp.y = clamped[1];
        isCollapsed = true;
        try { wm.updateViewLayout(floatView, lp); } catch (Exception ignored) {}
    }

    /**
     * 点击小球 / 收到新来电时触发：展开回完整卡片。
     *
     * v3.18 修复：展开时改为重新读取卡片自己保存的位置（applySavedPosition()），
     * 不再沿用小球被拖动时改写过的 lp.x/lp.y——卡片和小球现在各自独立记住自己的
     * 位置，互不影响。如果卡片从未被拖动过，会恢复到默认的"底部居中"位置
     * （Gravity.BOTTOM），这种情况下位置本来就贴边安全，不需要额外的边界钳制；
     * 只有卡片被拖动过（恢复出来是 Gravity.CENTER 的自定义坐标）时，才在展开后
     * 按卡片实际尺寸做一次边界钳制——因为 clampCenterOffset() 的算法是按
     * Gravity.CENTER 语义写的，套用在 BOTTOM 语义上会算错。
     */
    private void expandToCard() {
        if (floatView == null || lp == null) return;
        View cardRoot = floatView.findViewWithTag("card_root");
        View ballView = floatView.findViewWithTag("ball_view");
        if (cardRoot == null || ballView == null) return;

        ballView.setVisibility(View.GONE);
        cardRoot.setVisibility(View.VISIBLE);
        setCornerHandlesVisible(true);
        boolean restoredCustomPos = applySavedPosition(lp); // 卡片自己的坐标，与小球坐标完全独立
        isCollapsed = false;
        try { wm.updateViewLayout(floatView, lp); } catch (Exception ignored) {}

        if (!restoredCustomPos) return; // 默认底部居中位置本来就贴边安全，不需要边界钳制

        floatView.post(() -> {
            if (floatView == null || lp == null || isCollapsed) return;
            if (lp.gravity != Gravity.CENTER) return; // 双重保险：不是自定义坐标就不套用 CENTER 语义的钳制算法
            int[] clamped = clampCenterOffset(cardRoot.getWidth(), cardRoot.getHeight(), lp.x, lp.y);
            if (clamped[0] != lp.x || clamped[1] != lp.y) {
                lp.x = clamped[0];
                lp.y = clamped[1];
                try { wm.updateViewLayout(floatView, lp); } catch (Exception ignored) {}
            }
        });
    }

    private void saveBallPosition(int x, int y) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BALL_POS_SET, true)
                .putInt(KEY_BALL_POS_X, x)
                .putInt(KEY_BALL_POS_Y, y)
                .apply();
    }

    /**
     * 把给定内容尺寸（contentW × contentH）按 Gravity.CENTER 语义的 x/y 偏移量
     * 钳制在屏幕可见范围内，确保内容不会有任何一部分超出屏幕。
     */
    private int[] clampCenterOffset(int contentW, int contentH, int x, int y) {
        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        int halfW = contentW / 2, halfH = contentH / 2;
        int minX = -(dm.widthPixels / 2) + halfW;
        int maxX = (dm.widthPixels / 2) - halfW;
        int minY = -(dm.heightPixels / 2) + halfH;
        int maxY = (dm.heightPixels / 2) - halfH;
        int cx = (minX <= maxX) ? Math.max(minX, Math.min(maxX, x)) : 0;
        int cy = (minY <= maxY) ? Math.max(minY, Math.min(maxY, y)) : 0;
        return new int[]{cx, cy};
    }

    /** 小球自身的触摸行为：可拖动到任意位置（带屏幕边界限制），单击（未拖动）则展开回卡片 */
    private void attachBallBehavior(View ballView) {
        int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        ballView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (lp == null) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = lp.x;
                        initialY = lp.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) moved = true;
                        if (moved) {
                            int ballSizePx = dp(BALL_SIZE_DP);
                            int[] clamped = clampCenterOffset(ballSizePx, ballSizePx,
                                    initialX + dx, initialY + dy);
                            lp.x = clamped[0];
                            lp.y = clamped[1];
                            try { wm.updateViewLayout(floatView, lp); } catch (Exception ignored) {}
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            saveBallPosition(lp.x, lp.y);
                        } else {
                            expandToCard();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /** 悬浮窗即将销毁（挂断/新来电复用前）时，确保网页查询 WebView 一并释放，不留内存 */
    private void destroyWebQueryIfAny() {
        if (webQueryView != null) {
            try {
                webQueryView.stopLoading();
                webQueryView.loadUrl("about:blank");
                webQueryView.destroy();
            } catch (Exception ignored) {}
            webQueryView = null;
            webQueryLoadedNumber = null;
        }
    }

    private View buildView() {
        // v3.17：宽度是否被拖角自定义过，从 ModuleSettings 读入（开机/新来电重建悬浮窗时
        // 沿用上一次拖过的大小；从未拖过则维持原来的 WRAP_CONTENT 自然宽度）
        widthCustomized = ModuleSettings.isFloatWidthCustomized(this);
        cardWidthPx = widthCustomized ? ModuleSettings.getFloatWidthPx(this) : -1;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 16, 28, 16);
        root.setGravity(Gravity.CENTER_HORIZONTAL); // 宽度被拖宽之后，内部内容仍然水平居中

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColorFor(ModuleSettings.getFloatBgStyle(this)));
        bg.setCornerRadius(24f);
        root.setBackground(bg);

        float scale = effectiveWidthFontScale();

        // ── 顶部：标签 + 号码（唯一承载拖动手势的区域，见 attachDragBehavior 调用处） ──
        LinearLayout topRow = new LinearLayout(this);
        topRow.setTag("top_row");
        topRow.setOrientation(LinearLayout.VERTICAL);

        TextView tvNum = new TextView(this);
        tvNum.setTag("tv_num");
        tvNum.setTextColor(Color.WHITE);
        tvNum.setTextSize(NUM_BASE_SP * scale);
        // 号码不加粗
        tvNum.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        tvNum.setGravity(android.view.Gravity.CENTER);
        tvNum.setMaxLines(1);
        tvNum.setEllipsize(TextUtils.TruncateAt.END);
        tvNum.setText("");
        // 是否显示来电号码（v3.13 新增，默认显示）：关闭后悬浮窗第二行（号码）不显示
        tvNum.setVisibility(ModuleSettings.isShowCallerNumber(this) ? View.VISIBLE : View.GONE);

        TextView tvTag = new TextView(this);
        tvTag.setTag("tv_tag");
        tvTag.setTextColor(Color.WHITE);
        tvTag.setTextSize(TAG_BASE_SP * scale);
        tvTag.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); // 标签加粗
        tvTag.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        tvTag.setGravity(android.view.Gravity.CENTER);
        // v3.19 新增设置项：查询结果显示"一行"（默认，超出截断）还是"两行"
        // （超出的部分换行显示，再超出才截断）
        tvTag.setMaxLines(ModuleSettings.getQueryResultLines(this));
        tvTag.setEllipsize(TextUtils.TruncateAt.END);
        tvTag.setText("查询中\u2026");
        // 是否显示查询结果/标签（v3.15 新增，默认显示）：关闭后悬浮窗第一行（标签）不显示
        tvTag.setVisibility(ModuleSettings.isShowQueryResult(this) ? View.VISIBLE : View.GONE);

        // 标签在上，号码在下；两者都用 MATCH_PARENT 宽度，这样卡片宽度被拖动之后
        // 文字才能正确居中/换行，而不是仍然按自身文字内容的自然宽度显示
        LinearLayout.LayoutParams fullWidthWrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        topRow.addView(tvTag, fullWidthWrapLp);
        topRow.addView(tvNum, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(topRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 网页查询入口按钮所在行（v3.16 改为三段式：左侧占位 + 居中按钮本体 +
        //    右侧占位）。仅当总开关开启时整行显示；关闭时悬浮窗与之前完全一样。
        //    左右占位不承载点击开关逻辑，而是和 top_row 一样可拖动/双击折叠——
        //    原来这一整行是单个 MATCH_PARENT 的 TextView，按钮文字两侧本来就有
        //    大片空白，只是那片空白之前被算进了"点击=开关网页"的响应范围。
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setTag("btn_row");
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        boolean webQueryFeatureOn = ModuleSettings.isWebQueryEnabled(this);
        btnRow.setVisibility(webQueryFeatureOn ? View.VISIBLE : View.GONE);

        View btnRowSpacerLeft = new View(this);
        btnRowSpacerLeft.setTag("btn_row_spacer_left");
        LinearLayout.LayoutParams spacerLeftLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        btnRow.addView(btnRowSpacerLeft, spacerLeftLp);

        TextView btnWebToggle = new TextView(this);
        btnWebToggle.setTag("btn_web_toggle");
        btnWebToggle.setText("\uD83D\uDD0D 打开网页查询"); // 🔍 打开网页查询
        btnWebToggle.setTextColor(Color.WHITE);
        btnWebToggle.setTextSize(13f);
        btnWebToggle.setGravity(android.view.Gravity.CENTER);
        btnWebToggle.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams btnCenterLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRow.addView(btnWebToggle, btnCenterLp);

        View btnRowSpacerRight = new View(this);
        btnRowSpacerRight.setTag("btn_row_spacer_right");
        LinearLayout.LayoutParams spacerRightLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        btnRow.addView(btnRowSpacerRight, spacerRightLp);

        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = dp(4);
        root.addView(btnRow, btnRowLp);

        // ── 网页展开区域：高度直接取 ModuleSettings.getWebHeightPx()（v3.17：语义从
        //    "基础高度 - 底部裁剪像素"改为直接存绝对高度，可拖角缩放，也可以在设置页
        //    数字输入框里直接填，是同一个值的两种录入方式），默认收起（GONE）。
        FrameLayout webContainer = new FrameLayout(this);
        webContainer.setTag("web_container");
        webContainer.setVisibility(View.GONE);
        webContainerHeightPx = ModuleSettings.getWebHeightPx(this);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, webContainerHeightPx);
        webLp.topMargin = dp(4);
        root.addView(webContainer, webLp);

        btnWebToggle.setOnClickListener(v -> toggleWebQuery(btnWebToggle, webContainer));

        // "默认展开网页"选项（v3.15 新增）：总开关开启且勾选了这个选项时，
        // 悬浮窗一创建就自动展开网页区域，不需要用户再点一次按钮
        if (webQueryFeatureOn && ModuleSettings.isWebQueryDefaultExpanded(this)) {
            openWebQuery(btnWebToggle, webContainer);
        }

        // ── 外层容器：把上面整张卡片(root) 和 折叠悬浮球(ballView) 放在同一个窗口里，
        //    同一时间只显示其中一个（v3.14 新增，双击 top_row 折叠 / 点击小球展开）──
        root.setTag("card_root");

        FrameLayout ballView = new FrameLayout(this);
        ballView.setTag("ball_view");
        ballView.setVisibility(View.GONE);
        GradientDrawable ballBg = new GradientDrawable();
        ballBg.setShape(GradientDrawable.OVAL);
        ballBg.setColor(Color.parseColor("#CCFFFFFF")); // v3.18：半透明灰改成半透明白，黑色背景下更明显（透明度不变，只换 RGB）
        ballView.setBackground(ballBg);
        TextView ballIcon = new TextView(this);
        ballIcon.setText("\uD83D\uDD0D"); // 🔍 v3.20-2：改成和"打开网页查询"按钮同款搜索 emoji（原来的"查"字试验版）
        ballIcon.setTextSize(22f);
        ballIcon.setGravity(android.view.Gravity.CENTER);
        FrameLayout.LayoutParams ballIconLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        ballView.addView(ballIcon, ballIconLp);
        attachBallBehavior(ballView);

        FrameLayout outer = new FrameLayout(this);
        FrameLayout.LayoutParams rootLp = widthCustomized
                ? new FrameLayout.LayoutParams(cardWidthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
                : new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        outer.addView(root, rootLp);
        int ballSizePx = dp(BALL_SIZE_DP);
        outer.addView(ballView, new FrameLayout.LayoutParams(ballSizePx, ballSizePx));

        // 四角拖拽热区（v3.17 新增，拖角缩放）：叠加在卡片四个角上，折叠成球时不显示
        addCornerHandles(outer);

        return outer;
    }

    /** 悬浮窗背景颜色：根据 style 返回对应颜色（buildView 首次构建 / applySettingsRealtime 实时刷新共用）。 */
    private int bgColorFor(int bgStyle) {
        if (bgStyle == ModuleSettings.BG_STYLE_TRANSPARENT) {
            return Color.TRANSPARENT;
        } else if (bgStyle == ModuleSettings.BG_STYLE_TRANSLUCENT_WHITE) {
            // 圆角、50% 不透明度的白色背景（alpha = 0.50 * 255 = 0x80）
            return Color.parseColor("#80FFFFFF");
        } else {
            // 圆角、30% 不透明度的黑色背景（alpha = 0.30 * 255 ≈ 0x4D）
            return Color.parseColor("#4D000000");
        }
    }

    /**
     * 设置页里"悬浮窗背景 / 是否显示来电号码 / 是否显示查询结果"这三项修改后，如果当前
     * 正好有真实来电、悬浮窗正显示着，做一次局部刷新（不整体重建 floatView）。
     * 网页查询相关的整块设置（来源/深色模式/裁剪/默认展开/总开关）不在这里处理——
     * 分支太多，且拖角缩放已经天然解决了"所见即所得"的诉求；如果这块设置被关掉，
     * 当前正开着的网页区域会继续保持开着，直到下次悬浮窗重建（挂断/换号码）才会消失。
     */
    private void applySettingsRealtime() {
        if (floatView == null) return;

        View cardRoot = floatView.findViewWithTag("card_root");
        if (cardRoot != null) {
            android.graphics.drawable.Drawable bg = cardRoot.getBackground();
            if (bg instanceof GradientDrawable) {
                ((GradientDrawable) bg).setColor(bgColorFor(ModuleSettings.getFloatBgStyle(this)));
            }
        }

        TextView tvNum = floatView.findViewWithTag("tv_num");
        TextView tvTag = floatView.findViewWithTag("tv_tag");
        boolean showNum = ModuleSettings.isShowCallerNumber(this);
        boolean showResult = ModuleSettings.isShowQueryResult(this);

        // 查询结果显示行数（v3.19 新增）：直接改 maxLines + ellipsize 重新生效，不做
        // 精确的高度补偿（不像"是否显示"那样有对称的显示/隐藏两态可以互相估算）——
        // 切换这项本来就不常发生，允许有一次轻微的高度跳动，下次悬浮窗重建
        // （新来电/挂断）或拖角缩放（会重新走 fitTextToScreen）时会自然恢复精确。
        if (tvTag != null) tvTag.setMaxLines(ModuleSettings.getQueryResultLines(this));

        // 字体大小模式/档位（v3.19 新增）：和查询结果行数一样，直接重新走一遍
        // fitTextToScreen 重新生效，不做精确高度补偿，允许一次轻微跳动。
        if (!isCollapsed) refreshTextSizing();

        if (isCollapsed) {
            // 折叠成球时这两行本来就不可见，只改可见性状态，不涉及尺寸/位置补偿
            if (tvNum != null) tvNum.setVisibility(showNum ? View.VISIBLE : View.GONE);
            if (tvTag != null) tvTag.setVisibility(showResult ? View.VISIBLE : View.GONE);
            return;
        }

        int deltaHeightPx = 0;
        if (tvNum != null && (tvNum.getVisibility() == View.VISIBLE) != showNum) {
            deltaHeightPx += estimateVisibilityHeightDeltaPx(tvNum, showNum, NUM_BASE_SP, 1);
        }
        if (tvTag != null && (tvTag.getVisibility() == View.VISIBLE) != showResult) {
            deltaHeightPx += estimateVisibilityHeightDeltaPx(tvTag, showResult, TAG_BASE_SP, ModuleSettings.getQueryResultLines(this));
        }

        if (tvNum != null) tvNum.setVisibility(showNum ? View.VISIBLE : View.GONE);
        if (tvTag != null) tvTag.setVisibility(showResult ? View.VISIBLE : View.GONE);

        if (deltaHeightPx != 0) {
            // 复用 v3.16 就有的"顶部锚点不动"位置补偿，避免重演之前网页开关那种跳动
            applyHeightDeltaKeepingTopEdge(deltaHeightPx);
        } else if (lp != null) {
            try { wm.updateViewLayout(floatView, lp); } catch (Exception ignored) {}
        }
    }

    /**
     * 估算 tv_num / tv_tag 从"隐藏"变"显示"（或反过来）会让悬浮窗高度变化多少像素。
     * 隐藏时：直接读它当前真实的 getHeight()（此时还是 VISIBLE，读到的是可靠的真实高度）。
     * 显示时：还没有真实布局结果，改用同样字号下的字体行高估算（Paint.getFontMetricsInt），
     * 不做"先测量、下一帧再纠正"的两步流程，避免重演 v3.16 修过的那种跳动问题。
     */
    private int estimateVisibilityHeightDeltaPx(TextView tv, boolean toVisible, float baseSp, int maxLinesForEstimate) {
        if (!toVisible) {
            return -tv.getHeight();
        }
        float scale = effectiveWidthFontScale();
        int maxWidthPx = effectiveMaxTextWidthPx();
        fitTextToScreen(tv, baseSp * scale, maxWidthPx, maxLinesForEstimate);
        Paint paint = tv.getPaint();
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        int lineHeight = fm.bottom - fm.top;
        String text = tv.getText() == null ? "" : tv.getText().toString();
        boolean wraps = !text.isEmpty() && maxLinesForEstimate > 1 && paint.measureText(text) > maxWidthPx;
        int lines = wraps ? 2 : 1;
        return lineHeight * lines;
    }

    // ── 四角拖拽热区：拖角缩放（v3.17 新增） ──────────────────────────────

    /**
     * 在卡片(root)所在的外层容器四个角上各放一个小热区，仅用于"更方便抓取"，四个角
     * 不做功能区分——不管拖哪个角，效果都一样：横向距离→宽度（以水平中心对称缩放），
     * 纵向距离→网页查询区域高度（顶部固定不动，只网页区域往下长/往上收）。
     * 折叠成悬浮球状态下不显示这些热区，见 setCornerHandlesVisible()。
     */
    private void addCornerHandles(FrameLayout outer) {
        String[] tags = {"corner_tl", "corner_tr", "corner_bl", "corner_br"};
        int[] gravities = {
                Gravity.TOP | Gravity.START,
                Gravity.TOP | Gravity.END,
                Gravity.BOTTOM | Gravity.START,
                Gravity.BOTTOM | Gravity.END,
        };
        boolean[] isLeftCorner = {true, false, true, false};
        int handlePx = dp(CORNER_HANDLE_DP);
        int marginPx = dp(2);
        // v3.19：新建悬浮窗时，按当前"是否可拖角缩放"设置决定这四个角一开始是否
        // 直接隐藏（GONE）。折叠球状态下 buildView() 里 ballView 本来就是默认展开的
        // 卡片，所以这里只需要看开关本身，不用再额外判断 isCollapsed。
        boolean initiallyVisible = ModuleSettings.isFloatResizable(this);

        for (int i = 0; i < tags.length; i++) {
            View handle = new View(this);
            handle.setTag(tags[i]);
            // v3.18：拖拽热区改成完全隐藏式——直接不画任何背景（而不是尝试"匹配卡片
            // 当前背景色"），因为悬浮窗背景本身有三种可选样式（半透明黑/半透明白/
            // 全透明），"匹配颜色"在"全透明"样式下无从匹配，逻辑复杂还可能露馅。
            // 不管卡片是哪种背景样式，热区永远看不出来，抓取区域还在，只是不画任何东西。
            handle.setBackground(null);
            handle.setVisibility(initiallyVisible ? View.VISIBLE : View.GONE);

            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(handlePx, handlePx);
            hlp.gravity = gravities[i];
            hlp.setMargins(marginPx, marginPx, marginPx, marginPx);
            outer.addView(handle, hlp);

            attachCornerResizeBehavior(handle, isLeftCorner[i]);
        }
    }

    /**
     * 折叠/展开切换时，同步四个拖拽热区的可见性（折叠成球时不显示/不响应）。
     * v3.19 新增：再叠加一层"是否可拖角缩放"总开关（ModuleSettings.isFloatResizable()）
     * ——折叠状态 或 开关关闭，两者任一成立就整个隐藏（GONE，不只是触摸不生效），
     * 逻辑上是简单的"与"关系。开关关闭时四个角没有其他功能需要保留，可以直接
     * 整个跳过，比"看不见但还占着"更干净。
     */
    private void setCornerHandlesVisible(boolean visible) {
        if (floatView == null) return;
        boolean actuallyVisible = visible && ModuleSettings.isFloatResizable(this);
        String[] tags = {"corner_tl", "corner_tr", "corner_bl", "corner_br"};
        for (String t : tags) {
            View v = floatView.findViewWithTag(t);
            if (v != null) v.setVisibility(actuallyVisible ? View.VISIBLE : View.GONE);
        }
    }

    private void attachCornerResizeBehavior(View handle, boolean isLeftCorner) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float lastRawX, lastRawY;
            private boolean dragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        // v3.19 新增"是否可拖角缩放"开关：每次触摸时实时读取，不需要广播、
                        // 不需要重建悬浮窗，关闭后立刻生效（对称于"是否可移动"的处理方式）。
                        if (floatView == null || isCollapsed
                                || !ModuleSettings.isFloatResizable(FloatWindowService.this)) {
                            return false;
                        }
                        View cardRoot = floatView.findViewWithTag("card_root");
                        if (cardRoot == null) return false;
                        if (!widthCustomized) {
                            // 第一次拖角：把当前 WRAP_CONTENT 撑出来的自然宽度原地转换成
                            // 一个具体的绝对像素值——转换瞬间数值就是当前测量值，不会跳动
                            int naturalWidth = cardRoot.getWidth();
                            if (naturalWidth > 0) {
                                cardWidthPx = naturalWidth;
                                widthCustomized = true;
                                applyCardWidthPx(cardRoot, cardWidthPx);
                            }
                        }
                        lastRawX = event.getRawX();
                        lastRawY = event.getRawY();
                        dragging = true;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (!dragging || floatView == null) return false;
                        float rawX = event.getRawX();
                        float rawY = event.getRawY();
                        float dx = rawX - lastRawX;
                        float dy = rawY - lastRawY;
                        lastRawX = rawX;
                        lastRawY = rawY;

                        // 横向：以中心对称缩放，单侧移动量是 outward，总宽度变化是它的 2 倍
                        float outward = isLeftCorner ? -dx : dx;
                        int newWidth = clampWidthPx(Math.round(cardWidthPx + outward * 2f));
                        boolean widthChanged = (newWidth != cardWidthPx);
                        if (widthChanged) {
                            cardWidthPx = newWidth;
                            widthCustomized = true;
                            View cardRoot = floatView.findViewWithTag("card_root");
                            if (cardRoot != null) applyCardWidthPx(cardRoot, cardWidthPx);
                            refreshTextSizing();
                        }

                        // 纵向：不区分是哪个角，统一按"向下拖 = 网页区域变高"处理，顶部锚点不动
                        // （即使是从顶部两个角拖的，也只影响网页区域高度，不影响顶部裁剪锚点本身）
                        int newHeight = clampWebHeightPx(webContainerHeightPx + Math.round(dy));
                        int actualDelta = newHeight - webContainerHeightPx;
                        if (actualDelta != 0) {
                            webContainerHeightPx = newHeight;
                            applyWebContainerHeightPx(webContainerHeightPx);
                            applyHeightDeltaKeepingTopEdge(actualDelta);
                        } else if (widthChanged) {
                            // 只有宽度变了、高度没变时，也要让 WindowManager 重新量一次新宽度
                            if (lp != null) {
                                try { wm.updateViewLayout(floatView, lp); } catch (Exception ignored) {}
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            dragging = false;
                            if (widthCustomized) {
                                ModuleSettings.setFloatWidthPx(FloatWindowService.this, cardWidthPx);
                            }
                            ModuleSettings.setWebHeightPx(FloatWindowService.this, webContainerHeightPx);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /** 悬浮窗宽度钳制到 [FLOAT_WIDTH_MIN_RATIO, FLOAT_WIDTH_MAX_RATIO] × 屏幕宽度之间。 */
    private int clampWidthPx(int px) {
        int screenWidthPx = Resources.getSystem().getDisplayMetrics().widthPixels;
        int min = Math.round(screenWidthPx * ModuleSettings.FLOAT_WIDTH_MIN_RATIO);
        int max = Math.round(screenWidthPx * ModuleSettings.FLOAT_WIDTH_MAX_RATIO);
        return Math.max(min, Math.min(max, px));
    }

    /** 网页展示高度钳制到 [WEB_HEIGHT_MIN_DP, WEB_HEIGHT_MAX_RATIO × 屏幕高度] 之间。 */
    private int clampWebHeightPx(int px) {
        int minPx = dp(ModuleSettings.WEB_HEIGHT_MIN_DP);
        int screenHeightPx = Resources.getSystem().getDisplayMetrics().heightPixels;
        int maxPx = Math.round(screenHeightPx * ModuleSettings.WEB_HEIGHT_MAX_RATIO);
        if (maxPx < minPx) maxPx = minPx;
        return Math.max(minPx, Math.min(maxPx, px));
    }

    /** 把卡片(root)的宽度改成一个具体像素值（从 WRAP_CONTENT 转过来，或者拖动中持续更新）。 */
    private void applyCardWidthPx(View cardRoot, int widthPx) {
        ViewGroup.LayoutParams clp = cardRoot.getLayoutParams();
        if (clp != null) {
            clp.width = widthPx;
            cardRoot.setLayoutParams(clp);
        }
    }

    /**
     * 拖角缩放网页区域高度时，同步更新容器(web_container)自身的 LayoutParams.height，
     * 以及（如果网页当前正开着）里面 WebView 自身的高度——网页展示高度 = 容器高度 +
     * 顶部裁剪像素，两者必须在同一次拖动里一起改，否则会出现"容器变了、里面网页
     * 没跟着变/顶部裁剪的偏移量对不上"的问题。只改 LayoutParams，不重建 WebView。
     */
    private void applyWebContainerHeightPx(int heightPx) {
        if (floatView == null) return;
        FrameLayout container = floatView.findViewWithTag("web_container");
        if (container != null) {
            ViewGroup.LayoutParams clp = container.getLayoutParams();
            if (clp != null) {
                clp.height = heightPx;
                container.setLayoutParams(clp);
            }
        }
        if (webQueryView != null) {
            // 用"这个 WebView 实际打开时的来源"而不是实时查询当前设置里选的来源，
            // 避免"网页开着时在设置里切换了来源"导致裁剪值和屏幕上实际显示的网页对不上
            int cropPx = ModuleSettings.getWebTopCropPx(this, webQueryLoadedSource);
            if (cropPx < 0) cropPx = 0;
            ViewGroup.LayoutParams wvLp = webQueryView.getLayoutParams();
            if (wvLp != null) {
                wvLp.height = heightPx + cropPx;
                webQueryView.setLayoutParams(wvLp);
            }
        }
    }

    /** 点击"打开/关闭网页查询"按钮：在两种状态间切换，同时保持悬浮窗顶部视觉位置不动 */
    private void toggleWebQuery(TextView btn, FrameLayout container) {
        // 网页区域展开/收起造成的高度变化量是已知常量（v3.16：不再靠"改完之后量
        // 一遍"去发现，见 applyHeightDeltaKeepingTopEdge() 说明），开时是 +delta，
        // 关时是 -delta。注意 openWebQuery() 在号码为空/自定义网址未填写时会
        // 提前 return、什么都不做——这种情况下不能跟着套用高度补偿（否则会在
        // 内容其实没变化的情况下把悬浮窗错误地挪动位置），所以用调用前后
        // webQueryView 是否真的变化了来判断这次操作是否真的发生。
        int delta = webQueryHeightDeltaPx();
        if (webQueryView != null) {
            closeWebQuery(btn, container);
            applyHeightDeltaKeepingTopEdge(-delta);
        } else {
            openWebQuery(btn, container);
            if (webQueryView != null) {
                applyHeightDeltaKeepingTopEdge(delta);
            }
        }
    }

    /** 网页查询区域展开时，相对收起状态会额外占用的高度（自身高度 + 与上一行的间距）。 */
    private int webQueryHeightDeltaPx() {
        return webContainerHeightPx + dp(4);
    }

    /**
     * 让悬浮窗的内容高度改变 deltaHeightPx（正值=变高，比如展开网页；负值=变矮，
     * 比如收起网页），同时保持悬浮窗顶部边缘的屏幕位置不变，并在一次
     * updateViewLayout() 里跟内容变化一起提交——不等下一帧、不依赖事后测量，
     * 所以既不会有旧版那种"先跳一下再纠正回来"的可见闪动，也不存在测量误差
     * 累积导致悬浮窗越点越往上爬的问题（v3.16 新增，替代旧版
     * adjustWindowKeepingTopEdge()）。
     *
     * 具体换算：
     * - BOTTOM 对齐（从未拖动过的默认底部居中状态）：y 语义是"距屏幕底边的距离"，
     *   内容变高 deltaHeightPx 时，若不调整 y，窗口会以底边为轴向上长高
     *   （顶部上移 deltaHeightPx）；要把顶部position 补偿回原位，整体需要向下
     *   移动 deltaHeightPx，即 y 要减小 deltaHeightPx。
     * - CENTER 对齐（拖动过之后的常态）：窗口以自身几何中心为轴对称长高，顶部
     *   上移 deltaHeightPx/2；要补偿回原位，中心需要下移 deltaHeightPx/2，
     *   即 y 要增大 deltaHeightPx/2。
     *
     * 另外新增边界处理：如果按上面算完的位置，窗口下边缘会超出屏幕底部（比如
     * 悬浮窗本来就贴近底部，这时展开网页会把窗口下边缘顶到屏幕外面），则在此
     * 基础上把整个窗口再多向上移一段，刚好让下边缘落回屏幕内——这样"贴底展开
     * 网页时悬浮窗可以往上让位"的效果，也是这同一次计算里一并做掉的，不需要
     * 额外再触发一次布局。
     */
    private void applyHeightDeltaKeepingTopEdge(int deltaHeightPx) {
        if (floatView == null || lp == null || deltaHeightPx == 0) return;

        int heightBefore = floatView.getHeight();
        // 极端情况下（悬浮窗还没被真正布局过一次）拿不到有效高度，退化成不做
        // 任何位置补偿，只让内容变化生效，避免用一个不可靠的 0 去算出乱七八糟的偏移
        if (heightBefore <= 0) {
            try { wm.updateViewLayout(floatView, lp); } catch (Exception ignored) {}
            return;
        }

        int screenHeightPx = Resources.getSystem().getDisplayMetrics().heightPixels;
        int vGravity = lp.gravity & Gravity.VERTICAL_GRAVITY_MASK;
        boolean isBottomGravity = (vGravity == Gravity.BOTTOM);

        // 变化前窗口顶部的屏幕绝对坐标（按当前 gravity 语义反推，不依赖测量）
        int topBefore;
        if (isBottomGravity) {
            topBefore = screenHeightPx - lp.y - heightBefore;
        } else {
            int screenCenterY = screenHeightPx / 2;
            int centerYBefore = screenCenterY + lp.y;
            topBefore = centerYBefore - heightBefore / 2;
        }

        // 先按"顶部不动"补偿
        if (isBottomGravity) {
            lp.y -= deltaHeightPx;
        } else {
            lp.y += deltaHeightPx / 2;
        }

        // 再做贴底展开时的边界处理：顶部保持在 topBefore 的前提下，如果新高度会
        // 让下边缘超出屏幕，就把整体再往上移动刚好超出的这一部分
        if (deltaHeightPx > 0) {
            int heightAfter = heightBefore + deltaHeightPx;
            int targetBottom = topBefore + heightAfter;
            int maxBottom = screenHeightPx; // 不额外留边距，贴到屏幕最底也算合法
            int overflow = targetBottom - maxBottom;
            if (overflow > 0) {
                if (isBottomGravity) {
                    lp.y += overflow;
                } else {
                    lp.y -= overflow;
                }
            }
        }

        try { wm.updateViewLayout(floatView, lp); } catch (Exception ignored) {}
    }

    private void openWebQuery(TextView btn, FrameLayout container) {
        String number = currentNumber;
        if (number == null || number.isEmpty()) return;

        int source = ModuleSettings.getWebQuerySource(this);
        String template;
        if (source == ModuleSettings.WEB_SOURCE_360) {
            template = ModuleSettings.URL_360;
        } else if (source == ModuleSettings.WEB_SOURCE_CUSTOM) {
            template = ModuleSettings.getWebQueryCustomUrl(this);
            if (template == null || template.trim().isEmpty()) {
                Toast.makeText(this, "请先在设置中填写自定义网址", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            template = ModuleSettings.URL_SOGOU;
        }

        String url = template.contains("来电号码")
                ? template.replace("来电号码", number)
                : template; // 未检测到占位词：按用户设置时的提醒说明，原样加载固定网址

        WebView wv = new WebView(this);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setTextZoom(currentWebTextZoomPercent());
        // v3.19 新增"防真弹窗"通用保险：明确禁止 window.open() 弹出新窗口/新标签页
        // （不设置 WebChromeClient.onCreateWindow() 本来就无法真正弹出，这里是显式
        // 声明，双重保险）。alert/confirm/prompt 交给下面的 QueryWebChromeClient
        // 统一自动取消，不弹出打断操作的原生对话框。
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        wv.setWebChromeClient(new QueryWebChromeClient());
        // 链接跳转仍在本 WebView 内加载，不跳到外部浏览器；该子类还负责"强制反色"
        // 模式下的 CSS 注入，以及无条件隐藏搜狗"下载QQ浏览器"横幅（见 QueryWebViewClient）
        wv.setWebViewClient(new QueryWebViewClient());
        applyWebDarkMode(ws);

        // 网页顶部裁剪（v1.9 新增，v3.18 拆分为三来源各自独立）：WebView 实际高度做成
        // "容器高度 + 裁剪像素"，整体向上平移裁剪像素，容器保持原高度不变，超出部分
        // 被自动裁掉，从而把网页顶部固定的搜索框/标签栏区域整体裁掉不显示。
        // 触摸坐标随平移自动校正，滚动/点击不受影响。0 = 不裁剪。
        webQueryLoadedSource = source; // 记录这次实际打开用的来源，供拖角缩放时使用（见类字段注释）
        int cropPx = ModuleSettings.getWebTopCropPx(this, source);
        if (cropPx < 0) cropPx = 0;
        int wvHeightPx = webContainerHeightPx + cropPx;

        container.removeAllViews();
        container.addView(wv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, wvHeightPx));
        if (cropPx > 0) {
            wv.setTranslationY(-cropPx);
        }
        wv.loadUrl(url);

        webQueryView = wv;
        webQueryLoadedNumber = number;
        container.setVisibility(View.VISIBLE);
        btn.setText("\u2715 关闭网页查询"); // ✕ 关闭网页查询
    }

    private void closeWebQuery(TextView btn, FrameLayout container) {
        destroyWebQueryIfAny();
        container.removeAllViews();
        container.setVisibility(View.GONE);
        btn.setText("\uD83D\uDD0D 打开网页查询"); // 🔍 打开网页查询
    }

    /**
     * WebViewClient 子类：链接跳转仍在本 WebView 内加载（不跳到外部浏览器）；
     * 每次页面加载完成（onPageFinished，含页内点击链接跳转到新页面触发的那次）
     * 都会：
     * ① 无条件注入 HIDE_QB_DOWNLOAD_BANNER_JS，隐藏搜狗结果页"打开QQ浏览器，
     *    搜更多有用内容"这条自我推广横幅（class="qb-download-banner-non-share"，
     *    实测截图取得，v3.19 新增）。不区分查询来源——360/自定义网址页面里
     *    大概率没有这个 class，注入了也匹配不到任何元素，等于空操作，无害。
     *    这是页面自己的 DOM 内容，不是真正的浏览器弹窗，所以只能这样针对性
     *    隐藏，且完全依赖这个具体 class 名字——如果搜狗以后改版换了 class，
     *    这条规则会悄悄失效，需要重新抓取页面结构、更新选择器。
     * ② 在"强制反色"深色模式档位下，额外注入 FORCE_INVERT_JS 反色样式
     *    （v3.13 新增）。
     */
    private class QueryWebViewClient extends WebViewClient {
        private static final String FORCE_INVERT_JS =
                "(function(){"
                + "var s=document.createElement('style');"
                + "s.innerHTML='html{filter:invert(1) hue-rotate(180deg);}"
                + "img,video,picture,canvas,svg{filter:invert(1) hue-rotate(180deg);}';"
                + "document.head.appendChild(s);"
                + "})();";

        // v3.19 新增：隐藏搜狗结果页"下载QQ浏览器"横幅，class 名字通过实际截图
        // Elements 面板取得（qb-download-banner-non-share），只隐藏这一个容器，
        // 不影响页面其他内容。
        private static final String HIDE_QB_DOWNLOAD_BANNER_JS =
                "(function(){"
                + "var s=document.createElement('style');"
                + "s.innerHTML='.qb-download-banner-non-share{display:none !important;}';"
                + "document.head.appendChild(s);"
                + "})();";

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            try { view.evaluateJavascript(HIDE_QB_DOWNLOAD_BANNER_JS, null); } catch (Exception ignored) {}
            if (ModuleSettings.getWebDarkMode(FloatWindowService.this)
                    == ModuleSettings.WEB_DARK_MODE_FORCE_INVERT) {
                try { view.evaluateJavascript(FORCE_INVERT_JS, null); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * WebChromeClient 子类（v3.19 新增，"防真弹窗"通用保险）：网页调用
     * window.alert() / confirm() / prompt() 时，不弹出会打断操作的原生对话框，
     * 直接自动"取消"/"确定为空"处理掉。跟查询来源、深色模式无关，任何网站
     * 的类似骚扰都一并防住。window.open() 弹新窗口/新标签页不受这个类影响——
     * 那是由 WebSettings.setSupportMultipleWindows(false) /
     * setJavaScriptCanOpenWindowsAutomatically(false) 处理的，且本来不实现
     * onCreateWindow() 就无法真正弹出新窗口。
     */
    private class QueryWebChromeClient extends android.webkit.WebChromeClient {
        @Override
        public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
            result.cancel();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
            result.cancel();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                   android.webkit.JsPromptResult result) {
            result.cancel();
            return true;
        }
    }

    /**
     * 按用户设置的深色模式档位（跟随系统/浅色/深色），对网页做算法级强制深色渲染。
     * 使用 androidx.webkit 的兼容 API，运行时按 WebView 内核版本自动选择：
     * 新版 WebView 用 setAlgorithmicDarkeningAllowed()，不支持时退化到旧版 setForceDark()；
     * 两者都不支持的极老 WebView 内核上什么都不做（网页按原样浅色显示）。
     * "强制反色"档位不走这套系统 API（避免叠加导致颜色异常），而是在
     * QueryWebViewClient.onPageFinished() 里注入 CSS filter 实现，这里直接跳过。
     */
    private void applyWebDarkMode(WebSettings ws) {
        int mode = ModuleSettings.getWebDarkMode(this);
        if (mode == ModuleSettings.WEB_DARK_MODE_FORCE_INVERT) return;
        boolean wantDark;
        if (mode == ModuleSettings.WEB_DARK_MODE_DARK) {
            wantDark = true;
        } else if (mode == ModuleSettings.WEB_DARK_MODE_LIGHT) {
            wantDark = false;
        } else {
            int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            wantDark = (uiMode == Configuration.UI_MODE_NIGHT_YES);
        }

        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(ws, wantDark);
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(ws, wantDark
                        ? WebSettingsCompat.FORCE_DARK_ON
                        : WebSettingsCompat.FORCE_DARK_OFF);
            }
        } catch (Exception ignored) {
            // 极个别设备 WebView 内核实现不完整时，静默跳过，不影响网页正常加载
        }
    }

    /**
     * 计算 WebView.setTextZoom() 需要的百分比：
     * - 跟随系统：读取 Configuration.fontScale 换算成百分比（限制在 50~200 区间防极端值）
     * - 固定档位：80/100/120/150 直接返回
     * 注意：setTextZoom() 只影响本 App 自己创建的这个 WebView 实例，
     * 不会影响系统或其他 App 的 WebView。
     */
    private int currentWebTextZoomPercent() {
        int tier = ModuleSettings.getWebFontZoom(this);
        switch (tier) {
            case ModuleSettings.WEB_FONT_ZOOM_80:  return 80;
            case ModuleSettings.WEB_FONT_ZOOM_100: return 100;
            case ModuleSettings.WEB_FONT_ZOOM_120: return 120;
            case ModuleSettings.WEB_FONT_ZOOM_150: return 150;
            case ModuleSettings.WEB_FONT_ZOOM_SYSTEM:
            default:
                float fontScale = getResources().getConfiguration().fontScale;
                int pct = Math.round(fontScale * 100f);
                if (pct < 50)  pct = 50;
                if (pct > 200) pct = 200;
                return pct;
        }
    }
}
