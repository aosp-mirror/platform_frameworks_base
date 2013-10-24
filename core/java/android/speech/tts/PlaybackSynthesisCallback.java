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

import android.speech.tts.TextToSpeechService.UtteranceProgressDispatcher;
import android.util.Log;

/**
 * Speech synthesis request that plays the audio as it is received.
 */
class PlaybackSynthesisCallback extends AbstractSynthesisCallback {

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
     * Guards {@link #mAudioTrackHandler}, {@link #mItem} and {@link #mStopped}.
     */
    private final Object mStateLock = new Object();

    // Handler associated with a thread that plays back audio requests.
    private final AudioPlaybackHandler mAudioTrackHandler;
    // A request "token", which will be non null after start() has been called.
    private SynthesisPlaybackQueueItem mItem = null;

    private volatile boolean mDone = false;

    /** Status code of synthesis */
    protected int mStatusCode;

    private final UtteranceProgressDispatcher mDispatcher;
    private final Object mCallerIdentity;
    private final AbstractEventLogger mLogger;

    PlaybackSynthesisCallback(int streamType, float volume, float pan,
            AudioPlaybackHandler audioTrackHandler, UtteranceProgressDispatcher dispatcher,
            Object callerIdentity, AbstractEventLogger logger, boolean clientIsUsingV2) {
        super(clientIsUsingV2);
        mStreamType = streamType;
        mVolume = volume;
        mPan = pan;
        mAudioTrackHandler = audioTrackHandler;
        mDispatcher = dispatcher;
        mCallerIdentity = callerIdentity;
        mLogger = logger;
        mStatusCode = TextToSpeechClient.Status.SUCCESS;
    }

    @Override
    void stop() {
        if (DBG) Log.d(TAG, "stop()");

        SynthesisPlaybackQueueItem item;
        synchronized (mStateLock) {
            if (mDone) {
                return;
            }
            if (mStatusCode == TextToSpeechClient.Status.STOPPED) {
                Log.w(TAG, "stop() called twice");
                return;
            }

            item = mItem;
            mStatusCode = TextToSpeechClient.Status.STOPPED;
        }

        if (item != null) {
            // This might result in the synthesis thread being woken up, at which
            // point it will write an additional buffer to the item - but we
            // won't worry about that because the audio playback queue will be cleared
            // soon after (see SynthHandler#stop(String).
            item.stop(TextToSpeechClient.Status.STOPPED);
        } else {
            // This happens when stop() or error() were called before start() was.

            // In all other cases, mAudioTrackHandler.stop() will
            // result in onSynthesisDone being called, and we will
            // write data there.
            mLogger.onCompleted(TextToSpeechClient.Status.STOPPED);
            mDispatcher.dispatchOnStop();
        }
    }

    @Override
    public int getMaxBufferSize() {
        // The AudioTrack buffer will be at least MIN_AUDIO_BUFFER_SIZE, so that should always be
        // a safe buffer size to pass in.
        return MIN_AUDIO_BUFFER_SIZE;
    }

    @Override
    public boolean hasStarted() {
        synchronized (mStateLock) {
            return mItem != null;
        }
    }

    @Override
    public boolean hasFinished() {
        synchronized (mStateLock) {
            return mDone;
        }
    }

    @Override
    public int start(int sampleRateInHz, int audioFormat, int channelCount) {
        if (DBG) Log.d(TAG, "start(" + sampleRateInHz + "," + audioFormat + "," + channelCount
                + ")");

        int channelConfig = BlockingAudioTrack.getChannelConfig(channelCount);

        synchronized (mStateLock) {
            if (channelConfig == 0) {
                Log.e(TAG, "Unsupported number of channels :" + channelCount);
                mStatusCode = TextToSpeechClient.Status.ERROR_OUTPUT;
                return TextToSpeech.ERROR;
            }
            if (mStatusCode == TextToSpeechClient.Status.STOPPED) {
                if (DBG) Log.d(TAG, "stop() called before start(), returning.");
                return errorCodeOnStop();
            }
            if (mStatusCode != TextToSpeechClient.Status.SUCCESS) {
                if (DBG) Log.d(TAG, "Error was raised");
                return TextToSpeech.ERROR;
            }
            if (mItem != null) {
                Log.e(TAG, "Start called twice");
                return TextToSpeech.ERROR;
            }
            SynthesisPlaybackQueueItem item = new SynthesisPlaybackQueueItem(
                    mStreamType, sampleRateInHz, audioFormat, channelCount, mVolume, mPan,
                    mDispatcher, mCallerIdentity, mLogger);
            mAudioTrackHandler.enqueue(item);
            mItem = item;
        }

        return TextToSpeech.SUCCESS;
    }

    @Override
    public int audioAvailable(byte[] buffer, int offset, int length) {
        if (DBG) Log.d(TAG, "audioAvailable(byte[" + buffer.length + "]," + offset + "," + length
                + ")");

        if (length > getMaxBufferSize() || length <= 0) {
            throw new IllegalArgumentException("buffer is too large or of zero length (" +
                    + length + " bytes)");
        }

        SynthesisPlaybackQueueItem item = null;
        synchronized (mStateLock) {
            if (mItem == null) {
                mStatusCode = TextToSpeechClient.Status.ERROR_OUTPUT;
                return TextToSpeech.ERROR;
            }
            if (mStatusCode != TextToSpeechClient.Status.SUCCESS) {
                if (DBG) Log.d(TAG, "Error was raised");
                return TextToSpeech.ERROR;
            }
            if (mStatusCode == TextToSpeechClient.Status.STOPPED) {
                return errorCodeOnStop();
            }
            item = mItem;
        }

        // Sigh, another copy.
        final byte[] bufferCopy = new byte[length];
        System.arraycopy(buffer, offset, bufferCopy, 0, length);

        // Might block on mItem.this, if there are too many buffers waiting to
        // be consumed.
        try {
            item.put(bufferCopy);
        } catch (InterruptedException ie) {
            synchronized (mStateLock) {
                mStatusCode = TextToSpeechClient.Status.ERROR_OUTPUT;
                return TextToSpeech.ERROR;
            }
        }

        mLogger.onEngineDataReceived();
        return TextToSpeech.SUCCESS;
    }

    @Override
    public int done() {
        if (DBG) Log.d(TAG, "done()");

        int statusCode = 0;
        SynthesisPlaybackQueueItem item = null;
        synchronized (mStateLock) {
            if (mDone) {
                Log.w(TAG, "Duplicate call to done()");
                // Not an error that would prevent synthesis. Hence no
                // setStatusCode
                return TextToSpeech.ERROR;
            }
            if (mStatusCode == TextToSpeechClient.Status.STOPPED) {
                if (DBG) Log.d(TAG, "Request has been aborted.");
                return errorCodeOnStop();
            }
            mDone = true;

            if (mItem == null) {
                // .done() was called before .start. Treat it as successful synthesis
                // for a client, despite service bad implementation.
                Log.w(TAG, "done() was called before start() call");
                if (mStatusCode == TextToSpeechClient.Status.SUCCESS) {
                    mDispatcher.dispatchOnSuccess();
                } else {
                    mDispatcher.dispatchOnError(mStatusCode);
                }
                mLogger.onEngineComplete();
                return TextToSpeech.ERROR;
            }

            item = mItem;
            statusCode = mStatusCode;
        }

        // Signal done or error to item
        if (statusCode == TextToSpeechClient.Status.SUCCESS) {
            item.done();
        } else {
            item.stop(statusCode);
        }
        mLogger.onEngineComplete();
        return TextToSpeech.SUCCESS;
    }

    @Override
    public void error() {
        error(TextToSpeechClient.Status.ERROR_SYNTHESIS);
    }

    @Override
    public void error(int errorCode) {
        if (DBG) Log.d(TAG, "error() [will call stop]");
        synchronized (mStateLock) {
            if (mDone) {
                return;
            }
            mStatusCode = errorCode;
        }
    }

    @Override
    public int fallback() {
        synchronized (mStateLock) {
            if (hasStarted() || hasFinished()) {
                return TextToSpeech.ERROR;
            }

            mDispatcher.dispatchOnFallback();
            mStatusCode = TextToSpeechClient.Status.SUCCESS;
            return TextToSpeechClient.Status.SUCCESS;
        }
    }
}
