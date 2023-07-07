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

package android.app.admin;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;

/**
 * Class used to identify that authority of the {@link EnforcingAdmin} setting a policy is a DPC or
 * an app acting as a DPC (e.g. delegate apps).
 *
 * @hide
 */
@SystemApi
public final class DpcAuthority extends Authority {

    /**
     * Object representing a DPC authority.
     *
     * @hide
     */
    @NonNull
    @TestApi
    public static final DpcAuthority DPC_AUTHORITY = new DpcAuthority();

    /**
     * Creates an authority that represents a DPC admin.
     */
    public DpcAuthority() {}

    @Override
    public String toString() {
        return "DpcAuthority {}";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        return o != null && getClass() == o.getClass();
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {}

    @NonNull
    public static final Creator<DpcAuthority> CREATOR =
            new Creator<DpcAuthority>() {
                @Override
                public DpcAuthority createFromParcel(Parcel source) {
                    return DPC_AUTHORITY;
                }

                @Override
                public DpcAuthority[] newArray(int size) {
                    return new DpcAuthority[size];
                }
            };
}
