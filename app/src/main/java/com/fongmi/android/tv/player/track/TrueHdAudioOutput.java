package com.fongmi.android.tv.player.track;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

import java.util.Locale;

public final class TrueHdAudioOutput {

    public enum Stage {
        PASSTHROUGH,
        FFMPEG_PCM,
        FFMPEG_PCM_2_0,
        OTHER_TRACK
    }

    private volatile Stage stage = Stage.PASSTHROUGH;

    public void reset() {
        stage = Stage.PASSTHROUGH;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public Stage getStage() {
        return stage;
    }

    public boolean isFallbackActive() {
        return stage != Stage.PASSTHROUGH;
    }

    public boolean isFfmpegPcm() {
        return stage == Stage.FFMPEG_PCM || stage == Stage.FFMPEG_PCM_2_0;
    }

    public boolean rejectsTrueHd() {
        return stage != Stage.PASSTHROUGH;
    }

    public boolean isStereoOnly() {
        return stage == Stage.FFMPEG_PCM_2_0;
    }

    public AudioSink wrap(AudioSink sink) {
        return new PolicyAudioSink(sink, this);
    }

    public static boolean isTrueHd(@Nullable Format format) {
        if (format == null) return false;
        if (format.pcmEncoding == C.ENCODING_DOLBY_TRUEHD) return true;
        if (MimeTypes.AUDIO_TRUEHD.equals(format.sampleMimeType)) return true;
        return format.codecs != null && format.codecs.toLowerCase(Locale.ROOT).contains("truehd");
    }

    private static final class PolicyAudioSink extends ForwardingAudioSink {

        private final TrueHdAudioOutput policy;

        private PolicyAudioSink(AudioSink sink, TrueHdAudioOutput policy) {
            super(sink);
            this.policy = policy;
        }

        @Override
        public boolean supportsFormat(Format format) {
            return getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED;
        }

        @Override
        public int getFormatSupport(Format format) {
            if (policy.rejectsTrueHd() && isTrueHd(format)) {
                return AudioSink.SINK_FORMAT_UNSUPPORTED;
            }
            if (policy.isStereoOnly()
                    && (format.pcmEncoding == C.ENCODING_PCM_16BIT
                    || format.pcmEncoding == C.ENCODING_PCM_FLOAT)
                    && format.channelCount > 2) {
                // Float output bypasses custom AudioProcessors in this Media3 build.
                if (format.pcmEncoding == C.ENCODING_PCM_FLOAT
                        || (format.channelCount != 6
                        && format.channelCount != 8)) {
                    return AudioSink.SINK_FORMAT_UNSUPPORTED;
                }
                Format stereo = format.buildUpon()
                        .setChannelCount(2)
                        .setChannelMask(Format.NO_VALUE)
                        .build();
                return super.getFormatSupport(stereo);
            }
            return super.getFormatSupport(format);
        }
    }
}
