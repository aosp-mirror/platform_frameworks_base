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
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
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
    private static final String LOG_TAG = "MbmsDownloadRequest";

    /**
     * Maximum permissible length for the app's download-completion intent, when serialized via
     * {@link Intent#toUri(int)}.
     */
    public static final int MAX_APP_INTENT_SIZE = 50000;

    /**
     * Maximum permissible length for the app's destination path, when serialized via
     * {@link Uri#toString()}.
     */
    public static final int MAX_DESTINATION_URI_SIZE = 50000;

    /** @hide */
    private static class OpaqueDataContainer implements Serializable {
        private final String destinationUri;
        private final String appIntent;
        private final int version;

        public OpaqueDataContainer(String destinationUri, String appIntent, int version) {
            this.destinationUri = destinationUri;
            this.appIntent = appIntent;
            this.version = version;
        }
    }

    public static class Builder {
        private String fileServiceId;
        private Uri source;
        private Uri dest;
        private int subscriptionId;
        private String appIntent;
        private int version = CURRENT_VERSION;

        public Builder setServiceInfo(FileServiceInfo serviceInfo) {
            fileServiceId = serviceInfo.getServiceId();
            return this;
        }

        /**
         * @hide
         * TODO: systemapi
         */
        public Builder setServiceId(String serviceId) {
            fileServiceId = serviceId;
            return this;
        }

        public Builder setSource(Uri source) {
            this.source = source;
            return this;
        }

        public Builder setDest(Uri dest) {
            if (dest.toString().length() > MAX_DESTINATION_URI_SIZE) {
                throw new IllegalArgumentException("Destination uri must not exceed length " +
                        MAX_DESTINATION_URI_SIZE);
            }
            this.dest = dest;
            return this;
        }

        public Builder setSubscriptionId(int sub) {
            this.subscriptionId = sub;
            return this;
        }

        public Builder setAppIntent(Intent intent) {
            this.appIntent = intent.toUri(0);
            if (this.appIntent.length() > MAX_APP_INTENT_SIZE) {
                throw new IllegalArgumentException("App intent must not exceed length " +
                        MAX_APP_INTENT_SIZE);
            }
            return this;
        }

        /**
         * For use by middleware only
         * TODO: systemapi
         * @hide
         */
        public Builder setOpaqueData(byte[] data) {
            try {
                ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(data));
                OpaqueDataContainer dataContainer = (OpaqueDataContainer) stream.readObject();
                version = dataContainer.version;
                appIntent = dataContainer.appIntent;
                dest = Uri.parse(dataContainer.destinationUri);
            } catch (IOException e) {
                // Really should never happen
                Log.e(LOG_TAG, "Got IOException trying to parse opaque data");
                throw new IllegalArgumentException(e);
            } catch (ClassNotFoundException e) {
                Log.e(LOG_TAG, "Got ClassNotFoundException trying to parse opaque data");
                throw new IllegalArgumentException(e);
            }
            return this;
        }

        public DownloadRequest build() {
            return new DownloadRequest(fileServiceId, source, dest,
                    subscriptionId, appIntent, version);
        }
    }

    private final String fileServiceId;
    private final Uri sourceUri;
    private final Uri destinationUri;
    private final int subscriptionId;
    private final String serializedResultIntentForApp;
    private final int version;

    private DownloadRequest(String fileServiceId,
            Uri source, Uri dest,
            int sub, String appIntent, int version) {
        this.fileServiceId = fileServiceId;
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
        fileServiceId = dr.fileServiceId;
        sourceUri = dr.sourceUri;
        destinationUri = dr.destinationUri;
        subscriptionId = dr.subscriptionId;
        serializedResultIntentForApp = dr.serializedResultIntentForApp;
        version = dr.version;
    }

    private DownloadRequest(Parcel in) {
        fileServiceId = in.readString();
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
        out.writeString(fileServiceId);
        out.writeParcelable(sourceUri, flags);
        out.writeParcelable(destinationUri, flags);
        out.writeInt(subscriptionId);
        out.writeString(serializedResultIntentForApp);
        out.writeInt(version);
    }

    public String getFileServiceId() {
        return fileServiceId;
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

    /**
     * @hide
     * TODO: systemapi
     */
    public byte[] getOpaqueData() {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream stream = new ObjectOutputStream(byteArrayOutputStream);
            OpaqueDataContainer container = new OpaqueDataContainer(
                    destinationUri.toString(), serializedResultIntentForApp, version);
            stream.writeObject(container);
            stream.flush();
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            // Really should never happen
            Log.e(LOG_TAG, "Got IOException trying to serialize opaque data");
            return null;
        }
    }

    /** @hide */
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
        return subscriptionId == request.subscriptionId &&
                version == request.version &&
                Objects.equals(fileServiceId, request.fileServiceId) &&
                Objects.equals(sourceUri, request.sourceUri) &&
                Objects.equals(destinationUri, request.destinationUri) &&
                Objects.equals(serializedResultIntentForApp, request.serializedResultIntentForApp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileServiceId, sourceUri, destinationUri,
                subscriptionId, serializedResultIntentForApp, version);
    }
}
