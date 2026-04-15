package com.cappielloantonio.tempo.util;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

public final class ReplayGainAudioProcessor extends BaseAudioProcessor {

    private volatile float pendingGainDb = 0f;
    private float activeGainDb = 0f;
    private float activeLinearGain = 1f;

    public void setPendingGainDb(float gainDb) {
        pendingGainDb = gainDb;
    }

    @Override
    public AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        int encoding = inputAudioFormat.encoding;
        if (encoding != C.ENCODING_PCM_16BIT
                && encoding != C.ENCODING_PCM_24BIT
                && encoding != C.ENCODING_PCM_32BIT
                && encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return;
        }

        ByteBuffer outputBuffer = replaceOutputBuffer(inputBuffer.remaining());
        int encoding = inputAudioFormat.encoding;

        if (encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= 2) {
                short sample = inputBuffer.getShort();
                int scaled = Math.round(sample * activeLinearGain);
                if (scaled > Short.MAX_VALUE) scaled = Short.MAX_VALUE;
                if (scaled < Short.MIN_VALUE) scaled = Short.MIN_VALUE;
                outputBuffer.putShort((short) scaled);
            }
        } else if (encoding == C.ENCODING_PCM_24BIT) {
            while (inputBuffer.remaining() >= 3) {
                int b0 = inputBuffer.get() & 0xFF;
                int b1 = inputBuffer.get() & 0xFF;
                int b2 = inputBuffer.get() & 0xFF;
                int sample = b0 | (b1 << 8) | (b2 << 16);
                if ((sample & 0x00800000) != 0) {
                    sample |= 0xFF000000;
                }
                int scaled = Math.round(sample * activeLinearGain);
                if (scaled > 0x007FFFFF) scaled = 0x007FFFFF;
                if (scaled < -0x00800000) scaled = -0x00800000;
                outputBuffer.put((byte) (scaled & 0xFF));
                outputBuffer.put((byte) ((scaled >> 8) & 0xFF));
                outputBuffer.put((byte) ((scaled >> 16) & 0xFF));
            }
        } else if (encoding == C.ENCODING_PCM_32BIT) {
            while (inputBuffer.remaining() >= 4) {
                int sample = inputBuffer.getInt();
                long scaled = Math.round(sample * activeLinearGain);
                if (scaled > Integer.MAX_VALUE) scaled = Integer.MAX_VALUE;
                if (scaled < Integer.MIN_VALUE) scaled = Integer.MIN_VALUE;
                outputBuffer.putInt((int) scaled);
            }
        } else {
            while (inputBuffer.remaining() >= 4) {
                float sample = inputBuffer.getFloat();
                float scaled = sample * activeLinearGain;
                if (scaled > 1f) scaled = 1f;
                if (scaled < -1f) scaled = -1f;
                outputBuffer.putFloat(scaled);
            }
        }

        outputBuffer.flip();
    }

    @Override
    protected void onFlush() {
        activeGainDb = pendingGainDb;
        activeLinearGain = (float) Math.pow(10.0, activeGainDb / 20.0);
    }

    @Override
    protected void onReset() {
        pendingGainDb = 0f;
        activeGainDb = 0f;
        activeLinearGain = 1f;
    }
}
