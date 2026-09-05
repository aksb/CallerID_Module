package com.callerid.module;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;
import java.util.TreeMap;

/**
 * 用户自定义号码库
 *
 * 优先级最高：查询时最先检查这里，命中则直接返回，
 * 不再走联网缓存 / 内置数据库 / 联网查询。
 *
 * 持久化：SharedPreferences，key = "u_" + 号码，value = 用户填写的姓名/标签。
 * 与号码重复时，后写入的（用户最新一次添加/导入）会覆盖旧的。
 */
public class UserNumberStore {

    private static final String TAG  = "CallerID_UserDB";
    private static final String PREF = "user_numbers";

    private static SharedPreferences sp;

    public static synchronized void init(Context ctx) {
        if (sp == null) {
            sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }
    }

    /** 查用户自定义库；未命中返回 null */
    public static synchronized String get(String number) {
        if (sp == null || number == null) return null;
        return sp.getString("u_" + number, null);
    }

    /** 新增/覆盖一条 */
    public static synchronized void put(String number, String name) {
        if (sp == null || number == null || number.trim().isEmpty()
                || name == null || name.trim().isEmpty()) return;
        sp.edit().putString("u_" + number.trim(), name.trim()).apply();
        Log.d(TAG, "put: " + number + " -> " + name);
    }

    /** 删除一条 */
    public static synchronized void remove(String number) {
        if (sp == null || number == null) return;
        sp.edit().remove("u_" + number).apply();
        Log.d(TAG, "removed: " + number);
    }

    /** 批量删除 */
    public static synchronized void removeAll(java.util.Collection<String> numbers) {
        if (sp == null || numbers == null || numbers.isEmpty()) return;
        SharedPreferences.Editor editor = sp.edit();
        for (String n : numbers) editor.remove("u_" + n);
        editor.apply();
        Log.d(TAG, "removeAll: " + numbers.size() + " 条");
    }

    /** 返回 号码->姓名 的全部条目，按号码排序，便于列表展示 */
    public static synchronized Map<String, String> getAll() {
        Map<String, String> result = new TreeMap<>();
        if (sp == null) return result;
        Map<String, ?> all = sp.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getKey().startsWith("u_") && e.getValue() instanceof String) {
                result.put(e.getKey().substring(2), (String) e.getValue());
            }
        }
        return result;
    }

    public static synchronized int count() {
        return getAll().size();
    }
}
