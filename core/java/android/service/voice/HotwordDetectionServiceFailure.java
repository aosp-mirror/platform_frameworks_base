/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.voice;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.voice.flags.Flags;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class is used by the assistant application to know what went wrong during using the
 * {@link HotwordDetector} and which action that the application should take. When an error occurs
 * from Dsp hotword detection, software hotword detection and {@link HotwordDetectionService}, the
 * system will send {@link HotwordDetectionServiceFailure} which contains the error code, error
 * message and the suggested action to help the assistant application to take the next action.
 *
 * @hide
 */
@SystemApi
public final class HotwordDetectionServiceFailure implements Parcelable {

    /**
     * An error code which means an unknown error occurs.
     */
    public static final int ERROR_CODE_UNKNOWN = 0;

    /**
     * Indicates that the system server binds hotword detection service failure.
     */
    public static final int ERROR_CODE_BIND_FAILURE = 1;

    /**
     * Indicates that the hotword detection service is dead.
     */
    public static final int ERROR_CODE_BINDING_DIED = 2;

    /**
     * Indicates to copy audio data failure for external source detection.
     */
    public static final int ERROR_CODE_COPY_AUDIO_DATA_FAILURE = 3;

    /**
     * Indicates that the detection service doesn’t respond to the detection result before timeout.
     */
    public static final int ERROR_CODE_DETECT_TIMEOUT = 4;

    /**
     * Indicates that the security exception occurs in #onDetected method.
     */
    public static final int ERROR_CODE_ON_DETECTED_SECURITY_EXCEPTION = 5;

    /**
     * Indicates to copy the audio stream failure in #onDetected method.
     */
    public static final int ERROR_CODE_ON_DETECTED_STREAM_COPY_FAILURE = 6;

    /**
     * Indicates that the remote exception occurs when calling callback method.
     */
    public static final int ERROR_CODE_REMOTE_EXCEPTION = 7;

    /** Indicates shutdown of {@link HotwordDetectionService} due to voice activation op being
     * disabled. */
    @FlaggedApi(Flags.FLAG_ALLOW_TRAINING_DATA_EGRESS_FROM_HDS)
    public static final int ERROR_CODE_SHUTDOWN_HDS_ON_VOICE_ACTIVATION_OP_DISABLED = 10;

    /**
     * @hide
     */
    @IntDef(prefix = {"ERROR_CODE_"}, value = {
            ERROR_CODE_UNKNOWN,
            ERROR_CODE_BIND_FAILURE,
            ERROR_CODE_BINDING_DIED,
            ERROR_CODE_COPY_AUDIO_DATA_FAILURE,
            ERROR_CODE_DETECT_TIMEOUT,
            ERROR_CODE_ON_DETECTED_SECURITY_EXCEPTION,
            ERROR_CODE_ON_DETECTED_STREAM_COPY_FAILURE,
            ERROR_CODE_REMOTE_EXCEPTION,
            ERROR_CODE_SHUTDOWN_HDS_ON_VOICE_ACTIVATION_OP_DISABLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HotwordDetectionServiceErrorCode {}

    private int mErrorCode = ERROR_CODE_UNKNOWN;
    private String mErrorMessage = "Unknown";

    /**
     * @hide
     */
    @TestApi
    public HotwordDetectionServiceFailure(int errorCode, @NonNull String errorMessage) {
        if (TextUtils.isEmpty(errorMessage)) {
            throw new IllegalArgumentException("errorMessage is empty or null.");
        }
        mErrorCode = errorCode;
        mErrorMessage = errorMessage;
    }

    /**
     * Returns the error code.
     */
    @HotwordDetectionServiceErrorCode
    public int getErrorCode() {
        return mErrorCode;
    }

    /**
     * Returns the error message.
     */
    @NonNull
    public String getErrorMessage() {
        return mErrorMessage;
    }

    /**
     * Returns the suggested action.
     */
    @FailureSuggestedAction.FailureSuggestedActionDef
    public int getSuggestedAction() {
        switch (mErrorCode) {
            case ERROR_CODE_BIND_FAILURE:
            case ERROR_CODE_BINDING_DIED:
            case ERROR_CODE_REMOTE_EXCEPTION:
                return FailureSuggestedAction.RECREATE_DETECTOR;
            case ERROR_CODE_DETECT_TIMEOUT:
            case ERROR_CODE_ON_DETECTED_SECURITY_EXCEPTION:
            case ERROR_CODE_ON_DETECTED_STREAM_COPY_FAILURE:
                return FailureSuggestedAction.RESTART_RECOGNITION;
            default:
                return FailureSuggestedAction.NONE;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mErrorCode);
        dest.writeString8(mErrorMessage);
    }

    @Override
    public String toString() {
        return "HotwordDetectionServiceFailure { errorCode = " + mErrorCode + ", errorMessage = "
                + mErrorMessage + " }";
    }

    public static final @NonNull Parcelable.Creator<HotwordDetectionServiceFailure> CREATOR =
            new Parcelable.Creator<HotwordDetectionServiceFailure>() {
                @Override
                public HotwordDetectionServiceFailure[] newArray(int size) {
                    return new HotwordDetectionServiceFailure[size];
                }

                @Override
                public HotwordDetectionServiceFailure createFromParcel(@NonNull Parcel in) {
                    return new HotwordDetectionServiceFailure(in.readInt(), in.readString8());
                }
            };
}
