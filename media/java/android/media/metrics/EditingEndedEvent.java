/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.android.media.editing.flags.Flags.FLAG_ADD_MEDIA_METRICS_EDITING;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.util.Objects;

/** Event for an editing operation having ended. */
@FlaggedApi(FLAG_ADD_MEDIA_METRICS_EDITING)
public final class EditingEndedEvent extends Event implements Parcelable {

    // The special value 0 is reserved for the field being unspecified in the proto.

    /** The editing operation was successful. */
    public static final int FINAL_STATE_SUCCEEDED = 1;

    /** The editing operation was canceled. */
    public static final int FINAL_STATE_CANCELED = 2;

    /** The editing operation failed due to an error. */
    public static final int FINAL_STATE_ERROR = 3;

    /** @hide */
    @IntDef(
            prefix = {"FINAL_STATE_"},
            value = {
                FINAL_STATE_SUCCEEDED,
                FINAL_STATE_CANCELED,
                FINAL_STATE_ERROR,
            })
    @Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface FinalState {}

    private final @FinalState int mFinalState;

    // The special value 0 is reserved for the field being unspecified in the proto.

    /** Special value representing that no error occurred. */
    public static final int ERROR_CODE_NONE = 1;

    /** Error code for unexpected runtime errors. */
    public static final int ERROR_CODE_FAILED_RUNTIME_CHECK = 2;

    /** Error code for non-specific errors during input/output. */
    public static final int ERROR_CODE_IO_UNSPECIFIED = 3;

    /** Error code for network connection failures. */
    public static final int ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = 4;

    /** Error code for network timeouts. */
    public static final int ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT = 5;

    /** Caused by an HTTP server returning an unexpected HTTP response status code. */
    public static final int ERROR_CODE_IO_BAD_HTTP_STATUS = 6;

    /** Caused by a non-existent file. */
    public static final int ERROR_CODE_IO_FILE_NOT_FOUND = 7;

    /**
     * Caused by lack of permission to perform an IO operation. For example, lack of permission to
     * access internet or external storage.
     */
    public static final int ERROR_CODE_IO_NO_PERMISSION = 8;

    /**
     * Caused by failing to load data via cleartext HTTP, when the app's network security
     * configuration does not permit it.
     */
    public static final int ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED = 9;

    /** Caused by reading data out of the data bounds. */
    public static final int ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE = 10;

    /** Caused by a decoder initialization failure. */
    public static final int ERROR_CODE_DECODER_INIT_FAILED = 11;

    /** Caused by a failure while trying to decode media samples. */
    public static final int ERROR_CODE_DECODING_FAILED = 12;

    /** Caused by trying to decode content whose format is not supported. */
    public static final int ERROR_CODE_DECODING_FORMAT_UNSUPPORTED = 13;

    /** Caused by an encoder initialization failure. */
    public static final int ERROR_CODE_ENCODER_INIT_FAILED = 14;

    /** Caused by a failure while trying to encode media samples. */
    public static final int ERROR_CODE_ENCODING_FAILED = 15;

    /** Caused by trying to encode content whose format is not supported. */
    public static final int ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED = 16;

    /** Caused by a video frame processing failure. */
    public static final int ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED = 17;

    /** Caused by an audio processing failure. */
    public static final int ERROR_CODE_AUDIO_PROCESSING_FAILED = 18;

    /** Caused by a failure while muxing media samples. */
    public static final int ERROR_CODE_MUXING_FAILED = 19;

    /** @hide */
    @IntDef(
            prefix = {"ERROR_CODE_"},
            value = {
                ERROR_CODE_NONE,
                ERROR_CODE_FAILED_RUNTIME_CHECK,
                ERROR_CODE_IO_UNSPECIFIED,
                ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                ERROR_CODE_IO_BAD_HTTP_STATUS,
                ERROR_CODE_IO_FILE_NOT_FOUND,
                ERROR_CODE_IO_NO_PERMISSION,
                ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
                ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                ERROR_CODE_DECODER_INIT_FAILED,
                ERROR_CODE_DECODING_FAILED,
                ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                ERROR_CODE_ENCODER_INIT_FAILED,
                ERROR_CODE_ENCODING_FAILED,
                ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
                ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
                ERROR_CODE_AUDIO_PROCESSING_FAILED,
                ERROR_CODE_MUXING_FAILED,
            })
    @Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface ErrorCode {}

    /** Special value for unknown {@linkplain #getTimeSinceCreatedMillis() time since creation}. */
    public static final int TIME_SINCE_CREATED_UNKNOWN = -1;

    private final @ErrorCode int mErrorCode;
    @SuppressWarnings("HidingField") // Hiding field from superclass as for playback events.
    private final long mTimeSinceCreatedMillis;

    private EditingEndedEvent(
            @FinalState int finalState,
            @ErrorCode int errorCode,
            long timeSinceCreatedMillis,
            @NonNull Bundle extras) {
        mFinalState = finalState;
        mErrorCode = errorCode;
        mTimeSinceCreatedMillis = timeSinceCreatedMillis;
        mMetricsBundle = extras.deepCopy();
    }

