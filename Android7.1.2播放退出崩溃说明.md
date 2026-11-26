# Android 7.1.2 播放视频后退出崩溃说明

- 日期：2026-11-27
- 应用包名：`com.newtv.android.exo`
- 故障版本：`5.8.7.NewTV2.20261127.021711`（versionCode `95717031`）
- 设备：Phicomm X12-2027（`ro.product.device=X12-2027`）
- 系统：Android 7.1.2，API 25
- 系统指纹：`Phicomm/p230/p230:7.1.2/NHG47L/20270108:user/dev-keys`
- 设备 ABI：`arm64-v8a`

## 1. 故障现象

在 Android 7.1.2 电视上进入影片播放页，播放后按返回键退出，应用没有正常回到上一页，而是启动自定义崩溃页 `CrashActivity`。随后应用进程被终止，系统又尝试重新拉起播放服务。

该问题与应用是否成功解码、播放时长或影片格式没有直接关系；触发点是播放页销毁过程中的 Java 集合清理代码。

## 2. 复现步骤

1. 安装故障版本 `5.8.7.NewTV2.20261127.021711`。
2. 从站源进入任意可播放影片，进入 `VideoActivity`。
3. 开始播放。
4. 按遥控器返回键退出播放页。
5. 应用进入 `CrashActivity`，原播放进程死亡。

## 3. Logcat 证据

抓取命令：

```bash
adb logcat -d -v threadtime
```

目标进程 PID 为 `16345`。关键日志如下：

```text
11-27 02:44:37.346  SogouIME: onKeyDown: keyoCode=4
11-27 02:44:37.520  SogouIME: onKeyUp: keyoCode=4
11-27 02:44:37.734 16345 16345 D AndroidRuntime: Shutting down VM
11-27 02:44:37.742 16345 16345 E CustomActivityOnCrash: App has crashed, executing CustomActivityOnCrash's UncaughtExceptionHandler
11-27 02:44:37.742 16345 16345 E CustomActivityOnCrash: java.lang.RuntimeException: Unable to destroy activity {com.newtv.android.exo/com.fongmi.android.tv.ui.activity.VideoActivity}: java.lang.UnsupportedOperationException
11-27 02:44:37.742 16345 16345 E CustomActivityOnCrash: Caused by: java.lang.UnsupportedOperationException
11-27 02:44:37.742 16345 16345 E CustomActivityOnCrash:     at java.util.concurrent.CopyOnWriteArrayList$CowIterator.remove(CopyOnWriteArrayList.java:814)
11-27 02:44:37.742 16345 16345 E CustomActivityOnCrash:     at java.util.Collection.removeIf(Collection.java:402)
11-27 02:44:37.742 16345 16345 E CustomActivityOnCrash:     at com.fongmi.android.tv.ui.activity.VideoActivity.onDestroy(r8-map-id-0b20cbee6b8a5e12da8fe9ef3628520780412076b042fc5ddcd9f9b8fefcfde3:97)
11-27 02:44:37.742 16345 16345 E CustomActivityOnCrash:     at android.app.Activity.performDestroy(Activity.java:6884)
```

`onDestroy` 中的源码位置经过 R8 混淆，日志显示为映射 ID 和行号 `97`，不是可直接导航的源码行号。根据源码中的注册与销毁顺序，可以确定首个崩溃调用是 `DanmakuHeat.removeListener(mHeatListener)`。

异常后的系统行为也与销毁失败一致：

```text
11-27 02:44:37.762 ActivityManager: START ... com.fongmi.android.tv.ui.activity.CrashActivity ...
11-27 02:44:37.774 Process: Sending signal. PID: 16345 SIG: 9
11-27 02:44:38.002 ActivityManager: Process com.newtv.android.exo (pid 16345) has died
11-27 02:44:38.014 ActivityManager: Scheduling restart of crashed service ... PlaybackService in 1000ms
```

日志中还出现了 `ToastGuard` 的 `java.lang.reflect.Executable` 类缺失记录。它发生在进程被终止、崩溃页及服务重新启动之后，不是本次 `Unable to destroy activity` 异常的起因。

## 4. 根因

`VideoActivity` 在创建时注册了两个弱引用回调：

- `DanmakuHeat.addListener(mHeatListener)`
- `TmdbApi.addOnFetched(mTmdbRefresh)`

在 `onDestroy()` 中依次注销：

```java
DanmakuHeat.removeListener(mHeatListener);
TmdbApi.removeOnFetched(mTmdbRefresh);
```

