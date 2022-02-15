/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.companion.virtual.audio;

import static android.media.AudioRecord.RECORDSTATE_RECORDING;
import static android.media.AudioRecord.RECORDSTATE_STOPPED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.media.AudioRecord;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.nio.ByteBuffer;

/**
 * Wrapper around {@link AudioRecord} that allows for the underlying {@link AudioRecord} to
 * be swapped out while recording is ongoing.
 *
 * @hide
 */
// The stop() actually doesn't release resources, so should not force implementing Closeable.
@SuppressLint("NotCloseable")
@SystemApi
public final class AudioCapture {
    private static final String TAG = "AudioCapture";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    @Nullable
    private AudioRecord mAudioRecord;

    @GuardedBy("mLock")
    private int mRecordingState = RECORDSTATE_STOPPED;

    /**
     * Sets the {@link AudioRecord} to handle audio capturing.
     * Callers may call this multiple times with different audio records to change
     * the underlying {@link AudioRecord} without stopping and re-starting recording.
     *
     * @param audioRecord The underlying {@link AudioRecord} to use for capture,
     * or null if no audio (i.e. silence) should be captured while still keeping the
     * record in a recording state.
     */
    void setAudioRecord(@Nullable AudioRecord audioRecord) {
        Log.d(TAG, "set AudioRecord with " + audioRecord);
        synchronized (mLock) {
            // Release old reference.
            if (mAudioRecord != null) {
                mAudioRecord.release();
            }
            // Sync recording state for new reference.
            if (audioRecord != null) {
                if (mRecordingState == RECORDSTATE_RECORDING
                        && audioRecord.getRecordingState() != RECORDSTATE_RECORDING) {
                    audioRecord.startRecording();
                }
                if (mRecordingState == RECORDSTATE_STOPPED
                        && audioRecord.getRecordingState() != RECORDSTATE_STOPPED) {
                    audioRecord.stop();
                }
            }
            mAudioRecord = audioRecord;
        }
    }

    /** See {@link AudioRecord#read(ByteBuffer, int)}. */
    public int read(@NonNull ByteBuffer audioBuffer, int sizeInBytes) {
        final int sizeRead;
        synchronized (mLock) {
            if (mAudioRecord != null) {
                sizeRead = mAudioRecord.read(audioBuffer, sizeInBytes);
            } else {
                sizeRead = 0;
            }
        }
        return sizeRead;
    }

    /** See {@link AudioRecord#startRecording()}. */
    public void startRecording() {
        synchronized (mLock) {
            mRecordingState = RECORDSTATE_RECORDING;
            if (mAudioRecord != null && mAudioRecord.getRecordingState() != RECORDSTATE_RECORDING) {
                mAudioRecord.startRecording();
            }
        }
    }

    /** See {@link AudioRecord#stop()}. */
    public void stop() {
        synchronized (mLock) {
            mRecordingState = RECORDSTATE_STOPPED;
            if (mAudioRecord != null && mAudioRecord.getRecordingState() != RECORDSTATE_STOPPED) {
                mAudioRecord.stop();
            }
        }
    }

    /** See {@link AudioRecord#getRecordingState()}. */
    public int getRecordingState() {
        synchronized (mLock) {
            return mRecordingState;
        }
    }
}
