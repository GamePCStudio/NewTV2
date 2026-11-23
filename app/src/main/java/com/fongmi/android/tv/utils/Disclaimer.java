package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.DialogDisclaimerBinding;
import com.fongmi.android.tv.setting.Setting;

public final class Disclaimer {

    private static boolean showing;
    private static Activity host;
    private static Runnable pending;

    private Disclaimer() {
    }

    public static void check(Activity activity, Runnable onAgree) {
        if (onAgree != null) pending = onAgree;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (Setting.isDiagAccepted()) {
            Diag.ping(activity);
            drain();
            return;
        }
        activity.getWindow().getDecorView().post(() -> show(activity));
    }

    private static void show(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (Setting.isDiagAccepted()) {
            drain();
            return;
        }
        if (showing) {
            if (host != null && (host.isFinishing() || host.isDestroyed())) showing = false;
            else return;
        }
        showing = true;
        host = activity;
        DialogDisclaimerBinding binding = DialogDisclaimerBinding.inflate(LayoutInflater.from(activity));
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        ViewGroup.LayoutParams params = binding.declarationScroll.getLayoutParams();
        params.height = Math.min((int) (metrics.heightPixels * 0.46f), (int) (metrics.density * 420));
        binding.declarationScroll.setLayoutParams(params);
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(binding.getRoot());
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        binding.declarationAgree.setOnClickListener(view -> {
            Setting.putDiagAccepted(true);
            dialog.dismiss();
            Diag.ping(activity);
            drain();
        });
        binding.declarationDecline.setOnClickListener(view -> {
            Setting.putDiagAccepted(false);
            dialog.dismiss();
            exit(activity);
        });
        dialog.setOnDismissListener(d -> reset());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        if (window != null) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = Math.min((int) (metrics.widthPixels * 0.72f), (int) (metrics.density * 900));
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.dimAmount = 0.76f;
            window.setAttributes(attributes);
        }
        binding.declarationAgree.post(binding.declarationAgree::requestFocus);
    }

    private static void drain() {
        Runnable task = pending;
        pending = null;
        if (task != null) task.run();
    }

    private static void reset() {
        showing = false;
        host = null;
    }

    private static void exit(Activity activity) {
        activity.finishAffinity();
        App.post(() -> {
            Process.killProcess(Process.myPid());
            System.exit(0);
        });
    }
}
