package com.fongmi.android.tv.player.track;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import com.fongmi.android.tv.player.compat.DecoderMode;

import java.util.ArrayList;
import java.util.List;

public final class DecodeCodecSelector implements MediaCodecSelector {

    public static final boolean FFMPEG_AUDIO_USABLE = true;
    public static final boolean FFMPEG_VIDEO_USABLE = false;

    private static volatile DecoderMode audioMode = DecoderMode.AUTO;
    private static volatile DecoderMode videoMode = DecoderMode.AUTO;

    private final TrueHdAudioOutput trueHdOutput;

    public DecodeCodecSelector(TrueHdAudioOutput trueHdOutput) {
        this.trueHdOutput = trueHdOutput;
    }

    public static void setMode(@C.TrackType int trackType, DecoderMode mode) {
        if (mode == null) return;
        if (trackType == C.TRACK_TYPE_VIDEO) videoMode = mode;
        else if (trackType == C.TRACK_TYPE_AUDIO) audioMode = mode;
    }

    @NonNull
    public static DecoderMode getMode(@C.TrackType int trackType) {
        if (trackType == C.TRACK_TYPE_VIDEO) return videoMode;
        if (trackType == C.TRACK_TYPE_AUDIO) return audioMode;
        return DecoderMode.AUTO;
    }

    public static void reset(DecoderMode audio, DecoderMode video) {
        audioMode = audio == null ? DecoderMode.AUTO : audio;
        videoMode = video == null ? DecoderMode.AUTO : video;
    }

    @NonNull
    @Override
    public List<MediaCodecInfo> getDecoderInfos(
            @NonNull String mimeType,
            boolean requiresSecureDecoder,
            boolean requiresTunnelingDecoder)
            throws MediaCodecUtil.DecoderQueryException {
        List<MediaCodecInfo> infos =
                MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
        boolean video = MimeTypes.isVideo(mimeType);
        if (!video && MimeTypes.AUDIO_TRUEHD.equals(mimeType) && trueHdOutput.rejectsTrueHd()) {
            return List.of();
        }
        return filter(infos, video ? videoMode : audioMode, video);
    }

    private static List<MediaCodecInfo> filter(
            List<MediaCodecInfo> infos, DecoderMode mode, boolean video) {
        if (mode == null || mode == DecoderMode.AUTO) return infos;
        boolean ffmpegUsable = video ? FFMPEG_VIDEO_USABLE : FFMPEG_AUDIO_USABLE;
        if (mode == DecoderMode.FFMPEG && ffmpegUsable) return List.of();
        DecoderMode effective = mode == DecoderMode.FFMPEG ? DecoderMode.SOFTWARE : mode;
        List<MediaCodecInfo> result = new ArrayList<>(infos.size());
        for (MediaCodecInfo info : infos) {
            if (effective == DecoderMode.HARDWARE && info.hardwareAccelerated) result.add(info);
            else if (effective == DecoderMode.SOFTWARE && info.softwareOnly) result.add(info);
        }
        return result.isEmpty() ? infos : result;
    }
}
