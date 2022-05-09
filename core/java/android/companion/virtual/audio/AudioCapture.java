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

import static android.media.AudioRecord.READ_BLOCKING;
import static android.media.AudioRecord.RECORDSTATE_RECORDING;
import static android.media.AudioRecord.RECORDSTATE_STOPPED;
import static android.media.AudioRecord.STATE_INITIALIZED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.media.AudioFormat;
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

    private final AudioFormat mAudioFormat;
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
            // Sync recording state for new reference.
            if (audioRecord != null) {
                if (audioRecord.getState() != STATE_INITIALIZED) {
                    throw new IllegalStateException("set an uninitialized AudioRecord.");
                }

                if (mRecordingState == RECORDSTATE_RECORDING
                        && audioRecord.getRecordingState() != RECORDSTATE_RECORDING) {
                    audioRecord.startRecording();
                }
                if (mRecordingState == RECORDSTATE_STOPPED
                        && audioRecord.getRecordingState() != RECORDSTATE_STOPPED) {
                    audioRecord.stop();
                }
            }

            // Release old reference before assigning the new reference.
            if (mAudioRecord != null) {
                mAudioRecord.release();
            }
            mAudioRecord = audioRecord;
        }
    }

    AudioCapture(@NonNull AudioFormat audioFormat) {
        mAudioFormat = audioFormat;
    }

    void close() {
        synchronized (mLock) {
            if (mAudioRecord != null) {
                mAudioRecord.release();
                mAudioRecord = null;
            }
        }
    }

    /** See {@link AudioRecord#getFormat()} */
    public @NonNull AudioFormat getFormat() {
        return mAudioFormat;
    }

    /** See {@link AudioRecord#read(byte[], int, int)} */
    public int read(@NonNull byte[] audioData, int offsetInBytes, int sizeInBytes) {
        return read(audioData, offsetInBytes, sizeInBytes, READ_BLOCKING);
    }

    /** See {@link AudioRecord#read(byte[], int, int, int)} */
    public int read(@NonNull byte[] audioData, int offsetInBytes, int sizeInBytes,
            @AudioRecord.ReadMode int readMode) {
        final int sizeRead;
        synchronized (mLock) {
            if (mAudioRecord != null) {
                sizeRead = mAudioRecord.read(audioData, offsetInBytes, sizeInBytes, readMode);
            } else {
                sizeRead = 0;
            }
        }
        return sizeRead;
    }

    /** See {@link AudioRecord#read(ByteBuffer, int)}. */
    public int read(@NonNull ByteBuffer audioBuffer, int sizeInBytes) {
        return read(audioBuffer, sizeInBytes, READ_BLOCKING);
    }

    /** See {@link AudioRecord#read(ByteBuffer, int, int)}. */
    public int read(@NonNull ByteBuffer audioBuffer, int sizeInBytes,
            @AudioRecord.ReadMode int readMode) {
        final int sizeRead;
        synchronized (mLock) {
            if (mAudioRecord != null) {
                sizeRead = mAudioRecord.read(audioBuffer, sizeInBytes, readMode);
            } else {
                sizeRead = 0;
            }
        }
        return sizeRead;
    }

    /** See {@link AudioRecord#read(float[], int, int, int)}. */
    public int read(@NonNull float[] audioData, int offsetInFloats, int sizeInFloats,
            @AudioRecord.ReadMode int readMode) {
        final int sizeRead;
        synchronized (mLock) {
            if (mAudioRecord != null) {
                sizeRead = mAudioRecord.read(audioData, offsetInFloats, sizeInFloats, readMode);
            } else {
                sizeRead = 0;
            }
        }
        return sizeRead;
    }

    /** See {@link AudioRecord#read(short[], int, int)}. */
    public int read(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts) {
        return read(audioData, offsetInShorts, sizeInShorts, READ_BLOCKING);
    }

    /** See {@link AudioRecord#read(short[], int, int, int)}. */
    public int read(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts,
            @AudioRecord.ReadMode int readMode) {
        final int sizeRead;
        synchronized (mLock) {
            if (mAudioRecord != null) {
                sizeRead = mAudioRecord.read(audioData, offsetInShorts, sizeInShorts, readMode);
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
