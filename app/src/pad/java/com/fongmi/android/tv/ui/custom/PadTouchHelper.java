package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.R;

/**
 * 触控 Pad 的交互基座。只在 pad 源集编译，leanback/mobile 完全不包含此类。
 *
 * <p>设计约束：
 * <ol>
 *   <li>不改变页面层级与焦点链——热区用 {@link TouchDelegate} 外扩，不改控件尺寸；</li>
 *   <li>点击反馈用短促缩放 + vibrate，不改背景选择器（状态色由 selector_pad_* 统一负责）；</li>
 *   <li>长按与边缘滑动返回只绑定带 pad_touch 标记的控件/根视图，避免拦截列表滚动。</li>
 * </ol>
 */
public final class PadTouchHelper {

    private static final int LONG_PRESS_MS = 500;
    private static final int SLOP_DP = 8;
    private static final int EDGE_DP = 24;
    private static final float PRESSED_SCALE = 0.96f;
    private static final long PRESS_DURATION_MS = 100;

    private PadTouchHelper() {
    }

    /** 在 Activity setContentView 之后调用；只在 pad_touch=true 的变体生效。 */
    public static void install(@NonNull Activity activity) {
        View root = activity.findViewById(android.R.id.content);
        if (root != null) {
            expandTouchTargets(root);
            installEdgeBack(root, activity);
        }
    }

    /**
     * 把标记了 {@code android:tag="pad_expand"} 的控件的点击区外扩到 pad_touch_min，
     * 用父容器做 TouchDelegate，视觉尺寸与布局不变。
     */
    private static void expandTouchTargets(@NonNull View root) {
        if (!(root instanceof ViewGroup group)) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if ("pad_expand".equals(child.getTag()) && child.getParent() instanceof View parent) {
                parent.post(() -> {
                    Rect rect = new Rect();
                    child.getHitRect(rect);
                    int min = child.getResources().getDimensionPixelSize(R.dimen.pad_touch_min);
                    int growX = Math.max(0, (min - rect.width()) / 2);
                    int growY = Math.max(0, (min - rect.height()) / 2);
                    rect.inset(-growX, -growY);
                    parent.setTouchDelegate(new TouchDelegate(rect, child));
                });
            }
            expandTouchTargets(child);
        }
    }

    /** 绑定点击缩放与轻震动；返回 true 表示消费事件，交给调用方决定。 */
    @SuppressLint("ClickableViewAccessibility")
    public static void bindPressFeedback(@NonNull View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> animateScale(v, PRESSED_SCALE);
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateScale(v, 1f);
                default -> {
                }
            }
            return false;
        });
    }

    /** 通用长按监听：超过 500ms 且未滑动超过 8dp 时触发，返回 true 表示已消费。 */
    @SuppressLint("ClickableViewAccessibility")
    public static void bindLongClick(@NonNull View view, @NonNull Runnable onLongClick) {
        view.setOnTouchListener(new View.OnTouchListener() {
            private View host;
            private float startX;
            private float startY;
            private boolean pending = true;
            private final Runnable pendingAction = createLongPressAction();

            private Runnable createLongPressAction() {
                return () -> {
                    if (!pending || host == null) return;
                    vibrate(host, 20);
                    onLongClick.run();
                    pending = false;
                };
            }

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                host = v;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    startX = event.getX();
                    startY = event.getY();
                    pending = true;
                    v.removeCallbacks(pendingAction);
                    v.postDelayed(pendingAction, LONG_PRESS_MS);
                    return false;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    float slop = v.getResources().getDisplayMetrics().density * SLOP_DP;
                    if (Math.abs(event.getX() - startX) > slop || Math.abs(event.getY() - startY) > slop) pending = false;
                    return false;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    pending = false;
                    v.removeCallbacks(pendingAction);
                }
                return false;
            }
        });
    }

    /** 右边缘向内滑动返回，只在根视图边缘 24dp 内起手时生效，避免干扰横向列表。 */
    @SuppressLint("ClickableViewAccessibility")
    private static void installEdgeBack(@NonNull View root, @NonNull Activity activity) {
        root.setOnTouchListener(new View.OnTouchListener() {
            private float startX;
            private float startY;
            private boolean armed;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                float edge = v.getResources().getDisplayMetrics().density * EDGE_DP;
                if (action == MotionEvent.ACTION_DOWN) {
                    armed = event.getX() >= v.getWidth() - edge;
                    startX = event.getX();
                    startY = event.getY();
                    return false;
                }
                if (!armed) return false;
                if (action == MotionEvent.ACTION_MOVE) {
                    float dx = startX - event.getX();
                    float dy = Math.abs(event.getY() - startY);
                    if (dx > edge && dx > dy) {
                        armed = false;
                        vibrate(v, 10);
                        activity.onBackPressed();
                        return true;
                    }
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) armed = false;
                return false;
            }
        });
    }

    private static void animateScale(@NonNull View view, float to) {
        ValueAnimator animator = ValueAnimator.ofFloat(view.getScaleX(), to);
        animator.setDuration(PRESS_DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float value = (float) a.getAnimatedValue();
            view.setScaleX(value);
            view.setScaleY(value);
        });
        animator.start();
    }

    private static void vibrate(@NonNull View view, int milliseconds) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager = (VibratorManager) view.getContext().getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null) manager.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                Vibrator vibrator = (Vibrator) view.getContext().getSystemService(android.content.Context.VIBRATOR_SERVICE);
                if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {
            // 无震动马达的设备静默降级
        }
    }
}
