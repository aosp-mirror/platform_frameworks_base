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

import static android.media.AudioTrack.PLAYSTATE_PLAYING;
import static android.media.AudioTrack.PLAYSTATE_STOPPED;
import static android.media.AudioTrack.STATE_INITIALIZED;
import static android.media.AudioTrack.WRITE_BLOCKING;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.nio.ByteBuffer;

/**
 * Wrapper around {@link AudioTrack} that allows for the underlying {@link AudioTrack} to
 * be swapped out while playout is ongoing.
 *
 * @hide
 */
// The stop() actually doesn't release resources, so should not force implementing Closeable.
@SuppressLint("NotCloseable")
@SystemApi
public final class AudioInjection {
    private static final String TAG = "AudioInjection";

    private final AudioFormat mAudioFormat;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    @Nullable
    private AudioTrack mAudioTrack;
    @GuardedBy("mLock")
    private int mPlayState = PLAYSTATE_STOPPED;
    @GuardedBy("mLock")
    private boolean mIsSilent;

    /** Sets if the injected microphone sound is silent. */
    void setSilent(boolean isSilent) {
        synchronized (mLock) {
            mIsSilent = isSilent;
        }
    }

    /**
     * Sets the {@link AudioTrack} to handle audio injection.
     * Callers may call this multiple times with different audio tracks to change
     * the underlying {@link AudioTrack} without stopping and re-starting injection.
     *
     * @param audioTrack The underlying {@link AudioTrack} to use for injection,
     * or null if no audio (i.e. silence) should be injected while still keeping the
     * record in a playing state.
     */
    void setAudioTrack(@Nullable AudioTrack audioTrack) {
        Log.d(TAG, "set AudioTrack with " + audioTrack);
        synchronized (mLock) {
            // Sync play state for new reference.
            if (audioTrack != null) {
                if (audioTrack.getState() != STATE_INITIALIZED) {
                    throw new IllegalStateException("set an uninitialized AudioTrack.");
                }

                if (mPlayState == PLAYSTATE_PLAYING
                        && audioTrack.getPlayState() != PLAYSTATE_PLAYING) {
                    audioTrack.play();
                }
                if (mPlayState == PLAYSTATE_STOPPED
                        && audioTrack.getPlayState() != PLAYSTATE_STOPPED) {
                    audioTrack.stop();
                }
            }

            // Release old reference before assigning the new reference.
            if (mAudioTrack != null) {
                mAudioTrack.release();
            }
            mAudioTrack = audioTrack;
        }
    }

    AudioInjection(@NonNull AudioFormat audioFormat) {
        mAudioFormat = audioFormat;
    }

    void close() {
        synchronized (mLock) {
            if (mAudioTrack != null) {
                mAudioTrack.release();
                mAudioTrack = null;
            }
        }
    }

    /** See {@link AudioTrack#getFormat()}. */
    public @NonNull AudioFormat getFormat() {
        return mAudioFormat;
    }

    /** See {@link AudioTrack#write(byte[], int, int)}. */
    public int write(@NonNull byte[] audioData, int offsetInBytes, int sizeInBytes) {
        return write(audioData, offsetInBytes, sizeInBytes, WRITE_BLOCKING);
    }

    /** See {@link AudioTrack#write(byte[], int, int, int)}. */
    public int write(@NonNull byte[] audioData, int offsetInBytes, int sizeInBytes,
            @AudioTrack.WriteMode int writeMode) {
        final int sizeWrite;
        synchronized (mLock) {
            if (mAudioTrack != null && !mIsSilent) {
                sizeWrite = mAudioTrack.write(audioData, offsetInBytes, sizeInBytes, writeMode);
            } else {
                sizeWrite = 0;
            }
        }
        return sizeWrite;
    }

    /** See {@link AudioTrack#write(ByteBuffer, int, int)}. */
    public int write(@NonNull ByteBuffer audioBuffer, int sizeInBytes, int writeMode) {
        final int sizeWrite;
        synchronized (mLock) {
            if (mAudioTrack != null && !mIsSilent) {
                sizeWrite = mAudioTrack.write(audioBuffer, sizeInBytes, writeMode);
            } else {
                sizeWrite = 0;
            }
        }
        return sizeWrite;
    }

    /** See {@link AudioTrack#write(ByteBuffer, int, int, long)}. */
    public int write(@NonNull ByteBuffer audioBuffer, int sizeInBytes,
            @AudioTrack.WriteMode int writeMode, long timestamp) {
        final int sizeWrite;
        synchronized (mLock) {
            if (mAudioTrack != null && !mIsSilent) {
                sizeWrite = mAudioTrack.write(audioBuffer, sizeInBytes, writeMode, timestamp);
            } else {
                sizeWrite = 0;
            }
        }
        return sizeWrite;
    }

    /** See {@link AudioTrack#write(float[], int, int, int)}. */
    public int write(@NonNull float[] audioData, int offsetInFloats, int sizeInFloats,
            @AudioTrack.WriteMode int writeMode) {
        final int sizeWrite;
        synchronized (mLock) {
            if (mAudioTrack != null && !mIsSilent) {
                sizeWrite = mAudioTrack.write(audioData, offsetInFloats, sizeInFloats, writeMode);
            } else {
                sizeWrite = 0;
            }
        }
        return sizeWrite;
    }

    /** See {@link AudioTrack#write(short[], int, int)}. */
    public int write(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts) {
        return write(audioData, offsetInShorts, sizeInShorts, WRITE_BLOCKING);
    }

    /** See {@link AudioTrack#write(short[], int, int, int)}. */
    public int write(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts,
            @AudioTrack.WriteMode int writeMode) {
        final int sizeWrite;
        synchronized (mLock) {
            if (mAudioTrack != null && !mIsSilent) {
                sizeWrite = mAudioTrack.write(audioData, offsetInShorts, sizeInShorts, writeMode);
            } else {
                sizeWrite = 0;
            }
        }
        return sizeWrite;
    }

    /** See {@link AudioTrack#play()}. */
    public void play() {
        synchronized (mLock) {
            mPlayState = PLAYSTATE_PLAYING;
            if (mAudioTrack != null && mAudioTrack.getPlayState() != PLAYSTATE_PLAYING) {
                mAudioTrack.play();
            }
        }
    }

    /** See {@link AudioTrack#stop()}. */
    public void stop() {
        synchronized (mLock) {
            mPlayState = PLAYSTATE_STOPPED;
            if (mAudioTrack != null && mAudioTrack.getPlayState() != PLAYSTATE_STOPPED) {
                mAudioTrack.stop();
            }
        }
    }

    /** See {@link AudioTrack#getPlayState()}. */
    public int getPlayState() {
        synchronized (mLock) {
            return mPlayState;
        }
    }
}
