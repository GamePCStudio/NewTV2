package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;

import java.util.List;

public interface VodPlaybackHost {

    String getVodKey();

    String getVodId();

    void setVodId(String id);

    String getVodName();

    String getVodPic();

    String getVodMark();

    String getVodPlayId();

    void clearVodPlayId();

    String getHistoryKey();

    default History takeCastHistory() {
        return null;
    }

    boolean isSiteChangeable();

    boolean isFromCollect();

    boolean isHostFinishing();

    boolean isPlayerEmpty();

    boolean hasPlaybackSession();

    boolean isLivePlayback();

    boolean canTrackPlaybackProgress();

    default boolean isIsoNavigationPlayback() {
        return false;
    }

    boolean canPreloadNext();

    boolean isPlayerPlaying();

    long getPlayerPosition();

    long getPlayerDuration();

    void usePushId(String id);

    default void openPush(String target) {
        throw new UnsupportedOperationException();
    }

    void onPlaybackRequested();

    void prepareSource(Vod item);

    void stopPlaybackForRefresh();

    void resetPlaybackForError(String msg);

    void replay(long position);

    void seekPlayback(long position);

    default void setPlaybackEnding(long endingMs) {
    }

    default boolean isPlaybackEndingManaged() {
        return false;
    }

    void startPlayback(Result result, boolean useParse, long startPositionMs, MediaMetadata metadata);

    boolean preloadPlayback(Result result, long startPositionMs, MediaMetadata metadata, VodPlayRequest request);

    default boolean seekToPreloaded(VodPlayRequest request) {
        return false;
    }

    void clearPreload();

    void loadDanmaku(Result result, History history, Episode episode);

    void renderDetail(Vod item, History history);

    void renderVodUpdate(Vod item);

    void renderEmptyDetail();

    void renderFallbackName(String name);

    void renderFlags(List<Flag> items);

    void renderEpisodes(List<Episode> items);

    void renderFlagSelection(Flag item);

    void renderEpisodeSelection(Episode item);

    void renderReverseEpisodes(List<Episode> items, boolean scroll);

    void renderQuality(Result result, boolean visible);

    void renderQualityVisible(boolean visible);

    void renderSources(List<Vod> items);

    void renderHistory(History history);

    /**
     * 是否在控制条展示「解析」入口。
     *
     * <p>leanback 播放页已移除该入口（解析只在自动链路里按配置生效），不再覆写本方法；
     * mobile 仍保留手动切换解析源的按钮，所以钩子保留为默认空实现，由需要的宿主覆写。
     */
    default void renderUseParse(boolean useParse) {
    }

    void renderArtwork(String url);

    void renderDescription(String desc);

    void renderPlaybackMetadata(MediaMetadata metadata);

    void onDetailFallbackScheduled();

    void onDetailFallbackCancelled();

    void onSearchStarted(String keyword);

    void onSearchResult();

    void showDetailMessage(String msg);

    void showSwitchLine(Flag flag);

    void showSwitchSource(Vod item);

    void showNoNext(boolean reversed);

    void showNoPrev(boolean reversed);

    void finishVod();
}
