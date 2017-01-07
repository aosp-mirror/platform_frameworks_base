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

import android.os.Parcel;
import android.os.Parcelable;

import java.io.File;

/**
 * Storage summary for a single storage volume.
 * <p>
 * When presenting this summary to users, the
 * <p>
 * Details for specific UIDs are available through {@link StorageStats}.
 *
 * @see StorageStatsManager
 */
public final class StorageSummary implements Parcelable {
    /** {@hide} */ public final String volumeUuid;

    /** {@hide} */ public long totalBytes;
    /** {@hide} */ public long freeBytes;

    /** {@hide} */ public long sharedTotalBytes;
    /** {@hide} */ public long sharedAudioBytes;
    /** {@hide} */ public long sharedVideoBytes;
    /** {@hide} */ public long sharedImagesBytes;

    /**
     * Return the UUID of the storage volume that these statistics refer to. The
     * value {@code null} indicates the default internal storage.
     */
    public String getVolumeUuid() {
        return volumeUuid;
    }

    /**
     * Return the total size of the storage volume.
     * <p>
     * To reduce end user confusion, this value is the total storage size
     * advertised in a retail environment, which is typically larger than the
     * actual writable partition total size.
     */
    public long getTotalBytes() {
        return totalBytes;
    }

    /**
     * Return the free space on this storage volume.
     * <p>
     * The free space is equivalent to {@link File#getFreeSpace()} plus the size
     * of any cached data that can be automatically deleted by the system as
     * additional space is needed.
     */
    public long getFreeBytes() {
        return freeBytes;
    }

    /**
     * Return the total bytes used by all files in the shared storage hosted on
     * this volume.
     *
     * @return total bytes, or {@code -1} if no shared storage is hosted on this
     *         volume.
     */
    public long getSharedTotalBytes() {
        return sharedTotalBytes;
    }

    /**
     * Return the total bytes used by audio files in the shared storage hosted
     * on this volume.
     *
     * @return total bytes, or {@code -1} if no shared storage is hosted on this
     *         volume.
     */
    public long getSharedAudioBytes() {
        return sharedAudioBytes;
    }

    /**
     * Return the total bytes used by video files in the shared storage hosted
     * on this volume.
     *
     * @return total bytes, or {@code -1} if no shared storage is hosted on this
     *         volume.
     */
    public long getSharedVideoBytes() {
        return sharedVideoBytes;
    }

    /**
     * Return the total bytes used by image files in the shared storage hosted
     * on this volume.
     *
     * @return total bytes, or {@code -1} if no shared storage is hosted on this
     *         volume.
     */
    public long getSharedImagesBytes() {
        return sharedImagesBytes;
    }

    /** {@hide} */
    public StorageSummary(String uuid) {
        this.volumeUuid = uuid;
    }

    /** {@hide} */
    public StorageSummary(Parcel in) {
        this.volumeUuid = in.readString();
        this.totalBytes = in.readLong();
        this.freeBytes = in.readLong();
        this.sharedTotalBytes = in.readLong();
        this.sharedAudioBytes = in.readLong();
        this.sharedVideoBytes = in.readLong();
        this.sharedImagesBytes = in.readLong();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(volumeUuid);
        dest.writeLong(totalBytes);
        dest.writeLong(freeBytes);
        dest.writeLong(sharedTotalBytes);
        dest.writeLong(sharedAudioBytes);
        dest.writeLong(sharedVideoBytes);
        dest.writeLong(sharedImagesBytes);
    }

    public static final Creator<StorageSummary> CREATOR = new Creator<StorageSummary>() {
        @Override
        public StorageSummary createFromParcel(Parcel in) {
            return new StorageSummary(in);
        }

        @Override
        public StorageSummary[] newArray(int size) {
            return new StorageSummary[size];
        }
    };
}
