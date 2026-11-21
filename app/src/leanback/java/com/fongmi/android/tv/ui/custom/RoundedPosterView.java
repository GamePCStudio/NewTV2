package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;

/**
 * 圆角海报 View：把<b>直角原图</b>在代码层真正裁成四角透明的海报。
 *
 * <p>绘制内容：海报画面（BitmapShader 裁圆角）+ 顶部高光渐变 + 1px 内描边，
 * <b>不绘制底色</b>，因此调用方对整层 View 设置的 alpha（0.48 / 0.26 / 1.0）
 * 会直接体现在画面上，三层透明度差异清晰可见。</p>
 *
 * <p>实现要点：</p>
 * <ol>
 *   <li>{@link BitmapShader} + {@code drawRoundRect} 在 {@link #onDraw(Canvas)} 中绘制，
 *       shader 的 localMatrix 按 centerCrop 等比放大，不拉伸变形、不依赖 Canvas.clipPath；</li>
 *   <li>同一个 View 用 {@link ViewOutlineProvider} 给出<b>相同半径</b>的圆角 outline，
 *       配合 elevation 生成圆角阴影；</li>
 *   <li>圆角半径由外部（PosterStackView）按每层海报实际尺寸设置：以左侧海报 16dp 圆角
 *       为基准，乘以该层相对画框的缩放比例，使缩小后的圆角与左侧海报视觉等效；
 *       高光与描边用独立 Paint 绘制，随整层 alpha 一起淡化。</li>
 * </ol>
 *
 * <p>注：个别电视 ROM 的渲染器不支持 clipToOutline，此时仅阴影退化为直角，
 * 海报本身由 {@code drawRoundRect} 保证仍是真圆角、四角透明。</p>
 */
public class RoundedPosterView extends ImageView {

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF strokeRect = new RectF();
    private final Matrix shaderMatrix = new Matrix();

    private Bitmap bitmap;
    private BitmapShader shader;
    private float cornerRadius;
    private float radius;
    private int strokeWidth;
    private int highlightHeight;
    private int highlightStart;
    private int highlightEnd;

    public RoundedPosterView(@NonNull Context context) {
        this(context, null);
    }

    public RoundedPosterView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(@NonNull Context context) {
        bitmapPaint.setStyle(Paint.Style.FILL);
        bitmapPaint.setFilterBitmap(true);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokeWidth = context.getResources().getDimensionPixelSize(R.dimen.stack_stroke_width);
        strokePaint.setStrokeWidth(strokeWidth);
        strokePaint.setColor(context.getResources().getColor(R.color.stack_inner_stroke));

        highlightPaint.setStyle(Paint.Style.FILL);
        highlightHeight = context.getResources().getDimensionPixelSize(R.dimen.stack_highlight_height);
        highlightStart = context.getResources().getColor(R.color.stack_highlight_start);
        highlightEnd = context.getResources().getColor(R.color.stack_highlight_end);

        cornerRadius = context.getResources().getDimension(R.dimen.stack_corner_radius);

        // 圆角 outline：与 onDraw 用同一个 radius，elevation 阴影因此也是圆角。
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    /** 设置圆角半径（px）；由调用方按海报缩放比例换算后传入。 */
    public void setCornerRadius(float px) {
        this.cornerRadius = Math.max(0f, px);
        updateGeometry();
        invalidateOutline();
        invalidate();
    }

    /** 写入直角原图；由 shader 在 onDraw 中裁成圆角。传 null 表示清空画面。 */
    public void setPosterBitmap(@Nullable Bitmap source) {
        this.bitmap = source;
        this.shader = source == null ? null
                : new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        bitmapPaint.setShader(shader);
        updateShaderMatrix();
        invalidate();
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        setPosterBitmap(bm);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateGeometry();
        updateShaderMatrix();
        invalidateOutline();
    }

    private void updateGeometry() {
        int w = getWidth();
        int h = getHeight();
        rect.set(0, 0, w, h);
        // 极小卡片下圆角不超过短边的一半，避免圆角互相吃掉
        radius = Math.min(cornerRadius, Math.min(w, h) * 0.5f);
        float inset = strokeWidth * 0.5f;
        strokeRect.set(inset, inset, Math.max(inset, w - inset), Math.max(inset, h - inset));
        if (highlightHeight > 0) {
            highlightPaint.setShader(new LinearGradient(0, 0, 0, highlightHeight,
                    highlightStart, highlightEnd, Shader.TileMode.CLAMP));
        }
    }

    /** centerCrop：等比放大到完全覆盖，再把多出来的部分居中裁掉。 */
    private void updateShaderMatrix() {
        if (shader == null || bitmap == null) return;
        int w = getWidth();
        int h = getHeight();
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        if (w <= 0 || h <= 0 || bw <= 0 || bh <= 0) return;
        float scale = Math.max(w / bw, h / bh);
        float dx = (w - bw * scale) * 0.5f;
        float dy = (h - bh * scale) * 0.5f;
        shaderMatrix.setScale(scale, scale);
        shaderMatrix.postTranslate(dx, dy);
        shader.setLocalMatrix(shaderMatrix);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        // ① 海报画面：带整层 alpha，四角由 drawRoundRect 真正裁掉
        if (shader != null) canvas.drawRoundRect(rect, radius, radius, bitmapPaint);
        // ② 顶部轻微高光渐变，末端全透明
        canvas.drawRoundRect(rect, radius, radius, highlightPaint);
        // ③ 1px 内描边
        canvas.drawRoundRect(strokeRect, Math.max(0f, radius - strokeWidth * 0.5f),
                Math.max(0f, radius - strokeWidth * 0.5f), strokePaint);
    }
}
