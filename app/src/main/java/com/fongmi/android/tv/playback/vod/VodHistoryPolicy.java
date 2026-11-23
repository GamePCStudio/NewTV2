package com.fongmi.android.tv.playback.vod;

import android.text.TextUtils;

import androidx.media3.common.C;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.SourceUrl;
import com.github.catvod.utils.Task;

public class VodHistoryPolicy {

    public History findOrCreate(String key, String mark, Vod item, History history) {
        if (history == null) {
            history = History.find(key);
        } else if (!key.equals(history.getKey())) {
            history.setKey(key);
            history.setCid(VodConfig.getCid());
        }
        history = history == null ? create(key, item) : history;
        // 记录归属：写当前点播配置地址，换站点后首页据此判断该不该直接进播放页。
        history.setSiteUrl(VodConfig.getUrl());
        if (!TextUtils.isEmpty(mark)) history.setVodRemarks(mark);
        if (Setting.isIncognito()) history.delete();
        item.checkName(history.getVodName());
        history.setVodName(item.getName());
        return history;
    }

    private History create(String key, Vod item) {
        History history = new History();
        history.setKey(key);
        history.setCid(VodConfig.getCid());
        history.setSiteUrl(VodConfig.getUrl());
        history.setVodName(item.getName());
        history.findEpisode(item.getFlags());
        return history;
    }

    public void save(History history) {
        save(history, false);
    }

    public void save(History history, boolean exit) {
        if (history == null || !history.canSave() || Setting.isIncognito()) return;
        History copy = copyForSave(history);
        Task.executeSerial(() -> {
            // 跨站点共用：先在其它点播地址下删掉同名旧记录，再写入本站这条（替换而非新增）。
            copy.merge().replaceOthers().save();
            if (exit) RefreshEvent.history();
        });
    }

    public void saveProgress(History history, boolean exit, long time, long position, long duration) {
        applyProgress(history, time, position, duration);
        save(history, exit);
    }

    public void saveVisit(History history, boolean exit, long time) {
        if (history == null || Setting.isIncognito()) return;
        history.setCreateTime(time);
        history.setPosition(C.TIME_UNSET);
        history.setDuration(C.TIME_UNSET);
        History copy = copyForSave(history);
        Task.executeSerial(() -> {
            copy.save();
            if (exit) RefreshEvent.history();
        });
    }

    public void saveDiscVisit(History history, boolean exit, long time) {
        if (history == null || Setting.isIncognito()) return;
        history.setCreateTime(time);
        History copy = copyForSave(history);
        Task.executeSerial(() -> {
            copy.save();
            if (exit) RefreshEvent.history();
        });
    }

    public void saveCurrent(History history) {
        saveCurrent(history, false);
    }

    public void saveCurrent(History history, boolean refresh) {
        if (history == null || Setting.isIncognito()) return;
        History copy = copyForSave(history);
        Task.executeSerial(() -> {
            // 播放已经开始推进：立刻把其它点播地址下的同名旧记录替换掉，
            // 不必等退出播放页那一次保存。
            copy.replaceOthers().save();
            if (refresh) RefreshEvent.history();
        });
    }

    public void updateEpisode(History history, Flag flag, Episode episode) {
        updateEpisode(history, flag, episode, false);
    }

    public void updateEpisode(History history, Flag flag, Episode episode, boolean matchId) {
        if (history == null || flag == null || episode == null) return;
        boolean match = matchId ? episode.getUrl().equals(history.getEpisodeUrl()) : matchesEpisode(history, flag, episode);
        if (!match) history.setPosition(C.TIME_UNSET);
        if (!match) history.setDuration(C.TIME_UNSET);
        history.setVodFlag(flag.getFlag());
        history.setVodRemarks(episode.getName());
        history.setEpisodeUrl(episode.getUrl());
    }

    private boolean matchesEpisode(History history, Flag flag, Episode episode) {
        String previous = history.getEpisodeUrl();
        String current = episode.getUrl();
        boolean previousResolved = SourceUrl.isResolved(previous);
        boolean currentResolved = SourceUrl.isResolved(current);
        if (!previousResolved && !currentResolved) return episode.matchesName(history.getEpisode());
        return previousResolved && currentResolved && flag.getFlag().equals(history.getVodFlag()) && SourceUrl.same(previous, current);
    }

    public void updateProgress(History history, long time, long position, long duration) {
        if (position < 0 || duration <= 0) return;
        applyProgress(history, time, position, duration);
        if (history != null && history.canSave() && history.canScheduleSave()) saveCurrent(history);
    }

    private void applyProgress(History history, long time, long position, long duration) {
        if (history == null || position < 0 || duration <= 0) return;
        history.setCreateTime(time);
        history.setPosition(position);
        history.setDuration(duration);
    }

    private History copyForSave(History history) {
        history.markSaveScheduled();
        return history.copy();
    }
}
