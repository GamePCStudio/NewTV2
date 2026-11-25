package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 「查看全部」静态堆叠按钮。
 *
 * <p><b>几何</b>：整个控件就是示意图里的虚线框，尺寸与同行海报的画框一致（由 Presenter 写入，
 * 高度 = 画框高 + 片名区高，保证整卡等高）。三层海报都落在这个框内：
 * 基准卡宽 = 框宽 - 2×横向偏移，基准卡高按海报比例求得 = 框高 - 2×纵向偏移；
 * 后景 (0,0)、中景 (ox,oy)、前景 (2ox,2oy)，即后景左上贴框左上、前景右下贴框右下。
 * 偏移按「当前框宽 ÷ 最大卡宽」等比缩放，各档错位比例恒定，小档位也不会被偏移吃掉。</p>
 *
 * <p><b>圆角</b>：以左侧海报的 16dp 圆角为基准，按每层海报实际宽度相对画框宽度的缩放比例
 * 重新换算（前景 × baseW/框宽，中景 × 0.93×baseW/框宽，后景 × 0.86×baseW/框宽），
 * 三层圆角各不相同，保证缩小后的圆角占卡片比例与左侧海报视觉等效；
 * 不直接沿用缩小前的 16dp 固定值。前景的压暗层与海报共用同一缩放圆角。</p>
 *
 * <p><b>层级</b>：三卡均 0° 正向，层次只由位置、尺寸（1 / 1-step / 1-2step）、
 * elevation（24/14/8dp）与整层 opacity（1 / 0.48 / 0.26，作用于整层 View）构成，
 * 添加顺序由远到近。
 * 位置与尺寸直接写进 LayoutParams，不使用 translation/scale，避免被焦点动画覆盖或丢失。</p>
 *
 * <p><b>前景</b>：前景卡外再包一层容器，叠半透明圆角压暗层与「查看全部」文案。</p>
 *
 * <p><b>选中框</b>：{@code selector_vod} 白描边挂在 {@code layers} 容器上（作为 foreground），
 * 容器尺寸收敛到画框区域 (0,0)–(框宽,框高)。因 2×偏移 + 基准卡宽/高 = 框宽/框高，
 * 描边右/下边界正好贴合顶层海报的右/下边缘，左/上边界贴合底部海报的左/上边缘。</p>
 *
 * <p><b>焦点</b>：本控件不再自行处理焦点动画，由 Presenter 调用
 * {@link PosterFocusAnimator#bind(View, boolean)} 统一提供聚焦缩放 + 白描边 + 120ms 反馈，
 * 因此选中 / 未选中的视觉与行为与左侧海报卡完全一致。</p>
 */
public class PosterStackView extends FrameLayout {

    private final FrameLayout layers;
    private final RoundedPosterView back;
    private final RoundedPosterView middle;
    private final FrameLayout frontWrap;
    private final View scrim;
    private final TextView label;
    private final List<CustomTarget<Bitmap>> targets = new ArrayList<>();

    private int cardMaxWidth;
    private float cardRatio;
    private int cornerRadius;
    private int textBlockHeight;
    private int offsetXStep;
    private float scaleStep;
    private float alphaMiddle;
    private float alphaBack;
    private float elevationFront;
    private float elevationMiddle;
    private float elevationBack;
    private String labelText;

    private int baseW;
    private int baseH;
    /** 画框宽度（= 左侧海报画框宽），用于按缩放比例换算各层圆角。 */
    private int frameWidth;

    public PosterStackView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        setClickable(true);
        readAttrs(context, attrs);

        layers = new FrameLayout(context);
        layers.setClipChildren(false);
        layers.setClipToPadding(false);
        // 选中框框住整个画框区域：右/下贴顶层海报，左/上贴底部海报；foreground 绘制在三层之上。
        layers.setForeground(ContextCompat.getDrawable(context, R.drawable.selector_vod));
        layers.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(layers);

        back = createLayer(context);
        middle = createLayer(context);
        layers.addView(back, layerParams());
        layers.addView(middle, layerParams());

        frontWrap = new FrameLayout(context);
        frontWrap.setClipChildren(false);
        frontWrap.addView(createLayer(context), new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        scrim = new View(context);
        GradientDrawable scrimBg = new GradientDrawable();
        scrimBg.setColor(0x8C000000);
        scrimBg.setCornerRadius(cornerRadius);
        scrim.setBackground(scrimBg);
        frontWrap.addView(scrim, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        label = new TextView(context);
        label.setText(labelText);
        label.setTextColor(ContextCompat.getColor(context, R.color.stack_text_primary));
        // 字号以 sp 设置：从 dimen 的 14sp 反解出 sp 数值再按 COMPLEX_UNIT_SP 应用，
        // 使其随系统字体缩放（此前先转 px 再按 PX 设置，等于写死像素、不响应字体缩放）。
        float labelSp = context.getResources().getDimension(R.dimen.stack_label_size)
                / context.getResources().getDisplayMetrics().scaledDensity;
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp);
        label.setAlpha(readFloatResource(context.getResources(), R.dimen.stack_label_alpha, 0.7f));
        label.setGravity(Gravity.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        frontWrap.addView(label, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        layers.addView(frontWrap, layerParams());

        if (getContentDescription() == null) setContentDescription(labelText);
    }

    private RoundedPosterView createLayer(@NonNull Context context) {
        RoundedPosterView view = new RoundedPosterView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // 圆角不在构造期写死：onMeasure 中按该层实际宽度相对画框的比例换算后设置。
        return view;
    }

    private static LayoutParams layerParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    /** 前景卡：描边框住的那一层，用于读取实际尺寸给图片加载采样。 */
    private RoundedPosterView frontImage() {
        return (RoundedPosterView) frontWrap.getChildAt(0);
    }

    /**
     * 画框区域（三层海报 + 白描边所在容器），尺寸与同行海报卡的 {@code R.id.poster} 完全一致。
     *
     * <p>「查看全部」堆叠按钮内部没有 {@code R.id.poster}，首页整页背景光的圆心校正
     * 需要用这个画框来取几何，否则光晕会沿父链升到行容器、误命中该行第一张海报。</p>
     */
    public View getGlowFrame() {
        return layers;
    }

    private void readAttrs(@NonNull Context context, @Nullable AttributeSet attrs) {
        Resources res = context.getResources();
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.PosterStackView);
        try {
            cardMaxWidth = a.getDimensionPixelSize(R.styleable.PosterStackView_psvCardMaxWidth,
                    res.getDimensionPixelSize(R.dimen.stack_card_max_width));
            cardRatio = a.getFloat(R.styleable.PosterStackView_psvCardRatio,
                    readFloatResource(res, R.dimen.stack_card_ratio, 1.3294f));
            cornerRadius = a.getDimensionPixelOffset(R.styleable.PosterStackView_psvCornerRadius,
                    res.getDimensionPixelOffset(R.dimen.stack_corner_radius));
            textBlockHeight = res.getDimensionPixelOffset(R.dimen.stack_poster_text_block);
            offsetXStep = a.getDimensionPixelSize(R.styleable.PosterStackView_psvOffsetXStep,
                    res.getDimensionPixelSize(R.dimen.stack_offset_x_step));
            scaleStep = a.getFloat(R.styleable.PosterStackView_psvScaleStep,
                    readFloatResource(res, R.dimen.stack_scale_step, 0.07f));
            alphaMiddle = a.getFloat(R.styleable.PosterStackView_psvAlphaMiddle,
                    readFloatResource(res, R.dimen.stack_alpha_middle, 0.48f));
            alphaBack = a.getFloat(R.styleable.PosterStackView_psvAlphaBack,
                    readFloatResource(res, R.dimen.stack_alpha_back, 0.26f));
            elevationFront = a.getDimension(R.styleable.PosterStackView_psvElevationFront,
                    res.getDimension(R.dimen.stack_elevation_front));
            elevationMiddle = a.getDimension(R.styleable.PosterStackView_psvElevationMiddle,
                    res.getDimension(R.dimen.stack_elevation_middle));
            elevationBack = a.getDimension(R.styleable.PosterStackView_psvElevationBack,
                    res.getDimension(R.dimen.stack_elevation_back));
            labelText = a.getString(R.styleable.PosterStackView_psvLabel);
            if (labelText == null) labelText = res.getString(R.string.home_board_more_title);
        } finally {
            a.recycle();
        }
    }

    static float readFloatResource(@NonNull Resources res, int id, float fallback) {
        TypedValue value = new TypedValue();
        res.getValue(id, value, true);
        return value.type == TypedValue.TYPE_FLOAT ? value.getFloat() : fallback;
    }

    /**
     * 画框高宽比（h / w）。海报比例跟随「图片尺寸」所在页面的样式走，
     * 不能写死：海报从 3:4 改成 2:3 后，若这里仍是 1.3294，三层海报会缩在画框里、
     * 下方留出一截空白，白框就不再贴合海报了。
     */
    public void setCardRatio(float ratio) {
        if (ratio <= 0f || Math.abs(ratio - cardRatio) < 0.001f) return;
        cardRatio = ratio;
        requestLayout();
    }

    /**
     * 焦点描边来自 {@code selector_vod}，它挂在 layers 容器上并响应 selected 状态。
     * PosterFocusAnimator 只会把选中态设到控件根，这里同步给容器，描边才会亮起。
     */
    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        if (layers != null) layers.setSelected(selected);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = measureDimension(widthMeasureSpec, cardMaxWidth);
        int h = measureDimension(heightMeasureSpec, Math.round(cardMaxWidth * cardRatio));
        int areaW = Math.max(1, w);
        int areaH = Math.max(1, h - Math.min(textBlockHeight, Math.max(0, h / 3)));
        frameWidth = areaW;

        // layers 收敛到画框区域，其 foreground 选中框四边随之贴合三层堆叠的外轮廓
        ViewGroup.LayoutParams layersLp = layers.getLayoutParams();
        layersLp.width = areaW;
        layersLp.height = areaH;

        // 偏移按当前框宽相对最大卡宽等比缩放；纵向偏移用海报比例换算，保证三层不变形。
        float r = Math.min(1f, areaW / (float) cardMaxWidth);
        int ox = Math.max(1, Math.round(offsetXStep * r));
        int oy = Math.max(1, Math.round(ox * cardRatio));
        baseW = Math.max(1, areaW - 2 * ox);
        baseH = Math.max(1, Math.min(areaH - 2 * oy, Math.round(baseW * cardRatio)));

        int backW = Math.round(baseW * (1 - 2 * scaleStep));
        int midW = Math.round(baseW * (1 - scaleStep));
        place(back, 0, 0, backW, Math.round(baseH * (1 - 2 * scaleStep)), elevationBack, alphaBack);
        place(middle, ox, oy, midW, Math.round(baseH * (1 - scaleStep)), elevationMiddle, alphaMiddle);
        place(frontWrap, 2 * ox, 2 * oy, baseW, baseH, elevationFront, 1f);

        // 前景压暗层与前景海报共用同一缩放圆角
        int frontRadius = scaledRadius(baseW);
        frontImage().setCornerRadius(frontRadius);
        ((GradientDrawable) scrim.getBackground()).setCornerRadius(frontRadius);

        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    /** 以左侧海报圆角为基准，按该层海报实际宽度相对画框的比例换算圆角。 */
    private int scaledRadius(int layerWidth) {
        if (frameWidth <= 0) return cornerRadius;
        return Math.max(1, Math.round(cornerRadius * layerWidth / (float) frameWidth));
    }

    private void place(View view, int left, int top, int width, int height, float elevation, float alpha) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        lp.width = width;
        lp.height = height;
        LayoutParams mlp = (LayoutParams) lp;
        mlp.leftMargin = left;
        mlp.topMargin = top;
        view.setElevation(elevation);
        // opacity 作用于整层（底色、画面、高光、描边、阴影一起淡化），三层差异才明显。
        // 早期只淡化画面 Paint，被不透明的底色与高光盖住，看起来三层都是同一透明度。
        view.setAlpha(alpha);
        if (view instanceof RoundedPosterView) {
            // 圆角随该层缩放比例同比缩小，避免缩小后圆角占比偏大
            ((RoundedPosterView) view).setCornerRadius(scaledRadius(width));
        }
    }

    private static int measureDimension(int spec, int desired) {
        int mode = MeasureSpec.getMode(spec);
        int size = MeasureSpec.getSize(spec);
        if (mode == MeasureSpec.EXACTLY) return size;
        if (mode == MeasureSpec.AT_MOST) return Math.min(desired, size);
        return desired;
    }

    /** 绑定本地直角海报资源，顺序为 [后景, 中景, 前景]。 */
    public void setPosters(@Nullable List<Integer> imageResIds) {
        clearTargets();
        Bitmap[] pics = new Bitmap[3];
        if (imageResIds != null) {
            Resources res = getResources();
            for (int i = 0; i < 3 && i < imageResIds.size(); i++) {
                Integer id = imageResIds.get(i);
                if (id == null || id == 0) continue;
                try {
                    pics[i] = android.graphics.BitmapFactory.decodeResource(res, id);
                } catch (Throwable ignored) {
                    // 单张解码失败保留底色卡片
                }
            }
        }
        applyBitmaps(pics);
    }

    /** 绑定网络海报地址，顺序为 [后景, 中景, 前景]。 */
    public void setPosterUrls(@Nullable List<String> urls) {
        clearTargets();
        if (urls == null || urls.isEmpty()) return;
        int sampleW = Math.max(1, baseW > 0 ? baseW : cardMaxWidth);
        int sampleH = Math.max(1, baseH > 0 ? baseH : Math.round(cardMaxWidth * cardRatio));
        RoundedPosterView[] layersView = {back, middle, frontImage()};
        for (int i = 0; i < 3; i++) {
            String url = i < urls.size() ? urls.get(i) : null;
            if (url == null || url.isEmpty()) continue;
            final RoundedPosterView target = layersView[i];
            CustomTarget<Bitmap> holder = new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                    target.setPosterBitmap(resource);
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {
                }
            };
            targets.add(holder);
            try {
                // Application 上下文：Activity 销毁时 Glide.with(view) 会抛异常
                Glide.with(App.get()).asBitmap().load(ImgUtil.getUrl(url))
                        .override(sampleW, sampleH).into(holder);
            } catch (Throwable ignored) {
                // 加载失败保留底色卡片
            }
        }
    }

    private void applyBitmaps(Bitmap[] pics) {
        back.setPosterBitmap(pics[0]);
        middle.setPosterBitmap(pics[1]);
        frontImage().setPosterBitmap(pics[2]);
    }

    /** Presenter 解绑时调用：取消在途请求并释放位图，避免复用串图。 */
    public void clearPosters() {
        clearTargets();
        applyBitmaps(new Bitmap[3]);
    }

    private void clearTargets() {
        for (CustomTarget<Bitmap> holder : targets) {
            try {
                Glide.with(App.get()).clear(holder);
            } catch (Throwable ignored) {
                // Activity 已销毁等场景直接忽略
            }
        }
        targets.clear();
    }

    @Override
    protected void onDetachedFromWindow() {
        clearTargets();
        super.onDetachedFromWindow();
    }
}
