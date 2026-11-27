package com.fongmi.android.tv.player.effect.audio;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import com.fongmi.android.tv.player.track.TrueHdAudioOutput;

import java.nio.ByteBuffer;

public final class TrueHdDownmixProcessor extends BaseAudioProcessor {

    private static final float SURROUND_GAIN = 0.7071f;

    private final TrueHdAudioOutput trueHdOutput;
    private float[] frame = new float[0];

    public TrueHdDownmixProcessor(TrueHdAudioOutput trueHdOutput) {
        this.trueHdOutput = trueHdOutput;
    }

    @NonNull
    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
        boolean pcm = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
                || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT;
        if (!trueHdOutput.isStereoOnly()
                || !pcm
                || (inputAudioFormat.channelCount != 6
                && inputAudioFormat.channelCount != 8)) {
            return AudioFormat.NOT_SET;
        }
        frame = new float[inputAudioFormat.channelCount];
        return new AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding);
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int bytesPerSample = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ? 4 : 2;
        if (inputBuffer.remaining() % inputAudioFormat.bytesPerFrame != 0) {
            throw new IllegalStateException("Queued an incomplete frame.");
        }
        int frames = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame;
        ByteBuffer outputBuffer = replaceOutputBuffer(frames * 2 * bytesPerSample);
        while (inputBuffer.hasRemaining()) {
            for (int channel = 0; channel < frame.length; channel++) {
                float sample = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
                        ? inputBuffer.getFloat()
                        : inputBuffer.getShort() / 32768.0f;
                frame[channel] = Float.isFinite(sample) ? sample : 0.0f;
            }
            float left = sanitize(mixLeft(frame));
            float right = sanitize(mixRight(frame));
            if (bytesPerSample == 4) {
                outputBuffer.putFloat(left);
                outputBuffer.putFloat(right);
            } else {
                outputBuffer.putShort(toPcm16(left));
                outputBuffer.putShort(toPcm16(right));
            }
        }
        outputBuffer.flip();
    }

    private static float mixLeft(float[] samples) {
        float center = samples[2] * SURROUND_GAIN;
        float left = samples[0] + center;
        if (samples.length == 8) {
            return left + samples[4] * SURROUND_GAIN + samples[6] * SURROUND_GAIN;
        }
        return left + samples[4] * SURROUND_GAIN;
    }

    private static float mixRight(float[] samples) {
        float center = samples[2] * SURROUND_GAIN;
        float right = samples[1] + center;
        if (samples.length == 8) {
            return right + samples[5] * SURROUND_GAIN + samples[7] * SURROUND_GAIN;
        }
        return right + samples[5] * SURROUND_GAIN;
    }

    private static float sanitize(float sample) {
        float finite = Float.isFinite(sample) ? sample : 0.0f;
        return Math.max(-1.0f, Math.min(1.0f, finite));
    }

    private static short toPcm16(float sample) {
        return sample <= -1.0f
                ? Short.MIN_VALUE
                : (short) Math.round(sample * Short.MAX_VALUE);
    }
}
