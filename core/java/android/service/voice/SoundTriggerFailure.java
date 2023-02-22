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
 * This class is used by the assistant application to know what went wrong during using the
 * sound trigger system service {@link com.android.server.soundtrigger.SoundTriggerService} and
 * {@link com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareService}, and which
 * action that the application should take.
 *
 * @hide
 */
@SystemApi
public final class SoundTriggerFailure extends DetectorFailure {

    /**
     * An error code which means an unknown error occurs.
     */
    public static final int ERROR_CODE_UNKNOWN = 0;

    /**
     * Indicates that the underlying sound trigger module has died and will be restarted. All
     * session state has been invalidated.
     */
    public static final int ERROR_CODE_MODULE_DIED = 1;

    /**
     * Indicates that sound trigger service recognition resume has failed. The model is in the
     * stopped state and will not be restarted by the framework.
     */
    public static final int ERROR_CODE_RECOGNITION_RESUME_FAILED = 2;

    /**
     * Indicates that the sound trigger service has been unexpectedly preempted by another user.
     * The model is in the stopped state and will not be restarted by the framework.
     */
    public static final int ERROR_CODE_UNEXPECTED_PREEMPTION = 3;

    /**
     * @hide
     */
    @IntDef(prefix = {"ERROR_CODE_"}, value = {
            ERROR_CODE_UNKNOWN,
            ERROR_CODE_MODULE_DIED,
            ERROR_CODE_RECOGNITION_RESUME_FAILED,
            ERROR_CODE_UNEXPECTED_PREEMPTION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SoundTriggerErrorCode {}

    /**
     * @hide
     */
    @TestApi
    public SoundTriggerFailure(int errorCode, @NonNull String errorMessage) {
        super(ERROR_SOURCE_TYPE_SOUND_TRIGGER, errorCode, errorMessage);
    }

    /**
     * Returns the error code.
     */
    @SoundTriggerErrorCode
    public int getErrorCode() {
        return super.getErrorCode();
    }

    @Override
    public int getSuggestedAction() {
        switch (getErrorCode()) {
            case ERROR_CODE_MODULE_DIED:
            case ERROR_CODE_UNEXPECTED_PREEMPTION:
                return SUGGESTED_ACTION_RECREATE_DETECTOR;
            case ERROR_CODE_RECOGNITION_RESUME_FAILED:
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

    public static final @NonNull Parcelable.Creator<SoundTriggerFailure> CREATOR =
            new Parcelable.Creator<SoundTriggerFailure>() {
                @Override
                public SoundTriggerFailure[] newArray(int size) {
                    return new SoundTriggerFailure[size];
                }

                @Override
                public SoundTriggerFailure createFromParcel(@NonNull Parcel in) {
                    return (SoundTriggerFailure) DetectorFailure.CREATOR.createFromParcel(in);
                }
            };
}
