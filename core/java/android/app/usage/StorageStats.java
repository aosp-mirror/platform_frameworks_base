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

package android.app.usage;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Storage statistics for a single UID on a single storage volume.
 * <p class="note">
 * Note: multiple packages using the same {@code sharedUserId} in their manifest
 * will be merged into a single UID.
 * </p>
 *
 * @see StorageStatsManager
 */
public final class StorageStats implements Parcelable {
    /** {@hide} */ public final String volumeUuid;
    /** {@hide} */ public final int uid;

    /** {@hide} */ public long codeBytes;
    /** {@hide} */ public long dataBytes;
    /** {@hide} */ public long cacheQuotaBytes;
    /** {@hide} */ public long cacheBytes;

    /**
     * Return the UUID of the storage volume that these statistics refer to. The
     * value {@code null} indicates the default internal storage.
     */
    public String getVolumeUuid() {
        return volumeUuid;
    }

    /**
     * Return the UID that these statistics refer to.
     *
     * @see ApplicationInfo#uid
     * @see PackageManager#getPackagesForUid(int)
     */
    public int getUid() {
        return uid;
    }

    /**
     * Return the size of all code associated with {@link #getUid()} on
     * {@link #getVolumeUuid()}. This includes {@code APK} files and optimized
     * compiler output.
     * <p>
     * If the primary shared storage location is also hosted on
     * {@link #getVolumeUuid()} then this includes files stored under
     * {@link Context#getObbDir()}.
     * <p>
     * Code is shared between all users on a multiuser device.
     */
    public long getCodeBytes() {
        return codeBytes;
    }

    /**
     * Return the size of all data associated with {@link #getUid()} on
     * {@link #getVolumeUuid()}. This includes files stored under
     * {@link Context#getDataDir()}, {@link Context#getCacheDir()},
     * {@link Context#getCodeCacheDir()}.
     * <p>
     * If the primary shared storage location is also hosted on
     * {@link #getVolumeUuid()} then this includes files stored under
     * {@link Context#getExternalFilesDir(String)} and
     * {@link Context#getExternalCacheDir()}.
     * <p>
     * Data is isolated for each user on a multiuser device.
     */
    public long getDataBytes() {
        return dataBytes;
    }

    /**
     * Return the quota for cached data associated with {@link #getUid()} on
     * {@link #getVolumeUuid()}. This quota value is calculated based on how
     * frequently the user has interacted with the UID.
     * <p>
     * When clearing cached data, the system will first focus on packages whose
     * cached data is larger than their allocated quota.
     */
    public long getCacheQuotaBytes() {
        return cacheQuotaBytes;
    }

    /**
     * Return the size of all cached data associated with {@link #getUid()} on
     * {@link #getVolumeUuid()}. This includes files stored under
     * {@link Context#getCacheDir()} and {@link Context#getCodeCacheDir()}.
     * <p>
     * If the primary shared storage location is also hosted on
     * {@link #getVolumeUuid()} then this includes files stored under
     * {@link Context#getExternalCacheDir()}.
     * <p>
     * Cached data is isolated for each user on a multiuser device.
     */
    public long getCacheBytes() {
        return cacheBytes;
    }

    /** {@hide} */
    public StorageStats(String uuid, int uid) {
        this.volumeUuid = uuid;
        this.uid = uid;
    }

    /** {@hide} */
    public StorageStats(Parcel in) {
        this.volumeUuid = in.readString();
        this.uid = in.readInt();
        this.codeBytes = in.readLong();
        this.dataBytes = in.readLong();
        this.cacheQuotaBytes = in.readLong();
        this.cacheBytes = in.readLong();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(volumeUuid);
        dest.writeInt(uid);
        dest.writeLong(codeBytes);
        dest.writeLong(dataBytes);
        dest.writeLong(cacheQuotaBytes);
        dest.writeLong(cacheBytes);
    }

    public static final Creator<StorageStats> CREATOR = new Creator<StorageStats>() {
        @Override
        public StorageStats createFromParcel(Parcel in) {
            return new StorageStats(in);
        }

        @Override
        public StorageStats[] newArray(int size) {
            return new StorageStats[size];
        }
    };
}
