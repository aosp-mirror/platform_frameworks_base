/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.apphibernation;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Stats for a hibernating package.
 * @hide
 */
@SystemApi
public final class HibernationStats implements Parcelable {
    private final long mDiskBytesSaved;

    /** @hide */
    public HibernationStats(long diskBytesSaved) {
        mDiskBytesSaved = diskBytesSaved;
    }

    private HibernationStats(@NonNull Parcel in) {
        mDiskBytesSaved = in.readLong();
    }

    /**
     * Get the disk storage saved from hibernation in bytes.
     */
    public long getDiskBytesSaved() {
        return mDiskBytesSaved;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mDiskBytesSaved);
    }

    public static final @NonNull Creator<HibernationStats> CREATOR =
            new Creator<HibernationStats>() {
        @Override
        public HibernationStats createFromParcel(Parcel in) {
            return new HibernationStats(in);
        }

        @Override
        public HibernationStats[] newArray(int size) {
            return new HibernationStats[size];
        }
    };
}
