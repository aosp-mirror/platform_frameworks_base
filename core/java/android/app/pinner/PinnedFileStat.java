/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.pinner;

import static android.app.Flags.FLAG_PINNER_SERVICE_CLIENT_API;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
@TestApi
@FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
public final class PinnedFileStat implements Parcelable {
    private String filename;
    private long bytesPinned;
    private String groupName;

    /**
     * @hide
     */
    @TestApi
    @FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
    public long getBytesPinned() {
        return bytesPinned;
    }

    /**
     * @hide
     */
    @TestApi
    @FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
    public @NonNull String getFilename() {
        return filename;
    }

    /**
     * @hide
     */
    @TestApi
    @FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
    public @NonNull String getGroupName() {
        return groupName;
    }

    /**
     * @hide
     */
    @TestApi
    @FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
    public PinnedFileStat(@NonNull String filename, long bytesPinned, @NonNull String groupName) {
        this.filename = filename;
        this.bytesPinned = bytesPinned;
        this.groupName = groupName;
    }

    private PinnedFileStat(Parcel source) {
        readFromParcel(source);
    }

    /**
     * @hide
     */
    @TestApi
    @FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(filename);
        dest.writeLong(bytesPinned);
        dest.writeString8(groupName);
    }

    private void readFromParcel(@NonNull Parcel source) {
        filename = source.readString8();
        bytesPinned = source.readLong();
        groupName = source.readString8();
    }

    /**
     * @hide
     */
    @TestApi
    @FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    @TestApi
    @FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
    public static final @NonNull Creator<PinnedFileStat> CREATOR = new Creator<>() {
        /**
         * @hide
         */
        @TestApi
        @FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
        @Override
        public PinnedFileStat createFromParcel(Parcel source) {
            return new PinnedFileStat(source);
        }

        /**
         * @hide
         */
        @TestApi
        @FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
        @Override
        public PinnedFileStat[] newArray(int size) {
            return new PinnedFileStat[size];
        }
    };
}
