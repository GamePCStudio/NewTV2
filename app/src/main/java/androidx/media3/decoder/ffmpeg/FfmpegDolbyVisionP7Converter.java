package androidx.media3.decoder.ffmpeg;

import java.nio.ByteBuffer;

final class FfmpegDolbyVisionP7Converter {
    private FfmpegDolbyVisionP7Converter() {}

    private static native boolean ffmpegIsDolbyVisionP7ConversionAvailable(int profile);

    private static native long ffmpegCreateDolbyVisionP7Converter(
            int width, int height, boolean hasHdr10Plus, byte[] config);

    private static native int ffmpegConvertDolbyVisionP7(
            long converter, ByteBuffer input, int width, int height);

    private static native void ffmpegFlushDolbyVisionP7Converter(long converter);

    private static native void ffmpegReleaseDolbyVisionP7Converter(long converter);
}
