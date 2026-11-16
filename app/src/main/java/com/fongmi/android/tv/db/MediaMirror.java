package com.fongmi.android.tv.db;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.fongmi.android.tv.App;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 备份文件在系统媒体库里的持久副本。
 *
 * <p>应用专属目录（内部 files、Android/data）在卸载时会被系统一并删除，
 * 重装后无法恢复；公共目录 TV/backup 在 Android 11 起又需要「所有文件访问权限」，
 * 部分电视 ROM 干脆没有这个授权页。媒体库的 Download 集合是唯一既不要求权限、
 * 卸载后又保留的公开位置，所以备份在本机落盘后再镜像一份到这里。</p>
 *
 * <p>只在 Android 10（API 29）以上启用：更低的系统仍可直接写公共目录，
 * 而且那时没有 Downloads 集合。</p>
 */
final class MediaMirror {

    private static final String TAG = "MediaMirror";
    /** 相对 Download 的子目录，放在独立文件夹里避免混进用户下载。 */
    private static final String RELATIVE = Environment.DIRECTORY_DOWNLOADS + "/NewTV2";
    private static final String MIME = "application/octet-stream";

    private MediaMirror() {
    }

    static boolean available() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    static final class Entry {

        final long id;
        final String name;
        final long size;
        final long modified;

        Entry(long id, String name, long size, long modified) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.modified = modified;
        }
    }

    /** 写入（同名先删旧行，保证一份文件只对应一行记录）。 */
    static boolean write(String name, byte[] data) {
        if (name == null || data == null) return false;
        ContentResolver resolver = App.get().getContentResolver();
        delete(name);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, MIME);
        values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) return false;
                out.write(data);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Unable to write mirror name=" + name, e);
            if (uri != null) try {
                resolver.delete(uri, null, null);
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    static void delete(String name) {
        if (name == null) return;
        try {
            App.get().getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{name});
        } catch (Exception e) {
            Log.w(TAG, "Unable to delete mirror name=" + name, e);
        }
    }

    /** 列出镜像目录里的全部备份，按修改时间倒序。 */
    static List<Entry> list() {
        List<Entry> items = new ArrayList<>();
        String[] projection = {
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED
        };
        String selection = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
        String[] args = {RELATIVE + "%"};
        try (Cursor cursor = App.get().getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args,
                MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return items;
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name == null || !name.endsWith(BackupManager.BACKUP_EXTENSION)) continue;
                items.add(new Entry(cursor.getLong(0), name, cursor.getLong(2), cursor.getLong(3)));
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to list mirror", e);
        }
        return items;
    }

    /** 把镜像条目落回本机文件，供恢复流程按普通文件读取。 */
    static File copy(Entry entry, File dir) {
        if (entry == null || dir == null) return null;
        File target = new File(dir, entry.name);
        if (entry.size > 0 && target.length() == entry.size) {
            target.setLastModified(entry.modified * 1000);
            return target;
        }
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            Uri uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, entry.id);
            try (InputStream in = App.get().getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null) return null;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            target.setLastModified(entry.modified * 1000);
            return target;
        } catch (Exception e) {
            Log.w(TAG, "Unable to copy mirror name=" + entry.name, e);
            if (target.exists()) target.delete();
            return null;
        }
    }
}
