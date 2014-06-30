/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.os.SystemClock;

/**
 * Base class for storing data about a given speech synthesis request to the
 * event logs. The data that is logged depends on actual implementation. Note
 * that {@link AbstractEventLogger#onAudioDataWritten()} and
 * {@link AbstractEventLogger#onEngineComplete()} must be called from a single
 * thread (usually the audio playback thread}.
 */
abstract class AbstractEventLogger {
    protected final String mServiceApp;
    protected final int mCallerUid;
    protected final int mCallerPid;
    protected final long mReceivedTime;
    protected long mPlaybackStartTime = -1;

    private volatile long mRequestProcessingStartTime = -1;
    private volatile long mEngineStartTime = -1;
    private volatile long mEngineCompleteTime = -1;

    private boolean mLogWritten = false;

    AbstractEventLogger(int callerUid, int callerPid, String serviceApp) {
        mCallerUid = callerUid;
        mCallerPid = callerPid;
        mServiceApp = serviceApp;
        mReceivedTime = SystemClock.elapsedRealtime();
    }

    /**
     * Notifies the logger that this request has been selected from
     * the processing queue for processing. Engine latency / total time
     * is measured from this baseline.
     */
    public void onRequestProcessingStart() {
        mRequestProcessingStartTime = SystemClock.elapsedRealtime();
    }

    /**
     * Notifies the logger that a chunk of data has been received from
     * the engine. Might be called multiple times.
     */
    public void onEngineDataReceived() {
        if (mEngineStartTime == -1) {
            mEngineStartTime = SystemClock.elapsedRealtime();
        }
    }

    /**
     * Notifies the logger that the engine has finished processing data.
     * Will be called exactly once.
     */
    public void onEngineComplete() {
        mEngineCompleteTime = SystemClock.elapsedRealtime();
    }

    /**
     * Notifies the logger that audio playback has started for some section
     * of the synthesis. This is normally some amount of time after the engine
     * has synthesized data and varies depending on utterances and
     * other audio currently in the queue.
     */
    public void onAudioDataWritten() {
        // For now, keep track of only the first chunk of audio
        // that was played.
        if (mPlaybackStartTime == -1) {
            mPlaybackStartTime = SystemClock.elapsedRealtime();
        }
    }

    /**
     * Notifies the logger that the current synthesis has completed.
     * All available data is not logged.
     */
    public void onCompleted(int statusCode) {
        if (mLogWritten) {
            return;
        } else {
            mLogWritten = true;
        }

        long completionTime = SystemClock.elapsedRealtime();

        // We don't report latency for stopped syntheses because their overall
        // total time spent will be inaccurate (will not correlate with
        // the length of the utterance).

        // onAudioDataWritten() should normally always be called, and hence mPlaybackStartTime
        // should be set, if an error does not occur.
        if (statusCode != TextToSpeech.SUCCESS
                || mPlaybackStartTime == -1 || mEngineCompleteTime == -1) {
            logFailure(statusCode);
            return;
        }

        final long audioLatency = mPlaybackStartTime - mReceivedTime;
        final long engineLatency = mEngineStartTime - mRequestProcessingStartTime;
        final long engineTotal = mEngineCompleteTime - mRequestProcessingStartTime;
        logSuccess(audioLatency, engineLatency, engineTotal);
    }

    protected abstract void logFailure(int statusCode);
    protected abstract void logSuccess(long audioLatency, long engineLatency,
            long engineTotal);


}