两个回调列表都使用 `CopyOnWriteArrayList`。修复前，两处注销都调用列表的 `removeIf`：

```java
LISTENERS.removeIf(ref -> {
    Listener target = ref.get();
    return target == null || target == listener;
});
```

Android 7.1.2 的 `CopyOnWriteArrayList` 没有兼容当前调用方式的 `removeIf` 实现，最终进入 `java.util.Collection.removeIf`。该方法通过列表迭代器删除命中项，而 `CopyOnWriteArrayList` 的快照迭代器 `CowIterator` 不支持 `remove()`。

因此，当注销条件命中已注册的页面回调时，调用链为：

```text
VideoActivity.onDestroy()
  -> DanmakuHeat.removeListener()
  -> CopyOnWriteArrayList.removeIf()
  -> Collection.removeIf()
  -> CopyOnWriteArrayList.CowIterator.remove()
  -> UnsupportedOperationException
```

Activity 销毁方法抛出未捕获异常，被系统包装为：

```text
RuntimeException: Unable to destroy activity
```

这解释了为什么故障集中表现为“播放视频后退出程序崩溃”，而不是播放过程中崩溃。

只修复第一处还不够：`TmdbApi.removeOnFetched()` 使用相同的集合和删除模式，而且就在下一行执行；第一处修好后，它仍会在同一台 Android 7.1.2 设备上继续触发相同异常。

## 5. 修复方式

采用 `CopyOnWriteArrayList` 的快照遍历，并直接调用列表自身的 `remove(ref)`，不调用迭代器的 `remove()`。这样仍保留线程安全列表语义，同时兼容 Android 7.1.2。

### 5.1 弹幕热力图监听器

文件：`app/src/main/java/com/fongmi/android/tv/ui/danmaku/DanmakuHeat.java`

```java
public static void removeListener(Listener listener) {
    if (listener == null) return;
    // Android 7.1 的 CopyOnWriteArrayList.removeIf 会调用不支持的迭代器删除。
    for (WeakReference<Listener> ref : LISTENERS) {
        Listener target = ref.get();
        if (target == null || target == listener) LISTENERS.remove(ref);
    }
}
```

### 5.2 TMDB 抓取完成回调

文件：`app/src/main/java/com/fongmi/android/tv/api/TmdbApi.java`

```java
public static void removeOnFetched(Runnable callback) {
    if (callback == null) return;
    // Android 7.1 的 CopyOnWriteArrayList.removeIf 会调用不支持的迭代器删除。
    for (WeakReference<Runnable> ref : ON_FETCHED) {
        Runnable target = ref.get();
        if (target == null || target == callback) ON_FETCHED.remove(ref);
    }
}
```

两处均继续清理已经回收的弱引用和当前页面注册的弱引用，没有改变监听器的业务行为，也没有扩大改动范围。

## 6. 静态验证

已完成以下检查：

- `git diff --check` 通过，没有空白或冲突标记问题。
- `DanmakuHeat.java` 不再调用 `LISTENERS.removeIf`。
- `TmdbApi.java` 不再调用 `ON_FETCHED.removeIf`。
- 两个文件均存在 `LISTENERS.remove(ref)` / `ON_FETCHED.remove(ref)`。
- 两个注销方法保持原有的空参数保护、弱引用回收清理和页面回调匹配逻辑。
- 核对 `VideoActivity.onDestroy()` 的注册、注销顺序，确认两处相同风险均已处理。

静态检查不能替代真机运行验证，最终结果以新 APK 在 Android 7.1.2 设备上的复测为准。

## 7. 云端构建与真机复验

### 7.1 云端构建结果

