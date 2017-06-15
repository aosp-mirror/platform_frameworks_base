/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.service.euicc;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Result of a {@link EuiccService#onGetEuiccProfileInfoList} operation.
 * @hide
 *
 * TODO(b/35851809): Make this a SystemApi.
 */
public final class GetEuiccProfileInfoListResult implements Parcelable {

    public static final Creator<GetEuiccProfileInfoListResult> CREATOR =
            new Creator<GetEuiccProfileInfoListResult>() {
                @Override
                public GetEuiccProfileInfoListResult createFromParcel(Parcel in) {
                    return new GetEuiccProfileInfoListResult(in);
                }

                @Override
                public GetEuiccProfileInfoListResult[] newArray(int size) {
                    return new GetEuiccProfileInfoListResult[size];
                }
            };

    /**
     * Result of the operation.
     *
     * <p>May be one of the predefined {@code RESULT_} constants in EuiccService or any
     * implementation-specific code starting with {@link EuiccService#RESULT_FIRST_USER}.
     */
    public final int result;

    /** The profile list (only upon success). */
    @Nullable
    public final EuiccProfileInfo[] profiles;

    /** Whether the eUICC is removable. */
    public final boolean isRemovable;

    /**
     * Construct a new {@link GetEuiccProfileInfoListResult}.
     *
     * @param result Result of the operation. May be one of the predefined {@code RESULT_} constants
     *     in EuiccService or any implementation-specific code starting with
     *     {@link EuiccService#RESULT_FIRST_USER}.
     * @param profiles the list of profiles. Should only be provided if the result is
     *     {@link EuiccService#RESULT_OK}.
     * @param isRemovable whether the eUICC in this slot is removable. If true, the profiles
     *     returned here will only be considered accessible as long as this eUICC is present.
     *     Otherwise, they will remain accessible until the next time a response with isRemovable
     *     set to false is returned.
     */
    public GetEuiccProfileInfoListResult(
            int result, @Nullable EuiccProfileInfo[] profiles, boolean isRemovable) {
        this.result = result;
        this.isRemovable = isRemovable;
        if (this.result == EuiccService.RESULT_OK) {
            this.profiles = profiles;
        } else {
            if (profiles != null) {
                throw new IllegalArgumentException(
                        "Error result with non-null profiles: " + result);
            }
            this.profiles = null;
        }

    }

    private GetEuiccProfileInfoListResult(Parcel in) {
        this.result = in.readInt();
        this.profiles = in.createTypedArray(EuiccProfileInfo.CREATOR);
        this.isRemovable = in.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(result);
        dest.writeTypedArray(profiles, flags);
        dest.writeBoolean(isRemovable);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
