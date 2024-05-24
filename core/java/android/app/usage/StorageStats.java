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

import android.annotation.BytesLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Storage statistics for a UID, package, or {@link UserHandle} on a single
 * storage volume.
 *
 * @see StorageStatsManager
 */
public final class StorageStats implements Parcelable {
    /** @hide */ public long codeBytes;
    /** @hide */ public long dataBytes;
    /** @hide */ public long cacheBytes;
    /** @hide */ public long apkBytes;
    /** @hide */ public long libBytes;
    /** @hide */ public long dmBytes;
    /** @hide */ public long dexoptBytes;
    /** @hide */ public long curProfBytes;
    /** @hide */ public long refProfBytes;
    /** @hide */ public long externalCacheBytes;

    /**
     * Represents all nonstale dexopt and runtime artifacts of application.
     * This includes AOT-compiled code and other data that can speed up app execution.
     * For more detailed information, read the
     * <a href="https://source.android.com/docs/core/runtime/jit-compiler#flow">JIT compiler</a>
     * guide.
     *
     * Dexopt artifacts become stale when one of their dependencies
     * has changed. They may be cleaned up or replaced by ART Services at any time.
     *
     * For a preload app, this type includes dexopt artifacts on readonly partitions
     * if they are up-to-date.
     *
     * Can be used as an input to {@link #getAppBytesByDataType(int)}
     * to get the sum of sizes for files of this type. The sum might include the size of data
     * that is part of appBytes, dataBytes or cacheBytes.
     */
    @FlaggedApi(Flags.FLAG_GET_APP_BYTES_BY_DATA_TYPE_API)
    public static final int APP_DATA_TYPE_FILE_TYPE_DEXOPT_ARTIFACT = 0;

    /**
     * Represents reference profile of application.
     *
     * Reference profiles are the ones used during the last profile-guided dexopt.
     * If the last dexopt wasn't profile-guided, then these profiles were not used.
     *
     * Can be used as an input to {@link #getAppBytesByDataType(int)}
     * to get the size of files of this type.
     */
    @FlaggedApi(Flags.FLAG_GET_APP_BYTES_BY_DATA_TYPE_API)
    public static final int APP_DATA_TYPE_FILE_TYPE_REFERENCE_PROFILE = 1;

    /**
     * Represents current profile of application.
     *
     * Current profiles may or may not be used during the next profile-guided dexopt.
     *
     * Can be used as an input to {@link #getAppBytesByDataType(int)}
     * to get the size of files of this type. This size fluctuates regularly,
     * it goes up when the user uses more and more classes/methods and comes down when
     * a deamon merges this into the ref profile and does profile-guided dexopt.
     */
    @FlaggedApi(Flags.FLAG_GET_APP_BYTES_BY_DATA_TYPE_API)
    public static final int APP_DATA_TYPE_FILE_TYPE_CURRENT_PROFILE = 2;

    /**
     * Represents all .apk files in application code path.
     * Can be used as an input to {@link #getAppBytesByDataType(int)}
     * to get the sum of sizes for files of this type.
     */
    @FlaggedApi(Flags.FLAG_GET_APP_BYTES_BY_DATA_TYPE_API)
    public static final int APP_DATA_TYPE_FILE_TYPE_APK = 3;

    /**
     * Represents all .dm files in application code path.
     * Can be used as an input to {@link #getAppBytesByDataType(int)}
     * to get the sum of sizes for files of this type.
     */
    @FlaggedApi(Flags.FLAG_GET_APP_BYTES_BY_DATA_TYPE_API)
    public static final int APP_DATA_TYPE_FILE_TYPE_DM = 4;

    /**
     * Represents lib/ in application code path.
     * Can be used as an input to {@link #getAppBytesByDataType(int)}
     * to get the size of lib/ directory.
     */
    @FlaggedApi(Flags.FLAG_GET_APP_BYTES_BY_DATA_TYPE_API)
    public static final int APP_DATA_TYPE_LIB = 5;