- 修复提交：`7bb6b244f1fd1bed02d34ee8649d064ac2cca776`
- 提交标题：`fix: 修复 Android 7.1 播放页退出崩溃`
- 工作流：`Build & Release (leanback)`
- Actions run：[45882771666](https://github.com/GamePCStudio/NewTV2/actions/runs/45882771666)
- 构建结果：`success`
- Release：[v5.8.7.NewTV2.20261127.032004](https://github.com/GamePCStudio/NewTV2/releases/tag/v5.8.7.NewTV2.20261127.032004)
- 新版本：`5.8.7.NewTV2.20261127.032004`
- 新 versionCode：`95720804`
- 本地目录：`C:\Users\GamePC\Documents\Qoder\2026-11-25\48c3c228\NewTV2\apk\v5.8.7.NewTV2.20261127.032004`

### 7.2 四套 APK 校验

| 文件 | 包名 | ABI | SHA-256 |
|---|---|---|---|
| `leanback-arm64_v8a.apk` | `com.fongmi.android.tv` | `arm64-v8a` | `9715c1457778cfcd054dd5ba9e5d6ffd35cdd91140b9730012ee9c324ba7a053` |
| `leanback-armeabi_v7a.apk` | `com.fongmi.android.tv` | `armeabi-v7a` | `76aed15b716c88595a32692fa613fefaaed2bce66ebe3e3efb91ab036961e79a` |
| `newtv2-leanback-arm64_v8a.apk` | `com.newtv.android.exo` | `arm64-v8a` | `2a5727ba754909512bc236ae50ef3e3c2bedd743d5b85dd3bf2d439a98eae4f0` |
| `newtv2-leanback-armeabi_v7a.apk` | `com.newtv.android.exo` | `armeabi-v7a` | `c82996343055490f00d7317dfe5c6f0fd055d671dce8ca1491da6750a6d4e605` |

校验结果：

- 四套 APK 的应用名均为“天天至臻影院”。
- 四套 SHA-256 均与 GitHub Release 资产摘要一致。
- 使用 Google `apksig` 实际验证四套 APK 的 v2 签名，结果均为 `verified=true`、`errors=[]`、`warnings=[]`。
- 签名证书 SHA-256 为 `b10750e52e230acaca3946951ec5c0cfaeb7e636e1b35b8dd35c64df2687b802`，与故障版本一致。
- 目标设备安装的是 `newtv2-leanback-arm64_v8a.apk`。

### 7.3 Android 7.1.2 真机复验

覆盖安装命令：

```bash
adb install -r newtv2-leanback-arm64_v8a.apk
```

安装结果为 `Success`。设备上的版本信息确认为 `5.8.7.NewTV2.20261127.032004` / versionCode `95720804` / `arm64-v8a`。

验证过程：

1. 启动新版应用，进程 PID 为 `14168`。
2. 从“最近观看”进入《心灵猎人 第一季》，站源为“玩偶•4K”。
3. `dumpsys activity activities` 确认 `VideoActivity` 为 resumed Activity，并连接 `PlaybackService`。
4. 实际进入全屏播放；截图中可见视频画面和中文字幕“我表现得怎么样?”，确认不是仅打开详情页。
5. 清空设备 logcat 后开始退出复验。第一次返回键退出全屏播放层，`VideoActivity` 仍在运行；继续按返回键才真正退出播放页并触发销毁流程。
6. `2026-11-27 03:46:04`，`dumpsys activity activities` 确认任务中只剩 `HomeActivity`，`mResumedActivity` 和 `mFocusedActivity` 均为 `HomeActivity`，原 `VideoActivity` 已退出任务。
7. 同一时刻 `ExoPlayerImpl: Release` 完成，播放器与解码资源正常释放。
8. 退出后 `pidof com.newtv.android.exo` 仍返回 `14168`，原进程没有死亡，也没有启动 `CrashActivity`。

关键退出日志：

```text
11-27 03:46:04.269 14168 14168 D AndroidAutoSize: com.fongmi.android.tv.ui.activity.HomeActivity used the global configuration.
11-27 03:46:04.549 14168 14168 I ExoPlayerImpl: Release 5fb3948 [AndroidXMedia3/1.11.0] [X12-2027, X12-2027, Phicomm, 25]
```

清空日志后采集的退出日志中未出现以下任一内容：

- `FATAL EXCEPTION`
- `CustomActivityOnCrash`
- `UnsupportedOperationException`
- `CowIterator`
- `Unable to destroy activity`
- `CrashActivity`
- `Process com.newtv.android.exo ... has died`

### 7.4 验证结论

新版已在故障设备上完成“实际播放 → 退出全屏 → 销毁 `VideoActivity` → 返回主页”的完整复验。`VideoActivity` 正常销毁，播放资源正常释放，应用进程存活，未再出现 Android 7.1.2 的 `CopyOnWriteArrayList.CowIterator.remove` 异常。本次退出崩溃修复通过真机验证。

## 8. 同版本附带调整

本次提交还包含此前已完成、尚未出包的站源名截断调整：

- 主页站源名上限：16 → 20；
- 片库页站源名上限：6 → 16；
- 继续按 Unicode 字形簇截断，避免拆散 emoji 组合序列。

该调整与本次退出崩溃修复相互独立。
