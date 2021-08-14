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

    /** Error code for lack of network connectivity while trying to access a network resource */
    public static final int ERROR_IO_NETWORK_UNAVAILABLE = 3;
    /** Error code for a failure while establishing a network connection */
    public static final int ERROR_IO_NETWORK_CONNECTION_FAILED = 4;
    /** Error code for an HTTP server returning an unexpected HTTP response status code */
    public static final int ERROR_IO_BAD_HTTP_STATUS = 5;
    /** Error code for failing to resolve a hostname */
    public static final int ERROR_IO_DNS_FAILED = 6;
    /**
     * Error code for a network timeout, meaning the server is taking too long to fulfill
     * a request
     */
    public static final int ERROR_IO_CONNECTION_TIMEOUT = 7;
    /** Error code for an existing network connection being unexpectedly closed */
    public static final int ERROR_IO_CONNECTION_CLOSED = 8;
    /** Error code for other Input/Output errors */
    public static final int ERROR_IO_OTHER = 9;

    /** Error code for a parsing error associated to a media manifest */
    public static final int ERROR_PARSING_MANIFEST_MALFORMED = 10;
    /** Error code for a parsing error associated to a media container format bitstream */
    public static final int ERROR_PARSING_CONTAINER_MALFORMED = 11;
    /** Error code for other media parsing errors */
    public static final int ERROR_PARSING_OTHER = 12;

    /** Error code for a decoder initialization failure */
    public static final int ERROR_DECODER_INIT_FAILED = 13;
    /** Error code for a failure while trying to decode media samples */
    public static final int ERROR_DECODING_FAILED = 14;
    /**
     * Error code for trying to decode content whose format exceeds the capabilities of the device.
     */
    public static final int ERROR_DECODING_FORMAT_EXCEEDS_CAPABILITIES = 15;
    /** Error code for other decoding errors */
    public static final int ERROR_DECODING_OTHER = 16;

    /** Error code for an AudioTrack initialization failure */
    public static final int ERROR_AUDIO_TRACK_INIT_FAILED = 17;
    /** Error code for an AudioTrack write operation failure */
    public static final int ERROR_AUDIO_TRACK_WRITE_FAILED = 18;
    /** Error code for other AudioTrack errors */
    public static final int ERROR_AUDIO_TRACK_OTHER = 19;

    /** Error code for an unidentified error in a remote controller or player */
    public static final int ERROR_PLAYER_REMOTE = 20;
    /**
     * Error code for the loading position falling behind the sliding window of available live
     * content.
     */
    public static final int ERROR_PLAYER_BEHIND_LIVE_WINDOW = 21;
    /** Error code for other player errors */
    public static final int ERROR_PLAYER_OTHER = 22;

    /** Error code for a chosen DRM protection scheme not being supported by the device */
    public static final int ERROR_DRM_SCHEME_UNSUPPORTED = 23;
    /** Error code for a failure while provisioning the device */
    public static final int ERROR_DRM_PROVISIONING_FAILED = 24;
    /** Error code for a failure while trying to obtain a license */
    public static final int ERROR_DRM_LICENSE_ACQUISITION_FAILED = 25;
    /** Error code an operation being disallowed by a license policy */
    public static final int ERROR_DRM_DISALLOWED_OPERATION = 26;
    /** Error code for an error in the DRM system */
    public static final int ERROR_DRM_SYSTEM_ERROR = 27;
    /** Error code for attempting to play incompatible DRM-protected content */
    public static final int ERROR_DRM_CONTENT_ERROR = 28;
    /** Error code for the device having revoked DRM privileges */
    public static final int ERROR_DRM_DEVICE_REVOKED = 29;
    /** Error code for other DRM errors */
    public static final int ERROR_DRM_OTHER = 30;

    /** Error code for a non-existent file */
    public static final int ERROR_IO_FILE_NOT_FOUND = 31;
    /**
     * Error code for lack of permission to perform an IO operation, for example, lack of permission
     * to access internet or external storage.
     */
    public static final int ERROR_IO_NO_PERMISSION = 32;

    /** Error code for an unsupported feature in a media manifest */
    public static final int ERROR_PARSING_MANIFEST_UNSUPPORTED = 33;
    /**
     * Error code for attempting to extract a file with an unsupported media container format, or an
     * unsupported media container feature
     */
    public static final int ERROR_PARSING_CONTAINER_UNSUPPORTED = 34;

    /** Error code for trying to decode content whose format is not supported */
    public static final int ERROR_DECODING_FORMAT_UNSUPPORTED = 35;


    private final @Nullable String mExceptionStack;
    private final int mErrorCode;
    private final int mSubErrorCode;
    private final long mTimeSinceCreatedMillis;


    /** @hide */
    @IntDef(prefix = "ERROR_", value = {
        ERROR_UNKNOWN,
        ERROR_OTHER,
        ERROR_RUNTIME,
        ERROR_IO_NETWORK_UNAVAILABLE,
        ERROR_IO_NETWORK_CONNECTION_FAILED,
        ERROR_IO_BAD_HTTP_STATUS,
        ERROR_IO_DNS_FAILED,
        ERROR_IO_CONNECTION_TIMEOUT,
        ERROR_IO_CONNECTION_CLOSED,
        ERROR_IO_OTHER,
        ERROR_PARSING_MANIFEST_MALFORMED,
        ERROR_PARSING_CONTAINER_MALFORMED,
        ERROR_PARSING_OTHER,
        ERROR_DECODER_INIT_FAILED,
        ERROR_DECODING_FAILED,
        ERROR_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        ERROR_DECODING_OTHER,
        ERROR_AUDIO_TRACK_INIT_FAILED,
        ERROR_AUDIO_TRACK_WRITE_FAILED,
        ERROR_AUDIO_TRACK_OTHER,
        ERROR_PLAYER_REMOTE,
        ERROR_PLAYER_BEHIND_LIVE_WINDOW,
        ERROR_PLAYER_OTHER,
        ERROR_DRM_SCHEME_UNSUPPORTED,
        ERROR_DRM_PROVISIONING_FAILED,
        ERROR_DRM_LICENSE_ACQUISITION_FAILED,
        ERROR_DRM_DISALLOWED_OPERATION,
        ERROR_DRM_SYSTEM_ERROR,
        ERROR_DRM_CONTENT_ERROR,
        ERROR_DRM_DEVICE_REVOKED,
        ERROR_DRM_OTHER,
        ERROR_IO_FILE_NOT_FOUND,
        ERROR_IO_NO_PERMISSION,
        ERROR_PARSING_MANIFEST_UNSUPPORTED,
        ERROR_PARSING_CONTAINER_UNSUPPORTED,
        ERROR_DECODING_FORMAT_UNSUPPORTED,
    })
    @Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface ErrorCode {}

    /**
     * Creates a new PlaybackErrorEvent.
     */
    private PlaybackErrorEvent(
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
     * Gets the timestamp since creation of the playback session in milliseconds.
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

    private PlaybackErrorEvent(@NonNull Parcel in) {
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
        private int mErrorCode = ERROR_UNKNOWN;
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