    /**
     * Keep in sync with the file types defined above.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_GET_APP_BYTES_BY_DATA_TYPE_API)
    @IntDef(flag = false, value = {
        APP_DATA_TYPE_FILE_TYPE_DEXOPT_ARTIFACT,
        APP_DATA_TYPE_FILE_TYPE_REFERENCE_PROFILE,
        APP_DATA_TYPE_FILE_TYPE_CURRENT_PROFILE,
        APP_DATA_TYPE_FILE_TYPE_APK,
        APP_DATA_TYPE_FILE_TYPE_DM,
        APP_DATA_TYPE_LIB,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AppDataType {}

    /**
     * Return the size of app. This includes {@code APK} files, optimized
     * compiler output, and unpacked native libraries.
     * <p>
     * If the primary external/shared storage is hosted on this storage device,
     * then this includes files stored under {@link Context#getObbDir()}.
     * <p>
     * Code is shared between all users on a multiuser device.
     */
    public @BytesLong long getAppBytes() {
        return codeBytes;
    }

    /**
     * Return the size of the specified data type. This includes files stored under
     * application code path.
     * <p>
     * If there is more than one package inside a uid, the return represents the aggregated
     * stats when query StorageStat for package or uid.
     * The data  is not collected and the return defaults to 0 when query StorageStats for user.
     *
     * <p>
     * Data is isolated for each user on a multiuser device.
     */
    @FlaggedApi(Flags.FLAG_GET_APP_BYTES_BY_DATA_TYPE_API)
    public long getAppBytesByDataType(@AppDataType int dataType) {
        switch (dataType) {
          case APP_DATA_TYPE_FILE_TYPE_DEXOPT_ARTIFACT: return dexoptBytes;
          case APP_DATA_TYPE_FILE_TYPE_REFERENCE_PROFILE: return refProfBytes;
          case APP_DATA_TYPE_FILE_TYPE_CURRENT_PROFILE: return curProfBytes;
          case APP_DATA_TYPE_FILE_TYPE_APK: return apkBytes;
          case APP_DATA_TYPE_LIB: return libBytes;
          case APP_DATA_TYPE_FILE_TYPE_DM: return dmBytes;
          default: return 0;
        }
    }

    /**
     * Return the size of all data. This includes files stored under
     * {@link Context#getDataDir()}, {@link Context#getCacheDir()},
     * {@link Context#getCodeCacheDir()}.
     * <p>
     * If the primary external/shared storage is hosted on this storage device,
     * then this includes files stored under
     * {@link Context#getExternalFilesDir(String)},
     * {@link Context#getExternalCacheDir()}, and
     * {@link Context#getExternalMediaDirs()}.
     * <p>
     * Data is isolated for each user on a multiuser device.
     */
    public @BytesLong long getDataBytes() {
        return dataBytes;
    }

    /**
     * Return the size of all cached data. This includes files stored under
     * {@link Context#getCacheDir()} and {@link Context#getCodeCacheDir()}.
     * <p>
     * If the primary external/shared storage is hosted on this storage device,
     * then this includes files stored under
     * {@link Context#getExternalCacheDir()}.
     * <p>
     * Cached data is isolated for each user on a multiuser device.
     */
    public @BytesLong long getCacheBytes() {
        return cacheBytes;
    }

    /**
     * Return the size of all cached data in the primary external/shared storage.
     * This includes files stored under
     * {@link Context#getExternalCacheDir()}.
     * <p>
     * Cached data is isolated for each user on a multiuser device.
     */
    public @BytesLong long getExternalCacheBytes() {
        return externalCacheBytes;
    }

    /** {@hide} */
    public StorageStats() {
    }

    /** {@hide} */
    public StorageStats(Parcel in) {
        this.codeBytes = in.readLong();
        this.dataBytes = in.readLong();
        this.cacheBytes = in.readLong();
        this.dexoptBytes = in.readLong();
        this.refProfBytes = in.readLong();
        this.curProfBytes = in.readLong();
        this.apkBytes = in.readLong();
        this.libBytes = in.readLong();
        this.dmBytes = in.readLong();
        this.externalCacheBytes = in.readLong();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(codeBytes);
        dest.writeLong(dataBytes);
        dest.writeLong(cacheBytes);
        dest.writeLong(dexoptBytes);
        dest.writeLong(refProfBytes);
        dest.writeLong(curProfBytes);
        dest.writeLong(apkBytes);
        dest.writeLong(libBytes);
        dest.writeLong(dmBytes);
        dest.writeLong(externalCacheBytes);
    }

    public static final @android.annotation.NonNull Creator<StorageStats> CREATOR = new Creator<StorageStats>() {
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
