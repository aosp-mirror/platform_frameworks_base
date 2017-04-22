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

package android.telephony.mbms;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A Parcelable class describing the status of a Cell-Broadcast download request
 * @hide
 */
public class DownloadStatus implements Parcelable {
    // includes downloads and active repair work
    public final int activelyDownloading;

    // files scheduled for future broadcast
    public final int pendingDownloads;

    // files scheduled for future repairs
    public final int pendingRepairs;

    // is a future download window scheduled with unknown
    // number of files
    public final boolean windowPending;

    public DownloadStatus(int downloading, int downloads, int repairs, boolean window) {
        activelyDownloading = downloading;
        pendingDownloads = downloads;
        pendingRepairs = repairs;
        windowPending = window;
    }

    public static final Parcelable.Creator<DownloadStatus> CREATOR =
            new Parcelable.Creator<DownloadStatus>() {
        @Override
        public DownloadStatus createFromParcel(Parcel in) {
            return new DownloadStatus(in);
        }

        @Override
        public DownloadStatus[] newArray(int size) {
            return new DownloadStatus[size];
        }
    };

    DownloadStatus(Parcel in) {
        activelyDownloading = in.readInt();
        pendingDownloads = in.readInt();
        pendingRepairs = in.readInt();
        windowPending = (in.readInt() == 1);
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(activelyDownloading);
        dest.writeInt(pendingDownloads);
        dest.writeInt(pendingRepairs);
        dest.writeInt((windowPending ? 1 : 0));
    }

    public int describeContents() {
        return 0;
    }
}
