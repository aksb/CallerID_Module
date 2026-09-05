package com.callerid.module;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 号码查询结果缓存
 *
 * v1.4：双层缓存
 *  - 内存 LRU（运行期，500条，进程退出即失）
 *  - SharedPreferences 持久化（跨进程重启保留，无限期）
 *
 * 持久化 key 格式：num_XXXXXX → value 为查询结果字符串
 * 空结果（未知号码）也缓存，避免重复查询同一个无结果号码。
 */
public class CacheStore {

    private static final String TAG  = "CallerID_Cache";
    private static final String PREF = "num_cache";
    private static final int    MAX  = 500;

    // 内存 LRU
    @SuppressWarnings("unchecked")
    private static final Map<String, String> MEM =
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    return size() > MAX;
                }
            };

    private static SharedPreferences sp;

    /** 缓存有新查询结果写入时的回调，供主界面做实时刷新（v1.7 新增） */
    public interface OnCacheUpdatedListener {
        void onCacheUpdated();
    }

    private static OnCacheUpdatedListener listener;

    /** 注册/取消注册监听器。传 null 取消注册（Activity 应在 onPause 时取消，避免持有已销毁的 Activity）。 */
    public static synchronized void setOnCacheUpdatedListener(OnCacheUpdatedListener l) {
        listener = l;
    }

    public static synchronized void init(Context ctx) {
        if (sp == null) {
            sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }
    }

    /** 查缓存；先查内存，再查磁盘；null 表示无缓存（需查网络） */
    public static synchronized String get(String number) {
        String v = MEM.get(number);
        if (v != null) {
            Log.d(TAG, "MEM hit: " + number + " -> " + v);
            return v;
        }
        if (sp != null && sp.contains("num_" + number)) {
            v = sp.getString("num_" + number, null);
            if (v != null) MEM.put(number, v); // 回填内存
            Log.d(TAG, "DISK hit: " + number + " -> " + v);
            return v;
        }
        return null;
    }

    /**
     * 写缓存。result 可以是查询结果或 "" (未知)，均缓存以避免重查。
     * null 表示查询失败（网络错误），不缓存，下次重试。
     */
    public static synchronized void put(String number, String result) {
        if (result == null) return;
        String val = result.isEmpty() ? "" : result;
        MEM.put(number, val);
        if (sp != null) {
            sp.edit().putString("num_" + number, val).apply();
            Log.d(TAG, "CACHE put: " + number + " -> " + val);
        }
        if (listener != null) listener.onCacheUpdated();
    }

    /** 清除所有空串缓存（之前版本可能错误写入的无效条目） */
    public static synchronized void clearEmpty() {
        if (sp == null) return;
        SharedPreferences.Editor editor = sp.edit();
        java.util.Map<String, ?> all = sp.getAll();
        int count = 0;
        for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
            if (entry.getValue() instanceof String && ((String) entry.getValue()).isEmpty()) {
                editor.remove(entry.getKey());
                MEM.remove(entry.getKey().replace("num_", ""));
                count++;
            }
        }
        editor.apply();
        Log.d(TAG, "clearEmpty: removed " + count + " entries");
    }


    /** 删除指定号码的缓存（强制下次重查） */
    public static synchronized void remove(String number) {
        MEM.remove(number);
        if (sp != null) sp.edit().remove("num_" + number).apply();
        Log.d(TAG, "removed: " + number);
    }

    /** 批量删除（列表管理"删除选中"用） */
    public static synchronized void removeAll(java.util.Collection<String> numbers) {
        if (numbers == null || numbers.isEmpty()) return;
        for (String n : numbers) MEM.remove(n);
        if (sp != null) {
            SharedPreferences.Editor editor = sp.edit();
            for (String n : numbers) editor.remove("num_" + n);
            editor.apply();
        }
        Log.d(TAG, "removeAll: " + numbers.size() + " 条");
    }

    /** 清除全部缓存 */
    public static synchronized void clearAll() {
        MEM.clear();
        if (sp != null) sp.edit().clear().apply();
        Log.d(TAG, "clearAll done");
    }

    /** 导出全部持久化缓存条目（号码->查询结果），供"备份"功能使用。空结果("")条目会被跳过，没有备份价值。 */
    public static synchronized Map<String, String> getAll() {
        Map<String, String> result = new java.util.TreeMap<>();
        if (sp == null) return result;
        Map<String, ?> all = sp.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getKey().startsWith("num_") && e.getValue() instanceof String) {
                String v = (String) e.getValue();
                if (!v.isEmpty()) result.put(e.getKey().substring(4), v);
            }
        }
        return result;
    }

}