    /** Returns the state of the editing session when it ended. */
    @FinalState
    public int getFinalState() {
        return mFinalState;
    }

    /** Returns the error code for a {@linkplain #FINAL_STATE_ERROR failed} editing session. */
    @ErrorCode
    public int getErrorCode() {
        return mErrorCode;
    }

    /**
     * Gets the elapsed time since creating of the editing session, in milliseconds, or {@link
     * #TIME_SINCE_CREATED_UNKNOWN} if unknown.
     *
     * @return The elapsed time since creating the editing session, in milliseconds, or {@link
     *     #TIME_SINCE_CREATED_UNKNOWN} if unknown.
     * @see LogSessionId
     * @see EditingSession
     */
    @Override
    @IntRange(from = TIME_SINCE_CREATED_UNKNOWN)
    public long getTimeSinceCreatedMillis() {
        return mTimeSinceCreatedMillis;
    }

    /**
     * Gets metrics-related information that is not supported by dedicated methods.
     *
     * <p>It is intended to be used for backwards compatibility by the metrics infrastructure.
     */
    @Override
    @NonNull
    public Bundle getMetricsBundle() {
        return mMetricsBundle;
    }

    @Override
    @NonNull
    public String toString() {
        return "PlaybackErrorEvent { "
                + "finalState = "
                + mFinalState
                + ", "
                + "errorCode = "
                + mErrorCode
                + ", "
                + "timeSinceCreatedMillis = "
                + mTimeSinceCreatedMillis
                + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EditingEndedEvent that = (EditingEndedEvent) o;
        return mFinalState == that.mFinalState
                && mErrorCode == that.mErrorCode
                && mTimeSinceCreatedMillis == that.mTimeSinceCreatedMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFinalState, mErrorCode, mTimeSinceCreatedMillis);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mFinalState);
        dest.writeInt(mErrorCode);
        dest.writeLong(mTimeSinceCreatedMillis);
        dest.writeBundle(mMetricsBundle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private EditingEndedEvent(@NonNull Parcel in) {
        int finalState = in.readInt();
        int errorCode = in.readInt();
        long timeSinceCreatedMillis = in.readLong();
        Bundle metricsBundle = in.readBundle();

        mFinalState = finalState;
        mErrorCode = errorCode;
        mTimeSinceCreatedMillis = timeSinceCreatedMillis;
        mMetricsBundle = metricsBundle;
    }

    public static final @NonNull Creator<EditingEndedEvent> CREATOR =
            new Creator<>() {
                @Override
                public EditingEndedEvent[] newArray(int size) {
                    return new EditingEndedEvent[size];
                }

                @Override
                public EditingEndedEvent createFromParcel(@NonNull Parcel in) {
                    return new EditingEndedEvent(in);
                }
            };

    /** Builder for {@link EditingEndedEvent} */
    @FlaggedApi(FLAG_ADD_MEDIA_METRICS_EDITING)
    public static final class Builder {
        private final @FinalState int mFinalState;
        private @ErrorCode int mErrorCode;
        private long mTimeSinceCreatedMillis;
        private Bundle mMetricsBundle;

        /**
         * Creates a new Builder.
         *
         * @param finalState The state of the editing session when it ended.
         */
        public Builder(@FinalState int finalState) {
            mFinalState = finalState;
            mErrorCode = ERROR_CODE_NONE;
            mTimeSinceCreatedMillis = TIME_SINCE_CREATED_UNKNOWN;
            mMetricsBundle = new Bundle();
        }

        /**
         * Sets the elapsed time since creating the editing session, in milliseconds.
         *
         * @param timeSinceCreatedMillis The elapsed time since creating the editing session, in
         *     milliseconds, or {@link #TIME_SINCE_CREATED_UNKNOWN} if unknown.
         * @see #getTimeSinceCreatedMillis()
         */
        public @NonNull Builder setTimeSinceCreatedMillis(
                @IntRange(from = TIME_SINCE_CREATED_UNKNOWN) long timeSinceCreatedMillis) {
            mTimeSinceCreatedMillis = timeSinceCreatedMillis;
            return this;
        }

        /** Sets the error code for a {@linkplain #FINAL_STATE_ERROR failed} editing session. */
        public @NonNull Builder setErrorCode(@ErrorCode int value) {
            mErrorCode = value;
            return this;
        }

        /**
         * Sets metrics-related information that is not supported by dedicated methods.
         *
         * <p>Used for backwards compatibility by the metrics infrastructure.
         */
        public @NonNull Builder setMetricsBundle(@NonNull Bundle metricsBundle) {
            mMetricsBundle = metricsBundle;
            return this;
        }

        /** Builds an instance. */
        public @NonNull EditingEndedEvent build() {
            return new EditingEndedEvent(
                    mFinalState, mErrorCode, mTimeSinceCreatedMillis, mMetricsBundle);
        }
    }
}
