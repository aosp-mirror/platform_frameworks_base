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

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

/**
 * Writes data about a given speech synthesis request to the event logs.
 * The data that is logged includes the calling app, length of the utterance,
 * speech rate / pitch and the latency and overall time taken.
 *
 * Note that {@link EventLogger#onStopped()} and {@link EventLogger#onError()}
 * might be called from any thread, but on {@link EventLogger#onAudioDataWritten()} and
 * {@link EventLogger#onComplete()} must be called from a single thread
 * (usually the audio playback thread}
 */
class EventLogger {
    private final SynthesisRequest mRequest;
    private final String mServiceApp;
    private final int mCallerUid;
    private final int mCallerPid;
    private final long mReceivedTime;
    private long mPlaybackStartTime = -1;
    private volatile long mRequestProcessingStartTime = -1;
    private volatile long mEngineStartTime = -1;
    private volatile long mEngineCompleteTime = -1;

    private volatile boolean mError = false;
    private volatile boolean mStopped = false;
    private boolean mLogWritten = false;

    EventLogger(SynthesisRequest request, int callerUid, int callerPid, String serviceApp) {
        mRequest = request;
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
     * Notifies the logger that the current synthesis was stopped.
     * Latency numbers are not reported for stopped syntheses.
     */
    public void onStopped() {
        mStopped = false;
    }

    /**
     * Notifies the logger that the current synthesis resulted in
     * an error. This is logged using {@link EventLogTags#writeTtsSpeakFailure}.
     */
    public void onError() {
        mError = true;
    }

    /**
     * Notifies the logger that the current synthesis has completed.
     * All available data is not logged.
     */
    public void onWriteData() {
        if (mLogWritten) {
            return;
        } else {
            mLogWritten = true;
        }

        long completionTime = SystemClock.elapsedRealtime();
        // onAudioDataWritten() should normally always be called if an
        // error does not occur.
        if (mError || mPlaybackStartTime == -1 || mEngineCompleteTime == -1) {
            EventLogTags.writeTtsSpeakFailure(mServiceApp, mCallerUid, mCallerPid,
                    getUtteranceLength(), getLocaleString(),
                    mRequest.getSpeechRate(), mRequest.getPitch());
            return;
        }

        // We don't report stopped syntheses because their overall
        // total time spent will be innacurate (will not correlate with
        // the length of the utterance).
        if (mStopped) {
            return;
        }

        final long audioLatency = mPlaybackStartTime - mReceivedTime;
        final long engineLatency = mEngineStartTime - mRequestProcessingStartTime;
        final long engineTotal = mEngineCompleteTime - mRequestProcessingStartTime;

        EventLogTags.writeTtsSpeakSuccess(mServiceApp, mCallerUid, mCallerPid,
                getUtteranceLength(), getLocaleString(),
                mRequest.getSpeechRate(), mRequest.getPitch(),
                engineLatency, engineTotal, audioLatency);
    }

    /**
     * @return the length of the utterance for the given synthesis, 0
     *          if the utterance was {@code null}.
     */
    private int getUtteranceLength() {
        final String utterance = mRequest.getText();
        return utterance == null ? 0 : utterance.length();
    }

    /**
     * Returns a formatted locale string from the synthesis params of the
     * form lang-country-variant.
     */
    private String getLocaleString() {
        StringBuilder sb = new StringBuilder(mRequest.getLanguage());
        if (!TextUtils.isEmpty(mRequest.getCountry())) {
            sb.append('-');
            sb.append(mRequest.getCountry());

            if (!TextUtils.isEmpty(mRequest.getVariant())) {
                sb.append('-');
                sb.append(mRequest.getVariant());
            }
        }

        return sb.toString();
    }

}
