package com.fongmi.android.tv.utils;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Task;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

public final class Diag {

    private static final String ENDPOINT = "https://s.ctowl.cc.cd/v1/diag";
    private static final String SALT = "newtv2.diag.v1";
    private static final String KEY_INSTALL = "diag_install";
    private static final String KEY_LAST = "diag_last";
    private static final String KEY_VER = "diag_ver";
    private static final long INTERVAL_MS = 24L * 60 * 60 * 1000;

    private static volatile boolean sending;

    private Diag() {
    }

    public static void ping(Context context) {
        if (sending || TextUtils.isEmpty(ENDPOINT) || !Setting.isDiagAccepted()) return;
        long now = System.currentTimeMillis();
        String install = Prefers.getString(KEY_INSTALL, "");
        boolean first = TextUtils.isEmpty(install);
        if (!first && now - number(Prefers.getString(KEY_LAST, "0")) < INTERVAL_MS
                && number(Prefers.getString(KEY_VER, "0")) == BuildConfig.VERSION_CODE) {
            return;
        }
        if (first) {
            install = UUID.randomUUID().toString().replace("-", "");
            Prefers.put(KEY_INSTALL, install);
        }
        sending = true;
        Context app = context.getApplicationContext();
        String current = install;
        boolean initial = first;
        Task.executeSerial(() -> {
            try {
                if (send(payload(app, current, initial, now))) {
                    Prefers.put(KEY_LAST, String.valueOf(now));
                    Prefers.put(KEY_VER, String.valueOf(BuildConfig.VERSION_CODE));
                }
            } catch (Exception ignored) {
            } finally {
                sending = false;
            }
        });
    }

    private static String payload(Context context, String install, boolean first, long now) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return "{\"v\":1"
                + ",\"install\":\"" + install + "\""
                + ",\"device\":\"" + device(context) + "\""
                + ",\"ver\":\"" + BuildConfig.VERSION_NAME + "\""
                + ",\"code\":" + BuildConfig.VERSION_CODE
                + ",\"api\":" + Build.VERSION.SDK_INT
                + ",\"abi\":\"" + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "") + "\""
                + ",\"w\":" + metrics.widthPixels
                + ",\"h\":" + metrics.heightPixels
                + ",\"first\":" + (first ? 1 : 0)
                + ",\"ts\":" + now
                + "}";
    }

    private static String device(Context context) {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String raw = SALT + "|" + (androidId == null ? "" : androidId) + "|" + Build.FINGERPRINT + "|" + Build.MODEL;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8; i++) builder.append(String.format(Locale.US, "%02x", digest[i]));
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean send(String body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return connection.getResponseCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0;
        }
    }
}
