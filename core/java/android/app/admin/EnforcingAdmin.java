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
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.Objects;

/**
 * Class containing info about the admin enforcing a certain policy, e.g. its {@code packageName}
 * and {@link Authority}.
 *
 * @hide
 */
@SystemApi
public final class EnforcingAdmin implements Parcelable {
    private final String mPackageName;
    private final Authority mAuthority;
    private final UserHandle mUserHandle;

    /**
     * @hide
     */
    private final ComponentName mComponentName;

    /**
     * Creates an enforcing admin with the given params.
     */
    public EnforcingAdmin(
            @NonNull String packageName, @NonNull Authority authority,
            @NonNull UserHandle userHandle) {
        mPackageName = Objects.requireNonNull(packageName);
        mAuthority = Objects.requireNonNull(authority);
        mUserHandle = Objects.requireNonNull(userHandle);
        mComponentName = null;
    }

    /**
     * Creates an enforcing admin with the given params.
     *
     * @hide
     */
    @TestApi
    public EnforcingAdmin(
            @NonNull String packageName, @NonNull Authority authority,
            @NonNull UserHandle userHandle, @Nullable ComponentName componentName) {
        mPackageName = Objects.requireNonNull(packageName);
        mAuthority = Objects.requireNonNull(authority);
        mUserHandle = Objects.requireNonNull(userHandle);
        mComponentName = componentName;
    }

    private EnforcingAdmin(Parcel source) {
        mPackageName = Objects.requireNonNull(source.readString());
        mUserHandle = new UserHandle(source.readInt());
        mAuthority = Objects.requireNonNull(
                source.readParcelable(Authority.class.getClassLoader()));
        mComponentName = source.readParcelable(ComponentName.class.getClassLoader());
    }

    /**
     * Returns the {@link Authority} on which the admin is acting on, e.g. DPC, DeviceAdmin, etc.
     */
    @NonNull
    public Authority getAuthority() {
        return mAuthority;
    }

    /**
     * Returns the package name of the admin.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the {@link UserHandle} on which the admin is installed on.
     */
    @NonNull
    public UserHandle getUserHandle() {
        return mUserHandle;
    }

    /**
     * Returns the {@link ComponentName} of the admin if applicable.
     *
     * @hide
     */
    @Nullable
    public ComponentName getComponentName() {
        return mComponentName;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EnforcingAdmin other = (EnforcingAdmin) o;
        return Objects.equals(mPackageName, other.mPackageName)
                && Objects.equals(mAuthority, other.mAuthority)
                && Objects.equals(mUserHandle, other.mUserHandle)
                && Objects.equals(mComponentName, other.mComponentName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackageName, mAuthority, mUserHandle);
    }

    @Override
    public String toString() {
        return "EnforcingAdmin { mPackageName= " + mPackageName + ", mAuthority= " + mAuthority
                + ", mUserHandle= " + mUserHandle + ", mComponentName= " + mComponentName + " }";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mPackageName);
        dest.writeInt(mUserHandle.getIdentifier());
        dest.writeParcelable(mAuthority, flags);
        dest.writeParcelable(mComponentName, flags);
    }

    @NonNull
    public static final Parcelable.Creator<EnforcingAdmin> CREATOR =
            new Parcelable.Creator<EnforcingAdmin>() {
                @Override
                public EnforcingAdmin createFromParcel(Parcel source) {
                    return new EnforcingAdmin(source);
                }

                @Override
                public EnforcingAdmin[] newArray(int size) {
                    return new EnforcingAdmin[size];
                }
            };
}
