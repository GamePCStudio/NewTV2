package com.fongmi.android.tv.db;

import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.Formatters;
import com.github.catvod.utils.Archive;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Task;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BackupManager {

    private static final String TAG = BackupManager.class.getSimpleName();
    private static final int MAX_BACKUPS = 7;
    private static final String BACKUP_EXTENSION = ".tv";
    private static final String LEGACY_BACKUP_PREFIX = "tv-";
    private static final String LEGACY_BACKUP_SUFFIX = ".bk.gz";
    /** 外置目录不可写时退到应用内部空间，这里不需要任何存储权限。 */
    private static final String INTERNAL_DIR = "backup";
    private static final String PROBE = ".probe";

    public static void backup() {
        backup(new Callback());
    }

    public static void backup(Callback callback) {
        Task.executeSerial(() -> {
            boolean success = save();
            post(callback, success);
            if (success) trim();
        });
    }

    public static void restore(File file, Callback callback) {
        Task.executeSerial(() -> post(callback, restore(file)));
    }

    public static List<File> getFiles() {
        migrate();
        Map<String, File> backups = new LinkedHashMap<>();
        add(backups, Path.backup());
        add(backups, internal());
        for (File file : Path.list(Path.tv())) {
            if (!isUnmigratedLegacy(file)) continue;
            backups.putIfAbsent(file.getName(), file);
        }
        List<File> list = new ArrayList<>(backups.values());
        list.sort(Comparator.comparingLong(File::lastModified).reversed());
        return list;
    }

    /** 两处目录里的同名文件只留较新的那份，避免列表出现两条一模一样的日期。 */
    private static void add(Map<String, File> backups, File dir) {
        for (File file : Path.list(dir)) {
            if (!file.isFile() || !file.getName().endsWith(BACKUP_EXTENSION)) continue;
            File exist = backups.get(file.getName());
            if (exist == null || file.lastModified() > exist.lastModified()) backups.put(file.getName(), file);
        }
    }

    /**
     * 备份写入目录：优先外置的 TV/backup（用户能用文件管理器拷走），
     * Android 11 起没有「所有文件访问权限」写外置会直接 EPERM，这时退到应用内部目录。
     */
    private static File dir() {
        File external = Path.backup();
        if (writable(external)) return external;
        File internal = internal();
        return writable(internal) ? internal : null;
    }

    private static File internal() {
        return new File(Path.files(), INTERNAL_DIR);
    }

    /** canWrite() 在 FUSE 上不可靠，真的建一个探针文件来确认。 */
    private static boolean writable(File dir) {
        if (dir == null) return false;
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File probe = new File(dir, PROBE);
        try {
            if (probe.exists()) return true;
            boolean created = probe.createNewFile();
            if (created) probe.delete();
            return created;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean save() {
        File dir = dir();
        if (dir == null) return false;
        Backup backup = Backup.create();
        File file = new File(dir, LocalDate.now().format(Formatters.DATE) + BACKUP_EXTENSION);
        try {
            return !backup.getConfig().isEmpty() && Archive.gzipCompress(backup.toString().getBytes(StandardCharsets.UTF_8), file);
        } catch (Exception e) {
            Log.w(TAG, "Unable to write backup file=" + file, e);
            return false;
        }
    }

    /** 文件读不出来（没权限、已损坏）时按失败返回，别让异常静默终止任务。 */
    private static boolean restore(File file) {
        try {
            Backup backup = Backup.objectFrom(Archive.readGzip(file));
            boolean valid = !backup.getConfig().isEmpty();
            if (valid) backup.restore();
            return valid;
        } catch (Exception e) {
            Log.w(TAG, "Unable to read backup file=" + file, e);
            return false;
        }
    }

    private static void post(Callback callback, boolean success) {
        App.post(success ? callback::success : callback::error);
    }

    private static void trim() {
        getFiles().stream().skip(MAX_BACKUPS).forEach(Path::clear);
    }

    private static void migrate() {
        File dir = dir();
        if (dir == null) return;
        for (File file : Path.list(Path.tv())) if (isLegacy(file)) migrate(file, dir);
    }

    private static boolean isLegacy(File file) {
        String name = file.getName();
        return file.isFile() && name.startsWith(LEGACY_BACKUP_PREFIX) && name.endsWith(LEGACY_BACKUP_SUFFIX);
    }

    private static void migrate(File file, File dir) {
        File target = getMigrationTarget(file, dir);
        if (target.exists()) return;
        try {
            Path.move(file, target);
        } catch (IOException e) {
            Log.w(TAG, "Unable to migrate backup file=" + file, e);
        }
    }

    private static boolean isUnmigratedLegacy(File file) {
        return isLegacy(file) && !getMigrationTarget(file, Path.backup()).exists() && !getMigrationTarget(file, internal()).exists();
    }

    private static File getMigrationTarget(File file, File dir) {
        String name = file.getName();
        String date = name.substring(LEGACY_BACKUP_PREFIX.length(), name.length() - LEGACY_BACKUP_SUFFIX.length());
        return new File(dir, date + BACKUP_EXTENSION);
    }
}
