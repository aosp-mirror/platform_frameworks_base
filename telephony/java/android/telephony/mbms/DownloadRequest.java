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
import android.util.Base64;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * A Parcelable class describing a pending Cell-Broadcast download request
 * @hide
 */
public class DownloadRequest implements Parcelable {
    // Version code used to keep token calculation consistent.
    private static final int CURRENT_VERSION = 1;

    /** @hide */
    public static class Builder {
        private int id;
        private FileServiceInfo serviceInfo;
        private Uri source;
        private Uri dest;
        private int subscriptionId;
        private String appIntent;
        private int version = CURRENT_VERSION;

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

        public Builder setSubscriptionId(int sub) {
            this.subscriptionId = sub;
            return this;
        }

        public Builder setAppIntent(Intent intent) {
            this.appIntent = intent.toUri(0);
            return this;
        }

        public Builder setVersion(int version) {
            this.version = version;
            return this;
        }

        public DownloadRequest build() {
            return new DownloadRequest(id, serviceInfo, source, dest,
                    subscriptionId, appIntent, version);
        }
    }

    private final int downloadId;
    private final FileServiceInfo fileServiceInfo;
    private final Uri sourceUri;
    private final Uri destinationUri;
    private final int subscriptionId;
    private final String serializedResultIntentForApp;
    private final int version;

    private DownloadRequest(int id, FileServiceInfo serviceInfo,
            Uri source, Uri dest,
            int sub, String appIntent, int version) {
        downloadId = id;
        fileServiceInfo = serviceInfo;
        sourceUri = source;
        destinationUri = dest;
        subscriptionId = sub;
        serializedResultIntentForApp = appIntent;
        this.version = version;
    }

    public static DownloadRequest copy(DownloadRequest other) {
        return new DownloadRequest(other);
    }

    private DownloadRequest(DownloadRequest dr) {
        downloadId = dr.downloadId;
        fileServiceInfo = dr.fileServiceInfo;
        sourceUri = dr.sourceUri;
        destinationUri = dr.destinationUri;
        subscriptionId = dr.subscriptionId;
        serializedResultIntentForApp = dr.serializedResultIntentForApp;
        version = dr.version;
    }

    private DownloadRequest(Parcel in) {
        downloadId = in.readInt();
        fileServiceInfo = in.readParcelable(getClass().getClassLoader());
        sourceUri = in.readParcelable(getClass().getClassLoader());
        destinationUri = in.readParcelable(getClass().getClassLoader());
        subscriptionId = in.readInt();
        serializedResultIntentForApp = in.readString();
        version = in.readInt();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(downloadId);
        out.writeParcelable(fileServiceInfo, flags);
        out.writeParcelable(sourceUri, flags);
        out.writeParcelable(destinationUri, flags);
        out.writeInt(subscriptionId);
        out.writeString(serializedResultIntentForApp);
        out.writeInt(version);
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

    public int getSubscriptionId() {
        return subscriptionId;
    }

    public Intent getIntentForApp() {
        try {
            return Intent.parseUri(serializedResultIntentForApp, 0);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public int getVersion() {
        return version;
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

    /**
     * @hide
     */
    public boolean isMultipartDownload() {
        // TODO: figure out what qualifies a request as a multipart download request.
        return getSourceUri().getLastPathSegment() != null &&
                getSourceUri().getLastPathSegment().contains("*");
    }

    /**
     * Retrieves the hash string that should be used as the filename when storing a token for
     * this DownloadRequest.
     * @hide
     */
    public String getHash() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Could not get sha256 hash object");
        }
        if (version >= 1) {
            // Hash the source URI, destination URI, and the app intent
            digest.update(sourceUri.toString().getBytes(StandardCharsets.UTF_8));
            digest.update(destinationUri.toString().getBytes(StandardCharsets.UTF_8));
            digest.update(serializedResultIntentForApp.getBytes(StandardCharsets.UTF_8));
        }
        // Add updates for future versions here
        return Base64.encodeToString(digest.digest(), Base64.URL_SAFE | Base64.NO_WRAP);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) {
            return false;
        }
        if (!(o instanceof DownloadRequest)) {
            return false;
        }
        DownloadRequest request = (DownloadRequest) o;
        return downloadId == request.downloadId &&
                subscriptionId == request.subscriptionId &&
                version == request.version &&
                Objects.equals(fileServiceInfo, request.fileServiceInfo) &&
                Objects.equals(sourceUri, request.sourceUri) &&
                Objects.equals(destinationUri, request.destinationUri) &&
                Objects.equals(serializedResultIntentForApp, request.serializedResultIntentForApp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(downloadId, fileServiceInfo, sourceUri, destinationUri,
                subscriptionId, serializedResultIntentForApp, version);
    }
}
