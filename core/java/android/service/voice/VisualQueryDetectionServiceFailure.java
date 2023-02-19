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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class is used by the assistant application to know what went wrong during using
 * {@link VisualQueryDetectionService} and which action that the application should take.
 *
 * @hide
 */
@SystemApi
public final class VisualQueryDetectionServiceFailure extends DetectorFailure {

    /**
     * An error code which means an unknown error occurs.
     */
    public static final int ERROR_CODE_UNKNOWN = 0;

    /**
     * Indicates that the system server binds visual query detection service failure.
     */
    public static final int ERROR_CODE_BIND_FAILURE = 1;

    /**
     * Indicates that the visual query detection service is dead.
     */
    public static final int ERROR_CODE_BINDING_DIED = 2;

    /**
     * Indicates that the detection service has no attention listener registered.
     */
    public static final int ERROR_CODE_ILLEGAL_ATTENTION_STATE = 3;

    /**
     * Indicates that the detection service is not egressing and should not be streaming queries.
     */
    public static final int ERROR_CODE_ILLEGAL_STREAMING_STATE = 4;

    /**
     * Indicates that the remote exception occurs when calling callback method.
     */
    public static final int ERROR_CODE_REMOTE_EXCEPTION = 5;

    /**
     * @hide
     */
    @IntDef(prefix = {"ERROR_CODE_"}, value = {
            ERROR_CODE_UNKNOWN,
            ERROR_CODE_BIND_FAILURE,
            ERROR_CODE_BINDING_DIED,
            ERROR_CODE_ILLEGAL_ATTENTION_STATE,
            ERROR_CODE_ILLEGAL_STREAMING_STATE,
            ERROR_CODE_REMOTE_EXCEPTION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VisualQueryDetectionServiceErrorCode {}

    /**
     * @hide
     */
    @TestApi
    public VisualQueryDetectionServiceFailure(int errorCode, @NonNull String errorMessage) {
        super(ERROR_SOURCE_TYPE_VISUAL_QUERY_DETECTION, errorCode, errorMessage);
    }

    /**
     * Returns the error code.
     */
    @VisualQueryDetectionServiceErrorCode
    public int getErrorCode() {
        return super.getErrorCode();
    }

    @Override
    public int getSuggestedAction() {
        switch (getErrorCode()) {
            case ERROR_CODE_BIND_FAILURE:
            case ERROR_CODE_BINDING_DIED:
            case ERROR_CODE_ILLEGAL_ATTENTION_STATE:
            case ERROR_CODE_REMOTE_EXCEPTION:
                return SUGGESTED_ACTION_RECREATE_DETECTOR;
            case ERROR_CODE_ILLEGAL_STREAMING_STATE:
                return SUGGESTED_ACTION_RESTART_RECOGNITION;
            default:
                return SUGGESTED_ACTION_NONE;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
    }

    public static final @NonNull Parcelable.Creator<VisualQueryDetectionServiceFailure> CREATOR =
            new Parcelable.Creator<VisualQueryDetectionServiceFailure>() {
                @Override
                public VisualQueryDetectionServiceFailure[] newArray(int size) {
                    return new VisualQueryDetectionServiceFailure[size];
                }

                @Override
                public VisualQueryDetectionServiceFailure createFromParcel(@NonNull Parcel in) {
                    DetectorFailure detectorFailure = DetectorFailure.CREATOR.createFromParcel(in);
                    return (VisualQueryDetectionServiceFailure) detectorFailure;
                }
            };
}
