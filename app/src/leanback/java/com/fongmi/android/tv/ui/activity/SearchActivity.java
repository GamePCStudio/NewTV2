package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.databinding.ActivitySearchBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.adapter.RecordAdapter;
import com.fongmi.android.tv.ui.adapter.WordAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomKeyboard;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.ui.dialog.LocalConnectDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.RankLatest;
import com.fongmi.android.tv.utils.SearchSuggest;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.utils.ZhuToPin;
import com.github.catvod.net.OkHttp;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Response;

public class SearchActivity extends BaseActivity implements WordAdapter.OnClickListener, RecordAdapter.OnClickListener, CustomKeyboard.Callback {

    private static final int HOT_LIMIT = 10;

    private ActivitySearchBinding mBinding;
    private RecordAdapter mRecordAdapter;
    private WordAdapter mWordAdapter;
    private WordAdapter mMovieAdapter;
    private WordAdapter mTvAdapter;

    /** 联想词序号：双源并发返回，用序号丢弃过期响应，避免旧结果覆盖新结果。 */
    private int mSuggestSeq;
    private List<Word.Data> mIqiyi = new ArrayList<>();
    private List<Word.Data> mTencent = new ArrayList<>();

    /**
     * 是否已经历过一次 {@link #onResume()}。
     *
     * <p>首次 onResume 是刚进这个页面：此时搜索框里可能有 {@code start(activity, keyword)}
     * 带进来的关键词（首页主视觉、服务端推送搜索都会走这条路），必须原样保留。</p>
     *
     * <p>之后的 onResume 都意味着「从别的页面回到了搜索页」（收藏/播放/设置…），
     * 按需求一律清空搜索框，让页面回到「热词」初始态，而不是接着上次的词继续。</p>
     */
    private boolean mResumed;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SearchActivity.class));
    }

    public static void start(Activity activity, String keyword) {
        Intent intent = new Intent(activity, SearchActivity.class);
        intent.putExtra("keyword", keyword);
        activity.startActivity(intent);
    }

    private String getKeyword() {
        String keyword = getIntent().getStringExtra("keyword");
        return keyword != null ? keyword : "";
    }

    private boolean empty() {
        return mBinding.keyword.getText().toString().trim().isEmpty();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySearchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        CustomKeyboard.init(this, mBinding);
        setRecyclerView();
        checkKeyword();
        onSearch();
    }

    @Override
    protected void initEvent() {
        mBinding.keyword.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onSearch();
            return true;
        });
        mBinding.keyword.addTextChangedListener(new CustomTextListener() {
            @Override
            public void afterTextChanged(Editable s) {
                getWord(s.toString());
            }
        });
        mBinding.mic.setOnClickListener(v -> mBinding.mic.start());
        mBinding.mic.setListener(this, new CustomTextListener() {
            @Override
            public void onResults(String result) {
                if (!result.isEmpty()) setKeyword(result);
                mBinding.keyword.requestFocus();
            }
        });
    }

    private void setRecyclerView() {
        mBinding.wordRecycler.setItemAnimator(null);
        mBinding.wordRecycler.setHasFixedSize(false);
        mBinding.wordRecycler.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
        mBinding.wordRecycler.setAdapter(mWordAdapter = new WordAdapter(this));
        mBinding.movieWordRecycler.setItemAnimator(null);
        mBinding.movieWordRecycler.setHasFixedSize(false);
        mBinding.movieWordRecycler.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
        mBinding.movieWordRecycler.setAdapter(mMovieAdapter = new WordAdapter(this));
        mBinding.tvWordRecycler.setItemAnimator(null);
        mBinding.tvWordRecycler.setHasFixedSize(false);
        mBinding.tvWordRecycler.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
        mBinding.tvWordRecycler.setAdapter(mTvAdapter = new WordAdapter(this));
        mBinding.recordRecycler.setHasFixedSize(false);
        mBinding.recordRecycler.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
        mBinding.recordRecycler.setAdapter(mRecordAdapter = new RecordAdapter(this));
    }

    private void checkKeyword() {
        setKeyword(getKeyword());
        getWord(getKeyword());
    }

    private void setKeyword(String text) {
        mBinding.keyword.setText(text);
        mBinding.keyword.setSelection(text.length());
    }

    private void getWord(String text) {
        if (text.isEmpty()) getHot();
        else getSuggest(text);
    }

    /**
     * 热播剧集 / 热播电影：同一份 {@link RankLatest} 榜单的两段。
     *
     * <p>数据源 {@code rank/latest} 一份响应里就带 {@code categories.series} 与
     * {@code categories.movie}，服务端已用 TMDB 富化，因此这里只发一次请求、分别填充两组。
     * 缓存（内存 + 磁盘，30 分钟）由 {@code RankLatest} 统一管理：命中时回调几乎是同步的，
     * 过期才后台刷新；请求失败保留旧数据，不弹提示、不清空展示。</p>
     */
    private void getHot() {
        mSuggestSeq++;
        mBinding.word.setVisibility(View.GONE);
        mBinding.wordRecycler.setVisibility(View.GONE);
        RankLatest.load(data -> {
            // 回调回来时用户可能已经开始输入：热词组必须让位给联想词，不能盖回去。
            if (!empty()) return;
            bindRank(mMovieAdapter, mBinding.movieWordRecycler, mBinding.movieWord, data.getMovie());
            bindRank(mTvAdapter, mBinding.tvWordRecycler, mBinding.tvWord, data.getSeries());
        });
    }

    /** 把榜单条目转成热词胶囊：只取片名，最多 {@link #HOT_LIMIT} 条。 */
    private void bindRank(WordAdapter adapter, RecyclerView list, TextView label, List<RankLatest.RankItem> source) {
        List<Word.Data> data = new ArrayList<>(Math.min(source.size(), HOT_LIMIT));
        for (RankLatest.RankItem item : source) {
            if (item == null || TextUtils.isEmpty(item.getTitle())) continue;
            data.add(Word.Data.create(item.getTitle()));
            if (data.size() >= HOT_LIMIT) break;
        }
        adapter.setItems(data);
        boolean show = !data.isEmpty();
        label.setVisibility(show ? View.VISIBLE : View.GONE);
        list.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * 联想词：爱奇艺 + 腾讯视频双源并发，用序号丢弃过期响应，最后按出现顺序合并去重。
     *
     * <p>与 tv-latest 对齐，但<b>不引入「联网搜索建议」开关</b>：有输入就始终请求两个来源。
     * 单源失败不影响另一源，失败时保持另一源的结果。</p>
     */
    private void getSuggest(String text) {
        int seq = ++mSuggestSeq;
        mIqiyi = new ArrayList<>();
        mTencent = new ArrayList<>();
        mBinding.word.setText(R.string.search_suggest);
        mBinding.word.setVisibility(View.VISIBLE);
        hideHotGroups();
        mWordAdapter.setItems(new ArrayList<>());
        String keyword = ZhuToPin.get(text);
        requestSuggest(SearchSuggest.iqiyiUrl(keyword), seq, false);
        requestSuggest(SearchSuggest.tencentUrl(keyword), seq, true);
    }

    private void requestSuggest(String url, int seq, boolean tencent) {
        OkHttp.newCall(url).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.body() == null) return;
                String result = response.body().string();
                if (TextUtils.isEmpty(result)) return;
                App.post(() -> applySuggest(seq, result, tencent));
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                // 单源失败不影响另一源
            }
        });
    }

    private void applySuggest(int seq, String result, boolean tencent) {
        if (seq != mSuggestSeq || empty()) return;
        if (tencent) mTencent = SearchSuggest.parseTencent(result);
        else mIqiyi = SearchSuggest.parseIqiyi(result);
        // AsyncListDiffer 的提交是异步的，这里用解析结果判断可见性，不读 adapter.getItemCount()
        List<Word.Data> data = SearchSuggest.merge(mIqiyi, mTencent);
        mWordAdapter.setItems(data);
        boolean hasWord = !data.isEmpty();
        mBinding.word.setVisibility(hasWord ? View.VISIBLE : View.GONE);
        mBinding.wordRecycler.setVisibility(hasWord ? View.VISIBLE : View.GONE);
    }

    private void hideHotGroups() {
        mBinding.movieWord.setVisibility(View.GONE);
        mBinding.movieWordRecycler.setVisibility(View.GONE);
        mBinding.tvWord.setVisibility(View.GONE);
        mBinding.tvWordRecycler.setVisibility(View.GONE);
    }

    @Override
    public void onItemClick(String text) {
        setKeyword(text);
        onSearch();
    }

    @Override
    public void onDataChanged(int size) {
        mBinding.recordLayout.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
        if (size == 0) focusFirst(mBinding.wordRecycler);
    }

    /**
     * 搜索页发起搜索：<b>这里也是历史记录的唯一写入点</b>。
     *
     * <p>必须同步写盘，不能用延迟任务：结果页随后立刻启动，搜索页可能长时间不可见甚至被回收，
     * 延迟任务里的记录只会改内存；下次进搜索页读到旧偏好，看起来就像历史被清空。
     * 由首页主视觉 / 收藏 / 片库直接打开结果页的路径不经过本方法，因此既不计入历史，
     * 也不会改动已有记录。</p>
     */
    @Override
    public void onSearch() {
        if (empty()) return;
        String keyword = mBinding.keyword.getText().toString().trim();
        mRecordAdapter.add(keyword);
        Util.hideKeyboard(mBinding.keyword);
        CollectActivity.start(this, keyword);
    }

    @Override
    public void showDialog() {
        SiteDialog.create().search().show(this);
    }

    @Override
    public void onRemote() {
        LocalConnectDialog.show(this, "search");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) showDialog();
        if (KeyUtil.isActionDown(event) && findFocus(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean findFocus(KeyEvent event) {
        View current = getCurrentFocus();
        if (current == mBinding.keyword) return handleKeywordKey(event);
        View inKeyboard = mBinding.keyboard.findContainingItemView(current);
        View inWord = mBinding.wordRecycler.findContainingItemView(current);
        View inRecord = mBinding.recordRecycler.findContainingItemView(current);
        if (inKeyboard != null) return handleKeyboardKey(event, inKeyboard);
        if (inRecord != null) return handleRecordKey(event, inRecord);
        if (inWord != null) return handleWordKey(event, inWord);
        return false;
    }

    private View findNearestInLastRow(RecyclerView rv, int targetLeft) {
        if (rv.getChildCount() == 0) return null;
        int lastTop = rv.getChildAt(rv.getChildCount() - 1).getTop();
        View nearest = null;
        int minDist = Integer.MAX_VALUE;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getTop() == lastTop) {
                int dist = Math.abs(child.getLeft() - targetLeft);
                if (dist < minDist) {
                    minDist = dist;
                    nearest = child;
                }
            }
        }
        return nearest;
    }

    private boolean isFirstRow(RecyclerView rv, View item) {
        View first = rv.getChildAt(0);
        return first != null && item.getTop() == first.getTop();
    }

    private boolean isLastRow(RecyclerView rv, View item) {
        View last = rv.getChildAt(rv.getChildCount() - 1);
        return last != null && item.getTop() == last.getTop();
    }

    private boolean isFirstInRow(RecyclerView rv, View focused) {
        int top = focused.getTop();
        int left = focused.getLeft();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getTop() == top && child.getLeft() < left) return false;
        }
        return true;
    }

    private boolean isLastInRow(RecyclerView rv, View focused) {
        int top = focused.getTop();
        int right = focused.getRight();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getTop() == top && child.getRight() > right) return false;
        }
        return true;
    }

    private boolean handleKeywordKey(KeyEvent event) {
        if (!KeyUtil.isRightKey(event)) return false;
        if (mBinding.keyword.getSelectionEnd() < mBinding.keyword.getText().length()) return false;
        boolean hasRecord = mBinding.recordLayout.getVisibility() == View.VISIBLE;
        return focusFirst(hasRecord ? mBinding.recordRecycler : mBinding.wordRecycler);
    }

    private boolean handleKeyboardKey(KeyEvent event, View item) {
        if (KeyUtil.isUpKey(event) && isFirstRow(mBinding.keyboard, item)) {
            mBinding.keyword.requestFocus();
            return true;
        }
        if (KeyUtil.isLeftKey(event) && isFirstInRow(mBinding.keyboard, item)) return true;
        return KeyUtil.isDownKey(event) && isLastRow(mBinding.keyboard, item);
    }

    private boolean handleWordKey(KeyEvent event, View item) {
        if (KeyUtil.isRightKey(event)) return isLastInRow(mBinding.wordRecycler, item);
        if (KeyUtil.isDownKey(event)) return isLastRow(mBinding.wordRecycler, item);
        if (KeyUtil.isUpKey(event) && isFirstRow(mBinding.wordRecycler, item)) {
            if (mBinding.recordLayout.getVisibility() == View.VISIBLE) {
                View child = findNearestInLastRow(mBinding.recordRecycler, item.getLeft());
                if (child != null) {
                    mBinding.scroll.smoothScrollTo(0, 0);
                    child.requestFocus();
                    return true;
                }
            }
            return true;
        }
        return false;
    }

    private boolean handleRecordKey(KeyEvent event, View item) {
        if (KeyUtil.isRightKey(event)) return isLastInRow(mBinding.recordRecycler, item);
        if (KeyUtil.isUpKey(event)) return isFirstRow(mBinding.recordRecycler, item);
        if (KeyUtil.isDownKey(event) && isLastRow(mBinding.recordRecycler, item)) return focusFirst(mBinding.wordRecycler);
        return false;
    }

    private boolean focusFirst(RecyclerView rv) {
        View child = rv.getChildAt(0);
        if (child == null) return false;
        child.requestFocus();
        return true;
    }

    /**
     * 返回键：焦点在搜索框或搜索键盘上时逐字删除，文本已空才退出搜索页。
     *
     * <p>不接管 {@code dispatchKeyEvent} 里的返回键：那里返回 true 会吞掉事件，
     * 之后必须在每个分支自己决定是否 finish；放在 {@code onBackInvoked} 这一层，
     * 文本非空时删字、为空时直接落到父类 finish，语义只有一处。
     */
    @Override
    protected void onBackInvoked() {
        if (isSearchInputFocused() && deleteLastChar()) return;
        super.onBackInvoked();
    }

    private boolean isSearchInputFocused() {
        View current = getCurrentFocus();
        if (current == null) return false;
        if (current == mBinding.keyword) return true;
        return mBinding.keyboard.findContainingItemView(current) != null;
    }

    /** 删掉光标前一个字符；有选区时删选区。没有可删字符时返回 false。 */
    private boolean deleteLastChar() {
        Editable text = mBinding.keyword.getText();
        if (text == null || text.length() == 0) return false;
        int start = Math.max(mBinding.keyword.getSelectionStart(), 0);
        int end = Math.max(mBinding.keyword.getSelectionEnd(), 0);
        if (start != end) {
            text.delete(Math.min(start, end), Math.max(start, end));
            return true;
        }
        // 光标停在首位时删首字符，保证「还有字符就只删不退」
        int from = start > 0 ? start - 1 : 0;
        text.delete(from, Math.min(from + 1, text.length()));
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mBinding.mic.setFocusable(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mBinding.mic.setFocusable(true);
        // 从任意页面返回搜索页：清空搜索框内的全部字符；首次进入时保留 start(keyword) 带来的词。
        if (mResumed) clearKeyword();
        else mResumed = true;
        mBinding.keyword.requestFocus();
    }

    /**
     * 清空搜索框并回到热词态。
     *
     * <p>只在文本真的非空时写回：{@code setText("")} 会触发 {@code CustomTextListener}
     * → {@link #getWord(String)} → {@link #getHot()}，热词分组随之重新显示；
     * 本来就是空的话没必要再走一遍。</p>
     */
    private void clearKeyword() {
        if (mBinding.keyword.getText().length() > 0) mBinding.keyword.setText("");
        mBinding.keyword.setSelection(0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mBinding.mic.destroy();
    }
}
