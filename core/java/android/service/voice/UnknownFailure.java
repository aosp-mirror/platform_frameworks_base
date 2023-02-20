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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class which indicates an unknown error occurs during the detector doing detection. The class
 * is mainly used by the assistant application, the application still can get the suggested action
 * for the unknown error.
 *
 * @hide
 */
@SystemApi
public final class UnknownFailure extends DetectorFailure {

    /**
     * An error code which means an unknown error occurs.
     *
     * @hide
     */
    public static final int ERROR_CODE_UNKNOWN = 0;

    /**
     * @hide
     */
    @TestApi
    public UnknownFailure(@NonNull String errorMessage) {
        super(ERROR_SOURCE_TYPE_UNKNOWN, ERROR_CODE_UNKNOWN, errorMessage);
    }

    @Override
    public int getSuggestedAction() {
        return SUGGESTED_ACTION_UNKNOWN;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
    }

    public static final @NonNull Parcelable.Creator<UnknownFailure> CREATOR =
            new Parcelable.Creator<UnknownFailure>() {
                @Override
                public UnknownFailure[] newArray(int size) {
                    return new UnknownFailure[size];
                }

                @Override
                public UnknownFailure createFromParcel(@NonNull Parcel in) {
                    return (UnknownFailure) DetectorFailure.CREATOR.createFromParcel(in);
                }
            };
}
