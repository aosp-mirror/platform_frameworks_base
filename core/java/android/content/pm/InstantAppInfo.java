/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class represents the state of an instant app. Instant apps can
 * be installed or uninstalled. If the app is installed you can call
 * {@link #getApplicationInfo()} to get the app info, otherwise this
 * class provides APIs to get basic app info for showing it in the UI,
 * such as permissions, label, package name.
 *
 * @hide
 */
@SystemApi
public final class InstantAppInfo implements Parcelable {
    private final ApplicationInfo mApplicationInfo;

    private final String mPackageName;
    private final CharSequence mLabelText;

    private final String[] mRequestedPermissions;
    private final String[] mGrantedPermissions;

    public InstantAppInfo(ApplicationInfo appInfo,
            String[] requestedPermissions, String[] grantedPermissions) {
        mApplicationInfo = appInfo;
        mPackageName = null;
        mLabelText = null;
        mRequestedPermissions = requestedPermissions;
        mGrantedPermissions = grantedPermissions;
    }

    public InstantAppInfo(String packageName, CharSequence label,
            String[] requestedPermissions, String[] grantedPermissions) {
        mApplicationInfo = null;
        mPackageName = packageName;
        mLabelText = label;
        mRequestedPermissions = requestedPermissions;
        mGrantedPermissions = grantedPermissions;
    }

    private InstantAppInfo(Parcel parcel) {
        mPackageName = parcel.readString();
        mLabelText = parcel.readCharSequence();
        mRequestedPermissions = parcel.readStringArray();
        mGrantedPermissions = parcel.createStringArray();
        mApplicationInfo = parcel.readParcelable(null);
    }

    /**
     * @return The application info if the app is installed,
     *     <code>null</code> otherwise,
     */
    public @Nullable ApplicationInfo getApplicationInfo() {
        return mApplicationInfo;
    }

    /**
     * @return The package name.
     */
    public @NonNull String getPackageName() {
        if (mApplicationInfo != null) {
            return mApplicationInfo.packageName;
        }
        return mPackageName;
    }

    /**
     * @param packageManager Package manager for loading resources.
     * @return Loads the label if the app is installed or returns the cached one otherwise.
     */
    public @NonNull CharSequence loadLabel(@NonNull PackageManager packageManager) {
        if (mApplicationInfo != null) {
            return mApplicationInfo.loadLabel(packageManager);
        }
        return mLabelText;
    }

    /**
     * @param packageManager Package manager for loading resources.
     * @return Loads the icon if the app is installed or returns the cached one otherwise.
     */
    public @NonNull Drawable loadIcon(@NonNull PackageManager packageManager) {
        if (mApplicationInfo != null) {
            return mApplicationInfo.loadIcon(packageManager);
        }
        return packageManager.getInstantAppIcon(mPackageName);
    }

    /**
     * @return The requested permissions.
     */
    public @Nullable String[] getRequestedPermissions() {
        return mRequestedPermissions;
    }

    /**
     * @return The granted permissions.
     */
    public @Nullable String[] getGrantedPermissions() {
        return mGrantedPermissions;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mPackageName);
        parcel.writeCharSequence(mLabelText);
        parcel.writeStringArray(mRequestedPermissions);
        parcel.writeStringArray(mGrantedPermissions);
        parcel.writeParcelable(mApplicationInfo, flags);
    }

    public static final Creator<InstantAppInfo> CREATOR =
            new Creator<InstantAppInfo>() {
        @Override
        public InstantAppInfo createFromParcel(Parcel parcel) {
            return new InstantAppInfo(parcel);
        }

        @Override
        public InstantAppInfo[] newArray(int size) {
            return new InstantAppInfo[0];
        }
    };
}
