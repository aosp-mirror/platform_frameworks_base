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
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.util.Objects;

/**
 * Playback error event.
 */
public final class PlaybackErrorEvent extends Event implements Parcelable {
    /** Unknown error code. */
    public static final int ERROR_UNKNOWN = 0;
    /** Error code for other errors */
    public static final int ERROR_OTHER = 1;
    /** Error code for runtime errors */
    public static final int ERROR_RUNTIME = 2;

    /** No network */
    public static final int ERROR_NETWORK_OFFLINE = 3;
    /** Connection opening error */
    public static final int ERROR_NETWORK_CONNECT = 4;
    /** Bad HTTP status code */
    public static final int ERROR_NETWORK_BAD_STATUS = 5;
    /** DNS resolution error */
    public static final int ERROR_NETWORK_DNS = 6;
    /** Network socket timeout */
    public static final int ERROR_NETWORK_TIMEOUT = 7;
    /** Connection closed */
    public static final int ERROR_NETWORK_CLOSED = 8;
    /** Other network errors */
    public static final int ERROR_NETWORK_OTHER = 9;

    /** Manifest parsing error */
    public static final int ERROR_MEDIA_MANIFEST = 10;
    /**
     * Media bitstream (audio, video, text, metadata) parsing error, either malformed or
     * unsupported.
     */
    public static final int ERROR_MEDIA_PARSER = 11;
    /** Other media errors */
    public static final int ERROR_MEDIA_OTHER = 12;

    /** Codec initialization failed */
    public static final int ERROR_DECODER_INIT = 13;
    /** Decoding failed */
    public static final int ERROR_DECODER_DECODE = 14;
    /** Out of memory */
    public static final int ERROR_DECODER_OOM = 15;
    /** Other decoder errors */
    public static final int ERROR_DECODER_OTHER = 16;

    /** AudioTrack initialization failed */
    public static final int ERROR_AUDIOTRACK_INIT = 17;
    /** AudioTrack writing failed */
    public static final int ERROR_AUDIOTRACK_WRITE = 18;
    /** Other AudioTrack errors */
    public static final int ERROR_AUDIOTRACK_OTHER = 19;

    /** Exception in remote controller or player */
    public static final int ERROR_PLAYER_REMOTE = 20;
    /** Error when a Live playback falls behind the Live DVR window. */
    public static final int ERROR_PLAYER_BEHIND_LIVE_WINDOW = 21;
    /** Other player errors */
    public static final int ERROR_PLAYER_OTHER = 22;

    /** Scheme unsupported by device */
    public static final int ERROR_DRM_UNAVAILABLE = 23;
    /** Provisioning failed */
    public static final int ERROR_DRM_PROVISIONING_FAILED = 24;
    /** Failed to acquire license */
    public static final int ERROR_DRM_LICENSE_ERROR = 25;
    /** Operation prevented by license policy */
    public static final int ERROR_DRM_DISALLOWED = 26;
    /** Failure in the DRM system */
    public static final int ERROR_DRM_SYSTEM_ERROR = 27;
    /** Incompatible content */
    public static final int ERROR_DRM_CONTENT_ERROR = 28;
    /** Device has been revoked */
    public static final int ERROR_DRM_REVOKED = 29;
    /** Other drm errors */
    public static final int ERROR_DRM_OTHER = 30;


    private final @Nullable String mExceptionStack;
    private final int mErrorCode;
    private final int mSubErrorCode;
    private final long mTimeSinceCreatedMillis;


    /** @hide */
    @IntDef(prefix = "ERROR_", value = {
        ERROR_UNKNOWN,
        ERROR_OTHER,
        ERROR_RUNTIME,
        ERROR_NETWORK_OFFLINE,
        ERROR_NETWORK_CONNECT,
        ERROR_NETWORK_BAD_STATUS,
        ERROR_NETWORK_DNS,
        ERROR_NETWORK_TIMEOUT,
        ERROR_NETWORK_CLOSED,
        ERROR_NETWORK_OTHER,
        ERROR_MEDIA_MANIFEST,
        ERROR_MEDIA_PARSER,
        ERROR_MEDIA_OTHER,
        ERROR_DECODER_INIT,
        ERROR_DECODER_DECODE,
        ERROR_DECODER_OOM,
        ERROR_DECODER_OTHER,
        ERROR_AUDIOTRACK_INIT,
        ERROR_AUDIOTRACK_WRITE,
        ERROR_AUDIOTRACK_OTHER,
        ERROR_PLAYER_REMOTE,
        ERROR_PLAYER_BEHIND_LIVE_WINDOW,
        ERROR_PLAYER_OTHER,
        ERROR_DRM_UNAVAILABLE,
        ERROR_DRM_PROVISIONING_FAILED,
        ERROR_DRM_LICENSE_ERROR,
        ERROR_DRM_DISALLOWED,
        ERROR_DRM_SYSTEM_ERROR,
        ERROR_DRM_CONTENT_ERROR,
        ERROR_DRM_REVOKED,
        ERROR_DRM_OTHER,
    })
    @Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface ErrorCode {}

