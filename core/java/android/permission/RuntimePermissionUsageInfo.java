/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.permission;

import static com.android.internal.util.Preconditions.checkArgumentNonnegative;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class contains information about how a runtime permission
 * is used. A single runtime permission presented to the user may
 * correspond to multiple platform defined permissions, e.g. the
 * location permission may control both the coarse and fine platform
 * permissions.
 *
 * @hide
 */
@SystemApi
public final class RuntimePermissionUsageInfo implements Parcelable {
    private final @NonNull String mName;
    private final int mNumUsers;

    /**
     * Creates a new instance.
     *
     * @param name The permission group name.
     * @param numUsers The number of apps that have used this permission.
     */
    public RuntimePermissionUsageInfo(@NonNull String name, int numUsers) {
        checkNotNull(name);
        checkArgumentNonnegative(numUsers);

        mName = name;
        mNumUsers = numUsers;
    }

    private RuntimePermissionUsageInfo(Parcel parcel) {
        this(parcel.readString(), parcel.readInt());
    }

    /**
     * @return The number of apps that accessed this permission
     */
    public int getAppAccessCount() {
        return mNumUsers;
    }

    /**
     * Gets the permission group name.
     *
     * @return The name.
     */
    public @NonNull String getName() {
        return mName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mName);
        parcel.writeInt(mNumUsers);
    }

    public static final @android.annotation.NonNull Creator<RuntimePermissionUsageInfo> CREATOR =
            new Creator<RuntimePermissionUsageInfo>() {
        public RuntimePermissionUsageInfo createFromParcel(Parcel source) {
            return new RuntimePermissionUsageInfo(source);
        }

        public RuntimePermissionUsageInfo[] newArray(int size) {
            return new RuntimePermissionUsageInfo[size];
        }
    };
}
