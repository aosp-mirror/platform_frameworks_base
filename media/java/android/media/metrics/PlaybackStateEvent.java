/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.media.metrics;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.util.Objects;

/**
 * Playback state event.
 * @hide
 */
public final class PlaybackStateEvent implements Parcelable {
    // TODO: more states
    /** Playback has not started (initial state) */
    public static final int STATE_NOT_STARTED = 0;
    /** Playback is buffering in the background for initial playback start */
    public static final int STATE_JOINING_BACKGROUND = 1;
    /** Playback is buffering in the foreground for initial playback start */
    public static final int STATE_JOINING_FOREGROUND = 2;
    /** Playback is actively playing */
    public static final int STATE_PLAYING = 3;
    /** Playback is paused but ready to play */
    public static final int STATE_PAUSED = 4;

    private int mState;
    private long mTimeSincePlaybackCreatedMillis;

    // These track ExoPlayer states. See the ExoPlayer documentation for the state transitions.
    @IntDef(prefix = "STATE_", value = {
        STATE_NOT_STARTED,
        STATE_JOINING_BACKGROUND,
        STATE_JOINING_FOREGROUND,
        STATE_PLAYING,
        STATE_PAUSED
    })
    @Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface State {}

    /**
     * Converts playback state to string.
     */
    public static String stateToString(@State int value) {
        switch (value) {
            case STATE_NOT_STARTED:
                return "STATE_NOT_STARTED";
            case STATE_JOINING_BACKGROUND:
                return "STATE_JOINING_BACKGROUND";
            case STATE_JOINING_FOREGROUND:
                return "STATE_JOINING_FOREGROUND";
            case STATE_PLAYING:
                return "STATE_PLAYING";
            case STATE_PAUSED:
                return "STATE_PAUSED";
            default:
                return Integer.toHexString(value);
        }
    }

    /**
     * Creates a new PlaybackStateEvent.
     *
     * @hide
     */
    public PlaybackStateEvent(
            int state,
            long timeSincePlaybackCreatedMillis) {
        this.mState = state;
        this.mTimeSincePlaybackCreatedMillis = timeSincePlaybackCreatedMillis;
    }

    /**
     * Gets playback state.
     * @return
     */
    public int getState() {
        return mState;
    }

    /**
     * Gets time since the corresponding playback is created in millisecond.
     */
    public long getTimeSincePlaybackCreatedMillis() {
        return mTimeSincePlaybackCreatedMillis;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaybackStateEvent that = (PlaybackStateEvent) o;
        return mState == that.mState
                && mTimeSincePlaybackCreatedMillis == that.mTimeSincePlaybackCreatedMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mState, mTimeSincePlaybackCreatedMillis);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mState);
        dest.writeLong(mTimeSincePlaybackCreatedMillis);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    /* package-private */ PlaybackStateEvent(@NonNull Parcel in) {
        int state = in.readInt();
        long timeSincePlaybackCreatedMillis = in.readLong();

        this.mState = state;
        this.mTimeSincePlaybackCreatedMillis = timeSincePlaybackCreatedMillis;
    }

    public static final @NonNull Parcelable.Creator<PlaybackStateEvent> CREATOR =
            new Parcelable.Creator<PlaybackStateEvent>() {
        @Override
        public PlaybackStateEvent[] newArray(int size) {
            return new PlaybackStateEvent[size];
        }

        @Override
        public PlaybackStateEvent createFromParcel(@NonNull Parcel in) {
            return new PlaybackStateEvent(in);
        }
    };

}
