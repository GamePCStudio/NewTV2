package com.fongmi.android.tv.bean;

import com.fongmi.android.tv.utils.HomeBoard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「查看全部」堆叠按钮的数据项：只保存入口类型与三张海报地址，不含任何控件引用。
 *
 * <p>海报顺序固定为 [后景, 中景, 前景]，对应数据源的第 13 / 12 / 11 张；
 * 首页每行只展示前 10 部，因此前景就是「下一部未展示的影片」。
 * 数据不足 13 张时整体前移，保证三层都有图。</p>
 */
public class ViewAllItem {

    /** 「来自 · 站源名」入口；分类入口用 HomeBoard 的 0~3 下标。 */
    public static final int BOARD_SITE = -1;

    private final int board;
    private final List<String> posters;

    public ViewAllItem(int board, List<String> posters) {
        this.board = board;
        this.posters = posters == null ? Collections.emptyList() : new ArrayList<>(posters);
    }

    /** 列表样式下的哑条目走同一个入口，无海报。 */
    public static ViewAllItem site() {
        return new ViewAllItem(BOARD_SITE, Collections.emptyList());
    }

    /** 从该分类的完整结果里取第 11~13 张海报，返回 [后景, 中景, 前景]。 */
    public static List<String> postersFrom(List<Vod> items) {
        List<String> out = new ArrayList<>(3);
        out.add("");
        out.add("");
        out.add("");
        if (items == null || items.size() < 3) return out;
        int first = Math.min(HomeBoard.HOME_LIMIT, items.size() - 3);
        out.set(0, pic(items.get(first + 2)));
        out.set(1, pic(items.get(first + 1)));
        out.set(2, pic(items.get(first)));
        return out;
    }

    private static String pic(Vod vod) {
        return vod == null ? "" : vod.getDisplayPic();
    }

    public boolean isSite() {
        return board == BOARD_SITE;
    }

    public int getBoard() {
        return board;
    }

    public List<String> getPosters() {
        return posters;
    }
}
