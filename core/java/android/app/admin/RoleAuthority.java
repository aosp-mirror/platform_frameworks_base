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
import android.os.Parcelable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Class used to identify the authority of the {@link EnforcingAdmin} based on a certain role/roles
 * it holds.
 *
 * @hide
 */
@SystemApi
public final class RoleAuthority extends Authority {
    private final Set<String> mRoles;

    /**
     * Constructor for a role authority that accepts the list of roles held by the admin.
     */
    public RoleAuthority(@NonNull Set<String> roles) {
        mRoles = new HashSet<>(Objects.requireNonNull(roles));
    }

    private RoleAuthority(Parcel source) {
        mRoles = new HashSet<>();
        int size = source.readInt();
        for (int i = 0; i < size; i++) {
            mRoles.add(source.readString());
        }
    }

    /**
     * Returns the list of roles held by the associated admin.
     */
    @NonNull
    public Set<String> getRoles() {
        return mRoles;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mRoles.size());
        for (String role : mRoles) {
            dest.writeString(role);
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoleAuthority other = (RoleAuthority) o;
        return Objects.equals(mRoles, other.mRoles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRoles);
    }

    @Override
    public String toString() {
        return "RoleAuthority { mRoles= " + mRoles + " }";
    }

    @NonNull
    public static final Parcelable.Creator<RoleAuthority> CREATOR =
            new Parcelable.Creator<RoleAuthority>() {
                @Override
                public RoleAuthority createFromParcel(Parcel source) {
                    return new RoleAuthority(source);
                }

                @Override
                public RoleAuthority[] newArray(int size) {
                    return new RoleAuthority[size];
                }
            };
}