    /**
     * Creates a new PlaybackErrorEvent.
     *
     * @hide
     */
    public PlaybackErrorEvent(
            @Nullable String exceptionStack,
            int errorCode,
            int subErrorCode,
            long timeSinceCreatedMillis,
            @NonNull Bundle extras) {
        this.mExceptionStack = exceptionStack;
        this.mErrorCode = errorCode;
        this.mSubErrorCode = subErrorCode;
        this.mTimeSinceCreatedMillis = timeSinceCreatedMillis;
        this.mMetricsBundle = extras.deepCopy();
    }

    /** @hide */
    @Nullable
    public String getExceptionStack() {
        return mExceptionStack;
    }


    /**
     * Gets error code.
     */
    @ErrorCode
    public int getErrorCode() {
        return mErrorCode;
    }


    /**
     * Gets sub error code.
     */
    @IntRange(from = Integer.MIN_VALUE, to = Integer.MAX_VALUE)
    public int getSubErrorCode() {
        return mSubErrorCode;
    }

    /**
     * Gets the timestamp since creation in milliseconds.
     * @return the timestamp since the playback is created, or -1 if unknown.
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
    public String toString() {
        return "PlaybackErrorEvent { "
                + "exceptionStack = " + mExceptionStack + ", "
                + "errorCode = " + mErrorCode + ", "
                + "subErrorCode = " + mSubErrorCode + ", "
                + "timeSinceCreatedMillis = " + mTimeSinceCreatedMillis
                + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaybackErrorEvent that = (PlaybackErrorEvent) o;
        return Objects.equals(mExceptionStack, that.mExceptionStack)
                && mErrorCode == that.mErrorCode
                && mSubErrorCode == that.mSubErrorCode
                && mTimeSinceCreatedMillis == that.mTimeSinceCreatedMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mExceptionStack, mErrorCode, mSubErrorCode,
            mTimeSinceCreatedMillis);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        byte flg = 0;
        if (mExceptionStack != null) flg |= 0x1;
        dest.writeByte(flg);
        if (mExceptionStack != null) dest.writeString(mExceptionStack);
        dest.writeInt(mErrorCode);
        dest.writeInt(mSubErrorCode);
        dest.writeLong(mTimeSinceCreatedMillis);
        dest.writeBundle(mMetricsBundle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    /* package-private */ PlaybackErrorEvent(@NonNull Parcel in) {
        byte flg = in.readByte();
        String exceptionStack = (flg & 0x1) == 0 ? null : in.readString();
        int errorCode = in.readInt();
        int subErrorCode = in.readInt();
        long timeSinceCreatedMillis = in.readLong();
        Bundle extras = in.readBundle();

        this.mExceptionStack = exceptionStack;
        this.mErrorCode = errorCode;
        this.mSubErrorCode = subErrorCode;
        this.mTimeSinceCreatedMillis = timeSinceCreatedMillis;
        this.mMetricsBundle = extras;
    }


    public static final @NonNull Parcelable.Creator<PlaybackErrorEvent> CREATOR =
            new Parcelable.Creator<PlaybackErrorEvent>() {
        @Override
        public PlaybackErrorEvent[] newArray(int size) {
            return new PlaybackErrorEvent[size];
        }

        @Override
        public PlaybackErrorEvent createFromParcel(@NonNull Parcel in) {
            return new PlaybackErrorEvent(in);
        }
    };

    /**
     * A builder for {@link PlaybackErrorEvent}
     */
    public static final class Builder {
        private @Nullable Exception mException;
        private int mErrorCode;
        private int mSubErrorCode;
        private long mTimeSinceCreatedMillis = -1;
        private Bundle mMetricsBundle = new Bundle();

        /**
         * Creates a new Builder.
         */
        public Builder() {
        }

        /**
         * Sets the {@link Exception} object.
         */
        @SuppressLint("MissingGetterMatchingBuilder") // Exception is not parcelable.
        public @NonNull Builder setException(@NonNull Exception value) {
            mException = value;
            return this;
        }

        /**
         * Sets error code.
         */
        public @NonNull Builder setErrorCode(@ErrorCode int value) {
            mErrorCode = value;
            return this;
        }

        /**
         * Sets sub error code.
         */
        public @NonNull Builder setSubErrorCode(
                @IntRange(from = Integer.MIN_VALUE, to = Integer.MAX_VALUE) int value) {
            mSubErrorCode = value;
            return this;
        }

        /**
         * Set the timestamp since creation in milliseconds.
         * @param value the timestamp since the creation in milliseconds.
         *              -1 indicates the value is unknown.
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
        public @NonNull PlaybackErrorEvent build() {

            String stack;
            if (mException.getStackTrace() != null && mException.getStackTrace().length > 0) {
                // TODO: a better definition of the stack trace
                stack = mException.getStackTrace()[0].toString();
            } else {
                stack = null;
            }

            PlaybackErrorEvent o = new PlaybackErrorEvent(
                    stack,
                    mErrorCode,
                    mSubErrorCode,
                    mTimeSinceCreatedMillis,
                    mMetricsBundle);
            return o;
        }
    }
}
