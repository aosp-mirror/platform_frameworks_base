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
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;

@TargetApi(16)
abstract class TrackDecoder {

    interface Listener {
        void onDecodedOutputAvailable(TrackDecoder decoder);

        void onEndOfStream(TrackDecoder decoder);
    }

    private static final String LOG_TAG = "TrackDecoder";

    private static final long TIMEOUT_US = 50; // Timeout for en-queueing and de-queueing buffers.

    private static final int NO_INPUT_BUFFER = -1;

    private final int mTrackIndex;
    private final MediaFormat mMediaFormat;
    private final Listener mListener;

    private MediaCodec mMediaCodec;
    private MediaFormat mOutputFormat;

    private ByteBuffer[] mCodecInputBuffers;
    private ByteBuffer[] mCodecOutputBuffers;

    private boolean mShouldEnqueueEndOfStream;

    /**
     * @return a configured {@link MediaCodec}.
     */
    protected abstract MediaCodec initMediaCodec(MediaFormat format);

    /**
     * Called when decoded output is available. The implementer is responsible for releasing the
     * assigned buffer.
     *
     * @return {@code true} if any further decoding should be attempted at the moment.
     */
    protected abstract boolean onDataAvailable(
            MediaCodec codec, ByteBuffer[] buffers, int bufferIndex, BufferInfo info);

    protected TrackDecoder(int trackIndex, MediaFormat mediaFormat, Listener listener) {
        mTrackIndex = trackIndex;

        if (mediaFormat == null) {
            throw new NullPointerException("mediaFormat cannot be null");
        }
        mMediaFormat = mediaFormat;

        if (listener == null) {
            throw new NullPointerException("listener cannot be null");
        }
        mListener = listener;
    }

    public void init() {
        mMediaCodec = initMediaCodec(mMediaFormat);
        mMediaCodec.start();
        mCodecInputBuffers = mMediaCodec.getInputBuffers();
        mCodecOutputBuffers = mMediaCodec.getOutputBuffers();
    }

    public void signalEndOfInput() {
        mShouldEnqueueEndOfStream = true;
        tryEnqueueEndOfStream();
    }

    public void release() {
        if (mMediaCodec != null) {
            mMediaCodec.stop();
            mMediaCodec.release();
        }
    }

    protected MediaCodec getMediaCodec() {
        return mMediaCodec;
    }

    protected void notifyListener() {
        mListener.onDecodedOutputAvailable(this);
    }

    public boolean feedInput(MediaExtractor mediaExtractor) {
        long presentationTimeUs = 0;

        int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_US);
        if (inputBufferIndex != NO_INPUT_BUFFER) {
            ByteBuffer destinationBuffer = mCodecInputBuffers[inputBufferIndex];
            int sampleSize = mediaExtractor.readSampleData(destinationBuffer, 0);
            // We don't expect to get a sample without any data, so this should never happen.
            if (sampleSize < 0) {
                Log.w(LOG_TAG, "Media extractor had sample but no data.");

                // Signal the end of the track immediately anyway, using the buffer.
                mMediaCodec.queueInputBuffer(
                        inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                return false;
            }

            presentationTimeUs = mediaExtractor.getSampleTime();
            mMediaCodec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    sampleSize,
                    presentationTimeUs,
                    0);

            return mediaExtractor.advance()
                    && mediaExtractor.getSampleTrackIndex() == mTrackIndex;
        }
        return false;
    }

    private void tryEnqueueEndOfStream() {
        int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_US);
        // We will always eventually have an input buffer, because we keep trying until the last
        // decoded frame is output.
        // The EoS does not need to be signaled if the application stops decoding.
        if (inputBufferIndex != NO_INPUT_BUFFER) {
            mMediaCodec.queueInputBuffer(
                    inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            mShouldEnqueueEndOfStream = false;
        }
    }

    public boolean drainOutputBuffer() {
        BufferInfo outputInfo = new BufferInfo();
        int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(outputInfo, TIMEOUT_US);

        if ((outputInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mListener.onEndOfStream(this);
            return false;
        }
        if (mShouldEnqueueEndOfStream) {
            tryEnqueueEndOfStream();
        }
        if (outputBufferIndex >= 0) {
            return onDataAvailable(
                    mMediaCodec, mCodecOutputBuffers, outputBufferIndex, outputInfo);
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            mCodecOutputBuffers = mMediaCodec.getOutputBuffers();
            return true;
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            mOutputFormat = mMediaCodec.getOutputFormat();
            Log.d(LOG_TAG, "Output format has changed to " + mOutputFormat);
            return true;
        }
        return false;
    }

}
