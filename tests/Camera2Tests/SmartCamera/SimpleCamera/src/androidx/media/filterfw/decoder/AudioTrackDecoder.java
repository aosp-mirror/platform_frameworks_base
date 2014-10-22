/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media.filterfw.decoder;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;

import androidx.media.filterfw.FrameValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * {@link TrackDecoder} for decoding audio tracks.
 *
 * TODO: find out if we always get 16 bits per channel and document.
 */
@TargetApi(16)
public class AudioTrackDecoder extends TrackDecoder {

    private final ByteArrayOutputStream mAudioByteStream; // Guarded by mAudioByteStreamLock.
    private final Object mAudioByteStreamLock;

    private int mAudioSampleRate;
    private int mAudioChannelCount;
    private long mAudioPresentationTimeUs;

    public AudioTrackDecoder(int trackIndex, MediaFormat format, Listener listener) {
        super(trackIndex, format, listener);

        if (!DecoderUtil.isAudioFormat(format)) {
            throw new IllegalArgumentException(
                    "AudioTrackDecoder can only be used with audio formats");
        }

        mAudioByteStream = new ByteArrayOutputStream();
        mAudioByteStreamLock = new Object();

        mAudioSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        mAudioChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
    }

    @Override
    protected MediaCodec initMediaCodec(MediaFormat format) {
        MediaCodec mediaCodec;
        try {
            mediaCodec = MediaCodec.createDecoderByType(
                    format.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new RuntimeException(
                    "failed to create decoder for "
                    + format.getString(MediaFormat.KEY_MIME), e);
        }
        mediaCodec.configure(format, null, null, 0);
        return mediaCodec;
    }

    @Override
    protected boolean onDataAvailable(
            MediaCodec codec, ByteBuffer[] buffers, int bufferIndex, BufferInfo info) {
        ByteBuffer buffer = buffers[bufferIndex];
        byte[] data = new byte[info.size];
        buffer.position(info.offset);
        buffer.get(data, 0, info.size);

        synchronized (mAudioByteStreamLock) {
            try {
                if (mAudioByteStream.size() == 0 && data.length > 0) {
                    mAudioPresentationTimeUs = info.presentationTimeUs;
                }

                mAudioByteStream.write(data);
            } catch (IOException e) {
                // Just drop the audio sample.
            }
        }

        buffer.clear();
        codec.releaseOutputBuffer(bufferIndex, false);
        notifyListener();
        return true;
    }

    /**
     * Fills the argument {@link FrameValue} with an audio sample containing the audio that was
     * decoded since the last call of this method. The decoder's buffer is cleared as a result.
     */
    public void grabSample(FrameValue audioFrame) {
        synchronized (mAudioByteStreamLock) {
            if (audioFrame != null) {
                AudioSample sample = new AudioSample(
                        mAudioSampleRate, mAudioChannelCount, mAudioByteStream.toByteArray());
                audioFrame.setValue(sample);
                audioFrame.setTimestamp(mAudioPresentationTimeUs * 1000);
            }
            clearBuffer();
        }
    }

    /**
     * Clears the decoder's buffer.
     */
    public void clearBuffer() {
        synchronized (mAudioByteStreamLock) {
            mAudioByteStream.reset();
        }
    }

}
