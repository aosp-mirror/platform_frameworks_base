/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;

/**
 * Represents the usage of a permission group by an app. Supports package name, user, permission
 * group, whether or not the access is running or recent, whether the access is tied to a phone
 * call, and an optional special attribution.
 *
 * @hide
 */
@TestApi
public final class PermGroupUsage {

    private final String mPackageName;
    private final int mUid;
    private final long mLastAccess;
    private final String mPermGroupName;
    private final boolean mIsActive;
    private final boolean mIsPhoneCall;
    private final CharSequence mAttribution;

    /**
     *
     * @param packageName The package name of the using app
     * @param uid The uid of the using app
     * @param permGroupName The name of the permission group being used
     * @param lastAccess The time of last access
     * @param isActive Whether this is active
     * @param isPhoneCall Whether this is a usage by the phone
     * @param attribution An optional string attribution to show
     * @hide
     */
    @TestApi
    public PermGroupUsage(@NonNull String packageName, int uid,
            @NonNull String permGroupName, long lastAccess, boolean isActive, boolean isPhoneCall,
            @Nullable CharSequence attribution) {
        this.mPackageName = packageName;
        this.mUid = uid;
        this.mPermGroupName = permGroupName;
        this.mLastAccess = lastAccess;
        this.mIsActive = isActive;
        this.mIsPhoneCall = isPhoneCall;
        this.mAttribution = attribution;
    }

    /**
     * @hide
     */
    @TestApi
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /**
     * @hide
     */
    @TestApi
    public int getUid() {
        return mUid;
    }

    /**
     * @hide
     */
    @TestApi
    public @NonNull String getPermGroupName() {
        return mPermGroupName;
    }

    /**
     * @hide
     */
    @TestApi
    public long getLastAccess() {
        return mLastAccess;
    }

    /**
     * @hide
     */
    @TestApi
    public boolean isActive() {
        return mIsActive;
    }

    /**
     * @hide
     */
    @TestApi
    public boolean isPhoneCall() {
        return mIsPhoneCall;
    }

    /**
     * @hide
     */
    @TestApi
    public @Nullable CharSequence getAttribution() {
        return mAttribution;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(this))
                + " packageName: " + mPackageName + ", UID: " + mUid + ", permGroup: "
                + mPermGroupName + ", lastAccess: " + mLastAccess + ", isActive: " + mIsActive
                + ", attribution: " + mAttribution;
    }
}
