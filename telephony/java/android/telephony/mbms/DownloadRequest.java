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

import android.annotation.NonNull;
import android.annotation.SystemApi;
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
 * Describes a request to download files over cell-broadcast. Instances of this class should be
 * created by the app when requesting a download, and instances of this class will be passed back
 * to the app when the middleware updates the status of the download.
 * @hide
 */
public final class DownloadRequest implements Parcelable {
    // Version code used to keep token calculation consistent.
    private static final int CURRENT_VERSION = 1;
    private static final String LOG_TAG = "MbmsDownloadRequest";

    /** @hide */
    public static final int MAX_APP_INTENT_SIZE = 50000;

    /** @hide */
    public static final int MAX_DESTINATION_URI_SIZE = 50000;

    /** @hide */
    private static class OpaqueDataContainer implements Serializable {
        private final String appIntent;
        private final int version;

        public OpaqueDataContainer(String appIntent, int version) {
            this.appIntent = appIntent;
            this.version = version;
        }
    }

    public static class Builder {
        private String fileServiceId;
        private Uri source;
        private int subscriptionId;
        private String appIntent;
        private int version = CURRENT_VERSION;


        /**
         * Builds a new DownloadRequest.
         * @param sourceUri the source URI for the DownloadRequest to be built. This URI should
         *     never be null.
         */
        public Builder(@NonNull Uri sourceUri) {
            if (sourceUri == null) {
                throw new IllegalArgumentException("Source URI must be non-null.");
            }
            source = sourceUri;
        }

        /**
         * Sets the service from which the download request to be built will download from.
         * @param serviceInfo
         * @return
         */
        public Builder setServiceInfo(FileServiceInfo serviceInfo) {
            fileServiceId = serviceInfo.getServiceId();
            return this;
        }

        /**
         * Set the service ID for the download request. For use by the middleware only.
         * @hide
         */
        //@SystemApi
        public Builder setServiceId(String serviceId) {
            fileServiceId = serviceId;
            return this;
        }

        /**
         * Set the subscription ID on which the file(s) should be downloaded.
         * @param subscriptionId
         */
        public Builder setSubscriptionId(int subscriptionId) {
            this.subscriptionId = subscriptionId;
            return this;
        }

        /**
         * Set the {@link Intent} that should be sent when the download completes or fails. This
         * should be an intent with a explicit {@link android.content.ComponentName} targeted to a
         * {@link android.content.BroadcastReceiver} in the app's package.
         *
         * The middleware should not use this method.
         * @param intent
         */
        public Builder setAppIntent(Intent intent) {
            this.appIntent = intent.toUri(0);
            if (this.appIntent.length() > MAX_APP_INTENT_SIZE) {
                throw new IllegalArgumentException("App intent must not exceed length " +
                        MAX_APP_INTENT_SIZE);
            }
            return this;
        }

        /**
         * For use by the middleware to set the byte array of opaque data. The opaque data
         * includes information about the download request that is used by the client app and the
         * manager code, but is irrelevant to the middleware.
         * @param data A byte array, the contents of which should have been originally obtained
         *             from {@link DownloadRequest#getOpaqueData()}.
         * @hide
         */
        //@SystemApi
        public Builder setOpaqueData(byte[] data) {
            try {
                ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(data));
                OpaqueDataContainer dataContainer = (OpaqueDataContainer) stream.readObject();
                version = dataContainer.version;
                appIntent = dataContainer.appIntent;
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
            return new DownloadRequest(fileServiceId, source, subscriptionId, appIntent, version);
        }
    }

    private final String fileServiceId;
    private final Uri sourceUri;
    private final int subscriptionId;
    private final String serializedResultIntentForApp;
    private final int version;

    private DownloadRequest(String fileServiceId,
            Uri source, int sub,
            String appIntent, int version) {
        this.fileServiceId = fileServiceId;
        sourceUri = source;
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
        subscriptionId = dr.subscriptionId;
        serializedResultIntentForApp = dr.serializedResultIntentForApp;
        version = dr.version;
    }

    private DownloadRequest(Parcel in) {
        fileServiceId = in.readString();
        sourceUri = in.readParcelable(getClass().getClassLoader());
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
        out.writeInt(subscriptionId);
        out.writeString(serializedResultIntentForApp);
        out.writeInt(version);
    }

    /**
     * @return The ID of the file service to download from.
     */
    public String getFileServiceId() {
        return fileServiceId;
    }

    /**
     * @return The source URI to download from
     */
    public Uri getSourceUri() {
        return sourceUri;
    }

    /**
     * @return The subscription ID on which to perform MBMS operations.
     */
    public int getSubscriptionId() {
        return subscriptionId;
    }

    /**
     * For internal use -- returns the intent to send to the app after download completion or
     * failure.
     * @hide
     */
    public Intent getIntentForApp() {
        try {
            return Intent.parseUri(serializedResultIntentForApp, 0);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * For use by the middleware only. The byte array returned from this method should be
     * persisted and sent back to the app upon download completion or failure by passing it into
     * {@link Builder#setOpaqueData(byte[])}.
     * @return A byte array of opaque data to persist.
     * @hide
     */
    //@SystemApi
    public byte[] getOpaqueData() {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream stream = new ObjectOutputStream(byteArrayOutputStream);
            OpaqueDataContainer container = new OpaqueDataContainer(
                    serializedResultIntentForApp, version);
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
     * Maximum permissible length for the app's destination path, when serialized via
     * {@link Uri#toString()}.
     */
    public static int getMaxAppIntentSize() {
        return MAX_APP_INTENT_SIZE;
    }

    /**
     * Maximum permissible length for the app's download-completion intent, when serialized via
     * {@link Intent#toUri(int)}.
     */
    public static int getMaxDestinationUriSize() {
        return MAX_DESTINATION_URI_SIZE;
    }

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
            // Hash the source URI and the app intent
            digest.update(sourceUri.toString().getBytes(StandardCharsets.UTF_8));
            if (serializedResultIntentForApp != null) {
                digest.update(serializedResultIntentForApp.getBytes(StandardCharsets.UTF_8));
            }
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
                Objects.equals(serializedResultIntentForApp, request.serializedResultIntentForApp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileServiceId, sourceUri,
                subscriptionId, serializedResultIntentForApp, version);
    }
}
