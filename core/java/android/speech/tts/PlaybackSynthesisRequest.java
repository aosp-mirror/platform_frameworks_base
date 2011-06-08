/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.speech.tts;

import android.os.Bundle;
import android.speech.tts.TextToSpeechService.UtteranceCompletedDispatcher;
import android.util.Log;

/**
 * Speech synthesis request that plays the audio as it is received.
 */
class PlaybackSynthesisRequest extends SynthesisRequest {

    private static final String TAG = "PlaybackSynthesisRequest";
    private static final boolean DBG = false;

    private static final int MIN_AUDIO_BUFFER_SIZE = 8192;

    /**
     * Audio stream type. Must be one of the STREAM_ contants defined in
     * {@link android.media.AudioManager}.
     */
    private final int mStreamType;

    /**
     * Volume, in the range [0.0f, 1.0f]. The default value is
     * {@link TextToSpeech.Engine#DEFAULT_VOLUME} (1.0f).
     */
    private final float mVolume;

    /**
     * Left/right position of the audio, in the range [-1.0f, 1.0f].
     * The default value is {@link TextToSpeech.Engine#DEFAULT_PAN} (0.0f).
     */
    private final float mPan;

    /**
     * Guards {@link #mAudioTrackHandler}, {@link #mToken} and {@link #mStopped}.
     */
    private final Object mStateLock = new Object();

    // Handler associated with a thread that plays back audio requests.
    private final AudioPlaybackHandler mAudioTrackHandler;
    // A request "token", which will be non null after start() or
    // completeAudioAvailable() have been called.
    private SynthesisMessageParams mToken = null;
    // Whether this request has been stopped. This is useful for keeping
    // track whether stop() has been called before start(). In all other cases,
    // a non-null value of mToken will provide the same information.
    private boolean mStopped = false;

    private volatile boolean mDone = false;

    private final UtteranceCompletedDispatcher mDispatcher;

    PlaybackSynthesisRequest(String text, Bundle params, int streamType, float volume, float pan,
            AudioPlaybackHandler audioTrackHandler, UtteranceCompletedDispatcher dispatcher) {
        super(text, params);
        mStreamType = streamType;
        mVolume = volume;
        mPan = pan;
        mAudioTrackHandler = audioTrackHandler;
        mDispatcher = dispatcher;
    }

    @Override
    void stop() {
        if (DBG) Log.d(TAG, "stop()");

        synchronized (mStateLock) {
            if (mToken == null || mStopped) {
                Log.w(TAG, "stop() called twice, before start(), or after done()");
                return;
            }
            mAudioTrackHandler.stop(mToken);
            mToken = null;
            mStopped = true;
        }
    }

    @Override
    public int getMaxBufferSize() {
        // The AudioTrack buffer will be at least MIN_AUDIO_BUFFER_SIZE, so that should always be
        // a safe buffer size to pass in.
        return MIN_AUDIO_BUFFER_SIZE;
    }

    @Override
    boolean isDone() {
        return mDone;
    }

    @Override
    public int start(int sampleRateInHz, int audioFormat, int channelCount) {
        if (DBG) {
            Log.d(TAG, "start(" + sampleRateInHz + "," + audioFormat
                    + "," + channelCount + ")");
        }

        int channelConfig = AudioPlaybackHandler.getChannelConfig(channelCount);
        if (channelConfig == 0) {
            Log.e(TAG, "Unsupported number of channels :" + channelCount);
            return TextToSpeech.ERROR;
        }

        synchronized (mStateLock) {
            if (mStopped) {
                if (DBG) Log.d(TAG, "stop() called before start(), returning.");
                return TextToSpeech.ERROR;
            }
            SynthesisMessageParams params = new SynthesisMessageParams(
                    mStreamType, sampleRateInHz, audioFormat, channelCount, mVolume, mPan,
                    mDispatcher);
            mAudioTrackHandler.enqueueSynthesisStart(params);

            mToken = params;
        }

        return TextToSpeech.SUCCESS;
    }


    @Override
    public int audioAvailable(byte[] buffer, int offset, int length) {
        if (DBG) {
            Log.d(TAG, "audioAvailable(byte[" + buffer.length + "],"
                    + offset + "," + length + ")");
        }
        if (length > getMaxBufferSize() || length <= 0) {
            throw new IllegalArgumentException("buffer is too large or of zero length (" +
                    + length + " bytes)");
        }

        synchronized (mStateLock) {
            if (mToken == null) {
                return TextToSpeech.ERROR;
            }

            // Sigh, another copy.
            final byte[] bufferCopy = new byte[length];
            System.arraycopy(buffer, offset, bufferCopy, 0, length);
            mToken.addBuffer(bufferCopy);
            mAudioTrackHandler.enqueueSynthesisDataAvailable(mToken);
        }

        return TextToSpeech.SUCCESS;
    }

    @Override
    public int done() {
        if (DBG) Log.d(TAG, "done()");

        synchronized (mStateLock) {
            if (mDone) {
                Log.w(TAG, "Duplicate call to done()");
                return TextToSpeech.ERROR;
            }

            mDone = true;

            if (mToken == null) {
                return TextToSpeech.ERROR;
            }

            mAudioTrackHandler.enqueueSynthesisDone(mToken);
        }
        return TextToSpeech.SUCCESS;
    }

    @Override
    public void error() {
        if (DBG) Log.d(TAG, "error() [will call stop]");
        stop();
    }

    @Override
    public int completeAudioAvailable(int sampleRateInHz, int audioFormat, int channelCount,
            byte[] buffer, int offset, int length) {
        int channelConfig = AudioPlaybackHandler.getChannelConfig(channelCount);
        if (channelConfig == 0) {
            Log.e(TAG, "Unsupported number of channels :" + channelCount);
            return TextToSpeech.ERROR;
        }

        int bytesPerFrame = AudioPlaybackHandler.getBytesPerFrame(audioFormat);
        if (bytesPerFrame < 0) {
            Log.e(TAG, "Unsupported audio format :" + audioFormat);
            return TextToSpeech.ERROR;
        }

        synchronized (mStateLock) {
            if (mStopped) {
                return TextToSpeech.ERROR;
            }
            SynthesisMessageParams params = new SynthesisMessageParams(
                    mStreamType, sampleRateInHz, audioFormat, channelCount, mVolume, mPan,
                    mDispatcher);
            params.addBuffer(buffer, offset, length);

            mAudioTrackHandler.enqueueSynthesisCompleteDataAvailable(params);
            mToken = params;
        }

        return TextToSpeech.SUCCESS;
    }

}
