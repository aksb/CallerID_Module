package com.callerid.module;

import android.content.Context;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * WebView 查询 mhaoma.baidu.com，轮询 DOM 出现后立即抓取，不死等固定秒数。
 * v1.5：
 *  - 替换固定 5s 等待为轮询（最快约 1~2s 出结果）
 *  - 集成 CacheStore 双层缓存
 *  - 特殊号码白名单直接返回
 *  - 400/800 兜底
 *  - 企业详情页/普通号码分路径解析
 */
public class WebQueryHelper {

    // 当前是否已把本进程强制绑定到蜂窝网络（WiFi 环境下查询失败的兜底方案）
    private ConnectivityManager.NetworkCallback cellularCallback;
    private ConnectivityManager cachedCm;

    /**
     * 强制本进程走蜂窝数据网络，即使当前已连接 WiFi。
     * 原理：通过 ConnectivityManager 主动请求一个 TRANSPORT_CELLULAR 的 Network，
     * 拿到后用 bindProcessToNetwork() 把"进程默认网络"钉死为蜂窝网络，
     * 这样本进程后续发出的所有网络请求（包括 WebView 里的请求）都会走流量而不是 WiFi。
     *
     * @param onReady   成功绑定到蜂窝网络后回调（在主线程）
     * @param onGiveUp  没有可用蜂窝网络（比如没插卡/纯 WiFi 设备）或超时，回调后按原逻辑继续（不强制切换）
     */
    private void forceCellularThen(Context ctx, Runnable onReady, Runnable onGiveUp) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) { onGiveUp.run(); return; }
        cachedCm = cm;

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        final boolean[] finished = {false};
        Handler main = new Handler(Looper.getMainLooper());

        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (finished[0]) return;
                finished[0] = true;
                try {
                    boolean ok = cm.bindProcessToNetwork(network);
                    Log.d(TAG, "bindProcessToNetwork(cellular) = " + ok);
                } catch (Throwable e) {
                    Log.e(TAG, "bindProcessToNetwork failed", e);
                }
                cellularCallback = this;
                main.post(onReady);
            }

            @Override
            public void onUnavailable() {
                if (finished[0]) return;
                finished[0] = true;
                Log.e(TAG, "no cellular network available, fallback to default network");
                main.post(onGiveUp);
            }
        };

        try {
            // requestNetwork 自带超时参数：5s 内拿不到蜂窝网络就放弃强制切换
            cm.requestNetwork(request, callback, 5000);
        } catch (Throwable e) {
            Log.e(TAG, "requestNetwork failed", e);
            onGiveUp.run();
        }
    }

    /** 查询结束后解除蜂窝网络绑定，恢复系统默认路由（避免影响进程内其它网络行为） */
    private void releaseCellularBinding() {
        if (cachedCm != null) {
            try { cachedCm.bindProcessToNetwork(null); } catch (Throwable ignored) {}
            if (cellularCallback != null) {
                try { cachedCm.unregisterNetworkCallback(cellularCallback); } catch (Throwable ignored) {}
            }
        }
        cellularCallback = null;
    }

    // 内置号码数据库：1581 条，来源：metowolf/vCards + dianhuacha/114best/ijjnews
    private static final java.util.HashMap<String, String> BUILTIN_DB;
    static {
        BUILTIN_DB = new java.util.HashMap<>(3162);
        BUILTIN_DB.put("01053153053", "明道云");
        BUILTIN_DB.put("01053934972", "极客时间");
        BUILTIN_DB.put("01056640700", "什么值得买");
        BUILTIN_DB.put("01058160600", "民政部");
        BUILTIN_DB.put("01058209555", "神州租车");
        BUILTIN_DB.put("01058800260", "北京师范大学");
        BUILTIN_DB.put("01058934114", "建设部");
        BUILTIN_DB.put("01059344888", "小猿搜题");
        BUILTIN_DB.put("01059928888", "百度");
        BUILTIN_DB.put("01060606666", "小米");
        BUILTIN_DB.put("01060642355", "小米");
        BUILTIN_DB.put("01061190680", "知乎");
        BUILTIN_DB.put("01061840600", "雪球");
        BUILTIN_DB.put("01062511601", "中国人民大学");
        BUILTIN_DB.put("01062620698", "孔网");
        BUILTIN_DB.put("01062671188", "腾讯");
        BUILTIN_DB.put("01062752114", "北京大学");
        BUILTIN_DB.put("01062782165", "清华大学");
        BUILTIN_DB.put("01062789888", "清华同方");
        BUILTIN_DB.put("01062896811", "中国农业大学");
        BUILTIN_DB.put("01063202114", "水利部");
        BUILTIN_DB.put("01064035547", "中国青少年发展基金会");
        BUILTIN_DB.put("01064193366", "农业部");
        BUILTIN_DB.put("01065051156", "中金公司");
        BUILTIN_DB.put("01065051166", "中金公司");
        BUILTIN_DB.put("01065194114", "海关总署");
        BUILTIN_DB.put("01065292114", "交通部");
        BUILTIN_DB.put("01065551114", "文化部");
        BUILTIN_DB.put("01065961114", "外交部");
        BUILTIN_DB.put("01066083260", "中华慈善总会");
        BUILTIN_DB.put("01066096114", "教育部");
        BUILTIN_DB.put("01066411166", "信产部IP/网站备案");
        BUILTIN_DB.put("01068551114", "财政部");
        BUILTIN_DB.put("01082317114", "北京航空航天大学");
        BUILTIN_DB.put("01082558163", "网易");
        BUILTIN_DB.put("01082872688", "扶贫基金会");
        BUILTIN_DB.put("01083461806", "学浪");
        BUILTIN_DB.put("01084025890", "红十字会");
        BUILTIN_DB.put("01084201114", "劳动部");
        BUILTIN_DB.put("01084799058", "豆瓣");
        BUILTIN_DB.put("01085679888", "中金公司");
        BUILTIN_DB.put("01086485788", "脉脉");
        BUILTIN_DB.put("01088087733", "北京一卡通");
        BUILTIN_DB.put("01096066", "北京一卡通");
        BUILTIN_DB.put("01096123", "北京轨道交通路网乘客服务热线");
        BUILTIN_DB.put("01096165", "北京地铁");
        BUILTIN_DB.put("01096166", "北京公交");
        BUILTIN_DB.put("01096169", "北京银行");
        BUILTIN_DB.put("02038900009", "明基(BENQ)");
        BUILTIN_DB.put("02081167888", "腾讯");
        BUILTIN_DB.put("02082253777", "七喜");
        BUILTIN_DB.put("02083134264", "广东政务服务");
        BUILTIN_DB.put("02084113480", "中山大学");
        BUILTIN_DB.put("02085105163", "网易");
        BUILTIN_DB.put("02085220114", "暨南大学");
        BUILTIN_DB.put("02087110000", "华南理工大学");
        BUILTIN_DB.put("02095508", "广发银行");
        BUILTIN_DB.put("02134206500", "上海交通大学");
        BUILTIN_DB.put("02138690588", "中国建设银行");
        BUILTIN_DB.put("02138695188", "中国建设银行");
        BUILTIN_DB.put("02138784800", "招商银行");
        BUILTIN_DB.put("02139207888", "韵达快运");
        BUILTIN_DB.put("02139777777", "中通速递");
        BUILTIN_DB.put("02152504617", "壹基金");
        BUILTIN_DB.put("02154569595", "腾讯");
        BUILTIN_DB.put("02160868586", "盈透证券");
        BUILTIN_DB.put("02160876100", "哔哩哔哩");
        BUILTIN_DB.put("02161295599", "中国农业银行");
        BUILTIN_DB.put("02161679500", "哈啰出行");
        BUILTIN_DB.put("02161947163", "网易");
        BUILTIN_DB.put("02162233333", "华东师范大学");
        BUILTIN_DB.put("02162963636", "汇通快运");
        BUILTIN_DB.put("02164370000", "上海地铁");
        BUILTIN_DB.put("02165642222", "复旦大学");
        BUILTIN_DB.put("02165982200", "同济大学");
        BUILTIN_DB.put("02167662333", "天天快递");
        BUILTIN_DB.put("02168674800", "招商银行");
        BUILTIN_DB.put("02169777888", "圆通速递");
        BUILTIN_DB.put("02169781999", "鑫飞鸿快递");
        BUILTIN_DB.put("021962319", "上海交通卡");
        BUILTIN_DB.put("021962888", "上海银行");
        BUILTIN_DB.put("02223508219", "南开大学");
        BUILTIN_DB.put("02227403536", "天津大学");
        BUILTIN_DB.put("02483679262", "东北大学");
        BUILTIN_DB.put("02583593186", "南京大学");
        BUILTIN_DB.put("02768755114", "武汉大学");
        BUILTIN_DB.put("02787541114", "华中科技大学");
        BUILTIN_DB.put("02865730878", "云喇叭");
        BUILTIN_DB.put("02885225111", "腾讯");
        BUILTIN_DB.put("02885406437", "四川大学");
        BUILTIN_DB.put("028966185", "云喇叭");
        BUILTIN_DB.put("02982668888", "西安交通大学");
        BUILTIN_DB.put("043185166420", "吉林大学");
        BUILTIN_DB.put("045186414060", "哈尔滨工业大学");
        BUILTIN_DB.put("052780878888", "当当网");
        BUILTIN_DB.put("053188564774", "山东大学");
        BUILTIN_DB.put("053296588", "青岛银行");
        BUILTIN_DB.put("05513601000", "中国科技大学");
        BUILTIN_DB.put("055162641662", "飞常准");
        BUILTIN_DB.put("055196588", "徽商银行");
        BUILTIN_DB.put("057185022088", "阿里巴巴");
        BUILTIN_DB.put("057185172244", "浙江大学");
        BUILTIN_DB.put("057188158198", "淘宝");
        BUILTIN_DB.put("057188808880", "浙里办");
        BUILTIN_DB.put("057189852163", "网易");
        BUILTIN_DB.put("057189853546", "网易云音乐");
        BUILTIN_DB.put("057488382026", "小宇宙");
        BUILTIN_DB.put("05922186110", "厦门大学");
        BUILTIN_DB.put("05923386666", "瑞幸咖啡");
        BUILTIN_DB.put("073188876834", "中南大学");
        BUILTIN_DB.put("073196500", "湖南三湘银行");
        BUILTIN_DB.put("075522731198", "华为");
        BUILTIN_DB.put("075528780808", "华为");
        BUILTIN_DB.put("075582137777", "深圳水务");
        BUILTIN_DB.put("075583765566", "腾讯");
        BUILTIN_DB.put("075583865666", "立创商城");
        BUILTIN_DB.put("075586013388", "腾讯");
        BUILTIN_DB.put("075586965007", "酷安");
        BUILTIN_DB.put("075589888888", "比亚迪");
        BUILTIN_DB.put("076922862200", "华为");
        BUILTIN_DB.put("076923280808", "华为");
        BUILTIN_DB.put("079196268", "江西农商银行");
        BUILTIN_DB.put("087196533", "富滇银行");
        BUILTIN_DB.put("089865337739", "ToDesk");
        BUILTIN_DB.put("09318911114", "兰州大学");
        BUILTIN_DB.put("10000", "中国电信");
        BUILTIN_DB.put("1000010", "中国电信");
        BUILTIN_DB.put("100003", "中国电信");
        BUILTIN_DB.put("10001", "中国电信");
        BUILTIN_DB.put("10001666", "中国电信");
        BUILTIN_DB.put("10006", "中国电信");
        BUILTIN_DB.put("10010", "中国联通");
        BUILTIN_DB.put("10011", "中国联通");
        BUILTIN_DB.put("10012", "中国联合网络通信集团有限公司");
        BUILTIN_DB.put("10015", "中国联合网络通信集团有限公司");
        BUILTIN_DB.put("10016", "中国联通");
        BUILTIN_DB.put("10018", "中国联通");
        BUILTIN_DB.put("10019", "中国联合网络通信集团有限公司");
        BUILTIN_DB.put("10020", "巴士在线控股有限公司");
        BUILTIN_DB.put("10021", "深圳市爱施德股份有限公司");
        BUILTIN_DB.put("10023", "北京京东叁佰陆拾度电子商务有限公司");
        BUILTIN_DB.put("10024", "红豆集团有限公司");
        BUILTIN_DB.put("10025", "长江时代通信股份有限公司");
        BUILTIN_DB.put("10026", "北京迪信通通信服务有限公司");
        BUILTIN_DB.put("10027", "远特（北京）通信技术有限公司");
        BUILTIN_DB.put("10028", "北京乐语世纪通讯设备连锁有限公司");
        BUILTIN_DB.put("10029", "阿里通信");
        BUILTIN_DB.put("10030", "浙江连连科技有限公司");
        BUILTIN_DB.put("10031", "中期集团有限公司");
        BUILTIN_DB.put("10032", "贵阳朗玛信息技术股份有限公司");
        BUILTIN_DB.put("10033", "天音通信有限公司");
        BUILTIN_DB.put("10034", "二六三网络通信股份有限公司");
        BUILTIN_DB.put("10035", "苏宁易购");
        BUILTIN_DB.put("10036", "北京华翔联信科技有限公司");
        BUILTIN_DB.put("10037", "北京国美电器有限公司");
        BUILTIN_DB.put("10038", "厦门三五互联科技股份有限公司");
        BUILTIN_DB.put("10039", "北京分享在线网络技术有限公司");
        BUILTIN_DB.put("10040", "苏州蜗牛数字科技股份有限公司");
        BUILTIN_DB.put("10044", "海南海航信息技术有限公司");
        BUILTIN_DB.put("10045", "深圳市中兴视通科技有限公司");
        BUILTIN_DB.put("10048", "用友移动通信技术服务有限公司");
        BUILTIN_DB.put("10049", "深圳星美圣典文化传媒集团有限公司");
        BUILTIN_DB.put("10050", "中国移动");
        BUILTIN_DB.put("10080", "中国移动通信集团公司");
        BUILTIN_DB.put("10085", "中国移动");
        BUILTIN_DB.put("10086", "中国移动");
        BUILTIN_DB.put("10086021", "中国移动");
        BUILTIN_DB.put("1008611", "中国移动");
        BUILTIN_DB.put("10086766", "中国移动");
        BUILTIN_DB.put("10086900", "中国移动");
        BUILTIN_DB.put("10086999", "中国垃圾短信投诉号码");
        BUILTIN_DB.put("10088", "中国移动");
        BUILTIN_DB.put("10096", "中国铁塔股份有限公司");
        BUILTIN_DB.put("10099", "中国广电");
        BUILTIN_DB.put("10100011", "大众点评");
        BUILTIN_DB.put("10100571", "同花顺");
        BUILTIN_DB.put("10105678", "首汽约车");
        BUILTIN_DB.put("10105757", "饿了么");
        BUILTIN_DB.put("10106188", "贝壳");
        BUILTIN_DB.put("10107888", "美团");
        BUILTIN_DB.put("10109559", "中国农业银行");
        BUILTIN_DB.put("10109599", "中国农业银行");
        BUILTIN_DB.put("10109666", "链家");
        BUILTIN_DB.put("10109777", "美团外卖");
        BUILTIN_DB.put("10165", "中国联通");
        BUILTIN_DB.put("10188", "沃钱包");
        BUILTIN_DB.put("101906", "中国联通");
        BUILTIN_DB.put("10655152", "中国联通");
        BUILTIN_DB.put("10655511", "中国联通");
        BUILTIN_DB.put("1065795555", "招商银行");
        BUILTIN_DB.put("10658112", "中国移动");
        BUILTIN_DB.put("1065883001", "中国移动");
        BUILTIN_DB.put("1065911468", "中国电信");
        BUILTIN_DB.put("10659210000", "中国电信");
        BUILTIN_DB.put("106599366", "中国电信");
        BUILTIN_DB.put("1069095599", "中国农业银行");
        BUILTIN_DB.put("110", "报警服务台");
        BUILTIN_DB.put("11183", "中国邮政");
        BUILTIN_DB.put("11185", "中国邮政");
        BUILTIN_DB.put("112", "紧急呼叫中心");
        BUILTIN_DB.put("114", "查号台");
        BUILTIN_DB.put("116114", "中国联通的\"电话导航\"业务");
        BUILTIN_DB.put("118114", "中国电信号码百事通");
        BUILTIN_DB.put("11888", "翼支付");
        BUILTIN_DB.put("119", "火警电话");
        BUILTIN_DB.put("120", "急救电话");
        BUILTIN_DB.put("12110", "公安短信报警号码");
        BUILTIN_DB.put("12114", "工业和信息化部电信研究院");
        BUILTIN_DB.put("12117", "报时服务电话");
        BUILTIN_DB.put("12119", "由各通信管理局根据当地需求、按照规划核配启用");
        BUILTIN_DB.put("12121", "气象服务电话");
        BUILTIN_DB.put("12122", "交通事故报警电话");
        BUILTIN_DB.put("12123", "交通管理局");
        BUILTIN_DB.put("121231100", "交通管理局");
        BUILTIN_DB.put("121232303", "交通管理局");
        BUILTIN_DB.put("121233100", "交通管理局");
        BUILTIN_DB.put("121233201", "交通管理局");
        BUILTIN_DB.put("121233206", "交通管理局");
        BUILTIN_DB.put("121233207", "交通管理局");
        BUILTIN_DB.put("121233300", "交通管理局");
        BUILTIN_DB.put("122", "道路交通事故报警台");
        BUILTIN_DB.put("12300", "工业和信息化部");
        BUILTIN_DB.put("12301", "全国统一旅游资讯服务专用电话号码");
        BUILTIN_DB.put("12302", "全国统一科学技术服务专用电话号码");
        BUILTIN_DB.put("12303", "信访投诉受理查询服务电话号码");
        BUILTIN_DB.put("12305", "邮政业消费者申诉专用电话号码");
        BUILTIN_DB.put("12306", "中国铁路");
        BUILTIN_DB.put("12307", "干部理论学习热线专用电话号码");
        BUILTIN_DB.put("12308", "全球领事保护与应急服务专用号码");
        BUILTIN_DB.put("12309", "全国统一检察服务电话号码");
        BUILTIN_DB.put("12310", "机构编制违规举报热线");
        BUILTIN_DB.put("12311", "新华社新闻热线电话号码");
        BUILTIN_DB.put("12312", "全国统一商务领域举报投诉咨询电话号码");
        BUILTIN_DB.put("12313", "全国烟草专卖品市场监管举报电话号码");
        BUILTIN_DB.put("12315", "消费者投诉举报专线电话");
        BUILTIN_DB.put("12316", "全国农业系统服务专用电话号码");
        BUILTIN_DB.put("12317", "全国扶贫工作监督举报电话号码");
        BUILTIN_DB.put("12318", "文化市场统一举报电话");
        BUILTIN_DB.put("12319", "全国住房和城乡建设服务专用电话号码");
        BUILTIN_DB.put("12320", "公共卫生环境投诉");
        BUILTIN_DB.put("12321", "网络不良与垃圾信息举报受理中心");
        BUILTIN_DB.put("12322", "防震减灾服务热线电话号码");
        BUILTIN_DB.put("12323", "全国海洋行政执法举报电话号码");
        BUILTIN_DB.put("12325", "全国粮食流通监管热线电话号码");
        BUILTIN_DB.put("12328", "交通运输服务监督电话");
        BUILTIN_DB.put("12329", "住房公积金");
        BUILTIN_DB.put("12330", "知识产权维权援助公益服务专用电话号码");
        BUILTIN_DB.put("12331", "全国食品药品投诉举报热线专用电话号码");
        BUILTIN_DB.put("12332", "国务院办公厅");
        BUILTIN_DB.put("12333", "民工维权热线电话");
        BUILTIN_DB.put("12335", "中国企业境外商务投诉电话号码");
        BUILTIN_DB.put("12336", "全国统一国土资源违规违法举报电话号码");
        BUILTIN_DB.put("12338", "全国妇女维权服务专用电话号码");
        BUILTIN_DB.put("12339", "国家安全机关受理举报电话号码");
        BUILTIN_DB.put("12340", "社情民意调查热线电话号码");
        BUILTIN_DB.put("12341", "各地的地市级政府和直辖市政府开展咨询类公益服务号码");
        BUILTIN_DB.put("12342", "各地的地市级政府和直辖市政府开展投诉类公益服务号码");
        BUILTIN_DB.put("12343", "各地的地市级政府和直辖市政府开展便民服务类公益服务号码");
        BUILTIN_DB.put("12345", "政务服务便民热线");
        BUILTIN_DB.put("12346", "各省（自治区）政府开展非紧急类公益服务号码");
        BUILTIN_DB.put("12348", "全国法律服务热线");
        BUILTIN_DB.put("12349", "全国统一民政服务电话号码");
        BUILTIN_DB.put("12350", "安全生产举报投诉电话号码");
        BUILTIN_DB.put("12351", "中华全国总工会");
        BUILTIN_DB.put("12354", "全国统一青联服务热线电话号码");
        BUILTIN_DB.put("12355", "全国青少年维权和心理咨询服务热线电话号码");
        BUILTIN_DB.put("12356", "人口和计划生育法律法规咨询及举报投诉服务专用电话号码");
        BUILTIN_DB.put("12358", "价格监督举报电话");
        BUILTIN_DB.put("12359", "全国文物违法举报电话号码");
        BUILTIN_DB.put("12360", "全国统一海关服务电话号码");
        BUILTIN_DB.put("12361", "学习强国");
        BUILTIN_DB.put("12363", "全国统一金融消费者投诉服务专用电话号码");
        BUILTIN_DB.put("12365", "质量监督电话");
        BUILTIN_DB.put("12366", "中国税务");
        BUILTIN_DB.put("123660", "中国税务");
        BUILTIN_DB.put("12368", "全国法院系统统一电话号码");
        BUILTIN_DB.put("12369", "环保局监督电话");
        BUILTIN_DB.put("12370", "全国公务员管理业务政策咨询及公务员招考等全国统一人事政务公开电话号码");
        BUILTIN_DB.put("12371", "全国统一党员咨询服务电话号码");
        BUILTIN_DB.put("12377", "全国统一互联网违法和不良信息举报电话号码");
        BUILTIN_DB.put("12378", "全国保险投诉维权热线电话专用号码");
        BUILTIN_DB.put("12379", "中国气象局职责范围内国家突发事件预警信息发布专用号码");
        BUILTIN_DB.put("12380", "全国组织系统统一专用举报电话号码");
        BUILTIN_DB.put("12381", "工业和信息化部");
        BUILTIN_DB.put("12385", "全国残疾人维权服务电话号码");
        BUILTIN_DB.put("12386", "全国统一投资者合法权益保护专用电话号码");
        BUILTIN_DB.put("12388", "全国统一纪检监察举报电话号码");
        BUILTIN_DB.put("12389", "全国公安机关和民警违法违纪举报热线专用号码");
        BUILTIN_DB.put("12390", "全国统一反盗版举报投诉专用电话号码");
        BUILTIN_DB.put("12391", "全国统一教育事业服务专用电话号码");
        BUILTIN_DB.put("12395", "水上遇险求救电话");
        BUILTIN_DB.put("12396", "全国统一科技服务电话号码");
        BUILTIN_DB.put("12398", "全国能源监管投诉举报电话号码");
        BUILTIN_DB.put("12530", "中国移动");
        BUILTIN_DB.put("1253011", "中国移动");
        BUILTIN_DB.put("12583110086", "中国移动");
        BUILTIN_DB.put("12583210086", "中国移动");
        BUILTIN_DB.put("12583310086", "中国移动");
        BUILTIN_DB.put("13068400177", "CUniq");
        BUILTIN_DB.put("14125676602", "多邻国");
        BUILTIN_DB.put("16503198930", "Cloudflare");
        BUILTIN_DB.put("17900", "中国电信IP电话卡");
        BUILTIN_DB.put("17911", "中国联通IP号码");
        BUILTIN_DB.put("17951", "中国移动IP号码");
        BUILTIN_DB.put("18006330738", "Oracle");
        BUILTIN_DB.put("18774484824", "GitHub");
        BUILTIN_DB.put("2128913888", "PayPal");
        BUILTIN_DB.put("2161791000", "Apple");
        BUILTIN_DB.put("2162980000", "大众点评");
        BUILTIN_DB.put("2983895117", "国家授时中心");
        BUILTIN_DB.put("4000000666", "滴滴出行");
        BUILTIN_DB.put("4000000999", "滴滴出行");
        BUILTIN_DB.put("4000036036", "极氪汽车");
        BUILTIN_DB.put("4000095030", "荣耀");
        BUILTIN_DB.put("4000100100", "瑞幸咖啡");
        BUILTIN_DB.put("4000188113", "UCloud");
        BUILTIN_DB.put("4000190808", "GoFun出行");
        BUILTIN_DB.put("4000199166", "苏宁易购");
        BUILTIN_DB.put("4000228636", "享库生活");
        BUILTIN_DB.put("4000295558", "中信银行");
        BUILTIN_DB.put("4000368163", "网易严选");
        BUILTIN_DB.put("4000395558", "中信银行");
        BUILTIN_DB.put("4000495558", "中信银行");
        BUILTIN_DB.put("4000595558", "中信银行");
        BUILTIN_DB.put("4000605576", "Teambition");
        BUILTIN_DB.put("4000610999", "小桔充电");
        BUILTIN_DB.put("4000633333", "丰巢");
        BUILTIN_DB.put("4000682666", "飞书");
        BUILTIN_DB.put("4000795558", "中信银行");
        BUILTIN_DB.put("4000888816", "京东支付");
        BUILTIN_DB.put("4000895558", "中信银行");
        BUILTIN_DB.put("4000960960", "微博");
        BUILTIN_DB.put("4000988505", "京东云");
        BUILTIN_DB.put("4000995558", "中信银行");
        BUILTIN_DB.put("4001001111", "自如");
        BUILTIN_DB.put("4001005678", "小米");
        BUILTIN_DB.put("4001012895", "航旅纵横");
        BUILTIN_DB.put("4001066666", "当当网");
        BUILTIN_DB.put("4001083999", "UPS");
        BUILTIN_DB.put("4001095018", "OPPO");
        BUILTIN_DB.put("4001095528", "浦发银行");
        BUILTIN_DB.put("4001116555", "钉钉");
        BUILTIN_DB.put("4001188886", "雪盈证券");
        BUILTIN_DB.put("4001195508", "广发银行");
        BUILTIN_DB.put("4001195558", "中信银行");
        BUILTIN_DB.put("4001196811", "乐山市商业银行");
        BUILTIN_DB.put("4001202364", "达美航空");
        BUILTIN_DB.put("4001395558", "中信银行");
        BUILTIN_DB.put("4001402086", "学浪");
        BUILTIN_DB.put("4001595558", "中信银行");
        BUILTIN_DB.put("4001630886", "嘀嗒出行");
        BUILTIN_DB.put("4001666016", "比亚迪");
        BUILTIN_DB.put("4001782233", "哔哩哔哩");
        BUILTIN_DB.put("4001787878", "菜鸟");
        BUILTIN_DB.put("4001811212", "百果园");
        BUILTIN_DB.put("4001826888", "小米汽车");
        BUILTIN_DB.put("4001851688", "一号会员店");
        BUILTIN_DB.put("4006006655", "华硕");
        BUILTIN_DB.put("4006081111", "曹操出行");
        BUILTIN_DB.put("4006095537", "哈尔滨银行");
        BUILTIN_DB.put("4006095558", "中信银行");
        BUILTIN_DB.put("4006096558", "汉口银行");
        BUILTIN_DB.put("4006096777", "山航");
        BUILTIN_DB.put("4006126688", "闪送");
        BUILTIN_DB.put("4006166666", "神州租车");
        BUILTIN_DB.put("4006181518", "字节跳动");
        BUILTIN_DB.put("4006195599", "中国农业银行");
        BUILTIN_DB.put("4006198141", "Apple");
        BUILTIN_DB.put("4006272273", "Apple");
        BUILTIN_DB.put("4006280066", "realme");
        BUILTIN_DB.put("4006295508", "广发银行");
        BUILTIN_DB.put("4006299688", "vivo");
        BUILTIN_DB.put("4006306430", "深信服");
        BUILTIN_DB.put("4006336868", "山姆会员商店");
        BUILTIN_DB.put("4006350787", "飞常准");
        BUILTIN_DB.put("4006388666", "小牛电动");
        BUILTIN_DB.put("4006395558", "中信银行");
        BUILTIN_DB.put("4006495558", "中信银行");
        BUILTIN_DB.put("4006500309", "万事达");
        BUILTIN_DB.put("4006501656", "中金公司");
        BUILTIN_DB.put("4006562211", "京东");
        BUILTIN_DB.put("4006588555", "LOUIS VUITTON");
        BUILTIN_DB.put("4006601169", "北京银行");
        BUILTIN_DB.put("4006665608", "萝卜快跑");
        BUILTIN_DB.put("4006666312", "米哈游");
        BUILTIN_DB.put("4006668800", "Apple");
        BUILTIN_DB.put("4006695539", "南航");
        BUILTIN_DB.put("4006695558", "中信银行");
        BUILTIN_DB.put("4006695566", "中国银行");
        BUILTIN_DB.put("4006695568", "中国民生银行");
        BUILTIN_DB.put("4006695569", "中国银行");
        BUILTIN_DB.put("4006695577", "华夏银行");
        BUILTIN_DB.put("4006695599", "中国农业银行");
        BUILTIN_DB.put("4006696588", "青岛银行");
        BUILTIN_DB.put("4006700778", "问卷网");
        BUILTIN_DB.put("4006775005", "WPS");
        BUILTIN_DB.put("4006789000", "宅急送");
        BUILTIN_DB.put("4006796511", "长沙银行");
        BUILTIN_DB.put("4006799688", "vivo");
        BUILTIN_DB.put("4006800166", "拓竹");
        BUILTIN_DB.put("4006860900", "理想汽车");
        BUILTIN_DB.put("4006927927", "真功夫");
        BUILTIN_DB.put("4006962999", "上海农村商业银行");
        BUILTIN_DB.put("4006998888", "Oracle");
        BUILTIN_DB.put("4006999999", "海尔");
        BUILTIN_DB.put("4007000303", "DJI");
        BUILTIN_DB.put("4007006000", "吉祥航空");
        BUILTIN_DB.put("4007666998", "滴滴出行");
        BUILTIN_DB.put("4007995558", "中信银行");
        BUILTIN_DB.put("4008000222", "新邦物流");
        BUILTIN_DB.put("4008001688", "阿里巴巴");
        BUILTIN_DB.put("4008002345", "宜家家居");
        BUILTIN_DB.put("4008004818", "汇丰银行（中国）");
        BUILTIN_DB.put("4008006666", "BMW");
        BUILTIN_DB.put("4008008888", "百度");
        BUILTIN_DB.put("4008009888", "交通银行");
        BUILTIN_DB.put("4008061257", "云喇叭");
        BUILTIN_DB.put("4008066868", "深信服");
        BUILTIN_DB.put("4008100000", "索爱");
        BUILTIN_DB.put("4008100080", "高德地图");
        BUILTIN_DB.put("4008100580", "优酷");
        BUILTIN_DB.put("4008105666", "亚马逊");
        BUILTIN_DB.put("4008105858", "三星");
        BUILTIN_DB.put("4008108000", "DHL");
        BUILTIN_DB.put("4008109000", "Sony");
        BUILTIN_DB.put("4008109889", "天翼云");
        BUILTIN_DB.put("4008111111", "顺丰速运");
        BUILTIN_DB.put("4008111666", "长虹");
        BUILTIN_DB.put("4008123123", "必胜客");
        BUILTIN_DB.put("4008123456", "TCL");
        BUILTIN_DB.put("4008129999", "BMW");
        BUILTIN_DB.put("4008171888", "一汽大众");
        BUILTIN_DB.put("4008174008", "民航快递");
        BUILTIN_DB.put("4008181188", "奔驰汽车");
        BUILTIN_DB.put("4008187333", "美国航空");
        BUILTIN_DB.put("4008193388", "小鹏汽车");
        BUILTIN_DB.put("4008195558", "中信银行");
        BUILTIN_DB.put("4008196568", "湖北省农村信用社(农商银行)");
        BUILTIN_DB.put("4008199199", "北汽福田");
        BUILTIN_DB.put("4008199999", "LG");
        BUILTIN_DB.put("4008200588", "中国建设银行");
        BUILTIN_DB.put("4008201188", "安吉星");
        BUILTIN_DB.put("4008201668", "多普达");
        BUILTIN_DB.put("4008203737", "美国运通");
        BUILTIN_DB.put("4008203800", "微软");
        BUILTIN_DB.put("4008205555", "招商银行");
        BUILTIN_DB.put("4008206998", "星巴克");
        BUILTIN_DB.put("4008208388", "UPS");
        BUILTIN_DB.put("4008209868", "TNT");
        BUILTIN_DB.put("4008209999", "锦江酒店");
        BUILTIN_DB.put("4008211880", "花旗银行");
        BUILTIN_DB.put("4008229999", "华为");
        BUILTIN_DB.put("4008300999", "川航");
        BUILTIN_DB.put("4008303666", "比亚迪汽车");
        BUILTIN_DB.put("4008305555", "德邦物流");
        BUILTIN_DB.put("4008308003", "广发银行");
        BUILTIN_DB.put("4008308300", "华为");
        BUILTIN_DB.put("4008357300", "酷家乐");
        BUILTIN_DB.put("4008385616", "喜马拉雅");
        BUILTIN_DB.put("4008396699", "广州银行");
        BUILTIN_DB.put("4008507777", "美团外卖");
        BUILTIN_DB.put("4008517517", "麦当劳");
        BUILTIN_DB.put("4008595558", "中信银行");
        BUILTIN_DB.put("4008608608", "淘宝");
        BUILTIN_DB.put("4008701818", "富途证券");
        BUILTIN_DB.put("4008800123", "诺基亚");
        BUILTIN_DB.put("4008800400", "丽华快餐");
        BUILTIN_DB.put("4008802252", "高德地图");
        BUILTIN_DB.put("4008806453", "Nike");
        BUILTIN_DB.put("4008818088", "易方达");
        BUILTIN_DB.put("4008821111", "龙腾出行");
        BUILTIN_DB.put("4008822168", "速尔快递");
        BUILTIN_DB.put("4008822528", "拼多多");
        BUILTIN_DB.put("4008823823", "肯德基");
        BUILTIN_DB.put("4008838888", "奇瑞汽车");
        BUILTIN_DB.put("4008861888", "联邦快递");
        BUILTIN_DB.put("4008866023", "闪铸");
        BUILTIN_DB.put("4008866140", "Xbox");
        BUILTIN_DB.put("4008866688", "东风雪铁龙");
        BUILTIN_DB.put("4008869888", "吉利汽车");
        BUILTIN_DB.put("4008871133", "美宜佳");
        BUILTIN_DB.put("4008879988", "昌河汽车");
        BUILTIN_DB.put("4008880296", "优衣库");
        BUILTIN_DB.put("4008885555", "招商银行");
        BUILTIN_DB.put("4008888508", "杭州银行");
        BUILTIN_DB.put("4008888811", "渤海银行");
        BUILTIN_DB.put("4008888831", "中信银行");
        BUILTIN_DB.put("4008888835", "中信银行");
        BUILTIN_DB.put("4008888843", "中信银行");
        BUILTIN_DB.put("4008888853", "中信银行");
        BUILTIN_DB.put("4008888857", "中信银行");
        BUILTIN_DB.put("4008888871", "中信银行");
        BUILTIN_DB.put("4008888888", "银河证券");
        BUILTIN_DB.put("4008888891", "中信银行");
        BUILTIN_DB.put("4008888892", "中信银行");
        BUILTIN_DB.put("4008889883", "浦发银行");
        BUILTIN_DB.put("4008895518", "中国人保");
        BUILTIN_DB.put("4008895543", "申通快递");
        BUILTIN_DB.put("4008895555", "招商银行");
        BUILTIN_DB.put("4008895558", "中信银行");
        BUILTIN_DB.put("4008895561", "兴业银行");
        BUILTIN_DB.put("4008895580", "中国邮政储蓄银行");
        BUILTIN_DB.put("4008895599", "中国农业银行");
        BUILTIN_DB.put("4008896533", "富滇银行");
        BUILTIN_DB.put("4008896588", "徽商银行");
        BUILTIN_DB.put("4008903588", "万豪旅享家");
        BUILTIN_DB.put("4008916916", "苏宁易购");
        BUILTIN_DB.put("4008989158", "转转");
        BUILTIN_DB.put("4009004587", "摩点");
        BUILTIN_DB.put("4009100707", "特斯拉");
        BUILTIN_DB.put("4009107107", "海底捞");
        BUILTIN_DB.put("4009190707", "特斯拉");
        BUILTIN_DB.put("4009211000", "PayPal");
        BUILTIN_DB.put("4009237171", "爱奇艺");
        BUILTIN_DB.put("4009565656", "百世汇通");
        BUILTIN_DB.put("4009766666", "万里汇");
        BUILTIN_DB.put("4009770707", "芒果TV");
        BUILTIN_DB.put("4009907930", "Aqara");
        BUILTIN_DB.put("4009919512", "达达快送");
        BUILTIN_DB.put("4009995999", "Adidas");
        BUILTIN_DB.put("4009996699", "蔚来");
        BUILTIN_DB.put("4009998888", "联想");
        BUILTIN_DB.put("61132221", "CommBank");
        BUILTIN_DB.put("611800241600", "Telstra");
        BUILTIN_DB.put("61439125109", "Telstra");
        BUILTIN_DB.put("6502530000", "Google");
        BUILTIN_DB.put("6700", "UltraMobile");
        BUILTIN_DB.put("8008100285", "长城");
        BUILTIN_DB.put("8008100781", "松下");
        BUILTIN_DB.put("8008101100", "北京现代");
        BUILTIN_DB.put("8008101210", "一汽丰田");
        BUILTIN_DB.put("8008101992", "方正");
        BUILTIN_DB.put("8008105050", "摩托罗拉");
        BUILTIN_DB.put("8008108168", "Ford福特");
        BUILTIN_DB.put("8008108208", "东芝");
        BUILTIN_DB.put("8008108880", "北京吉普");
        BUILTIN_DB.put("8008108888", "联想");
        BUILTIN_DB.put("8008109110", "大中电器");
        BUILTIN_DB.put("8008109977", "爱普生");
        BUILTIN_DB.put("8008201111", "上海大众咨询");
        BUILTIN_DB.put("8008201201", "飞利浦");
        BUILTIN_DB.put("8008201902", "凯迪拉克咨询");
        BUILTIN_DB.put("8008201912", "雪佛兰");
        BUILTIN_DB.put("8008202020", "BUICK别克");
        BUILTIN_DB.put("8008202255", "惠普");
        BUILTIN_DB.put("8008205527", "大众点评");
        BUILTIN_DB.put("8008205598", "上海华普汽车");
        BUILTIN_DB.put("8008207007", "NEC");
        BUILTIN_DB.put("8008208388", "UPS");
        BUILTIN_DB.put("8008208865", "Nike");
        BUILTIN_DB.put("8008281892", "南京依维柯");
        BUILTIN_DB.put("8008301880", "花旗银行");
        BUILTIN_DB.put("8008302880", "汇丰银行");
        BUILTIN_DB.put("8008303202", "UPS");
        BUILTIN_DB.put("8008303811", "东亚银行");
        BUILTIN_DB.put("8008308300", "华为");
        BUILTIN_DB.put("8008308888", "广汽丰田");
        BUILTIN_DB.put("8008308899", "东风日产");
        BUILTIN_DB.put("8008308999", "广州本田");
        BUILTIN_DB.put("8008468680", "长春一汽");
        BUILTIN_DB.put("8008571616", "老板");
        BUILTIN_DB.put("8008576717", "苏泊尔");
        BUILTIN_DB.put("8008580888", "戴尔");
        BUILTIN_DB.put("8008581666", "东南汽车");
        BUILTIN_DB.put("8008691099", "江铃汽车");
        BUILTIN_DB.put("8008809899", "东风本田");
        BUILTIN_DB.put("8009881200", "沃尔沃轿车");
        BUILTIN_DB.put("8009906600", "国美电器");
        BUILTIN_DB.put("81362317628", "任你购");
        BUILTIN_DB.put("85221221188", "CUniq");
        BUILTIN_DB.put("85222333000", "汇丰银行");
        BUILTIN_DB.put("85222333033", "汇丰银行");
        BUILTIN_DB.put("85222333322", "汇丰银行");
        BUILTIN_DB.put("85231078333", "盈透证券");
        BUILTIN_DB.put("85236653665", "众安银行");
        BUILTIN_DB.put("85239882388", "中国银行（香港）");
        BUILTIN_DB.put("8531888", "中国电信");
        BUILTIN_DB.put("95000", "北京鸿联九五信息产业有限公司");
        BUILTIN_DB.put("95001", "北京鸿联九五信息产业有限公司");
        BUILTIN_DB.put("95002", "上海海阳保安服务股份有限公司");
        BUILTIN_DB.put("95003", "北京云瑞融通科技有限公司");
        BUILTIN_DB.put("95004", "上海月呈信息技术有限公司");
        BUILTIN_DB.put("95005", "中鑫之宝汽车服务有限公司");
        BUILTIN_DB.put("95006", "贵阳山恩科技有限公司");
        BUILTIN_DB.put("95007", "深圳市中燃电商服务有限公司");
        BUILTIN_DB.put("95008", "福建羽琦电子商务有限公司");
        BUILTIN_DB.put("95009", "山西中质信联信息科技有限公司");
        BUILTIN_DB.put("95010", "携程");
        BUILTIN_DB.put("95011", "山东高速信联支付有限公司");
        BUILTIN_DB.put("95012", "浙江三替家政连锁有限公司");
        BUILTIN_DB.put("95013", "北京天舟通信有限公司");
        BUILTIN_DB.put("950139", "京东物流");
        BUILTIN_DB.put("95014", "北京亚泰亨通信息技术有限公司");
        BUILTIN_DB.put("95015", "杭州远传新业科技有限公司");
        BUILTIN_DB.put("95016", "广州市拉卡拉互联网金融信息服务有限公司");
        BUILTIN_DB.put("95017", "微信支付");
        BUILTIN_DB.put("95018", "OPPO");
        BUILTIN_DB.put("95019", "江苏洋河酒厂股份有限公司");
        BUILTIN_DB.put("95020", "邦尼集团有限公司");
        BUILTIN_DB.put("95021", "东方财富信息股份有限公司");
        BUILTIN_DB.put("95022", "河南省视博电子股份有限公司");
        BUILTIN_DB.put("95024", "合肥旗御信息科技有限公司");
        BUILTIN_DB.put("95025", "东方钢铁电子商务有限公司");
        BUILTIN_DB.put("95026", "上海锦祺电信科技有限公司");
        BUILTIN_DB.put("95027", "广州风神资讯有限公司");
        BUILTIN_DB.put("95028", "叮当快药科技集团有限公司");
        BUILTIN_DB.put("95029", "河南虹天信息技术有限公司");
        BUILTIN_DB.put("95030", "荣耀");
        BUILTIN_DB.put("95031", "普天通信有限责任公司");
        BUILTIN_DB.put("95032", "北京创圆同一网络科技有限公司");
        BUILTIN_DB.put("95033", "vivo");
        BUILTIN_DB.put("95034", "北京中小在线信息服务有限公司");
        BUILTIN_DB.put("95035", "北京智云立方网络科技有限公司");
        BUILTIN_DB.put("95036", "东莞市帕马智能停车服务有限公司");
        BUILTIN_DB.put("95037", "中融国际信托有限公司");
        BUILTIN_DB.put("95038", "上海精语电子商务有限公司");
        BUILTIN_DB.put("95039", "广东网金控股股份有限公司");
        BUILTIN_DB.put("95040", "二六三网络通信股份有限公司");
        BUILTIN_DB.put("95041", "北京二六三企业通信有限公司");
        BUILTIN_DB.put("95042", "深圳位置网科技有限公司");
        BUILTIN_DB.put("95043", "北京锐意恒达科技股份有限公司");
        BUILTIN_DB.put("95044", "汇通宝支付有限责任公司");
        BUILTIN_DB.put("95045", "北京中天嘉华信息技术有限公司");
        BUILTIN_DB.put("95046", "厦门集微科技有限公司");
        BUILTIN_DB.put("95047", "用友移动通信技术服务有限公司");
        BUILTIN_DB.put("95048", "陕西天吉能源科技有限责任公司");
        BUILTIN_DB.put("95049", "天津融金汇银贵金属经营有限公司");
        BUILTIN_DB.put("95050", "二六三网络通信股份有限公司");
        BUILTIN_DB.put("95053", "深圳市梦网科技发展有限公司");
        BUILTIN_DB.put("95054", "北京堆砌科技有限公司");
        BUILTIN_DB.put("95055", "北京百付宝科技有限公司");
        BUILTIN_DB.put("95056", "江苏壹鼎数据网络科技有限公司");
        BUILTIN_DB.put("95057", "全时云商务服务股份有限公司");
        BUILTIN_DB.put("95058", "中移融创网络科技（北京）有限公司");
        BUILTIN_DB.put("95059", "杭州绿城信息技术有限公司");
        BUILTIN_DB.put("95060", "福建省海都公众服务股份有限公司");
        BUILTIN_DB.put("95061", "江苏圆周电子商务有限公司");
        BUILTIN_DB.put("950616", "京东物流");
        BUILTIN_DB.put("950618", "京东");
        BUILTIN_DB.put("95062", "北京中钢网信息股份有限公司");
        BUILTIN_DB.put("95063", "福建瓦特网络科技有限公司");
        BUILTIN_DB.put("95064", "北京容联易通信息技术有限公司");
        BUILTIN_DB.put("95065", "北京艾为飞鸿科技有限公司");
        BUILTIN_DB.put("95066", "北京小桔科技有限公司");
        BUILTIN_DB.put("95067", "甘肃赛亚汽车销售服务有限公司");
        BUILTIN_DB.put("95068", "大运盈通（北京）数码科技有限公司");
        BUILTIN_DB.put("95069", "快钱支付清算信息有限公司");
        BUILTIN_DB.put("95070", "易宝支付有限公司");
        BUILTIN_DB.put("95071", "海南海航航空信息系统有限公司");
        BUILTIN_DB.put("95071666", "福州航空");
        BUILTIN_DB.put("950718", "海航");
        BUILTIN_DB.put("95072", "广州唯品会信息科技有限公司");
        BUILTIN_DB.put("95073", "北京艾为飞鸿科技有限公司");
        BUILTIN_DB.put("95074", "北京今然科技有限公司");
        BUILTIN_DB.put("95075", "上海众徇电子商务有限公司");
        BUILTIN_DB.put("95076", "成都三泰控股集团股份有限公司");
        BUILTIN_DB.put("95077", "浙江彩虹鱼科技有限公司");
        BUILTIN_DB.put("95078", "北京承启通科技有限公司");
        BUILTIN_DB.put("95079", "长城宽带网络服务有限公司");
        BUILTIN_DB.put("95080", "深圳航空");
        BUILTIN_DB.put("950800", "华为软件技术有限公司");
        BUILTIN_DB.put("950808", "华为云");
        BUILTIN_DB.put("95081", "北京易盟天地信息技术股份有限公司");
        BUILTIN_DB.put("950816", "小米");
        BUILTIN_DB.put("95082", "中经汇通电子商务有限公司");
        BUILTIN_DB.put("95083", "中厚通信科技（上海）有限公司");
        BUILTIN_DB.put("95084", "深圳市轻码云科技有限公司");
        BUILTIN_DB.put("95085", "江苏先锋信息科技有限公司");
        BUILTIN_DB.put("950858", "比亚迪");
        BUILTIN_DB.put("95086", "中体彩彩票运营管理有限公司");
        BUILTIN_DB.put("95087", "成都金慧融智数据服务有限公司");
        BUILTIN_DB.put("95088", "西藏海涵实业有限公司");
        BUILTIN_DB.put("95089", "申通快递");
        BUILTIN_DB.put("95090", "北京移数通电讯有限公司");
        BUILTIN_DB.put("95092", "北京天润融通科技股份有限公司");
        BUILTIN_DB.put("95093", "上海赢信科技发展有限公司");
        BUILTIN_DB.put("95094", "深圳市云屋科技有限公司");
        BUILTIN_DB.put("95095", "北京鸿联九五信息产业有限公司");
        BUILTIN_DB.put("95096", "歌林浩海科技（北京）有限公司");
        BUILTIN_DB.put("95097", "郑州银行");
        BUILTIN_DB.put("95098", "广西玉柴机器集团有限公司");
        BUILTIN_DB.put("95099", "北京百度网讯科技有限公司");
        BUILTIN_DB.put("95100", "深圳市合众金鑫科技有限公司");
        BUILTIN_DB.put("95102", "浙江淘宝网络有限公司");
        BUILTIN_DB.put("9510208", "飞猪");
        BUILTIN_DB.put("9510211", "淘宝");
        BUILTIN_DB.put("9510217", "盒马鲜生");
        BUILTIN_DB.put("9510222", "闲鱼");
        BUILTIN_DB.put("9510286", "考拉海购");
        BUILTIN_DB.put("9510288", "淘宝");
        BUILTIN_DB.put("95103", "阿里巴巴云计算（北京）有限公司");
        BUILTIN_DB.put("95104", "广州冰瑞信息科技有限公司");
        BUILTIN_DB.put("95105", "中国移动通信集团公司");
        BUILTIN_DB.put("95106", "中国电信集团公司");
        BUILTIN_DB.put("95107", "北京优音通信有限公司");
        BUILTIN_DB.put("95108", "北京奇虎科技有限公司");
        BUILTIN_DB.put("95109", "中国移动通信集团公司");
        BUILTIN_DB.put("95111", "华网通信有限公司");
        BUILTIN_DB.put("95112", "北京融信智联网络科技有限公司");
        BUILTIN_DB.put("95113", "航天信息股份有限公司");
        BUILTIN_DB.put("95114", "北京微呼科技有限公司");
        BUILTIN_DB.put("95115", "中国联合网络通信集团有限公司");
        BUILTIN_DB.put("95116", "广东金点原子安防科技股份有限公司");
        BUILTIN_DB.put("95117", "去哪儿旅行");
        BUILTIN_DB.put("95118", "京东");
        BUILTIN_DB.put("95119", "森林火警电话");
        BUILTIN_DB.put("95121", "韵达速递");
        BUILTIN_DB.put("95123", "北京好助手网络科技有限公司");
        BUILTIN_DB.put("95124", "广州市正君信息科技有限公司");
        BUILTIN_DB.put("95125", "北京维那多信息技术有限公司");
        BUILTIN_DB.put("95126", "韵达速递");
        BUILTIN_DB.put("95127", "广东绿瘦健康信息咨询有限公司");
        BUILTIN_DB.put("95128", "北京国交信通科技发展有限公司");
        BUILTIN_DB.put("95129", "北京佳佳广运科技发展有限公司");
        BUILTIN_DB.put("95130", "神州通信集团有限公司");
        BUILTIN_DB.put("95131", "磊强通信有限公司");
        BUILTIN_DB.put("95133", "杭州舒讯信息技术有限公司");
        BUILTIN_DB.put("95135", "北京招金颐和文化投资有限公司");
        BUILTIN_DB.put("95136", "北京讯众通信技术股份有限公司");
        BUILTIN_DB.put("95137", "上海长信科技股份有限公司");
        BUILTIN_DB.put("95138", "上海富友金融网络技术有限公司");
        BUILTIN_DB.put("95139", "上海壹加陆信息技术有限公司");
        BUILTIN_DB.put("95140", "万达信息股份有限公司");
        BUILTIN_DB.put("95141", "上海航动科技有限公司");
        BUILTIN_DB.put("95142", "广州台盈信息科技有限公司");
        BUILTIN_DB.put("95143", "广东炬华信息技术有限公司");
        BUILTIN_DB.put("95144", "上海台英信息科技有限公司");
        BUILTIN_DB.put("95145", "北京证大向上金融信息服务有限公司");
        BUILTIN_DB.put("95146", "河南卧虎藏龙电子科技有限公司");
        BUILTIN_DB.put("95147", "北京羿世纪文化传媒有限公司");
        BUILTIN_DB.put("95148", "国智恒北斗科技集团股份有限公司");
        BUILTIN_DB.put("95149", "深圳市智语科技有限公司");
        BUILTIN_DB.put("95150", "北京兆维电子（集团）有限责任公司");
        BUILTIN_DB.put("95151", "北京二六三企业通信有限公司");
        BUILTIN_DB.put("95152", "抖音");
        BUILTIN_DB.put("95153", "杭州律动信息技术有限公司");
        BUILTIN_DB.put("95154", "内蒙古大宗畜产品交易所有限公司");
        BUILTIN_DB.put("95155", "北京千方科技股份有限公司");
        BUILTIN_DB.put("95156", "北京网阔科技有限公司");
        BUILTIN_DB.put("95158", "必拓电子商务有限公司");
        BUILTIN_DB.put("95159", "资和信电子支付有限公司");
        BUILTIN_DB.put("95160", "北京嘉达高科投资有限公司");
        BUILTIN_DB.put("95161", "圆通速递");
        BUILTIN_DB.put("95162", "北京中期移动传媒有限公司");
        BUILTIN_DB.put("95163", "网易宝有限公司");
        BUILTIN_DB.put("95163000", "网易");
        BUILTIN_DB.put("95163234", "网易");
        BUILTIN_DB.put("95163555", "网易");
        BUILTIN_DB.put("95163777", "网易");
        BUILTIN_DB.put("95163999", "网易");
        BUILTIN_DB.put("95164", "北京赢和博雅文化发展有限公司");
        BUILTIN_DB.put("95165", "浙江力天世纪通信有限公司");
        BUILTIN_DB.put("95166", "亿阳信通股份有限公司");
        BUILTIN_DB.put("95167", "广州韵声信息咨询服务有限公司");
        BUILTIN_DB.put("95168", "红杉树视讯（北京）信息技术有限公司");
        BUILTIN_DB.put("95169", "红杉树视讯（北京）信息技术有限公司");
        BUILTIN_DB.put("95170", "蓝海（福建）信息科技有限公司");
        BUILTIN_DB.put("95171", "分享通信集团有限公司");
        BUILTIN_DB.put("95172", "北京钱袋宝支付技术有限公司");
        BUILTIN_DB.put("95173", "上海华瑞金融科技有限公司");
        BUILTIN_DB.put("95174", "深圳市闪遇科技有限公司");
        BUILTIN_DB.put("95175", "中通天鸿（北京）通信科技股份有限公司");
        BUILTIN_DB.put("95175177", "哈啰出行");
        BUILTIN_DB.put("95176", "上海帜讯信息技术股份有限公司");
        BUILTIN_DB.put("95177", "苏宁易购");
        BUILTIN_DB.put("95178", "深圳市银波通信有限公司");
        BUILTIN_DB.put("95179", "吉林省文化产业投资控股（集团）有限公司");
        BUILTIN_DB.put("95180", "浙江天德网络通信有限公司");
        BUILTIN_DB.put("95181", "环球航通信息服务有限公司");
        BUILTIN_DB.put("95182", "深圳市安吉尔信息技术有限公司");
        BUILTIN_DB.put("95183", "宜信惠民投资管理（北京）有限公司");
        BUILTIN_DB.put("95184", "北京华云天下科技有限公司");
        BUILTIN_DB.put("95185", "天津环融集团有限公司");
        BUILTIN_DB.put("95186", "中原银行");
        BUILTIN_DB.put("95187", "阿里云");
        BUILTIN_DB.put("95188", "支付宝");
        BUILTIN_DB.put("95189", "英泰伟业信息技术股份有限公司");
        BUILTIN_DB.put("95190", "北京九五智驾信息技术股份有限公司");
        BUILTIN_DB.put("95191", "西安楼市通网络科技股份有限公司");
        BUILTIN_DB.put("95192", "易联支付有限公司");
        BUILTIN_DB.put("95193", "通联支付网络服务股份有限公司");
        BUILTIN_DB.put("95194", "上海郝泰信息科技有限公司");
        BUILTIN_DB.put("95195", "北京恩源科技有限公司");
        BUILTIN_DB.put("951966", "菜鸟");
        BUILTIN_DB.put("9519668", "菜鸟");
        BUILTIN_DB.put("95197", "河南宅乐送电子商务有限公司");
        BUILTIN_DB.put("95198", "东方口岸科技有限公司");
        BUILTIN_DB.put("95199", "中国长安汽车集团股份有限公司");
        BUILTIN_DB.put("952000", "天津嘉视通科技有限公司");
        BUILTIN_DB.put("952001", "天津恒荣世达科技有限公司");
        BUILTIN_DB.put("952002", "亿联无线（武汉）信息技术有限公司");
        BUILTIN_DB.put("952003", "厦门知信信息科技有限公司");
        BUILTIN_DB.put("952004", "北京秒信科技有限公司");
        BUILTIN_DB.put("952005", "嘉联支付有限公司");
        BUILTIN_DB.put("952006", "东云技术有限公司");
        BUILTIN_DB.put("952007", "山东青鸟软通信息技术股份有限公司");
        BUILTIN_DB.put("952008", "京移互通网络科技（北京）有限公司");
        BUILTIN_DB.put("952009", "北京艳阳高照科技有限公司");
        BUILTIN_DB.put("952010", "厦门引翼网络科技有限公司");
        BUILTIN_DB.put("952011", "河北鑫蓬嘉创信息技术有限公司");
        BUILTIN_DB.put("952012", "辽宁蓝卡医疗投资管理有限公司");
        BUILTIN_DB.put("952013", "北京龙吟创展信息技术有限公司");
        BUILTIN_DB.put("952014", "广东叮当投资有限公司");
        BUILTIN_DB.put("952015", "西安数之元网络科技有限公司");
        BUILTIN_DB.put("952016", "福建智宇通讯科技有限公司");
        BUILTIN_DB.put("952017", "安徽黄埔网络科技集团股份有限公司");
        BUILTIN_DB.put("952018", "上臻科技有限公司");
        BUILTIN_DB.put("952019", "福建新达共创信息科技有限公司");
        BUILTIN_DB.put("952020", "北京青峰无限信息科技有限公司");
        BUILTIN_DB.put("952021", "广州市讯和电信有限公司");
        BUILTIN_DB.put("952022", "北京闪讯联众科技有限公司");
        BUILTIN_DB.put("952023", "天下畅通（北京）科技有限公司");
        BUILTIN_DB.put("952024", "南京小苗信息科技有限公司");
        BUILTIN_DB.put("952025", "天津永明利意科技有限公司");
        BUILTIN_DB.put("952026", "莱州买啦网络技术有限公司");
        BUILTIN_DB.put("952027", "上海焱燕网络科技有限公司");
        BUILTIN_DB.put("952028", "深圳市呼吧通信有限公司");
        BUILTIN_DB.put("952029", "厦门易合通通讯科技有限公司");
        BUILTIN_DB.put("952030", "福建亿博通讯科技有限公司");
        BUILTIN_DB.put("952031", "苏州绿派生物科技有限公司");
        BUILTIN_DB.put("952032", "七九通信（江苏）有限公司");
        BUILTIN_DB.put("952033", "福建秋夕信步信息科技有限公司");
        BUILTIN_DB.put("952034", "深圳市金码云科技有限公司");
        BUILTIN_DB.put("952035", "厦门长翰通信科技有限公司");
        BUILTIN_DB.put("952036", "北京中迈和信科技有限公司");
        BUILTIN_DB.put("952037", "江苏新纬度科技有限公司");
        BUILTIN_DB.put("952038", "四川中屹互联信息技术有限公司");
        BUILTIN_DB.put("952039", "福建博士通信息有限责任公司");
        BUILTIN_DB.put("952040", "广东乐米科技有限公司");
        BUILTIN_DB.put("952041", "福建宇硕软件科技有限公司");
        BUILTIN_DB.put("952042", "福建拓羽信息科技有限公司");
        BUILTIN_DB.put("952043", "深圳市逸讯通科技有限公司");
        BUILTIN_DB.put("952044", "广东通牌教育装备发展有限公司");
        BUILTIN_DB.put("952045", "福州达达平台信息科技有限公司");
        BUILTIN_DB.put("952046", "山东创海信息科技有限公司");
        BUILTIN_DB.put("952047", "福建华住网络科技有限公司");
        BUILTIN_DB.put("952048", "福州美美易团云信息技术有限公司");
        BUILTIN_DB.put("952049", "福建弘汇通网络科技有限公司");
        BUILTIN_DB.put("952050", "永保保险代理有限公司");
        BUILTIN_DB.put("952051", "福建锐智通讯科技有限公司");
        BUILTIN_DB.put("952052", "福建欧信电子商务有限公司");
        BUILTIN_DB.put("952053", "福建音彤信息科技有限公司");
        BUILTIN_DB.put("952055", "北京中企天下科技有限公司");
        BUILTIN_DB.put("952056", "北京博泰祥瑞科技有限公司");
        BUILTIN_DB.put("952057", "河南普威通讯科技有限公司");
        BUILTIN_DB.put("952058", "福建古米通信科技有限公司");
        BUILTIN_DB.put("952059", "北京畅卓科技有限公司");
        BUILTIN_DB.put("952060", "厦门易法通法务信息管理股份有限公司");
        BUILTIN_DB.put("952061", "乐寿（北京）投资控股有限公司");
        BUILTIN_DB.put("952062", "北京云盛互动科技有限公司");
        BUILTIN_DB.put("952063", "福建凯盛汇网络科技有限公司");
        BUILTIN_DB.put("952064", "福州摩雷拜登信息科技有限公司");
        BUILTIN_DB.put("952065", "全天候保险代理（北京）有限公司");
        BUILTIN_DB.put("952066", "河南招源科技有限公司");
        BUILTIN_DB.put("952067", "山东东益通通信技术有限公司");
        BUILTIN_DB.put("952068", "福建弘泰祥通信技术有限公司");
        BUILTIN_DB.put("952069", "东莞市诚誉商务信息咨询有限公司");
        BUILTIN_DB.put("952070", "厦门随心淘电子商务有限公司");
        BUILTIN_DB.put("952071", "福建烟雨通信科技有限公司");
        BUILTIN_DB.put("952072", "上海垚麦信息科技有限公司");
        BUILTIN_DB.put("952073", "福建盈汇通网络科技有限公司");
        BUILTIN_DB.put("952074", "福建阿曼达软件科技有限公司");
        BUILTIN_DB.put("952075", "福建联信互联网络信息服务有限公司");
        BUILTIN_DB.put("952076", "洛阳江洋网络科技有限公司");
        BUILTIN_DB.put("952077", "北京智博联信科技有限公司");
        BUILTIN_DB.put("952078", "福建木棉花语文化传媒有限公司");
        BUILTIN_DB.put("952079", "福建祝华软件科技有限公司");
        BUILTIN_DB.put("952080", "福建佰利达通信科技有限公司");
        BUILTIN_DB.put("952081", "北京千丁互联科技有限公司");
        BUILTIN_DB.put("952082", "厦门互联星空网络科技有限公司");
        BUILTIN_DB.put("952083", "郑州希尔信息技术有限公司");
        BUILTIN_DB.put("952084", "福建旭日东升信息科技有限公司");
        BUILTIN_DB.put("952085", "福建互脉信息科技有限公司");
        BUILTIN_DB.put("952086", "华动泰越科技有限责任公司");
        BUILTIN_DB.put("952087", "南京颢志苍信息科技有限公司");
        BUILTIN_DB.put("952088", "中网数信（福建）科技有限公司");
        BUILTIN_DB.put("952089", "福建丰谷信息科技有限公司");
        BUILTIN_DB.put("952090", "杭州沄涛计算机技术有限公司");
        BUILTIN_DB.put("952091", "淮安达拓科技有限公司");
        BUILTIN_DB.put("952092", "福建雅南互联通信科技有限公司");
        BUILTIN_DB.put("952093", "福建卓宇信息科技有限公司");
        BUILTIN_DB.put("952094", "福建你我黛贷网络科技有限公司");
        BUILTIN_DB.put("952095", "北京哲里木科技有限公司");
        BUILTIN_DB.put("952096", "石家庄华盈天下信息科技有限公司");
        BUILTIN_DB.put("952097", "福建蜘蛛信息科技有限公司");
        BUILTIN_DB.put("952098", "福建红果果文化传媒有限公司");
        BUILTIN_DB.put("952099", "厦门纠缠态科技有限公司");
        BUILTIN_DB.put("952100", "深圳市赛格导航科技股份有限公司");
        BUILTIN_DB.put("952102", "上海博泰悦臻网络技术服务有限公司");
        BUILTIN_DB.put("952103", "北京人众互联信息技术有限公司");
        BUILTIN_DB.put("952104", "天津易乐享网络科技有限公司");
        BUILTIN_DB.put("952105", "江苏步云通信技术有限公司");
        BUILTIN_DB.put("952106", "江苏云禹通信有限公司");
        BUILTIN_DB.put("952107", "北京新方通信技术有限公司");
        BUILTIN_DB.put("952108", "无锡线上线下通讯信息技术股份有限公司");
        BUILTIN_DB.put("952109", "湖北同济堂电子商务有限公司");
        BUILTIN_DB.put("952111", "浙江蓝天航空经济发展有限公司");
        BUILTIN_DB.put("952112", "江苏楷文电信技术有限公司");
        BUILTIN_DB.put("952113", "陕西云驰信息科技股份有限公司");
        BUILTIN_DB.put("952114", "北京恒润杰通信息科技有限公司");
        BUILTIN_DB.put("952115", "四川莫名信息科技有限公司");
        BUILTIN_DB.put("952116", "天津亿嘉华商务信息咨询有限公司");
        BUILTIN_DB.put("952117", "武汉迪鳌科技有限公司");
        BUILTIN_DB.put("952118", "上海百事通信息技术股份有限公司");
        BUILTIN_DB.put("952119", "北京苍穹之心科技有限公司");
        BUILTIN_DB.put("952121", "北京御景圣天科技有限公司");
        BUILTIN_DB.put("952122", "艾斯欧艾斯信息科技有限公司");
        BUILTIN_DB.put("952123", "北京百讯科技有限公司");
        BUILTIN_DB.put("952124", "安徽人和市场研究咨询有限公司");
        BUILTIN_DB.put("952125", "北京活力无限信息科技有限公司");
        BUILTIN_DB.put("952126", "深圳立安保险经纪有限公司");
        BUILTIN_DB.put("952127", "广州信正信息科技有限公司");
        BUILTIN_DB.put("952128", "上海港联电信股份有限公司");
        BUILTIN_DB.put("952129", "东方联信（北京）科技有限公司");
        BUILTIN_DB.put("952130", "中商盛达（北京）信息技术有限公司");
        BUILTIN_DB.put("952131", "深圳市微米达通讯有限公司");
        BUILTIN_DB.put("952132", "陕西西部数通电信资讯有限公司");
        BUILTIN_DB.put("952134", "北京合信网联通信技术有限公司");
        BUILTIN_DB.put("952135", "深圳市五州通讯有限公司");
        BUILTIN_DB.put("952136", "北京烽火万家科技有限公司");
        BUILTIN_DB.put("952137", "深圳市富盛通通信设备有限公司");
        BUILTIN_DB.put("952138", "世纪恒通科技股份有限公司");
        BUILTIN_DB.put("952139", "福建邦信信息科技有限公司");
        BUILTIN_DB.put("952140", "南通正盈永益信息技术有限公司");
        BUILTIN_DB.put("952141", "无锡贝瑞特信息技术有限公司");
        BUILTIN_DB.put("952142", "上海杰尔讯科技发展有限公司");
        BUILTIN_DB.put("952143", "北京鸿源福通讯科技有限公司");
        BUILTIN_DB.put("952144", "河北商惠网络科技有限公司");
        BUILTIN_DB.put("952145", "成都通祥福明物流有限公司");
        BUILTIN_DB.put("952146", "安徽粤信网络通信技术有限公司");
        BUILTIN_DB.put("952147", "扬州昊驰呼叫中心管理有限公司");
        BUILTIN_DB.put("952148", "北京天彩汇诚信息技术有限公司");
        BUILTIN_DB.put("952149", "成都路行通信息技术有限公司");
        BUILTIN_DB.put("952153", "北京京怀龙腾科技有限公司");
        BUILTIN_DB.put("952154", "上海然豊信息科技有限公司");
        BUILTIN_DB.put("952155", "四川省艾普网络股份有限公司");
        BUILTIN_DB.put("952156", "广东林安物流发展有限公司");
        BUILTIN_DB.put("952157", "江苏迈泉科技有限公司");
        BUILTIN_DB.put("952158", "北京英特达系统技术有限公司");
        BUILTIN_DB.put("952159", "中山新联医疗科技有限公司");
        BUILTIN_DB.put("952161", "石家庄开发区天远科技有限公司");
        BUILTIN_DB.put("952162", "上海易云网络科技有限公司");
        BUILTIN_DB.put("952163", "四川馨枫叶科技有限公司");
        BUILTIN_DB.put("952164", "深圳市龙禧星科技有限公司");
        BUILTIN_DB.put("952165", "南京易米云通网络科技有限公司");
        BUILTIN_DB.put("952166", "山东福生佳信科技股份有限公司");
        BUILTIN_DB.put("952167", "北京百川正讯科技有限公司");
        BUILTIN_DB.put("952170", "深圳普惠易达通信技术工程有限公司");
        BUILTIN_DB.put("952171", "成都瑞星时代科技有限公司");
        BUILTIN_DB.put("952172", "方正宽带网络服务有限公司");
        BUILTIN_DB.put("952173", "北京天创文立信息科技有限公司");
        BUILTIN_DB.put("952174", "昆山筋斗云信息科技有限公司");
        BUILTIN_DB.put("952175", "中网数信科技有限公司");
        BUILTIN_DB.put("952176", "河南省长宏通信科技有限公司");
        BUILTIN_DB.put("952177", "北京市均豪物业管理股份有限公司");
        BUILTIN_DB.put("952178", "北京红树科技有限公司");
        BUILTIN_DB.put("952179", "浙江完美在线网络科技有限公司");
        BUILTIN_DB.put("952180", "深圳市快汇宝信息技术有限公司");
        BUILTIN_DB.put("952182", "北京指南针科技发展股份有限公司");
        BUILTIN_DB.put("952183", "南京禾康智慧养老产业有限公司");
        BUILTIN_DB.put("952184", "杭州晶羿科技有限公司");
        BUILTIN_DB.put("952186", "陕西省通信服务有限公司");
        BUILTIN_DB.put("952187", "中城银信控股集团有限公司");
        BUILTIN_DB.put("952188", "德仁高科（北京）投资有限公司");
        BUILTIN_DB.put("952189", "北京合力亿捷科技股份有限公司");
        BUILTIN_DB.put("952190", "海普信息技术服务有限公司");
        BUILTIN_DB.put("952191", "金科物业服务集团有限公司");
        BUILTIN_DB.put("952192", "广州市单元信息科技有限公司");
        BUILTIN_DB.put("952193", "北京融合天地通讯科技有限公司");
        BUILTIN_DB.put("952194", "福建闵酷划在线通信科技有限公司");
        BUILTIN_DB.put("952195", "拓维信息系统股份有限公司");
        BUILTIN_DB.put("952196", "北京天恒联众通信技术服务有限公司");
        BUILTIN_DB.put("952198", "河北岱慧商务信息咨询服务有限公司");
        BUILTIN_DB.put("952199", "上海未讯信息科技有限公司");
        BUILTIN_DB.put("952200", "睿智合创（北京）科技有限公司");
        BUILTIN_DB.put("952201", "深圳市小牛普惠投资管理有限公司");
        BUILTIN_DB.put("952202", "重庆智考信息技术有限公司");
        BUILTIN_DB.put("952203", "北京信捷易达科技有限公司");
        BUILTIN_DB.put("952204", "北京聚通达科技股份有限公司");
        BUILTIN_DB.put("952205", "福建省力天网络科技股份有限公司");
        BUILTIN_DB.put("952206", "深圳众诚泰保险经纪有限公司");
        BUILTIN_DB.put("952207", "福州茉京熙东臻到家通信科技有限公司");
        BUILTIN_DB.put("952208", "北京英华融涛信息有限公司");
        BUILTIN_DB.put("952209", "北京自建科技有限公司");
        BUILTIN_DB.put("952210", "福建悟空理喻财居信息服务有限公司");
        BUILTIN_DB.put("952211", "北京致鼎科技有限责任公司");
        BUILTIN_DB.put("952212", "福建曼丽文化传媒有限公司");
        BUILTIN_DB.put("952213", "山东壹嘉恩信息技术有限公司");
        BUILTIN_DB.put("952214", "上海言通网络科技有限公司");
        BUILTIN_DB.put("952215", "河北道萌信息技术有限公司");
        BUILTIN_DB.put("952216", "南京玺冠信息科技有限公司");
        BUILTIN_DB.put("952217", "福建叶子文化传媒有限公司");
        BUILTIN_DB.put("952218", "北京易通网讯科技有限公司");
        BUILTIN_DB.put("952219", "北京中创恒通科技有限公司");
        BUILTIN_DB.put("952220", "北京汉英融盛科技有限公司");
        BUILTIN_DB.put("952221", "深圳明辉智能技术有限公司");
        BUILTIN_DB.put("952222", "北京建树科技有限公司");
        BUILTIN_DB.put("952223", "北京裕魂科技有限公司");
        BUILTIN_DB.put("952224", "深圳市优客云科技有限公司");
        BUILTIN_DB.put("952225", "北京汇商金金融服务外包有限公司");
        BUILTIN_DB.put("952226", "福建陆离通讯科技有限公司");
        BUILTIN_DB.put("952227", "福建腾逸通信科技有限公司");
        BUILTIN_DB.put("952228", "无锡拍拍贷金融信息服务有限公司");
        BUILTIN_DB.put("952229", "北京七小科技有限公司");
        BUILTIN_DB.put("952230", "深圳市优品投资顾问有限公司");
        BUILTIN_DB.put("952231", "福建诺曼文化传媒有限公司");
        BUILTIN_DB.put("952232", "南京瑞俊信息技术有限公司");
        BUILTIN_DB.put("952233", "福建启度信息技术有限公司");
        BUILTIN_DB.put("952234", "北京恢弘科技有限公司");
        BUILTIN_DB.put("952235", "福建博衍通信科技有限公司");
        BUILTIN_DB.put("952236", "万人控股集团有限公司");
        BUILTIN_DB.put("952237", "江苏快享科技有限公司");
        BUILTIN_DB.put("952238", "美华保险销售有限公司");
        BUILTIN_DB.put("952239", "天津立泰企业咨询服务有限公司");
        BUILTIN_DB.put("952241", "深圳市码讯科技有限公司");
        BUILTIN_DB.put("952242", "福建丁叮当当大管家软件科技有限公司");
        BUILTIN_DB.put("952243", "重庆传显科技有限公司");
        BUILTIN_DB.put("952244", "成都肯特精英科技有限公司");
        BUILTIN_DB.put("952245", "重庆尚优科技有限公司");
        BUILTIN_DB.put("952246", "中讯通（福建）技术服务有限公司");
        BUILTIN_DB.put("952247", "宁波启动电子商务有限公司");
        BUILTIN_DB.put("952248", "天津九嘉企业管理咨询有限公司");
        BUILTIN_DB.put("952249", "福州速递易电子商务有限公司");
        BUILTIN_DB.put("952250", "福建陌璃采桑文化传媒有限公司");
        BUILTIN_DB.put("952251", "珠海信盟科技发展有限公司");
        BUILTIN_DB.put("952252", "北京网聚世通科技有限公司");
        BUILTIN_DB.put("952253", "福建纳中电子商务有限公司");
        BUILTIN_DB.put("952254", "北京格梦坊文化发展有限公司");
        BUILTIN_DB.put("952255", "北京乌涂科技有限公司");
        BUILTIN_DB.put("952256", "北京讯龙科技有限公司");
        BUILTIN_DB.put("952257", "福建平安陆驰金所通信科技有限公司");
        BUILTIN_DB.put("952258", "北京盛世创富证券投资顾问有限公司");
        BUILTIN_DB.put("952259", "浙江风临信息科技有限公司");
        BUILTIN_DB.put("952260", "北京信诺众赢科技有限公司");
        BUILTIN_DB.put("952261", "深圳云网联合手机通讯有限公司");
        BUILTIN_DB.put("952262", "南凌科技股份有限公司");
        BUILTIN_DB.put("952263", "重庆金耳麦信息技术有限公司");
        BUILTIN_DB.put("952264", "北京烽火信通信息技术有限公司");
        BUILTIN_DB.put("952265", "福建斯维尔信息科技有限公司");
        BUILTIN_DB.put("952266", "北京众信佳科技发展有限公司");
        BUILTIN_DB.put("952267", "安徽兆航科技有限公司");
        BUILTIN_DB.put("952268", "北京中创云天科技有限公司");
        BUILTIN_DB.put("952269", "福建泽琦信息科技有限公司");
        BUILTIN_DB.put("952270", "北京颂武科技有限公司");
        BUILTIN_DB.put("952271", "福建艾洋软件科技有限公司");
        BUILTIN_DB.put("952272", "福建一点通网络科技有限公司");
        BUILTIN_DB.put("952273", "四川顶筑建筑工程有限公司");
        BUILTIN_DB.put("952274", "深圳市云昱科技有限公司");
        BUILTIN_DB.put("952275", "福建佳鑫昇信息科技有限公司");
        BUILTIN_DB.put("952276", "广东创途科技有限公司");
        BUILTIN_DB.put("952277", "北京昊通伟业通讯科技有限公司");
        BUILTIN_DB.put("952278", "郑州玛森电子科技有限公司");
        BUILTIN_DB.put("952279", "北京易达网讯科技有限公司");
        BUILTIN_DB.put("952280", "北京楠彧彤科技有限公司");
        BUILTIN_DB.put("952281", "福建燕笙通信科技有限公司");
        BUILTIN_DB.put("952282", "苏州普林信息科技有限公司");
        BUILTIN_DB.put("952283", "北京众信优联科技有限公司");
        BUILTIN_DB.put("952284", "重庆啄木鸟网络科技有限公司");
        BUILTIN_DB.put("952285", "河北融洋电子科技有限公司");
        BUILTIN_DB.put("952286", "北京易联天下科技有限公司");
        BUILTIN_DB.put("952287", "武汉乐橙云科技有限公司");
        BUILTIN_DB.put("952288", "东方银谷（北京）科技发展有限公司");
        BUILTIN_DB.put("952289", "深圳中网讯通技术有限公司");
        BUILTIN_DB.put("952290", "北京豪泰园林工程有限公司");
        BUILTIN_DB.put("952291", "厦门百事盛信息科技有限公司");
        BUILTIN_DB.put("952292", "安徽捷歌科技有限公司");
        BUILTIN_DB.put("952293", "福建普阳电子商务有限公司");
        BUILTIN_DB.put("952294", "杭州颂诚科技有限公司");
        BUILTIN_DB.put("952295", "中国农业银行");
        BUILTIN_DB.put("952296", "福建天科电子商务有限公司");
        BUILTIN_DB.put("952297", "厦门乐都会通信科技有限公司");
        BUILTIN_DB.put("952298", "南京霞光信息科技有限公司");
        BUILTIN_DB.put("952299", "东郭科技（北京）有限公司");
        BUILTIN_DB.put("952300", "北京友虎科技有限公司");
        BUILTIN_DB.put("952301", "北京万信讯通科技有限公司");
        BUILTIN_DB.put("952302", "湖南永诚信息技术有限公司");
        BUILTIN_DB.put("952303", "华瑞保险销售有限公司");
        BUILTIN_DB.put("952305", "上海卡卡贷数据科技有限公司");
        BUILTIN_DB.put("952306", "安徽融翱科技有限公司");
        BUILTIN_DB.put("952307", "中报泰裕(北京)信息技术有限公司");
        BUILTIN_DB.put("952308", "北京中淘易企业管理有限公司");
        BUILTIN_DB.put("952309", "北京鸿愿科技有限公司");
        BUILTIN_DB.put("952310", "济南铂润网络科技有限公司");
        BUILTIN_DB.put("952311", "元同（北京）数据有限公司");
        BUILTIN_DB.put("952312", "北京汇智互动科技有限公司");
        BUILTIN_DB.put("952313", "广州圣亚科技有限公司");
        BUILTIN_DB.put("952314", "北京云电天下科技有限公司");
        BUILTIN_DB.put("952315", "宁波方太营销有限公司");
        BUILTIN_DB.put("952316", "广州市万网通讯科技有限公司");
        BUILTIN_DB.put("952317", "杭州铜板街互联网金融信息服务有限公司");
        BUILTIN_DB.put("952318", "信和惠民投资管理（北京）有限公司");
        BUILTIN_DB.put("952319", "硬金（北京）科技有限公司");
        BUILTIN_DB.put("952320", "福建省腾龙讯飞通信科技有限公司");
        BUILTIN_DB.put("952321", "深圳市信昌通科技有限公司");
        BUILTIN_DB.put("952322", "北京恒创联众科技有限公司");
        BUILTIN_DB.put("952323", "深圳市览通科技有限公司");
        BUILTIN_DB.put("952324", "东益通（北京）网络通信技术有限公司");
        BUILTIN_DB.put("952325", "厦门三速信网络科技有限公司");
        BUILTIN_DB.put("952326", "深圳市出众网络有限公司");
        BUILTIN_DB.put("952327", "福建斯猫夜眼电闪影通信科技有限公司");
        BUILTIN_DB.put("952328", "北京融汇天鸿科技有限公司");
        BUILTIN_DB.put("952329", "深圳市信协和科技有限公司");
        BUILTIN_DB.put("952330", "苏州磐石云通信技术有限公司");
        BUILTIN_DB.put("952331", "广州德久信息科技有限公司");
        BUILTIN_DB.put("952332", "福建启明信息科技有限公司");
        BUILTIN_DB.put("952333", "广汇物流股份有限公司");
        BUILTIN_DB.put("952334", "鼎环通信技术（苏州）有限公司");
        BUILTIN_DB.put("952335", "深圳市渤洋科技有限公司");
        BUILTIN_DB.put("952336", "北京波涛依旧科技有限公司");
        BUILTIN_DB.put("952337", "福建承宇软件科技有限公司");
        BUILTIN_DB.put("952338", "泰州戴特思统计调查服务有限公司");
        BUILTIN_DB.put("952339", "北京北翱高科投资管理有限公司");
        BUILTIN_DB.put("952340", "北京保江科技有限公司");
        BUILTIN_DB.put("952341", "陕西凯森保险代理有限公司");
        BUILTIN_DB.put("952342", "江苏语智信息科技有限公司");
        BUILTIN_DB.put("952343", "福建鼎天通信技术有限公司");
        BUILTIN_DB.put("952344", "厦门邮速达商务服务有限公司");
        BUILTIN_DB.put("952345", "前锦众程科技（武汉）有限公司");
        BUILTIN_DB.put("952346", "上海而迈网络信息科技有限公司");
        BUILTIN_DB.put("952348", "广州嘉音信息科技有限公司");
        BUILTIN_DB.put("952349", "广州华胜企业管理服务有限公司");
        BUILTIN_DB.put("952350", "北京灵越互动科技有限公司");
        BUILTIN_DB.put("952351", "福建时空漫步软件科技有限公司");
        BUILTIN_DB.put("952352", "南京红高粱信息技术有限公司");
        BUILTIN_DB.put("952353", "吉米通信（武汉）有限公司");
        BUILTIN_DB.put("952354", "深圳市联众云迅科技有限公司");
        BUILTIN_DB.put("952355", "北京中海科强科技有限公司");
        BUILTIN_DB.put("952356", "星东科技（北京）有限公司");
        BUILTIN_DB.put("952357", "深圳市资源云软件有限公司");
        BUILTIN_DB.put("952358", "北京容讯科技有限公司");
        BUILTIN_DB.put("952359", "武汉大汉三通通信有限公司");
        BUILTIN_DB.put("952360", "河南美卓通信科技有限公司");
        BUILTIN_DB.put("952361", "深圳市瑞音丰科技有限责任公司");
        BUILTIN_DB.put("952362", "北京经纬融创科技有限公司");
        BUILTIN_DB.put("952363", "福建信通互联软件科技有限公司");
        BUILTIN_DB.put("952364", "上海紫瑜信息科技有限公司");
        BUILTIN_DB.put("952365", "杭州来鼓科技有限公司");
        BUILTIN_DB.put("952366", "北京致创科技有限公司");
        BUILTIN_DB.put("952367", "亿人在线（武汉）信息技术有限公司");
        BUILTIN_DB.put("952368", "广州仁千信息科技有限公司");
        BUILTIN_DB.put("952369", "深圳市信昇科技有限公司");
        BUILTIN_DB.put("952370", "保歌（上海）金融信息服务有限公司");
        BUILTIN_DB.put("952371", "北京凤凰石科技有限公司");
        BUILTIN_DB.put("952372", "北京聚英杰通信技术有限公司");
        BUILTIN_DB.put("952373", "奥德科技有限公司");
        BUILTIN_DB.put("952374", "武汉华科数通科技有限公司");
        BUILTIN_DB.put("952375", "杭州华时科技有限公司");
        BUILTIN_DB.put("952376", "安徽淳侗科技有限公司");
        BUILTIN_DB.put("952377", "旅与拍网络科技（上海）有限公司");
        BUILTIN_DB.put("952378", "福州易芷购芍药网络科技有限公司");
        BUILTIN_DB.put("952379", "深圳市千联科技有限公司");
        BUILTIN_DB.put("952380", "武汉融创信华信息科技有限公司");
        BUILTIN_DB.put("952381", "中经信汇（北京）征信有限公司");
        BUILTIN_DB.put("952382", "杭州可遇信息技术有限公司");
        BUILTIN_DB.put("952383", "苏州聚源益网络科技有限公司");
        BUILTIN_DB.put("952384", "上海卓著互联网科技有限公司");
        BUILTIN_DB.put("952385", "梧桐树保险经纪有限公司");
        BUILTIN_DB.put("952386", "上海利真汽车服务咨询有限公司");
        BUILTIN_DB.put("952387", "福州京洛东汐网络科技有限公司");
        BUILTIN_DB.put("952388", "北京吟啸科技有限公司");
        BUILTIN_DB.put("952389", "北京短码科技有限公司");
        BUILTIN_DB.put("952390", "北京首宽信联通讯技术有限公司");
        BUILTIN_DB.put("952391", "北京灵铱科技有限公司");
        BUILTIN_DB.put("952392", "济南心龙达通信技术有限公司");
        BUILTIN_DB.put("952393", "厦门千德顺网络科技有限公司");
        BUILTIN_DB.put("952394", "上海云信留客信息科技有限公司");
        BUILTIN_DB.put("952395", "北京中海文凯科技有限公司");
        BUILTIN_DB.put("952396", "上海以岚信息技术有限公司");
        BUILTIN_DB.put("952397", "安徽省渠道网络股份有限公司");
        BUILTIN_DB.put("952398", "厦门正三角科技有限公司");
        BUILTIN_DB.put("952399", "金天国际医疗科技有限公司");
        BUILTIN_DB.put("952400", "北京思空科技有限公司");
        BUILTIN_DB.put("952401", "河南省金盾信息安全等级技术测评中心有限公司");
        BUILTIN_DB.put("952402", "山东鲁联信息科技有限公司");
        BUILTIN_DB.put("952404", "上海金蔺网络科技有限公司");
        BUILTIN_DB.put("952406", "智慧云（厦门）物联网科技有限公司");
        BUILTIN_DB.put("952408", "江苏新玉通信技术有限公司");
        BUILTIN_DB.put("952409", "北京玖陆零电力科技有限公司");
        BUILTIN_DB.put("952410", "保定和讯云联计算机信息技术服务有限公司");
        BUILTIN_DB.put("952411", "南京嘉之乐信息技术有限公司");
        BUILTIN_DB.put("952412", "深圳软通天下信息技术有限公司");
        BUILTIN_DB.put("952413", "河南硕朗通讯有限公司");
        BUILTIN_DB.put("952416", "深圳市君语科技有限公司");
        BUILTIN_DB.put("952420", "北京华信天成通信技术有限公司");
        BUILTIN_DB.put("952421", "北京复讯科技有限公司");
        BUILTIN_DB.put("952422", "南京新之瑞信息科技有限公司");
        BUILTIN_DB.put("952423", "深圳市畅想云科技有限公司");
        BUILTIN_DB.put("952425", "江西丹海科技有限公司");
        BUILTIN_DB.put("952426", "玖富金科控股集团有限责任公司");
        BUILTIN_DB.put("952428", "山东飞翔通讯科技有限公司");
        BUILTIN_DB.put("952430", "杭州大坝科技有限公司");
        BUILTIN_DB.put("952432", "北京科聚汇达科技有限公司");
        BUILTIN_DB.put("952433", "厦门同朋软件技术有限公司");
        BUILTIN_DB.put("952436", "河南吉通悦信息技术有限公司");
        BUILTIN_DB.put("952438", "福建触众网络科技有限公司");
        BUILTIN_DB.put("952440", "苏州市德旭通讯科技有限公司");
        BUILTIN_DB.put("952452", "武汉里达科技有限公司");
        BUILTIN_DB.put("952453", "南京铭津信息技术有限公司");
        BUILTIN_DB.put("952456", "北京怪诞科技有限公司");
        BUILTIN_DB.put("952459", "辽宁微派网络科技有限公司");
        BUILTIN_DB.put("952461", "北京远洋博瑞通信科技有限公司");
        BUILTIN_DB.put("952462", "广州市雷商网络科技有限公司");
        BUILTIN_DB.put("952463", "成都融智汽车服务有限公司");
        BUILTIN_DB.put("952467", "黑龙江八方科技有限公司");
        BUILTIN_DB.put("952470", "北京乐聚天下文化发展有限公司");
        BUILTIN_DB.put("952475", "南京苗花信息科技有限公司");
        BUILTIN_DB.put("952476", "广州颢然信息科技有限公司");
        BUILTIN_DB.put("952478", "广州飞鸟软件科技有限公司");
        BUILTIN_DB.put("952482", "广州网游信息科技有限公司");
        BUILTIN_DB.put("952483", "深圳市友讯云科技有限公司");
        BUILTIN_DB.put("952485", "北京智博天下科技有限公司");
        BUILTIN_DB.put("952486", "上海拓鹏信息科技有限公司");
        BUILTIN_DB.put("952489", "南京云声信息技术有限公司");
        BUILTIN_DB.put("952493", "河北泉进科技有限公司");
        BUILTIN_DB.put("952496", "乐网（福建）信息科技有限公司");
        BUILTIN_DB.put("952497", "北京亚飞光速网络科技有限公司");
        BUILTIN_DB.put("952524", "天啸融通科技（北京）有限公司");
        BUILTIN_DB.put("952528", "北京久言科技有限公司");
        BUILTIN_DB.put("952533", "成都身边科技有限公司");
        BUILTIN_DB.put("952548", "陕西联合四海文化传播有限公司");
        BUILTIN_DB.put("952551", "杭州言牛科技有限公司");
        BUILTIN_DB.put("952555", "同花顺");
        BUILTIN_DB.put("952556", "江苏满运软件科技有限公司");
        BUILTIN_DB.put("952562", "北京万联宏信科技有限公司");
        BUILTIN_DB.put("952568", "中国民生银行");
        BUILTIN_DB.put("952572", "金惠家社区服务有限责任公司");
        BUILTIN_DB.put("952586", "高邮市圣亚智能科技有限公司");
        BUILTIN_DB.put("952595", "北京信华锐安科技有限公司");
        BUILTIN_DB.put("95300", "华夏人寿保险股份有限公司");
        BUILTIN_DB.put("95301", "天安人寿保险股份有限公司");
        BUILTIN_DB.put("95302", "南京银行");
        BUILTIN_DB.put("95303", "安心财产保险有限责任公司");
        BUILTIN_DB.put("95304", "宏信证券有限责任公司");
        BUILTIN_DB.put("95305", "九州证券股份有限公司");
        BUILTIN_DB.put("95306", "中国铁路总公司");
        BUILTIN_DB.put("95307", "奥凯航空有限公司");
        BUILTIN_DB.put("95308", "中美联泰大都会人寿保险有限公司");
        BUILTIN_DB.put("95309", "东兴证券");
        BUILTIN_DB.put("95310", "国金证券股份有限公司");
        BUILTIN_DB.put("95311", "中通快递");
        BUILTIN_DB.put("95312", "紫金财产保险股份有限公司");
        BUILTIN_DB.put("95313", "广州农村商业银行");
        BUILTIN_DB.put("95315", "苏宁云商集团股份有限公司");
        BUILTIN_DB.put("95316", "九江银行");
        BUILTIN_DB.put("95317", "财富证券有限责任公司");
        BUILTIN_DB.put("95318", "华安证券股份有限公司");
        BUILTIN_DB.put("95319", "江苏银行");
        BUILTIN_DB.put("95320", "百世快递");
        BUILTIN_DB.put("95321", "信达证券股份有限公司");
        BUILTIN_DB.put("95322", "万联证券股份有限公司");
        BUILTIN_DB.put("95323", "华鑫证券有限责任公司");
        BUILTIN_DB.put("95325", "开源证券股份有限公司");
        BUILTIN_DB.put("95326", "云南祥鹏航空有限责任公司");
        BUILTIN_DB.put("95327", "上海红楼快递集团有限公司");
        BUILTIN_DB.put("95328", "东莞证券");
        BUILTIN_DB.put("95329", "中山证券有限责任公司");
        BUILTIN_DB.put("95330", "东吴证券股份有限公司");
        BUILTIN_DB.put("95331", "建信人寿保险股份有限公司");
        BUILTIN_DB.put("95332", "天天快递有限公司");
        BUILTIN_DB.put("95333", "中国民族证券有限责任公司");
        BUILTIN_DB.put("95334", "乌鲁木齐航空有限责任公司");
        BUILTIN_DB.put("95335", "中航证券有限公司");
        BUILTIN_DB.put("95336", "财通证券股份有限公司");
        BUILTIN_DB.put("95337", "盛京银行");
        BUILTIN_DB.put("95338", "顺丰速运");
        BUILTIN_DB.put("95339", "海南航空");
        BUILTIN_DB.put("95341", "银泰证券有限责任公司");
        BUILTIN_DB.put("95343", "浙江民泰商业银行股份有限公司");
        BUILTIN_DB.put("95344", "上海安能聚创供应链管理有限公司");
        BUILTIN_DB.put("95345", "浙商证券股份有限公司");
        BUILTIN_DB.put("95346", "中天证券股份有限公司");
        BUILTIN_DB.put("95347", "泰隆银行");
        BUILTIN_DB.put("95348", "光大永明人寿保险有限公司");
        BUILTIN_DB.put("95349", "优速物流有限公司");
        BUILTIN_DB.put("95350", "天津航空有限责任公司");
        BUILTIN_DB.put("95351", "湘财证券股份有限公司");
        BUILTIN_DB.put("95352", "包商银行股份有限公司");
        BUILTIN_DB.put("95353", "德邦快递");
        BUILTIN_DB.put("95354", "东吴人寿保险股份有限公司");
        BUILTIN_DB.put("95355", "西南证券股份有限公司");
        BUILTIN_DB.put("95356", "中国农业发展银行");
        BUILTIN_DB.put("95357", "东方财富");
        BUILTIN_DB.put("95358", "第一创业证券股份有限公司");
        BUILTIN_DB.put("95359", "工银安盛人寿保险有限公司");
        BUILTIN_DB.put("95360", "东北证券股份有限公司");
        BUILTIN_DB.put("95361", "深圳航空");
        BUILTIN_DB.put("95362", "招商信诺人寿保险有限公司");
        BUILTIN_DB.put("95363", "财达证券股份有限公司");
        BUILTIN_DB.put("95364", "渤海财产保险股份有限公司");
        BUILTIN_DB.put("95365", "信泰人寿保险股份有限公司");
        BUILTIN_DB.put("95366", "汇丰银行（中国）");
        BUILTIN_DB.put("95367", "武汉农商银行");
        BUILTIN_DB.put("95368", "华龙证券股份有限公司");
        BUILTIN_DB.put("95369", "山东航空");
        BUILTIN_DB.put("95370", "广西北部湾航空有限责任公司");
        BUILTIN_DB.put("95371", "深圳福田银座村镇银行");
        BUILTIN_DB.put("95372", "金元证券股份有限公司");
        BUILTIN_DB.put("95373", "西部航空有限责任公司");
        BUILTIN_DB.put("95375", "北京首都航空有限公司");
        BUILTIN_DB.put("95376", "民生证券股份有限公司");
        BUILTIN_DB.put("95377", "中原证券股份有限公司");
        BUILTIN_DB.put("95378", "四川航空");
        BUILTIN_DB.put("95379", "昆仑银行");
        BUILTIN_DB.put("95380", "DHL");
        BUILTIN_DB.put("95381", "首创证券有限责任公司");
        BUILTIN_DB.put("95382", "东亚银行（中国）有限公司");
        BUILTIN_DB.put("95383", "中宏保险");
        BUILTIN_DB.put("95384", "微众银行");
        BUILTIN_DB.put("95385", "国融证券股份有限公司");
        BUILTIN_DB.put("95386", "南京证券股份有限公司");
        BUILTIN_DB.put("95387", "北京如风达快递有限公司");
        BUILTIN_DB.put("95388", "中国石化");
        BUILTIN_DB.put("95389", "重庆农村商业银行");
        BUILTIN_DB.put("95390", "华融证券股份有限公司");
        BUILTIN_DB.put("95391", "天风证券股份有限公司");
        BUILTIN_DB.put("95392", "成都农村商业银行股份有限公司");
        BUILTIN_DB.put("95393", "鼎和财产保险股份有限公司");
        BUILTIN_DB.put("95395", "恒丰银行股份有限公司");
        BUILTIN_DB.put("95396", "广州证券股份有限公司");
        BUILTIN_DB.put("95397", "太平洋证券股份有限公司");
        BUILTIN_DB.put("95398", "杭州银行");
        BUILTIN_DB.put("95399", "新时代证券股份有限公司");
        BUILTIN_DB.put("95500", "太平洋保险");
        BUILTIN_DB.put("95501", "深发银行");
        BUILTIN_DB.put("95502", "永安财产保险股份有限公司");
        BUILTIN_DB.put("95503", "东方证券");
        BUILTIN_DB.put("95504", "中国石油");
        BUILTIN_DB.put("95505", "天安财产保险股份有限公司");
        BUILTIN_DB.put("95506", "亚太财产保险有限公司");
        BUILTIN_DB.put("95507", "成都银行");
        BUILTIN_DB.put("95508", "广发银行");
        BUILTIN_DB.put("95509", "华泰保险集团股份有限公司");
        BUILTIN_DB.put("95510", "阳光保险");
        BUILTIN_DB.put("95511", "中国平安");
        BUILTIN_DB.put("95512", "中国平安财产保险股份有限公司");
        BUILTIN_DB.put("95513", "华泰联合证券有限责任公司");
        BUILTIN_DB.put("95514", "长城证券股份有限公司");
        BUILTIN_DB.put("95515", "合众人寿保险股份有限公司");
        BUILTIN_DB.put("95516", "中国银联");
        BUILTIN_DB.put("95517", "国投证券");
        BUILTIN_DB.put("95518", "中国人保");
        BUILTIN_DB.put("95519", "中国人寿");
        BUILTIN_DB.put("95520", "吉祥航空");
        BUILTIN_DB.put("95521", "国泰海通证券");
        BUILTIN_DB.put("95522", "泰康人寿");
        BUILTIN_DB.put("95523", "申万宏源证券有限公司");
        BUILTIN_DB.put("95524", "春秋航空");
        BUILTIN_DB.put("95525", "光大证券股份有限公司");
        BUILTIN_DB.put("95526", "北京银行");
        BUILTIN_DB.put("95527", "浙商银行");
        BUILTIN_DB.put("95528", "浦发银行");
        BUILTIN_DB.put("95529", "太平财产保险有限公司");
        BUILTIN_DB.put("95530", "中国东方航空");
        BUILTIN_DB.put("95531", "东海证券股份有限公司");
        BUILTIN_DB.put("95532", "中国中投证券有限责任公司");
        BUILTIN_DB.put("95533", "中国建设银行");
        BUILTIN_DB.put("95534", "银联商务");
        BUILTIN_DB.put("95535", "富德生命人寿保险股份有限公司");
        BUILTIN_DB.put("95536", "国信证券");
        BUILTIN_DB.put("95537", "哈尔滨银行");
        BUILTIN_DB.put("95538", "中泰证券股份有限公司");
        BUILTIN_DB.put("95539", "中国南方航空");
        BUILTIN_DB.put("95540", "安华农业保险股份有限公司");
        BUILTIN_DB.put("95541", "渤海银行");
        BUILTIN_DB.put("95542", "百年人寿保险股份有限公司");
        BUILTIN_DB.put("95543", "申通快递");
        BUILTIN_DB.put("95544", "安诚财产保险股份有限公司");
        BUILTIN_DB.put("95545", "中英人寿保险有限公司");
        BUILTIN_DB.put("95546", "韵达速递");
        BUILTIN_DB.put("95547", "华福证券有限责任公司");
        BUILTIN_DB.put("95548", "中信证券");
        BUILTIN_DB.put("95549", "国华人寿保险股份有限公司");
        BUILTIN_DB.put("95550", "安盛保险");
        BUILTIN_DB.put("95551", "银河证券");
        BUILTIN_DB.put("95552", "永诚财产保险股份有限公司");
        BUILTIN_DB.put("95553", "海通证券股份有限公司");
        BUILTIN_DB.put("95554", "圆通速递");
        BUILTIN_DB.put("95555", "招商银行");
        BUILTIN_DB.put("95556", "华安财产保险股份有限公司");
        BUILTIN_DB.put("95557", "厦门航空");
        BUILTIN_DB.put("95558", "中信银行");
        BUILTIN_DB.put("9555801", "中信银行");
        BUILTIN_DB.put("955581101", "中信银行");
        BUILTIN_DB.put("95559", "交通银行");
        BUILTIN_DB.put("95560", "幸福人寿保险股份有限公司");
        BUILTIN_DB.put("95561", "兴业银行");
        BUILTIN_DB.put("95562", "兴业证券");
        BUILTIN_DB.put("95563", "国海证券股份有限公司");
        BUILTIN_DB.put("95564", "联讯证券股份有限公司");
        BUILTIN_DB.put("95565", "招商证券");
        BUILTIN_DB.put("95566", "中国银行");
        BUILTIN_DB.put("95567", "新华人寿保险股份有限公司");
        BUILTIN_DB.put("95568", "中国民生银行");
        BUILTIN_DB.put("95569", "安邦保险集团股份有限公司");
        BUILTIN_DB.put("95570", "国联证券股份有限公司");
        BUILTIN_DB.put("95571", "方正证券股份有限公司");
        BUILTIN_DB.put("95572", "中铁快运股份有限公司");
        BUILTIN_DB.put("95573", "山西证券股份有限公司");
        BUILTIN_DB.put("95574", "宁波银行");
        BUILTIN_DB.put("95575", "广发证券");
        BUILTIN_DB.put("95576", "长城人寿保险股份有限公司");
        BUILTIN_DB.put("95577", "华夏银行");
        BUILTIN_DB.put("95578", "国元证券股份有限公司");
        BUILTIN_DB.put("95579", "长江证券股份有限公司");
        BUILTIN_DB.put("95580", "中国邮政储蓄银行");
        BUILTIN_DB.put("95581", "农银人寿保险股份有限公司");
        BUILTIN_DB.put("95582", "西部证券股份有限公司");
        BUILTIN_DB.put("95583", "中国国际航空");
        BUILTIN_DB.put("95584", "华西证券股份有限公司");
        BUILTIN_DB.put("95585", "中华保险");
        BUILTIN_DB.put("95586", "都邦财产保险股份有限公司");
        BUILTIN_DB.put("95587", "中信建投证券股份有限公司");
        BUILTIN_DB.put("95588", "中国工商银行");
        BUILTIN_DB.put("95589", "中国太平");
        BUILTIN_DB.put("95590", "大地保险");
        BUILTIN_DB.put("95591", "中国人保");
        BUILTIN_DB.put("95592", "长安责任保险股份有限公司");
        BUILTIN_DB.put("95593", "国家开发银行");
        BUILTIN_DB.put("95594", "上海银行");
        BUILTIN_DB.put("95595", "光大银行");
        BUILTIN_DB.put("95596", "民生人寿保险股份有限公司");
        BUILTIN_DB.put("95597", "华泰证券");
        BUILTIN_DB.put("95598", "国家电网");
        BUILTIN_DB.put("955981112", "国家电网");
        BUILTIN_DB.put("95599", "中国农业银行");
        BUILTIN_DB.put("956000", "利宝保险有限公司");
        BUILTIN_DB.put("956001", "中航安盟财产保险有限公司");
        BUILTIN_DB.put("956005", "阳光保险");
        BUILTIN_DB.put("956006", "联储证券有限责任公司");
        BUILTIN_DB.put("956007", "江海证券有限公司");
        BUILTIN_DB.put("956008", "中国人民人寿保险股份有限公司");
        BUILTIN_DB.put("956009", "利安人寿保险股份有限公司");
        BUILTIN_DB.put("956010", "恒大人寿保险有限公司");
        BUILTIN_DB.put("956011", "华金证券股份有限公司");
        BUILTIN_DB.put("956016", "君康人寿保险股份有限公司");
        BUILTIN_DB.put("956018", "大通证券股份有限公司");
        BUILTIN_DB.put("956020", "江苏常熟农村商业银行股份有限公司");
        BUILTIN_DB.put("956023", "重庆银行");
        BUILTIN_DB.put("956025", "极兔速递");
        BUILTIN_DB.put("956028", "成都航空有限公司");
        BUILTIN_DB.put("956033", "东莞银行");
        BUILTIN_DB.put("956036", "速尔快递有限公司");
        BUILTIN_DB.put("956055", "江西银行");
        BUILTIN_DB.put("956056", "天津银行");
        BUILTIN_DB.put("956058", "宁波鄞州农村商业银行股份有限公司");
        BUILTIN_DB.put("956060", "红塔证券股份有限公司");
        BUILTIN_DB.put("956065", "吉林银行股份有限公司");
        BUILTIN_DB.put("956066", "渤海证券股份有限公司");
        BUILTIN_DB.put("956068", "易安财产保险股份有限公司");
        BUILTIN_DB.put("956077", "中邮人寿保险股份有限公司");
        BUILTIN_DB.put("956078", "江苏江阴农村商业银行股份有限公司");
        BUILTIN_DB.put("956085", "厦门国际银行");
        BUILTIN_DB.put("956086", "大同证券有限责任公司");
        BUILTIN_DB.put("956088", "恒泰证券股份有限公司");
        BUILTIN_DB.put("956095", "同方全球人寿保险有限公司");
        BUILTIN_DB.put("956096", "西藏航空有限公司");
        BUILTIN_DB.put("956098", "华林证券股份有限公司");
        BUILTIN_DB.put("956099", "前海人寿保险股份有限公司");
        BUILTIN_DB.put("956100", "中国石油");
        BUILTIN_DB.put("956111", "苏州农商银行");
        BUILTIN_DB.put("956168", "东莞农商银行");
        BUILTIN_DB.put("956169", "大连银行");
        BUILTIN_DB.put("956199", "浙江长龙航空");
        BUILTIN_DB.put("95700", "普天新能源（北京）有限公司");
        BUILTIN_DB.put("95701", "福建观奇信息科技有限公司");
        BUILTIN_DB.put("95702", "北京空间畅想信息技术有限责任公司");
        BUILTIN_DB.put("95703", "舟山柯胜信息科技有限公司");
        BUILTIN_DB.put("95704", "福建生益信息科技有限公司");
        BUILTIN_DB.put("95705", "上海率若信息科技有限公司");
        BUILTIN_DB.put("95706", "权健自然医学科技发展有限公司");
        BUILTIN_DB.put("95707", "福建锐标电子商务有限公司");
        BUILTIN_DB.put("95708", "福建建川网络科技有限公司");
        BUILTIN_DB.put("95709", "天津中科易佰科技有限公司");
        BUILTIN_DB.put("95710", "吉林省云上汽车股份有限公司");
        BUILTIN_DB.put("95711", "同程旅行");
        BUILTIN_DB.put("95712", "北京一零六九科技有限公司");
        BUILTIN_DB.put("95713", "北京张舟怡帆网络技术有限公司");
        BUILTIN_DB.put("95714", "北京捷诚通达网络技术有限公司");
        BUILTIN_DB.put("95715", "上海亿保健康管理有限公司");
        BUILTIN_DB.put("95716", "腾讯云");
        BUILTIN_DB.put("95717", "石家庄市智云电子商务有限公司");
        BUILTIN_DB.put("95718", "品骏控股有限公司");
        BUILTIN_DB.put("95719", "南京奥之泉文化科技有限公司");
        BUILTIN_DB.put("95720", "中通快递");
        BUILTIN_DB.put("95721", "北京成艳科技有限公司");
        BUILTIN_DB.put("95722", "北京信诺飞凡信息技术有限公司");
        BUILTIN_DB.put("95723", "广州远程教育中心有限公司");
        BUILTIN_DB.put("95724", "上海诗宝通讯技术有限公司");
        BUILTIN_DB.put("95725", "福建萤火虫电子商务有限公司");
        BUILTIN_DB.put("95726", "杭州远帆科技有限公司");
        BUILTIN_DB.put("95727", "北京易趣方舟科技有限公司");
        BUILTIN_DB.put("95728", "上海友康信息科技有限公司");
        BUILTIN_DB.put("95729", "北京博汇音通文化传播有限公司");
        BUILTIN_DB.put("95730", "深圳市分期乐网络科技有限公司");
        BUILTIN_DB.put("95731", "普维丰信息咨询服务（北京）有限公司");
        BUILTIN_DB.put("95732", "上海泷盛科技有限公司");
        BUILTIN_DB.put("95733", "上海誉勋互联网信息技术有限公司");
        BUILTIN_DB.put("95734", "北京中兴云际科技有限公司");
        BUILTIN_DB.put("95736", "天津行健信通科技有限公司");
        BUILTIN_DB.put("95737", "山西黑龙实业集团有限公司");
        BUILTIN_DB.put("95738", "深圳市博赢实业有限公司");
        BUILTIN_DB.put("95739", "深圳市秒嘀科技有限公司");
        BUILTIN_DB.put("95740", "厦门网通网联信息科技有限公司");
        BUILTIN_DB.put("95741", "上海顽色投资管理有限公司");
        BUILTIN_DB.put("95742", "北京百志科技有限公司");
        BUILTIN_DB.put("95743", "信元公众信息发展有限责任公司");
        BUILTIN_DB.put("95744", "厦门太阳羽信息科技有限公司");
        BUILTIN_DB.put("95745", "福建慕宸信息技术有限公司");
        BUILTIN_DB.put("95746", "上海欣率信息科技有限公司");
        BUILTIN_DB.put("95747", "北京万通融科科技有限公司");
        BUILTIN_DB.put("95748", "厦门双螺旋科技有限公司");
        BUILTIN_DB.put("95749", "北京亿美软通科技有限公司");
        BUILTIN_DB.put("95750", "深圳平安通信科技有限公司");
        BUILTIN_DB.put("95751", "杭州鸿大网络技术有限公司");
        BUILTIN_DB.put("95752", "北京华睿德冠科技有限公司");
        BUILTIN_DB.put("95753", "北京大米科技有限公司");
        BUILTIN_DB.put("95754", "上海厨康电子商务有限公司");
        BUILTIN_DB.put("95755", "北京优致达科技有限公司");
        BUILTIN_DB.put("95756", "济南辰启网络科技有限公司");
        BUILTIN_DB.put("95757", "北京阳光和美信息科技有限公司");
        BUILTIN_DB.put("95758", "北京金创意网络科技有限公司");
        BUILTIN_DB.put("95759", "北京盈联网络通信有限公司");
        BUILTIN_DB.put("95760", "石家庄惠买网络科技有限公司");
        BUILTIN_DB.put("95761", "北京福企万业科技有限公司");
        BUILTIN_DB.put("95762", "北京尚源汇科技有限公司");
        BUILTIN_DB.put("95763", "网易乐得优佳科技（北京）有限公司");
        BUILTIN_DB.put("95764", "河北昂讯科技有限公司");
        BUILTIN_DB.put("95765", "福建安联广告有限公司");
        BUILTIN_DB.put("95766", "惠州诺盾高科电子有限公司");
        BUILTIN_DB.put("95767", "北京凌渡科技有限公司");
        BUILTIN_DB.put("95768", "中国民生银行");
        BUILTIN_DB.put("95769", "派生科技集团股份有限公司");
        BUILTIN_DB.put("95770", "上海友桑网络科技有限公司");
        BUILTIN_DB.put("95771", "新兴重工（天津）国际贸易有限公司");
        BUILTIN_DB.put("95772", "福建瓯诚信息技术有限公司");
        BUILTIN_DB.put("95773", "青岛鲁诺金融电子技术有限公司");
        BUILTIN_DB.put("95774", "山东三七网络科技有限公司");
        BUILTIN_DB.put("95775", "上海视菲网络科技有限公司");
        BUILTIN_DB.put("95776", "深圳淘淘金互联网金融服务有限公司");
        BUILTIN_DB.put("95777", "鲁能集团有限公司");
        BUILTIN_DB.put("95778", "山东恒贝信息技术有限公司");
        BUILTIN_DB.put("95779", "翰威通信有限公司");
        BUILTIN_DB.put("95780", "深圳市佰仟金融服务有限公司");
        BUILTIN_DB.put("95781", "上海晓泉信息科技有限公司");
        BUILTIN_DB.put("95782", "北京网信白泽投资服务有限公司");
        BUILTIN_DB.put("95783", "微贷（杭州）金融信息服务有限公司");
        BUILTIN_DB.put("95785", "福建深业广告有限公司");
        BUILTIN_DB.put("95787", "北京稀有科技有限公司");
        BUILTIN_DB.put("95788", "晋城市皓睿网络技术有限公司");
        BUILTIN_DB.put("95789", "上海长信科技股份有限公司");
        BUILTIN_DB.put("95790", "税友软件集团股份有限公司");
        BUILTIN_DB.put("95791", "福建南音通信科技有限公司");
        BUILTIN_DB.put("95792", "福建未来无线信息技术有限公司");
        BUILTIN_DB.put("95793", "木枫科技（北京）有限公司");
        BUILTIN_DB.put("95794", "北京讯博源科技有限公司");
        BUILTIN_DB.put("95795", "北京虚游网络科技有限公司");
        BUILTIN_DB.put("95796", "北京华信杰通科技有限公司");
        BUILTIN_DB.put("95797", "上海卉靓信息科技有限公司");
        BUILTIN_DB.put("95798", "上海暔皙信息科技有限公司");
        BUILTIN_DB.put("95799", "东方时尚驾驶学校股份有限公司");
        BUILTIN_DB.put("96005", "江南农商银行");
        BUILTIN_DB.put("96110", "国家反诈中心");
        BUILTIN_DB.put("96119", "江苏消防");
        BUILTIN_DB.put("96138", "广东农信");
        BUILTIN_DB.put("96169", "中国联通");
        BUILTIN_DB.put("96233", "上饶银行");
        BUILTIN_DB.put("96268", "江西农商银行");
        BUILTIN_DB.put("96299", "桂林银行");
        BUILTIN_DB.put("962999", "上海农村商业银行");
        BUILTIN_DB.put("96500", "云南省农村信用社");
        BUILTIN_DB.put("96511", "长沙银行");
        BUILTIN_DB.put("96518", "山西农商联合银行");
        BUILTIN_DB.put("96558", "汉口银行");
        BUILTIN_DB.put("96568", "湖北省农村信用社(农商银行)");
        BUILTIN_DB.put("96596", "浙江农商联合银行");
        BUILTIN_DB.put("96599", "湖南银行");
        BUILTIN_DB.put("966888", "广西农商联合银行");
        BUILTIN_DB.put("96699", "广州银行");
        BUILTIN_DB.put("96811", "乐山市商业银行");
        BUILTIN_DB.put("96836", "长城华西银行");
        BUILTIN_DB.put("999", "红十字会急救台");
    }

    private static final String TAG = "CallerID_Web";

    public interface Callback {
        void onResult(String result);
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    // 轮询间隔和上限
    private static final int POLL_INTERVAL_MS = 500;
    private static final int POLL_MAX_MS      = 12000;
    private static final int TIMEOUT_MS       = 15000;

    // 特殊短号白名单
    private static final String[][] SPECIAL_NUMBERS = {
            {"110",   "报警电话"},
            {"119",   "火警电话"},
            {"120",   "急救电话"},
            {"122",   "交通报警"},
            {"12110", "短信报警"},
            {"96110", "反诈专线"},
            {"12321", "骚扰举报"},
            {"12315", "消费者投诉"},
            {"12345", "政务服务热线"},
            {"10086", "中国移动客服"},
            {"10010", "中国联通客服"},
            {"10000", "中国电信客服"},
    };

    // 轮询用的 JS：检测页面关键内容是否已渲染
    // 条件：号码出现在页面中，且不再是加载态（loading skeleton 消失）
    private static final String POLL_JS =
            "(function(){"
            + "var b=document.body;"
            + "if(!b)return 'NOT_READY';"
            + "var t=b.innerText||'';"
            // 页面至少要有"更多服务"或"查询号码"才算渲染完
            + "if(t.indexOf('更多服务')>=0||t.indexOf('查询号码')>=0)return t;"
            + "return 'NOT_READY';"
            + "})();";

    public void query(Context ctx, String number, Callback cb) {
        Log.d(TAG, "query() called, number=" + number);

        // 0. 用户自定义号码库（最高优先级，覆盖内置库/联网结果）
        UserNumberStore.init(ctx);
        String userDefined = UserNumberStore.get(number);
        if (userDefined != null) {
            Log.d(TAG, "USER hit: " + number + " -> " + userDefined);
            new Handler(Looper.getMainLooper()).post(() -> cb.onResult(userDefined));
            return;
        }

        // 1. 缓存命中（即之前联网查询过的结果，优先级高于内置库）
        CacheStore.init(ctx);
        String cached = CacheStore.get(number);
        if (cached != null && !cached.isEmpty()) {
            // 空串视为无效缓存，走后面的内置库/联网查询兜底
            Log.d(TAG, "CACHE hit: " + number + " -> " + cached);
            new Handler(Looper.getMainLooper()).post(() -> cb.onResult(cached));
            return;
        }

        // 2. 特殊号码白名单
        for (String[] entry : SPECIAL_NUMBERS) {
            if (entry[0].equals(number)) {
                Log.d(TAG, "SPECIAL hit: " + entry[1]);
                new Handler(Looper.getMainLooper()).post(() -> cb.onResult(entry[1]));
                return;
            }
        }

        // 3. 内置号码数据库查询（本地直接返回，无网络请求）
        String builtin = BUILTIN_DB.get(number);
        if (builtin != null) {
            Log.d(TAG, "BUILTIN hit: " + number + " -> " + builtin);
            new Handler(Looper.getMainLooper()).post(() -> cb.onResult(builtin));
            return;
        }

        // 3.5 百度静默查询开关（v3.13 新增）：只影响本步骤（联网查询），
        //     前面 0~3 步（自定义库/缓存/白名单/内置库）命中与否完全不受影响。
        //     关闭后，本地未命中时不再联网查询，直接回落到"未知号码"（不写入缓存，
        //     以便用户重新开启后仍能正常联网查询，不被占位结果挡住）。
        if (!ModuleSettings.isBaiduSilentQueryEnabled(ctx)) {
            Log.d(TAG, "baidu silent query disabled, skip WebView query");
            new Handler(Looper.getMainLooper()).post(() -> cb.onResult(null));
            return;
        }

        // 4. WebView 查询。
        //    根据设置决定联网方式：
        //    - 开启"强制使用流量查询"：把本进程强制切到蜂窝网络（即使当前连着 WiFi），
        //      避免部分 WiFi 环境下 DNS 污染/路由劫持导致查不到号码。
        //      拿不到蜂窝网络（比如设备没插卡）则按原逻辑走系统默认网络。
        //    - 默认（未开启）：不做任何绑定，走系统默认联网方式
        //      （有 WiFi 走 WiFi，没 WiFi 走流量，和普通 App 一致）。
        if (ModuleSettings.isForceCellular(ctx)) {
            forceCellularThen(ctx,
                    () -> doWebViewQuery(ctx, number, cb),
                    () -> doWebViewQuery(ctx, number, cb));
        } else {
            doWebViewQuery(ctx, number, cb);
        }
    }

    private void doWebViewQuery(Context ctx, String number, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) { cb.onResult(null); return; }

            final WebView wv;
            try {
                wv = new WebView(ctx);
            } catch (Throwable e) {
                Log.e(TAG, "create WebView failed", e);
                cb.onResult(null);
                return;
            }

            final boolean[] done     = {false};
            final boolean[] attached = {false};
            final int[]     pollMs   = {0};

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = 0; lp.y = 0;

            try {
                wv.setAlpha(0.01f);
                wm.addView(wv, lp);
                attached[0] = true;
            } catch (Throwable e) {
                Log.e(TAG, "add WebView failed", e);
                try { wv.destroy(); } catch (Exception ignored) {}
                cb.onResult(null);
                return;
            }

            // 轮询 Runnable
            Runnable[] pollHolder = new Runnable[1];
            pollHolder[0] = new Runnable() {
                @Override
                public void run() {
                    if (done[0]) return;
                    pollMs[0] += POLL_INTERVAL_MS;
                    if (pollMs[0] > POLL_MAX_MS) {
                        Log.e(TAG, "POLL_MAX reached, force extract");
                        extractAndFinish(wv, number, done, wm, attached, cb);
                        return;
                    }
                    wv.evaluateJavascript(POLL_JS, val -> {
                        if (done[0]) return;
                        if (val != null && !val.contains("NOT_READY")) {
                            Log.d(TAG, "POLL ready at " + pollMs[0] + "ms");
                            extractAndFinish(wv, number, done, wm, attached, cb);
                        } else {
                            main.postDelayed(pollHolder[0], POLL_INTERVAL_MS);
                        }
                    });
                }
            };

            try {
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setDatabaseEnabled(true);
                s.setBlockNetworkImage(true);
                s.setLoadsImagesAutomatically(false);
                s.setUserAgentString(UA);
                s.setCacheMode(WebSettings.LOAD_DEFAULT);
                WebView.setWebContentsDebuggingEnabled(true);

                wv.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onConsoleMessage(ConsoleMessage m) {
                        Log.d(TAG, "console: " + m.message());
                        return true;
                    }
                });

                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        Log.d(TAG, "onPageFinished: " + url);
                        if (url.contains("mhaoma.baidu.com")) {
                            // 页面 HTML 加载完，开始轮询 JS 渲染
                            main.postDelayed(pollHolder[0], POLL_INTERVAL_MS);
                        }
                    }

                    @Override
                    public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                        if (req != null && req.isForMainFrame()) {
                            Log.e(TAG, "error: " + err.getErrorCode());
                        }
                    }
                });

                String url = "https://mhaoma.baidu.com/pages/search-result/search-result?search="
                        + number + "&srcid=swan-search";
                Log.d(TAG, "loadUrl=" + url);
                wv.loadUrl(url);

                // 全局超时兜底
                main.postDelayed(() -> {
                    if (!done[0]) {
                        done[0] = true;
                        Log.e(TAG, "TIMEOUT");
                        cb.onResult(null);
                        cleanup(wm, wv, attached);
                    }
                }, TIMEOUT_MS);

            } catch (Throwable e) {
                Log.e(TAG, "crashed", e);
                if (!done[0]) { done[0] = true; cb.onResult(null); }
                cleanup(wm, wv, attached);
            }
        });
    }

    private void extractAndFinish(WebView wv, String number,
                                   boolean[] done,
                                   WindowManager wm, boolean[] attached,
                                   Callback cb) {
        String js = "(function(){return document.body?(document.body.innerText||''):'NO_BODY';})();";
        wv.evaluateJavascript(js, val -> {
            if (done[0]) return;
            done[0] = true;
            Log.d(TAG, "CARD_RAW=" + val);
            String result = parseCard(val, number);
            Log.d(TAG, "PARSED=" + result);
            CacheStore.put(number, result != null ? result : "");
            cb.onResult(result);
            cleanup(wm, wv, attached);
        });
    }

    private void cleanup(WindowManager wm, WebView wv, boolean[] attached) {
        try { wv.stopLoading(); wv.loadUrl("about:blank"); } catch (Exception ignored) {}
        try {
            if (attached[0]) { wm.removeViewImmediate(wv); attached[0] = false; }
        } catch (Exception ignored) {}
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { wv.clearHistory(); wv.removeAllViews(); wv.destroy(); } catch (Exception ignored) {}
            Log.d(TAG, "cleanup done");
        }, 200);
        // 查询结束，解除蜂窝网络强绑定，恢复系统默认网络选择
        releaseCellularBinding();
    }

    private static final String HEADER_MARKER = "搜号码、企业客服、维修电话、公共热线";

    private String parseCard(String jsVal, String number) {
        if (jsVal == null || jsVal.isEmpty()) return null;

        String t = jsVal
                .replace("\\n", "\n")
                .replace("\\t", " ")
                .replace("\\\"", "")
                .replace("\"", "")
                .trim();

        if (t.contains("NO_BODY") || t.length() < 5) return null;

        int headerEnd = t.indexOf(HEADER_MARKER);
        if (headerEnd >= 0) t = t.substring(headerEnd + HEADER_MARKER.length());

        int stopPos = t.indexOf("更多服务");
        if (stopPos > 0) t = t.substring(0, stopPos);

        boolean isEnterprise = t.contains("网络收录号码") || t.contains("官方号码") || t.contains("网络收录数据") || t.contains("百度认证号码");
        int numPos = t.indexOf(number);

        String card;
        if (isEnterprise) {
            String before = (numPos >= 0) ? t.substring(0, numPos) : t;
            String after  = (numPos >= 0) ? t.substring(numPos + number.length()) : "";
            if (after.length() > 50) after = after.substring(0, 50);
            card = (before.trim() + " " + after.trim()).trim();
        } else {
            card = (numPos >= 0) ? t.substring(numPos + number.length()) : t;
        }
        card = card.replaceAll("\\s+", " ").trim();
        Log.d(TAG, "CARD=" + card);

        // 企业/官方页：按行提取企业名（官方号码/网络收录号码前一个多字行）
        if (isEnterprise) {
            String[] cardLines = t.split("\n");
            String enterpriseName = null;
            for (int li = 0; li < cardLines.length; li++) {
                String ln = cardLines[li].trim();
                if ("官方号码".equals(ln) || "网络收录号码".equals(ln) || "网络收录数据".equals(ln) || "百度认证号码".equals(ln)) {
                    for (int lj = li - 1; lj >= 0; lj--) {
                        String cand = cardLines[lj].trim();
                        if (cand.length() > 1 && !cand.startsWith("http")) {
                            enterpriseName = cand;
                            break;
                        }
                    }
                    break;
                }
            }
            if (enterpriseName != null) {
                // 在"号码信息"列表里找被查号码对应的功能描述
                // 支持两种情况：
                //   1. 主号码直接查询（号码在列表第一位，后面是本号描述）
                //   2. 子号码查询（被查号码在列表中间，找到它紧跟的描述行）
                String sub = null;
                String[] allLines = t.split("\n");  // t 里是真实换行符
                boolean inNumInfo = false;
                for (int ai = 0; ai < allLines.length; ai++) {
                    String aln = allLines[ai].trim();
                    if ("号码信息".equals(aln)) { inNumInfo = true; continue; }
                    if (inNumInfo && aln.equals(number)) {
                        if (ai + 1 < allLines.length) {
                            String cand = allLines[ai + 1].trim();
                            // 过滤无意义词：纯数字、单字、孤立通用词
                            boolean meaningless = cand.matches("[\\d]+")
                                    || cand.length() <= 1
                                    || "电话".equals(cand)
                                    || "服务".equals(cand)
                                    || "热线".equals(cand)
                                    || "客服".equals(cand);
                            if (!meaningless) {
                                sub = cand;
                            }
                        }
                        break;
                    }
                }
                String result = (sub != null) ? enterpriseName + " / " + sub : enterpriseName;
                Log.d(TAG, "ENTERPRISE_NAME=" + result);
                return result;
            }
        }

        String[] tagWords = {
                "广告营销", "商业营销", "广告推销",
                "贷款推销", "金融推销", "教育推销", "装修推销",
                "快递物流", "外卖送餐", "快递外卖",
                "骚扰电话", "疑似诈骗", "诈骗电话",
                "房产中介", "保险推销", "催收电话",
                "政府机构", "公益热线", "政务服务",
        };

        String foundTag = null;
        for (String tag : tagWords) {
            if (card.contains(tag)) { foundTag = normalizeTag(tag); break; }
        }
        if (foundTag == null && isEnterprise) {
            if (card.contains("客服")) foundTag = "客服热线";
            else if (card.contains("官方号码") || card.contains("政府机构") || card.contains("司法")
                    || card.contains("公安") || card.contains("法院") || card.contains("税务")
                    || card.contains("社保") || card.contains("医保") || card.contains("教育局")
                    || card.contains("热线")) foundTag = "官方热线";
        }

        String province = isEnterprise ? null : findFirst(card, new String[]{
                "北京","天津","上海","重庆","河北","山西","辽宁","吉林","黑龙江",
                "江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","海南",
                "四川","贵州","云南","陕西","甘肃","青海","台湾","内蒙古","广西","西藏","宁夏","新疆","香港","澳门"
        });
        String city = isEnterprise ? null : findCity(card);

        String carrier = null;
        if      (card.contains("中国移动")) carrier = "中国移动";
        else if (card.contains("中国联通")) carrier = "中国联通";
        else if (card.contains("中国电信")) carrier = "中国电信";

        if (foundTag != null) {
            StringBuilder sb = new StringBuilder(foundTag);
            if (province != null) sb.append(" ").append(province);
            if (city != null && !city.equals(province)) sb.append(" ").append(city);
            if (carrier != null) sb.append(" ").append(carrier);
            return sb.toString().trim();
        }
        if (province != null || city != null || carrier != null) {
            StringBuilder sb = new StringBuilder();
            if (province != null) sb.append(province);
            if (city != null && !city.equals(province)) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(city);
            }
            if (carrier != null) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(carrier);
            }
            return sb.toString().trim();
        }
        if (number.startsWith("400") || number.startsWith("800")) return "企业服务号";
        return "";
    }

    private String findFirst(String text, String[] words) {
        for (String w : words) { if (text.contains(w)) return w; }
        return null;
    }

    private String findCity(String text) {
        String[] cities = {
                "广州","深圳","珠海","佛山","东莞","中山","惠州","汕头","汕尾",
                "江门","肇庆","清远","韶关","梅州","潮州","揭阳","阳江","茂名","湛江",
                "北京","上海","天津","重庆","杭州","宁波","温州","苏州","南京","无锡","常州","镇江","扬州","徐州",
                "武汉","长沙","郑州","成都","西安","昆明","贵阳","南宁","海口","三亚",
                "福州","厦门","南昌","济南","青岛","沈阳","大连","长春","哈尔滨",
                "石家庄","太原","合肥","兰州","西宁","银川","呼和浩特","乌鲁木齐","拉萨",
                "烟台","唐山","保定","洛阳","南阳"
        };
        for (String c : cities) { if (text.contains(c)) return c; }
        return null;
    }

    private String normalizeTag(String tag) {
        if ("快递外卖".equals(tag) || "外卖送餐".equals(tag)) return "快递物流";
        if ("诈骗电话".equals(tag)) return "疑似诈骗";
        if ("广告推销".equals(tag) || "广告营销".equals(tag)) return "广告营销";
        if ("贷款推销".equals(tag) || "金融推销".equals(tag)) return "贷款推销";
        return tag;
    }
}
