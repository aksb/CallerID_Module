package com.callerid.module;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 只监听 127.0.0.1 的极简本地 HTTP 查询服务（v3.19 新增，"结合 SpamBlocker 拦截器"功能）。
 *
 * 用途：SpamBlocker 的"即时查询"引擎在来电时会主动发一个 HTTP 请求过来，
 * 本服务复用 WebQueryHelper 的查询链路（自定义库/缓存/白名单/内置库/联网查询百度）
 * 拿到号码标签，再按用户配置的关键词表判断 is_spam，把结果拼成 JSON 返回。
 * 方向是"SpamBlocker 主动来问，本模块被动回答"，本模块自己不会主动发起任何请求。
 *
 * 请求格式：GET /query?number=13800138000&token=xxxx
 * 响应格式：200 + {"number":"13800138000","label":"商业营销","is_spam":true}
 *          401（token 不对）/ 400（缺 number）/ 200 但 label 为 null（未查到，is_spam=false）
 *
 * 只监听回环地址，外部设备/网络访问不到；同设备上的其它 App 理论上可以打到这个端口，
 * 所以额外加了 token 鉴权这一层，防止本机其它恶意 App 冒充 SpamBlocker 骚扰查询接口
 * （虽然查询接口本身不算敏感数据，但鉴权几乎零成本，加上更安心）。
 *
 * 查询本身要跑在主线程（WebQueryHelper 内部用到 Handler(Looper.getMainLooper())
 * 和 WebView，都要求主线程），而这个类是在独立线程里跑 accept 循环，所以用
 * CountDownLatch 把"主线程查询结果"同步回工作线程，并且加超时保护——SpamBlocker
 * 官方文档里"即时查询"整个决策窗口大约 4~5 秒，超时不返回的话 SpamBlocker 会按
 * "未识别"处理（不会误拦截，只是这一次没生效），所以默认超时给 4000ms，略小于
 * 那个决策窗口，确保能来得及把"超时"这个结果本身返回回去。
 */
public class LocalQueryServer {

    private static final String TAG = "CallerID_LocalQueryServer";

    private final Context appContext;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public LocalQueryServer(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 启动服务，绑定到 127.0.0.1:port。已经在运行时调用无效果（先 stop 再 start 才能换端口）。 */
    public synchronized void start(int port) {
        if (running.get()) {
            Log.d(TAG, "start() ignored, already running");
            return;
        }
        try {
            serverSocket = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            Log.e(TAG, "bind failed on port " + port, e);
            serverSocket = null;
            return;
        }
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "CallerID-LocalQueryServer-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.d(TAG, "started on 127.0.0.1:" + port);
    }

    public synchronized void stop() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        serverSocket = null;
        Log.d(TAG, "stopped");
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                // running=false 时 stop() 会主动 close serverSocket，这里的异常是正常退出路径
                if (running.get()) Log.e(TAG, "accept() failed", e);
                break;
            }
            // 每个连接单独开线程处理，避免一个慢查询卡住后续请求；本地场景并发量极低，
            // 不需要线程池。
            Thread worker = new Thread(() -> handleConnection(socket), "CallerID-LocalQueryServer-worker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(8000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                writeResponse(socket, 400, "{\"error\":\"empty request\"}");
                return;
            }
            Log.d(TAG, "request: " + requestLine);

            // 消费掉剩余请求头，直到空行；GET 请求没有 body，不用处理
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // 忽略 header 内容
            }

            // 请求行形如："GET /query?number=138xxx&token=yyy HTTP/1.1"
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equalsIgnoreCase(parts[0])) {
                writeResponse(socket, 400, "{\"error\":\"only GET /query is supported\"}");
                return;
            }
            String pathAndQuery = parts[1];
            String path = pathAndQuery;
            String query = "";
            int qIdx = pathAndQuery.indexOf('?');
            if (qIdx >= 0) {
                path = pathAndQuery.substring(0, qIdx);
                query = pathAndQuery.substring(qIdx + 1);
            }
            if (!"/query".equals(path)) {
                writeResponse(socket, 404, "{\"error\":\"not found\"}");
                return;
            }

            String number = null;
            String token = null;
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq < 0) continue;
                String k = urlDecode(kv.substring(0, eq));
                String v = urlDecode(kv.substring(eq + 1));
                if ("number".equals(k)) number = v;
                else if ("token".equals(k)) token = v;
            }

            String expectedToken = ModuleSettings.getSbToken(appContext);
            if (token == null || !token.equals(expectedToken)) {
                writeResponse(socket, 401, "{\"error\":\"invalid token\"}");
                return;
            }
            if (number == null || number.trim().isEmpty()) {
                writeResponse(socket, 400, "{\"error\":\"missing number\"}");
                return;
            }
            number = number.trim();

            String label = queryOnMainThreadBlocking(number);
            boolean isSpam = isSpamLabel(label);

            JSONObject body = new JSONObject();
            body.put("number", number);
            body.put("label", label); // null 会被 JSONObject 序列化成 JSON null
            body.put("is_spam", isSpam);
            writeResponse(socket, 200, body.toString());

        } catch (SocketTimeoutException e) {
            Log.e(TAG, "connection timeout", e);
        } catch (Exception e) {
            Log.e(TAG, "handleConnection error", e);
            try { writeResponse(socket, 500, "{\"error\":\"internal error\"}"); } catch (Exception ignored) {}
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 把查询请求 post 到主线程执行（WebQueryHelper 内部依赖主 Looper / WebView），
     * 用 CountDownLatch 同步等待结果，超过 ModuleSettings.getSbTimeoutMs() 就放弃等待，
     * 返回 null（未识别），不会无限阻塞把 SpamBlocker 的请求拖死。
     */
    private String queryOnMainThreadBlocking(String number) {
        CountDownLatch latch = new CountDownLatch(1);
        String[] resultHolder = new String[1];
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            try {
                new WebQueryHelper().query(appContext, number, result -> {
                    resultHolder[0] = result;
                    latch.countDown();
                });
            } catch (Exception e) {
                Log.e(TAG, "query() threw", e);
                latch.countDown();
            }
        });
        int timeoutMs = ModuleSettings.getSbTimeoutMs(appContext);
        try {
            boolean onTime = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!onTime) Log.d(TAG, "query timed out after " + timeoutMs + "ms, number=" + number);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return resultHolder[0];
    }

    /** 标签文本命中关键词表中任意一个词就判定为骚扰（大小写不敏感，中文关键词本身不受影响）。 */
    private boolean isSpamLabel(String label) {
        if (label == null || label.isEmpty()) return false;
        for (String kw : ModuleSettings.getSbKeywordsArray(appContext)) {
            if (label.contains(kw)) return true;
        }
        return false;
    }

    private void writeResponse(Socket socket, int statusCode, String jsonBody) throws IOException {
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        String statusText;
        switch (statusCode) {
            case 200: statusText = "OK"; break;
            case 400: statusText = "Bad Request"; break;
            case 401: statusText = "Unauthorized"; break;
            case 404: statusText = "Not Found"; break;
            default:  statusText = "Internal Server Error"; break;
        }
        String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
