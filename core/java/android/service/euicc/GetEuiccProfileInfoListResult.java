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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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

    /** @hide */
    @IntDef({
            RESULT_OK,
            RESULT_GENERIC_ERROR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {}

    public static final int RESULT_OK = 0;
    public static final int RESULT_GENERIC_ERROR = 1;

    /** Result of the operation - one of the RESULT_* constants. */
    public final @ResultCode int result;

    /** Implementation-defined detailed error code in case of a failure not covered here. */
    public final int detailedCode;

    /** The profile list (only upon success). */
    @Nullable
    public final EuiccProfileInfo[] profiles;

    /** Whether the eUICC is removable. */
    public final boolean isRemovable;

    private GetEuiccProfileInfoListResult(int result, int detailedCode, EuiccProfileInfo[] profiles,
            boolean isRemovable) {
        this.result = result;
        this.detailedCode = detailedCode;
        this.profiles = profiles;
        this.isRemovable = isRemovable;
    }

    private GetEuiccProfileInfoListResult(Parcel in) {
        this.result = in.readInt();
        this.detailedCode = in.readInt();
        this.profiles = in.createTypedArray(EuiccProfileInfo.CREATOR);
        this.isRemovable = in.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(result);
        dest.writeInt(detailedCode);
        dest.writeTypedArray(profiles, flags);
        dest.writeBoolean(isRemovable);
    }

    /**
     * Return a result indicating that the listing was successful.
     *
     * @param profiles the list of profiles
     * @param isRemovable whether the eUICC in this slot is removable. If true, the profiles
     *     returned here will only be considered accessible as long as this eUICC is present.
     *     Otherwise, they will remain accessible until the next time a response with isRemovable
     *     set to false is returned.
     */
    public static GetEuiccProfileInfoListResult success(
            EuiccProfileInfo[] profiles, boolean isRemovable) {
        return new GetEuiccProfileInfoListResult(
                RESULT_OK, 0 /* detailedCode */, profiles, isRemovable);
    }

    /**
     * Return a result indicating that an error occurred for which no other more specific error
     * code has been defined.
     *
     * @param detailedCode an implementation-defined detailed error code for debugging purposes.
     * @param isRemovable whether the eUICC in this slot is removable. If true, only removable
     *     profiles will be made inaccessible. Otherwise, all embedded profiles will be
     *     considered inaccessible.
     */
    public static GetEuiccProfileInfoListResult genericError(
            int detailedCode, boolean isRemovable) {
        return new GetEuiccProfileInfoListResult(
                RESULT_GENERIC_ERROR, detailedCode, null /* profiles */, isRemovable);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
