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

import android.content.Intent;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.IllegalStateException;
import java.net.URISyntaxException;

/**
 * A Parcelable class describing a pending Cell-Broadcast download request
 * @hide
 */
public class DownloadRequest implements Parcelable {
    /** @hide */
    public static class Builder {
        private int id;
        private FileServiceInfo serviceInfo;
        private Uri source;
        private Uri dest;
        private int sub;
        private String appIntent;
        private String appName;  // not the Android app Name, the embms app Name

        public Builder setId(int id) {
            this.id = id;
            return this;
        }

        public Builder setServiceInfo(FileServiceInfo serviceInfo) {
            this.serviceInfo = serviceInfo;
            return this;
        }

        public Builder setSource(Uri source) {
            this.source = source;
            return this;
        }

        public Builder setDest(Uri dest) {
            this.dest = dest;
            return this;
        }

        public Builder setSub(int sub) {
            this.sub = sub;
            return this;
        }

        public Builder setAppIntent(Intent intent) {
            this.appIntent = intent.toUri(0);
            return this;
        }

        public DownloadRequest build() {
            return new DownloadRequest(id, serviceInfo, source, dest, sub, appIntent, appName);
        }
    }

    private final int downloadId;
    private final FileServiceInfo fileServiceInfo;
    private final Uri sourceUri;
    private final Uri destinationUri;
    private final int subId;
    private final String serializedResultIntentForApp;
    private String appName; // not the Android app Name, the embms app name

    private DownloadRequest(int id, FileServiceInfo serviceInfo,
            Uri source, Uri dest,
            int sub, String appIntent, String name) {
        downloadId = id;
        fileServiceInfo = serviceInfo;
        sourceUri = source;
        destinationUri = dest;
        subId = sub;
        serializedResultIntentForApp = appIntent;
        appName = name;
    }

    public static DownloadRequest copy(DownloadRequest other) {
        return new DownloadRequest(other);
    }

    private DownloadRequest(DownloadRequest dr) {
        downloadId = dr.downloadId;
        fileServiceInfo = dr.fileServiceInfo;
        sourceUri = dr.sourceUri;
        destinationUri = dr.destinationUri;
        subId = dr.subId;
        serializedResultIntentForApp = dr.serializedResultIntentForApp;
        appName = dr.appName;
    }

    private DownloadRequest(Parcel in) {
        downloadId = in.readInt();
        fileServiceInfo = in.readParcelable(getClass().getClassLoader());
        sourceUri = in.readParcelable(getClass().getClassLoader());
        destinationUri = in.readParcelable(getClass().getClassLoader());
        subId = in.readInt();
        serializedResultIntentForApp = in.readString();
        appName = in.readString();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(downloadId);
        out.writeParcelable(fileServiceInfo, flags);
        out.writeParcelable(sourceUri, flags);
        out.writeParcelable(destinationUri, flags);
        out.writeInt(subId);
        out.writeString(serializedResultIntentForApp);
        out.writeString(appName);
    }

    public int getDownloadId() {
        return downloadId;
    }

    public FileServiceInfo getFileServiceInfo() {
        return fileServiceInfo;
    }

    public Uri getSourceUri() {
        return sourceUri;
    }

    public Uri getDestinationUri() {
        return destinationUri;
    }

    public int getSubId() {
        return subId;
    }

    public Intent getIntentForApp() {
        try {
            return Intent.parseUri(serializedResultIntentForApp, 0);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** @hide */
    public synchronized void setAppName(String newAppName) {
        if (appName != null) {
            throw new IllegalStateException("Attempting to reset appName");
        }
        appName = newAppName;
    }

    public String getAppName() {
        return appName;
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
