package com.fongmi.android.tv.utils;

import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.fongmi.android.tv.setting.NoticeSetting;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 框架层 Toast 入队拦截：纯 Java，不 hook 任何 ART 方法、不含 native 库。
 *
 * <p>背景：站源本地代理在 native 侧直接调用 {@code Toast.makeText(...).show()}，
 * 绕过 {@link Notify} 等应用内全部提示出口。但所有 Toast 最终都要经过
 * {@code Toast.sService} 这个静态的 {@code INotificationManager}，调用
 * {@code enqueueTextToast(pkg, token, text, ...)}（API 30+ 文本 Toast）或
 * {@code enqueueToast(...)}（自定义 View Toast）交给 SystemUI 渲染。</p>
 *
 * <p>做法：用 HiddenApiBypass 精准放行框架类的隐藏成员，把 {@code sService}
 * 换成一层动态代理，在入队前按 {@link NoticePolicy#shouldBlockNotice(CharSequence)}
 * 判定文本；命中就不转发，系统收不到入队请求自然不会绘制，其它调用原样转发。
 * 判定每次实时读取设置，「启用提示精简 / 过滤站源播报 / 过滤关键字」改完立即生效。</p>
 *
 * <p>与 ART hook 方案的区别：不需要解析 libart.so 符号，在 MuMu 这类 ART 被改过的
 * 环境也能稳定加载。失败安全：豁免、字段替换或代理构造任一步失败都静默禁用，
 * 不抛异常、不影响启动与站源加载；总开关关闭时不安装，行为与改动前一致。</p>
 */
public final class ToastGuard {

    private static final String TAG = "ToastGuard";

    /** 只放行本功能需要的框架类，不全局放宽 hidden API 策略。 */
    private static final String[] EXEMPTIONS = {
            "Landroid/widget/Toast;",
            "Landroid/widget/Toast$TN;"
    };

    private static volatile boolean installed = false;
    private static volatile boolean attempted = false;

    private ToastGuard() {
    }

    public static boolean isInstalled() {
        return installed;
    }

    /** Application.onCreate 调用一次；总开关重新打开时可再次调用补装。 */
    public static synchronized void install() {
        if (installed || attempted) return;
        if (!NoticeSetting.isMasterEnabled()) return;
        attempted = true;
        try {
            boolean exempted = HiddenApiBypass.addHiddenApiExemptions(EXEMPTIONS);
            Log.i(TAG, "exempted=" + exempted);
            Field field = findServiceField();
            if (field == null) {
                Log.i(TAG, "install skipped: sService not found");
                return;
            }
            field.setAccessible(true);
            Class<?> iface = field.getType();
            Log.i(TAG, "stepA iface=" + iface.getName());
            Object original = field.get(null);
            if (original == null) {
                Method getService = Toast.class.getDeclaredMethod("getService");
                getService.setAccessible(true);
                original = getService.invoke(null);
            }
            if (original == null) {
                Log.i(TAG, "install skipped: notification service not found");
                return;
            }
            Log.i(TAG, "stepB original=" + original.getClass().getName());
            final Object target = original;
            Object proxy = Proxy.newProxyInstance(
                    ToastGuard.class.getClassLoader(),
                    new Class<?>[]{iface},
                    new GuardHandler(target));
            field.set(null, proxy);
            installed = true;
            Log.i(TAG, "installed api=" + Build.VERSION.SDK_INT);
        } catch (Throwable e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            Log.i(TAG, "install failed: " + e + " root=" + cause);
        }
    }

    /** 先走普通反射；个别 ROM 上过滤更严时，用豁免库直接枚举静态字段。 */
    private static Field findServiceField() {
        try {
            return Toast.class.getDeclaredField("sService");
        } catch (Throwable ignored) {
        }
        try {
            for (Field f : HiddenApiBypass.getStaticFields(Toast.class)) {
                if ("sService".equals(f.getName())) return f;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final class GuardHandler implements InvocationHandler {

        private final Object original;

        private GuardHandler(Object original) {
            this.original = original;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Object[] safeArgs = args == null ? new Object[0] : args;
            try {
                if ("enqueueTextToast".equals(name) && safeArgs.length > 2) {
                    Object text = safeArgs[2];
                    if (text instanceof CharSequence && NoticePolicy.shouldBlockNotice((CharSequence) text)) {
                        Log.i(TAG, "blocked text toast: " + text);
                        return defaultValue(method.getReturnType());
                    }
                } else if ("enqueueToast".equals(name) && safeArgs.length > 2) {
                    CharSequence text = findText(safeArgs[2]);
                    if (!TextUtils.isEmpty(text) && NoticePolicy.shouldBlockNotice(text)) {
                        Log.i(TAG, "blocked view toast: " + text);
                        return defaultValue(method.getReturnType());
                    }
                }
                return method.invoke(original, safeArgs);
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }

    /** Android 14/15 的入队方法返回 boolean（是否已入队），旧版本是 void。 */
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        return null;
    }

    /** 自定义 View Toast 从 TN 里取 View，再递归找第一个有文字的 TextView。 */
    private static CharSequence findText(Object tn) {
        if (tn == null) return null;
        try {
            View view = null;
            for (String name : new String[]{"mNextView", "mView"}) {
                try {
                    Field f = tn.getClass().getDeclaredField(name);
                    f.setAccessible(true);
                    Object value = f.get(tn);
                    if (value instanceof View) view = (View) value;
                } catch (Throwable ignored) {
                }
            }
            if (view == null) {
                Field f = tn.getClass().getDeclaredField("mNextViewWeakRef");
                f.setAccessible(true);
                Object ref = f.get(tn);
                if (ref instanceof WeakReference) view = (View) ((WeakReference<?>) ref).get();
            }
            return view == null ? null : firstText(view);
        } catch (Throwable e) {
            return null;
        }
    }

    private static CharSequence firstText(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (!TextUtils.isEmpty(text)) return text;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CharSequence text = firstText(group.getChildAt(i));
                if (!TextUtils.isEmpty(text)) return text;
            }
        }
        return null;
    }
}
