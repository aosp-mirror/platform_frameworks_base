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
import android.os.Parcel;

/**
 * Class used to identify a default value for the authority of the {@link EnforcingAdmin} setting
 * a policy, meaning it is not one of the other known subclasses of {@link Authority}, this would be
 * the case for example for a system component setting the policy.
 *
 * @hide
 */
@SystemApi
public final class UnknownAuthority extends Authority {

    /**
     * @hide
     */
    public static final UnknownAuthority UNKNOWN_AUTHORITY = new UnknownAuthority();

    private UnknownAuthority() {}

    @Override
    public String toString() {
        return "DefaultAuthority {}";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {}

    @NonNull
    public static final Creator<UnknownAuthority> CREATOR =
            new Creator<UnknownAuthority>() {
                @Override
                public UnknownAuthority createFromParcel(Parcel source) {
                    return new UnknownAuthority();
                }

                @Override
                public UnknownAuthority[] newArray(int size) {
                    return new UnknownAuthority[size];
                }
            };
}
