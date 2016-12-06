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

import android.app.PendingIntent;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A Parcelable class describing a pending Cell-Broadcast download request
 * @hide
 */
public class DownloadRequest implements Parcelable {
    public DownloadRequest(int id, FileServiceInfo serviceInfo, Uri source, Uri dest,
            PendingIntent resultPI, int sub) {
        downloadId = id;
        fileServiceInfo = serviceInfo;
        sourceUri = source;
        destinationUri = dest;
        subId = sub;
    }

    /** @hide */
    public DownloadRequest(DownloadRequest dr, PendingIntent fdRequestPI, PendingIntent cleanupPI) {
        downloadId = dr.downloadId;
        fileServiceInfo = dr.fileServiceInfo;
        sourceUri = dr.sourceUri;
        destinationUri = dr.destinationUri;
        subId = dr.subId;
        /*
         * resultPI = new PI
         * fileDescriptorRequstPI = fdRequestPI;
         * this.cleanupPI = cleanupPI;
         */
    }

    public final int downloadId;
    public final FileServiceInfo fileServiceInfo;
    public final Uri sourceUri;
    public final Uri destinationUri;
    public final int subId;

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(downloadId);
        out.writeParcelable(fileServiceInfo, flags);
        out.writeParcelable(sourceUri, flags);
        out.writeParcelable(destinationUri, flags);
        out.writeInt(subId);
    }

    private DownloadRequest(Parcel in) {
        downloadId = in.readInt();
        fileServiceInfo = in.readParcelable(null);
        sourceUri = in.readParcelable(null);
        destinationUri = in.readParcelable(null);
        subId = in.readInt();
    }

    public static final Parcelable.Creator<DownloadRequest> CREATOR =
            new Parcelable.Creator<DownloadRequest>() {
        public DownloadRequest createFromParcel(Parcel in) {
            return new DownloadRequest(in);
        }
        public DownloadRequest[] newArray(int size) {
            return new DownloadRequest[size];
        }
    };

}
