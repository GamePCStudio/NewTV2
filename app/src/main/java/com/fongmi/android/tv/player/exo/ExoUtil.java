package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.effect.audio.TrueHdDownmixProcessor;
import com.fongmi.android.tv.player.subtitle.AssSubtitleSupport;
import com.fongmi.android.tv.player.track.DecodeCodecSelector;
import com.fongmi.android.tv.player.track.LangUtil;
import com.fongmi.android.tv.player.track.TrueHdAudioOutput;
import com.fongmi.android.tv.setting.DecodeSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.SpeedSetting;
import com.github.catvod.utils.Auth;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * EXO 播放器装配（3a 降级版）。
 *
 * <p>相比上游去掉了三处私有分支依赖：{@code DecoderManager}（解码器编排）、
 * {@code LibassPlaybackSession}（libass 渲染器）、{@code SecondaryTextOutput}（二级字幕渲染器）。
 * 其余缓冲/隧道/杜比视界策略均沿用公开 API。
 */
public final class ExoUtil {

    public static ExoPlayer buildPlayer(AudioProcessor audioProcessor) {
        return buildPlayer(audioProcessor, null);
    }

    public static ExoPlayer buildPlayer(AudioProcessor audioProcessor, @Nullable AssSubtitleSupport ass) {
        return buildPlayer(audioProcessor, ass, new TrueHdAudioOutput());
    }

    public static ExoPlayer buildPlayer(
            AudioProcessor audioProcessor,
            @Nullable AssSubtitleSupport ass,
            TrueHdAudioOutput trueHdOutput) {
        RenderersFactory renderersFactory = buildRenderersFactory(audioProcessor, trueHdOutput);
        if (ass != null) renderersFactory = ass.wrap(renderersFactory);
        ExoPlayer.Builder builder = new ExoPlayer.Builder(App.get(), renderersFactory)
                .setTrackSelector(buildTrackSelector())
                .setLoadControl(buildLoadControl())
                .setSkipSilenceEnabled(SpeedSetting.isSkipSilence());
        ExoPlayer player = builder.build();
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);
        return player;
    }

    static LoadControl buildLoadControl() {
        int buffer = PlayerSetting.getBuffer();
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMsForStreaming(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * buffer, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * buffer, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setBufferDurationsMsForLocalPlayback(DefaultLoadControl.DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS * buffer, DefaultLoadControl.DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS * buffer, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS)
                .build();
    }

    public static Map<String, String> extractHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null) return new HashMap<>();
        return extras.keySet().stream().filter(key -> extras.getString(key) != null).collect(Collectors.toMap(key -> key, extras::getString));
    }

    public static Auth.Context extractAuth(MediaItem item) {
        return item.localConfiguration != null && item.localConfiguration.tag instanceof Auth.Context auth ? auth : null;
    }

    static DefaultTrackSelector buildTrackSelector() {
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(App.get());
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        if (DecodeSetting.isPreferAAC()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        builder.setPreferredTextLanguages(LangUtil.getPreferredTextLanguages());
        builder.setTunnelingEnabled(DecodeSetting.isTunnelingEnabled());
        builder.setForceHighestSupportedBitrate(true);
        trackSelector.setParameters(builder.build());
        return trackSelector;
    }

    static RenderersFactory buildRenderersFactory() {
        return buildRenderersFactory(null);
    }

    static RenderersFactory buildRenderersFactory(@Nullable AudioProcessor audioProcessor) {
        return buildRenderersFactory(audioProcessor, new TrueHdAudioOutput());
    }

    static RenderersFactory buildRenderersFactory(
            @Nullable AudioProcessor audioProcessor, TrueHdAudioOutput trueHdOutput) {
        return new ExoRenderersFactory(audioProcessor, trueHdOutput);
    }

    private static AudioSink buildAudioSink(
            Context context,
            boolean enableFloatOutput,
            boolean enableAudioOutputPlaybackParams,
            @Nullable AudioProcessor audioProcessor,
            TrueHdAudioOutput trueHdOutput) {
        DefaultAudioSink.Builder builder =
                new DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams);
        if (!DecodeSetting.isAudioPassThrough()) {
            builder.setAudioOutputProvider(
                    new AudioTrackAudioOutputProvider.Builder(context).build());
        }
        AudioProcessor[] processors = audioProcessor == null
                ? new AudioProcessor[] {new TrueHdDownmixProcessor(trueHdOutput)}
                : new AudioProcessor[] {
                        new TrueHdDownmixProcessor(trueHdOutput), audioProcessor};
        builder.setAudioProcessors(processors);
        return trueHdOutput.wrap(builder.build());
    }

    private static final class ExoRenderersFactory extends DefaultRenderersFactory {

        @Nullable
        private final AudioProcessor audioProcessor;

        private final TrueHdAudioOutput trueHdOutput;

        private ExoRenderersFactory(
                @Nullable AudioProcessor audioProcessor, TrueHdAudioOutput trueHdOutput) {
            super(App.get());
            this.audioProcessor = audioProcessor;
            this.trueHdOutput = trueHdOutput;
            // 与上游一致：解码器回退由下面的 selector 决定，框架层不再自行轮换，
            // 否则 4K 这类"首个解码器初始化成功但出帧失败"的场景会被反复重试后才报错。
            setEnableDecoderFallback(false);
            setMediaCodecSelector(new DecodeCodecSelector(trueHdOutput));
            experimentalSetEnableMediaCodecVideoRendererPrewarming(PreloadSetting.shouldPrewarmDecoder());
            // AV1 的 sample dependency 不解析会导致 4K AV1 直播出帧错乱或直接解码失败
            experimentalSetParseAv1SampleDependencies(true);
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON);
            setDolbyVisionOutputPolicy(DecodeSetting.getDolbyVisionOutputPolicy());
        }

        @NonNull
        @Override
        public Renderer[] createRenderers(@NonNull Handler eventHandler, @NonNull VideoRendererEventListener videoRendererEventListener, @NonNull AudioRendererEventListener audioRendererEventListener, @NonNull TextOutput textRendererOutput, @NonNull MetadataOutput metadataRendererOutput) {
            Renderer[] renderers =
                    super.createRenderers(
                            eventHandler,
                            videoRendererEventListener,
                            audioRendererEventListener,
                            textRendererOutput,
                            metadataRendererOutput);
            if (DecodeCodecSelector.FFMPEG_VIDEO_USABLE) return renderers;
            return Arrays.stream(renderers)
                    .filter(renderer -> !isFfmpegVideoRenderer(renderer))
                    .toArray(Renderer[]::new);
        }

        private static boolean isFfmpegVideoRenderer(Renderer renderer) {
            for (Class<?> type = renderer.getClass(); type != null; type = type.getSuperclass()) {
                if ("androidx.media3.decoder.ffmpeg.FfmpegVideoRenderer".equals(type.getName())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected AudioSink buildAudioSink(
                @NonNull Context context,
                boolean enableFloatOutput,
                boolean enableAudioOutputPlaybackParams) {
            return ExoUtil.buildAudioSink(
                    context,
                    enableFloatOutput,
                    enableAudioOutputPlaybackParams,
                    audioProcessor,
                    trueHdOutput);
        }
    }
}
