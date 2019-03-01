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
package android.content.pm;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Encapsulates a package and its version code.
 */
public final class VersionedPackage implements Parcelable {
    private final String mPackageName;
    private final long mVersionCode;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntRange(from = PackageManager.VERSION_CODE_HIGHEST)
    public @interface VersionCode{}

    /**
     * Creates a new instance. Use {@link PackageManager#VERSION_CODE_HIGHEST}
     * to refer to the highest version code of this package.
     * @param packageName The package name.
     * @param versionCode The version code.
     */
    public VersionedPackage(@NonNull String packageName,
            @VersionCode int versionCode) {
        mPackageName = packageName;
        mVersionCode = versionCode;
    }

    /**
     * Creates a new instance. Use {@link PackageManager#VERSION_CODE_HIGHEST}
     * to refer to the highest version code of this package.
     * @param packageName The package name.
     * @param versionCode The version code.
     */
    public VersionedPackage(@NonNull String packageName,
            @VersionCode long versionCode) {
        mPackageName = packageName;
        mVersionCode = versionCode;
    }

    private VersionedPackage(Parcel parcel) {
        mPackageName = parcel.readString();
        mVersionCode = parcel.readLong();
    }

    /**
     * Gets the package name.
     *
     * @return The package name.
     */
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /**
     * @deprecated use {@link #getLongVersionCode()} instead.
     */
    @Deprecated
    public @VersionCode int getVersionCode() {
        return (int) (mVersionCode & 0x7fffffff);
    }

    /**
     * Gets the version code.
     *
     * @return The version code.
     */
    public @VersionCode long getLongVersionCode() {
        return mVersionCode;
    }

    @Override
    public String toString() {
        return "VersionedPackage[" + mPackageName + "/" + mVersionCode + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mPackageName);
        parcel.writeLong(mVersionCode);
    }

    public static final @android.annotation.NonNull Creator<VersionedPackage> CREATOR = new Creator<VersionedPackage>() {
        @Override
        public VersionedPackage createFromParcel(Parcel source) {
            return new VersionedPackage(source);
        }

        @Override
        public VersionedPackage[] newArray(int size) {
            return new VersionedPackage[size];
        }
    };
}
