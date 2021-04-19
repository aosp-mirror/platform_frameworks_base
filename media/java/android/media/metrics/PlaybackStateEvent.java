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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.util.Objects;

/**
 * Playback state event.
 */
public final class PlaybackStateEvent extends Event implements Parcelable {
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
    /** Playback is handling a seek. */
    public static final int STATE_SEEKING = 5;
    /** Playback is buffering to resume active playback. */
    public static final int STATE_BUFFERING = 6;
    /** Playback is buffering while paused. */
    public static final int STATE_PAUSED_BUFFERING = 7;
    /** Playback is suppressed (e.g. due to audio focus loss). */
    public static final int STATE_SUPPRESSED = 9;
    /**
     * Playback is suppressed (e.g. due to audio focus loss) while buffering to resume a playback.
     */
    public static final int STATE_SUPPRESSED_BUFFERING = 10;
    /** Playback has reached the end of the media. */
    public static final int STATE_ENDED = 11;
    /** Playback is stopped and can be restarted. */
    public static final int STATE_STOPPED = 12;
    /** Playback is stopped due a fatal error and can be retried. */
    public static final int STATE_FAILED = 13;
    /** Playback is interrupted by an ad. */
    public static final int STATE_INTERRUPTED_BY_AD = 14;
    /** Playback is abandoned before reaching the end of the media. */
    public static final int STATE_ABANDONED = 15;

    private final int mState;
    private final long mTimeSinceCreatedMillis;

    // These track ExoPlayer states. See the ExoPlayer documentation for the state transitions.
    /** @hide */
    @IntDef(prefix = "STATE_", value = {
        STATE_NOT_STARTED,
        STATE_JOINING_BACKGROUND,
        STATE_JOINING_FOREGROUND,
        STATE_PLAYING,
        STATE_PAUSED,
        STATE_SEEKING,
        STATE_BUFFERING,
        STATE_PAUSED_BUFFERING,
        STATE_SUPPRESSED,
        STATE_SUPPRESSED_BUFFERING,
        STATE_ENDED,
        STATE_STOPPED,
        STATE_FAILED,
        STATE_INTERRUPTED_BY_AD,
        STATE_ABANDONED,
    })
    @Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface State {}

    /**
     * Converts playback state to string.
     * @hide
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
            case STATE_SEEKING:
                return "STATE_SEEKING";
            case STATE_BUFFERING:
                return "STATE_BUFFERING";
            case STATE_PAUSED_BUFFERING:
                return "STATE_PAUSED_BUFFERING";
            case STATE_SUPPRESSED:
                return "STATE_SUPPRESSED";
            case STATE_SUPPRESSED_BUFFERING:
                return "STATE_SUPPRESSED_BUFFERING";
            case STATE_ENDED:
                return "STATE_ENDED";
            case STATE_STOPPED:
                return "STATE_STOPPED";
            case STATE_FAILED:
                return "STATE_FAILED";
            case STATE_INTERRUPTED_BY_AD:
                return "STATE_INTERRUPTED_BY_AD";
            case STATE_ABANDONED:
                return "STATE_ABANDONED";
            default:
                return Integer.toHexString(value);
        }
    }

    /**
     * Creates a new PlaybackStateEvent.
     */
    private PlaybackStateEvent(
            int state,
            long timeSinceCreatedMillis,
            @NonNull Bundle extras) {
        this.mTimeSinceCreatedMillis = timeSinceCreatedMillis;
        this.mState = state;
        this.mMetricsBundle = extras.deepCopy();
    }

    /**
     * Gets playback state.
     */
    @State
    public int getState() {
        return mState;
    }

    /**
     * Gets time since the corresponding playback session is created in millisecond.
     * @return the timestamp since the playback is created, or -1 if unknown.
     * @see LogSessionId
     * @see PlaybackSession
     */
    @Override
    @IntRange(from = -1)
    public long getTimeSinceCreatedMillis() {
        return mTimeSinceCreatedMillis;
    }

    /**
     * Gets metrics-related information that is not supported by dedicated methods.
     * <p>It is intended to be used for backwards compatibility by the metrics infrastructure.
     */
    @Override
    @NonNull
    public Bundle getMetricsBundle() {
        return mMetricsBundle;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaybackStateEvent that = (PlaybackStateEvent) o;
        return mState == that.mState
                && mTimeSinceCreatedMillis == that.mTimeSinceCreatedMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mState, mTimeSinceCreatedMillis);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mState);
        dest.writeLong(mTimeSinceCreatedMillis);
        dest.writeBundle(mMetricsBundle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private PlaybackStateEvent(@NonNull Parcel in) {
        int state = in.readInt();
        long timeSinceCreatedMillis = in.readLong();
        Bundle extras = in.readBundle();

        this.mState = state;
        this.mTimeSinceCreatedMillis = timeSinceCreatedMillis;
        this.mMetricsBundle = extras;
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

    /**
     * A builder for {@link PlaybackStateEvent}
     */
    public static final class Builder {
        private int mState = STATE_NOT_STARTED;
        private long mTimeSinceCreatedMillis = -1;
        private Bundle mMetricsBundle = new Bundle();

        /**
         * Creates a new Builder.
         */
        public Builder() {
        }

        /**
         * Sets playback state.
         */
        public @NonNull Builder setState(@State int value) {
            mState = value;
            return this;
        }

        /**
         * Sets timestamp since the creation in milliseconds.
         * @param value the timestamp since the creation in milliseconds.
         *              -1 indicates the value is unknown.
         * @see #getTimeSinceCreatedMillis()
         */
        public @NonNull Builder setTimeSinceCreatedMillis(@IntRange(from = -1) long value) {
            mTimeSinceCreatedMillis = value;
            return this;
        }

        /**
         * Sets metrics-related information that is not supported by dedicated
         * methods.
         * <p>It is intended to be used for backwards compatibility by the
         * metrics infrastructure.
         */
        public @NonNull Builder setMetricsBundle(@NonNull Bundle metricsBundle) {
            mMetricsBundle = metricsBundle;
            return this;
        }

        /** Builds the instance. */
        public @NonNull PlaybackStateEvent build() {
            PlaybackStateEvent o = new PlaybackStateEvent(
                    mState,
                    mTimeSinceCreatedMillis,
                    mMetricsBundle);
            return o;
        }
    }
}
