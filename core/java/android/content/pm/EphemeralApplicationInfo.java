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
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class represents the state of an ephemeral app.
 *
 * @hide
 */
public final class EphemeralApplicationInfo implements Parcelable {
    private final ApplicationInfo mApplicationInfo;

    private final String mPackageName;
    private final CharSequence mLabelText;

    private final String[] mRequestedPermissions;
    private final String[] mGrantedPermissions;

    public EphemeralApplicationInfo(ApplicationInfo appInfo,
            String[] requestedPermissions, String[] grantedPermissions) {
        mApplicationInfo = appInfo;
        mPackageName = null;
        mLabelText = null;
        mRequestedPermissions = requestedPermissions;
        mGrantedPermissions = grantedPermissions;
    }

    public EphemeralApplicationInfo(String packageName, CharSequence label,
            String[] requestedPermissions, String[] grantedPermissions) {
        mApplicationInfo = null;
        mPackageName = packageName;
        mLabelText = label;
        mRequestedPermissions = requestedPermissions;
        mGrantedPermissions = grantedPermissions;
    }

    private EphemeralApplicationInfo(Parcel parcel) {
        mPackageName = parcel.readString();
        mLabelText = parcel.readCharSequence();
        mRequestedPermissions = parcel.readStringArray();
        mGrantedPermissions = parcel.createStringArray();
        mApplicationInfo = parcel.readParcelable(null);
    }

    public @NonNull String getPackageName() {
        if (mApplicationInfo != null) {
            return mApplicationInfo.packageName;
        }
        return mPackageName;
    }

    public @NonNull CharSequence loadLabel(@NonNull PackageManager packageManager) {
        if (mApplicationInfo != null) {
            return mApplicationInfo.loadLabel(packageManager);
        }
        return mLabelText;
    }

    public @NonNull Drawable loadIcon(@NonNull PackageManager packageManager) {
        if (mApplicationInfo != null) {
            return mApplicationInfo.loadIcon(packageManager);
        }
        return packageManager.getEphemeralApplicationIcon(mPackageName);
    }

    public @Nullable String[] getRequestedPermissions() {
        return mRequestedPermissions;
    }

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

    public static final Creator<EphemeralApplicationInfo> CREATOR =
            new Creator<EphemeralApplicationInfo>() {
        @Override
        public EphemeralApplicationInfo createFromParcel(Parcel parcel) {
            return new EphemeralApplicationInfo(parcel);
        }

        @Override
        public EphemeralApplicationInfo[] newArray(int size) {
            return new EphemeralApplicationInfo[0];
        }
    };
}
