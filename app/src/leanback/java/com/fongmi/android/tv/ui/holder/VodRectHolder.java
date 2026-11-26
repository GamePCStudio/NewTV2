package com.fongmi.android.tv.ui.holder;

import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterVodRectBinding;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.base.BaseVodHolder;
import com.fongmi.android.tv.ui.custom.CardGlowComposeView;
import com.fongmi.android.tv.ui.custom.PosterFocusAnimator;
import com.fongmi.android.tv.ui.custom.PosterFrameBreath;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.HeroGlowColor;
import com.fongmi.android.tv.utils.ImgUtil;

public class VodRectHolder extends BaseVodHolder {

    private final VodPresenter.OnClickListener listener;
    private final AdapterVodRectBinding binding;
    /** 白框慢闪呼吸：静止 500ms 后在 100%↔20% 之间往复。 */
    private final PosterFrameBreath breath;
    /** 当前绑定的海报 URL：提色回调回来时据此判断卡片是否已被复用。 */
    private String boundPic = "";

    public VodRectHolder(@NonNull AdapterVodRectBinding binding, VodPresenter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
        // 卡片内不再挂任何绘制层：可见光统一由整页背景层提供，这里只保留提色 / 几何上报。
        breath = new PosterFrameBreath(binding.poster);
        // 卡片根只做 PosterFocusAnimator.FOCUS_SCALE_CARD 缩放；聚焦时展开元数据行、跑马灯并点亮背景光。
        PosterFocusAnimator.bind(binding.getRoot(), false, (card, focused) -> {
            int visibility = focused ? View.VISIBLE : View.INVISIBLE;
            if (binding.metadata.getVisibility() != visibility) binding.metadata.setVisibility(visibility);
            PosterFocusAnimator.marquee(binding.name, focused);
            breath.setFocused(focused);
            if (focused) {
                // 提色是异步的，先把局部提亮的圆心推到这张卡，避免光滞留在上一张卡上。
                CardGlowComposeView.reportPageGlowCenter(binding.poster);
                extractGlow(boundPic, binding.image.getDrawable());
            }
        });
    }

    /** 聚焦时从当前海报提主色；URL 与 drawable 双判空，异步回调只认仍绑定的那张卡。 */
    private void extractGlow(String url, Drawable drawable) {
        if (drawable == null) return;
        HeroGlowColor.load(url, drawable, color -> {
            if (!url.equals(boundPic)) return;
            // 只上报主色；可见光由当前页面的整页背景层绘制。
            CardGlowComposeView.reportPageGlow(binding.poster, color);
        });
    }

    /**
     * 把「站点名称」和「其它备注信息」拼成一行，交给单个 TextView 统一截断。
     * 任一段为空时不留多余空格，两段都为空返回空串（调用方据此隐藏整行）。
     */
    private static String joinMeta(String site, String remark) {
        boolean hasSite = !TextUtils.isEmpty(site);
        boolean hasRemark = !TextUtils.isEmpty(remark);
        if (hasSite && hasRemark) return site + " " + remark;
        return hasSite ? site : hasRemark ? remark : "";
    }

    public VodRectHolder size(int[] size) {
        binding.poster.getLayoutParams().height = size[1];
        binding.getRoot().getLayoutParams().width = size[0];
        float scale = Product.getPosterTextScale(size[0]);
        boolean large = PlayerSetting.getSize() >= 2;
        int titleRes = large ? R.dimen.poster_title_text_large : R.dimen.poster_title_text;
        int metaRes = large ? R.dimen.poster_meta_text_large : R.dimen.poster_meta_text;
        float titleSize = binding.getRoot().getResources().getDimension(titleRes) * scale;
        float metaSize = binding.getRoot().getResources().getDimension(metaRes) * scale;
        binding.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleSize);
        binding.year.setTextSize(TypedValue.COMPLEX_UNIT_PX, metaSize);
        binding.remark.setTextSize(TypedValue.COMPLEX_UNIT_PX, metaSize);
        binding.site.setTextSize(TypedValue.COMPLEX_UNIT_PX, metaSize);
        return this;
    }

    @Override
    public void initView(Vod item) {
        boundPic = item.getDisplayPic();
        binding.name.setText(item.getName());
        binding.year.setText(item.getYear());
        binding.site.setText(item.getSiteName());
        /*
         * 评分不单独占位：素材写入时已把「评分：x.x」拼进备注字符串（见 ArtSource），
         * 这里与评分角标出现前的历史实现一致 —— 只把备注原样渲染到片名下方的次标题，
         * 由 TextView 按海报实际宽度统一截断。不要再从备注里解析出独立角标。
         */
        String remark = item.getRemarks();
        if (item.getSite() != null) {
            /*
             * 搜索结果页（全项目只有 SiteApi.searchContent 会给 Vod 挂 site）：
             * 片名下方只留一行「站点名称 其它备注信息」。
             *
             * <p>两段必须落在<b>同一个 TextView</b> 里：拆成两个控件各自 ellipsize 会出现
             * 两个省略号，而且站点那格被 maxWidth 64dp 卡死，稍长的站名先被截、后面的备注
             * 却还占着宽度。合一之后宽度 = 海报卡宽度（match_parent），截断点由
             * TextView 按实际可用宽度算 —— 卡片越宽显示越多，天然随海报尺寸动态变化。</p>
             */
            String meta = joinMeta(item.getSiteName(), remark);
            binding.remark.setText(meta);
            binding.remark.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
            binding.site.setVisibility(View.GONE);
            binding.year.setVisibility(View.GONE);
        } else {
            binding.remark.setText(remark);
            binding.remark.setVisibility(remark.isEmpty() ? View.GONE : item.getRemarkVisible());
            binding.site.setVisibility(item.getSiteVisible());
            binding.year.setVisibility(item.getYearVisible());
        }
        binding.name.setVisibility(item.getNameVisible());
        binding.metadata.setVisibility(binding.getRoot().hasFocus() ? View.VISIBLE : View.INVISIBLE);
        // 行末卡片是滚动时才复用的，绑定时已在聚焦态、焦点回调不会再触发，这里补一次。
        if (binding.getRoot().hasFocus()) breath.setFocused(true);
        binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
        binding.getRoot().setOnLongClickListener(v -> listener.onLongClick(item));
        // 图片异步就绪后若这张卡仍聚焦，补一次提色——聚焦瞬间 drawable 常为 null，
        // 只靠 focus 回调会让整页背景光迟迟不亮。
        ImgUtil.load(item.getName(), item.getDisplayPic(), binding.image, true, () -> {
            if (binding.getRoot().hasFocus()) extractGlow(boundPic, binding.image.getDrawable());
        });
    }

    @Override
    public void unbind() {
        // 先停呼吸再 reset：reset 的 jumpToCurrentState 会把 foreground 的 alpha 落回呼吸最后的值。
        breath.release();
        PosterFocusAnimator.reset(binding.getRoot());
        boundPic = "";
        Glide.with(binding.image).clear(binding.image);
    }
}
