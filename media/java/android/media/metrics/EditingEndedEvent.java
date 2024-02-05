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
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.List;
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

    private final ArrayList<MediaItemInfo> mInputMediaItemInfos;
    @Nullable private final MediaItemInfo mOutputMediaItemInfo;

    /** @hide */
    @LongDef(
            prefix = {"OPERATION_TYPE_"},
            flag = true,
            value = {
                OPERATION_TYPE_VIDEO_TRANSCODE,
                OPERATION_TYPE_AUDIO_TRANSCODE,
                OPERATION_TYPE_VIDEO_EDIT,
                OPERATION_TYPE_AUDIO_EDIT,
                OPERATION_TYPE_VIDEO_TRANSMUX,
                OPERATION_TYPE_AUDIO_TRANSMUX,
                OPERATION_TYPE_PAUSED,
                OPERATION_TYPE_RESUMED,
            })
    @Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface OperationType {}

    /** Input video was decoded and re-encoded. */
    public static final long OPERATION_TYPE_VIDEO_TRANSCODE = 1;

    /** Input audio was decoded and re-encoded. */
    public static final long OPERATION_TYPE_AUDIO_TRANSCODE = 1L << 1;

    /** Input video was edited. */
    public static final long OPERATION_TYPE_VIDEO_EDIT = 1L << 2;

    /** Input audio was edited. */
    public static final long OPERATION_TYPE_AUDIO_EDIT = 1L << 3;

    /** Input video samples were writted (muxed) directly to the output file without transcoding. */
    public static final long OPERATION_TYPE_VIDEO_TRANSMUX = 1L << 4;

    /** Input audio samples were written (muxed) directly to the output file without transcoding. */
    public static final long OPERATION_TYPE_AUDIO_TRANSMUX = 1L << 5;

    /** The editing operation was paused before it completed. */
    public static final long OPERATION_TYPE_PAUSED = 1L << 6;

    /** The editing operation resumed a previous (paused) operation. */
    public static final long OPERATION_TYPE_RESUMED = 1L << 7;

    private final @OperationType long mOperationTypes;

    private EditingEndedEvent(
            @FinalState int finalState,
            @ErrorCode int errorCode,
            long timeSinceCreatedMillis,
            ArrayList<MediaItemInfo> inputMediaItemInfos,
            @Nullable MediaItemInfo outputMediaItemInfo,
            @OperationType long operationTypes,
            @NonNull Bundle extras) {
        mFinalState = finalState;
        mErrorCode = errorCode;
        mTimeSinceCreatedMillis = timeSinceCreatedMillis;
        mInputMediaItemInfos = inputMediaItemInfos;
        mOutputMediaItemInfo = outputMediaItemInfo;
        mOperationTypes = operationTypes;
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

    /** Gets information about the input media items, or an empty list if unspecified. */
    @NonNull
    public List<MediaItemInfo> getInputMediaItemInfos() {
        return new ArrayList<>(mInputMediaItemInfos);
    }

    /** Gets information about the output media item, or {@code null} if unspecified. */
    @Nullable
    public MediaItemInfo getOutputMediaItemInfo() {
        return mOutputMediaItemInfo;
    }

    /** Gets a set of flags describing the types of operations performed. */
    public @OperationType long getOperationTypes() {
        return mOperationTypes;
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
        return "EditingEndedEvent { "
                + "finalState = "
                + mFinalState
                + ", "
                + "errorCode = "
                + mErrorCode
                + ", "
                + "timeSinceCreatedMillis = "
                + mTimeSinceCreatedMillis
                + ", "
                + "inputMediaItemInfos = "
                + mInputMediaItemInfos
                + ", "
                + "outputMediaItemInfo = "
                + mOutputMediaItemInfo
                + ", "
                + "operationTypes = "
                + mOperationTypes
                + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EditingEndedEvent that = (EditingEndedEvent) o;
        return mFinalState == that.mFinalState
                && mErrorCode == that.mErrorCode
                && Objects.equals(mInputMediaItemInfos, that.mInputMediaItemInfos)
                && Objects.equals(mOutputMediaItemInfo, that.mOutputMediaItemInfo)
                && mOperationTypes == that.mOperationTypes
                && mTimeSinceCreatedMillis == that.mTimeSinceCreatedMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mFinalState,
                mErrorCode,
                mInputMediaItemInfos,
                mOutputMediaItemInfo,
                mOperationTypes,
                mTimeSinceCreatedMillis);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mFinalState);
        dest.writeInt(mErrorCode);
        dest.writeLong(mTimeSinceCreatedMillis);
        dest.writeTypedList(mInputMediaItemInfos);
        dest.writeTypedObject(mOutputMediaItemInfo, /* parcelableFlags= */ 0);
        dest.writeLong(mOperationTypes);
        dest.writeBundle(mMetricsBundle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private EditingEndedEvent(@NonNull Parcel in) {
        mFinalState = in.readInt();
        mErrorCode = in.readInt();
        mTimeSinceCreatedMillis = in.readLong();
        mInputMediaItemInfos = new ArrayList<>();
        in.readTypedList(mInputMediaItemInfos, MediaItemInfo.CREATOR);
        mOutputMediaItemInfo = in.readTypedObject(MediaItemInfo.CREATOR);
        mOperationTypes = in.readLong();
        mMetricsBundle = in.readBundle();
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
        private final ArrayList<MediaItemInfo> mInputMediaItemInfos;
        private @ErrorCode int mErrorCode;
        private long mTimeSinceCreatedMillis;
        @Nullable private MediaItemInfo mOutputMediaItemInfo;
        private @OperationType long mOperationTypes;
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
            mInputMediaItemInfos = new ArrayList<>();
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

        /** Adds information about a media item that was input to the editing operation. */
        public @NonNull Builder addInputMediaItemInfo(@NonNull MediaItemInfo mediaItemInfo) {
            mInputMediaItemInfos.add(Objects.requireNonNull(mediaItemInfo));
            return this;
        }

        /** Sets information about the output media item. */
        public @NonNull Builder setOutputMediaItemInfo(@NonNull MediaItemInfo mediaItemInfo) {
            mOutputMediaItemInfo = Objects.requireNonNull(mediaItemInfo);
            return this;
        }

        /**
         * Adds an operation type to the set of operations performed.
         *
         * @param operationType A type of operation performed as part of this editing operation.
         */
        public @NonNull Builder addOperationType(@OperationType long operationType) {
            mOperationTypes |= operationType;
            return this;
        }

        /**
         * Sets metrics-related information that is not supported by dedicated methods.
         *
         * <p>Used for backwards compatibility by the metrics infrastructure.
         */
        public @NonNull Builder setMetricsBundle(@NonNull Bundle metricsBundle) {
            mMetricsBundle = Objects.requireNonNull(metricsBundle);
            return this;
        }

        /** Builds an instance. */
        public @NonNull EditingEndedEvent build() {
            return new EditingEndedEvent(
                    mFinalState,
                    mErrorCode,
                    mTimeSinceCreatedMillis,
                    mInputMediaItemInfos,
                    mOutputMediaItemInfo,
                    mOperationTypes,
                    mMetricsBundle);
        }
    }

}
