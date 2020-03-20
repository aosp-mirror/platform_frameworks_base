/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.usage.StorageStatsManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;

import java.util.Objects;

/**
 * implementation of PackageStats associated with a application package.
 *
 * @deprecated this class is an orphan that could never be obtained from a valid
 *             public API. If you need package storage statistics use the new
 *             {@link StorageStatsManager} APIs.
 */
@Deprecated
public class PackageStats implements Parcelable {
    /** Name of the package to which this stats applies. */
    public String packageName;

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int userHandle;

    /** Size of the code (e.g., APK) */
    public long codeSize;

    /**
     * Size of the internal data size for the application. (e.g.,
     * /data/data/<app>)
     */
    public long dataSize;

    /** Size of cache used by the application. (e.g., /data/data/<app>/cache) */
    public long cacheSize;

    /**
     * Size of the secure container on external storage holding the
     * application's code.
     */
    public long externalCodeSize;

    /**
     * Size of the external data used by the application (e.g.,
     * <sdcard>/Android/data/<app>)
     */
    public long externalDataSize;

    /**
     * Size of the external cache used by the application (i.e., on the SD
     * card). If this is a subdirectory of the data directory, this size will be
     * subtracted out of the external data size.
     */
    public long externalCacheSize;

    /** Size of the external media size used by the application. */
    public long externalMediaSize;

    /** Size of the package's OBBs placed on external media. */
    public long externalObbSize;

    public static final @android.annotation.NonNull Parcelable.Creator<PackageStats> CREATOR
            = new Parcelable.Creator<PackageStats>() {
        public PackageStats createFromParcel(Parcel in) {
            return new PackageStats(in);
        }

        public PackageStats[] newArray(int size) {
            return new PackageStats[size];
        }
    };

    public String toString() {
        final StringBuilder sb = new StringBuilder("PackageStats{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" ");
        sb.append(packageName);
        if (codeSize != 0) {
            sb.append(" code=");
            sb.append(codeSize);
        }
        if (dataSize != 0) {
            sb.append(" data=");
            sb.append(dataSize);
        }
        if (cacheSize != 0) {
            sb.append(" cache=");
            sb.append(cacheSize);
        }
        if (externalCodeSize != 0) {
            sb.append(" extCode=");
            sb.append(externalCodeSize);
        }
        if (externalDataSize != 0) {
            sb.append(" extData=");
            sb.append(externalDataSize);
        }
        if (externalCacheSize != 0) {
            sb.append(" extCache=");
            sb.append(externalCacheSize);
        }
        if (externalMediaSize != 0) {
            sb.append(" media=");
            sb.append(externalMediaSize);
        }
        if (externalObbSize != 0) {
            sb.append(" obb=");
            sb.append(externalObbSize);
        }
        sb.append("}");
        return sb.toString();
    }

    public PackageStats(String pkgName) {
        packageName = pkgName;
        userHandle = UserHandle.myUserId();
    }

    /** @hide */
    public PackageStats(String pkgName, int userHandle) {
        this.packageName = pkgName;
        this.userHandle = userHandle;
    }

    public PackageStats(Parcel source) {
        packageName = source.readString();
        userHandle = source.readInt();
        codeSize = source.readLong();
        dataSize = source.readLong();
        cacheSize = source.readLong();
        externalCodeSize = source.readLong();
        externalDataSize = source.readLong();
        externalCacheSize = source.readLong();
        externalMediaSize = source.readLong();
        externalObbSize = source.readLong();
    }

    public PackageStats(PackageStats pStats) {
        packageName = pStats.packageName;
        userHandle = pStats.userHandle;
        codeSize = pStats.codeSize;
        dataSize = pStats.dataSize;
        cacheSize = pStats.cacheSize;
        externalCodeSize = pStats.externalCodeSize;
        externalDataSize = pStats.externalDataSize;
        externalCacheSize = pStats.externalCacheSize;
        externalMediaSize = pStats.externalMediaSize;
        externalObbSize = pStats.externalObbSize;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags){
        dest.writeString(packageName);
        dest.writeInt(userHandle);
        dest.writeLong(codeSize);
        dest.writeLong(dataSize);
        dest.writeLong(cacheSize);
        dest.writeLong(externalCodeSize);
        dest.writeLong(externalDataSize);
        dest.writeLong(externalCacheSize);
        dest.writeLong(externalMediaSize);
        dest.writeLong(externalObbSize);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PackageStats)) {
            return false;
        }

        final PackageStats otherStats = (PackageStats) obj;
        return ((TextUtils.equals(packageName, otherStats.packageName))
                && userHandle == otherStats.userHandle
                && codeSize == otherStats.codeSize
                && dataSize == otherStats.dataSize
                && cacheSize == otherStats.cacheSize
                && externalCodeSize == otherStats.externalCodeSize
                && externalDataSize == otherStats.externalDataSize
                && externalCacheSize == otherStats.externalCacheSize
                && externalMediaSize == otherStats.externalMediaSize
                && externalObbSize == otherStats.externalObbSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName, userHandle, codeSize, dataSize,
                cacheSize, externalCodeSize, externalDataSize, externalCacheSize, externalMediaSize,
                externalObbSize);
    }

}
