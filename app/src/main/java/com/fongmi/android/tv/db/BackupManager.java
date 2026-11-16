package com.fongmi.android.tv.db;

import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.Formatters;
import com.github.catvod.utils.Archive;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Task;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BackupManager {

    private static final String TAG = BackupManager.class.getSimpleName();
    private static final int MAX_BACKUPS = 7;
    static final String BACKUP_EXTENSION = ".tv";
    private static final String LEGACY_BACKUP_PREFIX = "tv-";
    private static final String LEGACY_BACKUP_SUFFIX = ".bk.gz";
    /** 外置目录不可写时退到应用内部空间，这里不需要任何存储权限。 */
    private static final String INTERNAL_DIR = "backup";
    /** 应用专属外部目录（Android/data/<包名>/files/backup）：uid 变化后依然可写可读。 */
    private static final String APP_EXTERNAL_DIR = "backup";
    private static final String PROBE = ".probe";

    public static void backup() {
        backup(new Callback());
    }

    public static void backup(Callback callback) {
        Task.executeSerial(() -> {
            boolean success = save();
            if (success) mirrorAll();
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
        add(backups, external());
        add(backups, internal());
        for (File file : Path.list(Path.tv())) {
            if (!isUnmigratedLegacy(file)) continue;
            backups.putIfAbsent(file.getName(), file);
        }
        // 本机副本会随卸载删除：系统媒体库里还有的，落回本机后一并列出。
        addMirror(backups);
        List<File> list = new ArrayList<>(backups.values());
        list.sort(Comparator.comparingLong(File::lastModified).reversed());
        return list;
    }

    /** 补上只在系统媒体库里存在的备份：重装后本机目录已空，靠这一步把列表恢复出来。 */
    private static void addMirror(Map<String, File> backups) {
        if (!MediaMirror.available()) return;
        File dir = internal();
        for (MediaMirror.Entry entry : MediaMirror.list()) {
            if (backups.containsKey(entry.name)) continue;
            File file = MediaMirror.copy(entry, dir);
            if (file != null) backups.put(entry.name, file);
        }
    }

    /** 两处目录里的同名文件只留一份：优先保留当前应用真正读得到的，再比修改时间。 */
    private static void add(Map<String, File> backups, File dir) {
        if (dir == null) return;
        for (File file : Path.list(dir)) {
            if (!file.isFile() || !file.getName().endsWith(BACKUP_EXTENSION)) continue;
            File exist = backups.get(file.getName());
            if (exist == null) {
                backups.put(file.getName(), file);
                continue;
            }
            boolean readable = file.canRead();
            boolean existReadable = exist.canRead();
            // 重装后 uid 变化会让旧目录的文件「看得到、读不了」：不能让它盖掉新目录里可恢复的那份。
            if (readable != existReadable) {
                if (readable) backups.put(file.getName(), file);
            } else if (file.lastModified() > exist.lastModified()) {
                backups.put(file.getName(), file);
            }
        }
    }

    /**
     * 备份写入目录优先级：
     * <ol>
     *   <li>外置的 TV/backup（用户能用文件管理器直接拷走）；</li>
     *   <li>应用专属外部目录 Android/data/&lt;包名&gt;/files/backup —— 重装后 uid 变化，
     *       旧版留下的 TV/backup 可能既不可写也不可读，而这一层永远属于当前应用；</li>
     *   <li>应用内部 files/backup，任何权限都不需要。</li>
     * </ol>
     */
    private static File dir() {
        File external = Path.backup();
        if (writable(external)) return external;
        File appExternal = external();
        if (writable(appExternal)) return appExternal;
        File internal = internal();
        return writable(internal) ? internal : null;
    }

    /** 应用专属外部目录：卸载才会清除，重装（uid 变化）后仍可写可读。 */
    private static File external() {
        File base = App.get().getExternalFilesDir(null);
        return base == null ? null : new File(base, APP_EXTERNAL_DIR);
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
        // 不再用「点播配置表非空」作为唯一判据：新设备 / 未添加站源时该表为空，
        // 但偏好、历史、收藏等仍应能备份。
        if (!backup.hasData()) return false;
        String name = LocalDate.now().format(Formatters.DATE) + BACKUP_EXTENSION;
        File file = new File(dir, name);
        try {
            byte[] data = backup.toString().getBytes(StandardCharsets.UTF_8);
            if (!Archive.gzipCompress(data, file)) return false;
        } catch (Exception e) {
            Log.w(TAG, "Unable to write backup file=" + file, e);
            return false;
        }
        // 公共目录 TV/backup 本身就能跨卸载保留；退到应用专属 / 内部目录时，
        // 再往系统媒体库写一份副本，否则卸载时这份备份会被一起删掉。
        if (!isPublic(dir)) mirror(file);
        return true;
    }

    /** 本机副本 + 媒体库副本一起删，避免删掉后列表里又从镜像里冒出来。 */
    public static void delete(File file) {
        if (file == null) return;
        if (MediaMirror.available()) MediaMirror.delete(file.getName());
        Path.clear(file);
    }

    /** 把当前本机目录里的备份补一份到媒体库；公共目录可写时无需镜像。 */
    private static void mirror(File file) {
        if (file == null || !MediaMirror.available()) return;
        if (writable(Path.backup())) return;
        byte[] data = read(file);
        if (data != null) MediaMirror.write(file.getName(), data);
    }

    /** 历史版本留在本机目录（卸载即删）的备份，补一份到媒体库。 */
    private static void mirrorAll() {
        if (!MediaMirror.available() || writable(Path.backup())) return;
        Set<String> names = new HashSet<>();
        for (MediaMirror.Entry entry : MediaMirror.list()) names.add(entry.name);
        for (File dir : new File[]{external(), internal()}) {
            if (dir == null) continue;
            for (File file : Path.list(dir)) {
                if (!file.isFile() || !file.getName().endsWith(BACKUP_EXTENSION)) continue;
                if (!names.add(file.getName())) continue;
                byte[] data = read(file);
                if (data != null) MediaMirror.write(file.getName(), data);
            }
        }
    }

    private static boolean isPublic(File dir) {
        try {
            return dir.getCanonicalPath().equals(Path.backup().getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] read(File file) {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "Unable to read backup file=" + file, e);
            return null;
        }
    }

    /** 文件读不出来（没权限、已损坏）时按失败返回，别让异常静默终止任务。 */
    private static boolean restore(File file) {
        try {
            Backup backup = Backup.objectFrom(Archive.readGzip(file));
            // 与备份端同一口径：偏好 / 历史 / 收藏 / 站源任一有内容都算有效。
            // 只有「整份备份完全为空」才拒绝，避免用空文件把设备数据清空。
            boolean valid = backup.hasData();
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
        getFiles().stream().skip(MAX_BACKUPS).forEach(BackupManager::delete);
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
