package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.History;

import java.util.List;

@Dao
public abstract class HistoryDao extends BaseDao<History> {

    @Query("SELECT * FROM History")
    public abstract List<History> findAll();

    @Query("SELECT * FROM History WHERE cid = :cid AND createTime >= :createTime ORDER BY createTime DESC LIMIT 60")
    public abstract List<History> find(int cid, long createTime);

    /**
     * 不分配置的最近观看（跨站点共用）。
     *
     * <p>首页「最近观看」用这条：换了点播地址后仍能看到上一站的记录，
     * 由 {@code History#getSiteUrl()} 判断记录属于哪个站点。</p>
     */
    @Query("SELECT * FROM History WHERE createTime >= :createTime ORDER BY createTime DESC LIMIT 60")
    public abstract List<History> findSince(long createTime);

    /**
     * 删除其它点播地址下同名的旧记录（本站播放成功后调用）。
     * siteUrl 为空的老数据一并清掉：它们多半属于换了地址之前的那一份。
     */
    @Query("DELETE FROM History WHERE vodName = :vodName AND `key` != :key AND (siteUrl IS NULL OR siteUrl != :siteUrl)")
    public abstract void deleteOthers(String vodName, String key, String siteUrl);

    @Query("SELECT * FROM History WHERE cid = :cid AND `key` = :key")
    public abstract History find(int cid, String key);

    @Query("SELECT * FROM History WHERE cid = :cid AND vodName = :vodName ORDER BY createTime DESC")
    public abstract List<History> findByName(int cid, String vodName);

    @Query("DELETE FROM History WHERE cid = :cid AND `key` = :key")
    public abstract void delete(int cid, String key);

    @Query("DELETE FROM History WHERE cid = :cid")
    public abstract void delete(int cid);

    @Query("DELETE FROM History")
    public abstract void delete();
}
