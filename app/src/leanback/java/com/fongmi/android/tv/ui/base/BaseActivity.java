package com.fongmi.android.tv.ui.base;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.utils.TouchUi;
import com.fongmi.android.tv.utils.Util;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import me.jessyan.autosize.AutoSizeCompat;

public abstract class BaseActivity extends AppCompatActivity {

    protected abstract ViewBinding getBinding();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getBinding().getRoot());
        // pad 变体专属触控基座；leanback/mobile 的 pad_touch=false，反射类不存在时安全跳过
        if (TouchUi.isTouch(this)) installPadTouch();
        EventBus.getDefault().register(this);
        initView(savedInstanceState);
        if (TouchUi.isTouch(this)) applyTouchMode();
        Util.hideSystemUI(this);
        setBackCallback();
        initEvent();
    }

    /**
     * Pad 触控模式：递归关掉非输入控件的焦点能力，屏蔽系统默认焦点高亮，
     * 并挂一个全局监听兜住异步 requestFocus（View 不可聚焦时请求会直接失败）。
     * 页面异步加载完成后再清一次，避免晚到的 requestFocus 留下残框。
     */
    private void applyTouchMode() {
        View root = getBinding().getRoot();
        clearNonInputFocus(root);
        stripFocus(root);
        root.post(() -> {
            clearNonInputFocus(root);
            stripFocus(root);
            View decor = getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
                if (newFocus == null || TouchUi.isInput(newFocus)) return;
                newFocus.post(() -> {
                    newFocus.clearFocus();
                    newFocus.setFocusable(false);
                    newFocus.setFocusableInTouchMode(false);
                });
            });
        });
    }

    private void stripFocus(View view) {
        if (view == null) return;
        if (!TouchUi.isInput(view)) {
            view.setFocusable(false);
            view.setFocusableInTouchMode(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) view.setDefaultFocusHighlightEnabled(false);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) stripFocus(group.getChildAt(i));
        }
    }

    private void clearNonInputFocus(View root) {
        View focused = root.findFocus();
        if (focused != null && !TouchUi.isInput(focused)) focused.clearFocus();
    }

    /** Pad 上没有方向键走位：吞掉方向键/Tab，输入框内保留光标移动与换行。 */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (TouchUi.isTouch(this) && event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN) {
            int keyCode = event.getKeyCode();
            boolean navigation = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_TAB;
            if (navigation) {
                View focused = getCurrentFocus();
                if (!TouchUi.isInput(focused)) return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    protected FragmentActivity getActivity() {
        return this;
    }

    protected void initView(Bundle savedInstanceState) {
    }

    protected void initEvent() {
    }

    protected boolean isVisible(View view) {
        return view.getVisibility() == View.VISIBLE;
    }

    protected boolean isGone(View view) {
        return view.getVisibility() == View.GONE;
    }

    protected void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        view.post(() -> adapter.notifyDataSetChanged());
    }

    private void installPadTouch() {
        try {
            Class<?> helper = Class.forName("com.fongmi.android.tv.ui.custom.PadTouchHelper");
            helper.getMethod("install", android.app.Activity.class).invoke(null, this);
        } catch (Exception ignored) {
            // pad 源集未参与编译时无此类，属于预期
        }
    }

    private void setBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackInvoked();
            }
        });
    }

    private Resources hackResources(Resources resources) {
        try {
            AutoSizeCompat.autoConvertDensityOfGlobal(resources);
            return resources;
        } catch (Exception ignored) {
            return resources;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSubscribe(Object o) {
    }

    @Override
    public Resources getResources() {
        return hackResources(super.getResources());
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    protected void onBackInvoked() {
        finish();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
