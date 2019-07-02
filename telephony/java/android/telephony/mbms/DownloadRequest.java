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
import android.annotation.TestApi;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Externalizable;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Describes a request to download files over cell-broadcast. Instances of this class should be
 * created by the app when requesting a download, and instances of this class will be passed back
 * to the app when the middleware updates the status of the download.
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
    private static class SerializationDataContainer implements Externalizable {
        private String fileServiceId;
        private Uri source;
        private Uri destination;
        private int subscriptionId;
        private String appIntent;
        private int version;

        public SerializationDataContainer() {}

        SerializationDataContainer(DownloadRequest request) {
            fileServiceId = request.fileServiceId;
            source = request.sourceUri;
            destination = request.destinationUri;
            subscriptionId = request.subscriptionId;
            appIntent = request.serializedResultIntentForApp;
            version = request.version;
        }

        @Override
        public void writeExternal(ObjectOutput objectOutput) throws IOException {
            objectOutput.write(version);
            objectOutput.writeUTF(fileServiceId);
            objectOutput.writeUTF(source.toString());
            objectOutput.writeUTF(destination.toString());
            objectOutput.write(subscriptionId);
            objectOutput.writeUTF(appIntent);
        }

        @Override
        public void readExternal(ObjectInput objectInput) throws IOException {
            version = objectInput.read();
            fileServiceId = objectInput.readUTF();
            source = Uri.parse(objectInput.readUTF());
            destination = Uri.parse(objectInput.readUTF());
            subscriptionId = objectInput.read();
            appIntent = objectInput.readUTF();
            // Do version checks here -- future versions may have other fields.
        }
    }

    public static class Builder {
        private String fileServiceId;
        private Uri source;
        private Uri destination;
        private int subscriptionId;
        private String appIntent;
        private int version = CURRENT_VERSION;

        /**
         * Constructs a {@link Builder} from a {@link DownloadRequest}
         * @param other The {@link DownloadRequest} from which the data for the {@link Builder}
         *              should come.
         * @return An instance of {@link Builder} pre-populated with data from the provided
         *         {@link DownloadRequest}.
         */
        public static Builder fromDownloadRequest(DownloadRequest other) {
            Builder result = new Builder(other.sourceUri, other.destinationUri)
                    .setServiceId(other.fileServiceId)
                    .setSubscriptionId(other.subscriptionId);
            result.appIntent = other.serializedResultIntentForApp;
            // Version of the result is going to be the current version -- as this class gets
            // updated, new fields will be set to default values in here.
            return result;
        }

        /**
         * This method constructs a new instance of {@link Builder} based on the serialized data
         * passed in.
         * @param data A byte array, the contents of which should have been originally obtained
         *             from {@link DownloadRequest#toByteArray()}.
         */
        public static Builder fromSerializedRequest(byte[] data) {
            Builder builder;
            try {
                ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(data));
                SerializationDataContainer dataContainer =
                        (SerializationDataContainer) stream.readObject();
                builder = new Builder(dataContainer.source, dataContainer.destination);
                builder.version = dataContainer.version;
                builder.appIntent = dataContainer.appIntent;
                builder.fileServiceId = dataContainer.fileServiceId;
                builder.subscriptionId = dataContainer.subscriptionId;
            } catch (IOException e) {
                // Really should never happen
                Log.e(LOG_TAG, "Got IOException trying to parse opaque data");
                throw new IllegalArgumentException(e);
            } catch (ClassNotFoundException e) {
                Log.e(LOG_TAG, "Got ClassNotFoundException trying to parse opaque data");
                throw new IllegalArgumentException(e);
            }
            return builder;
        }

        /**
         * Builds a new DownloadRequest.
         * @param sourceUri the source URI for the DownloadRequest to be built. This URI should
         *     never be null.
         * @param destinationUri The final location for the file(s) that are to be downloaded. It
         *     must be on the same filesystem as the temp file directory set via
         *     {@link android.telephony.MbmsDownloadSession#setTempFileRootDirectory(File)}.
         *     The provided path must be a directory that exists. An
         *     {@link IllegalArgumentException} will be thrown otherwise.
         */
        public Builder(@NonNull Uri sourceUri, @NonNull Uri destinationUri) {
            if (sourceUri == null || destinationUri == null) {
                throw new IllegalArgumentException("Source and destination URIs must be non-null.");
            }
            source = sourceUri;
            destination = destinationUri;
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
        @SystemApi
        @TestApi
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

        public DownloadRequest build() {
            return new DownloadRequest(fileServiceId, source, destination,
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
            Uri source, Uri destination, int sub,
            String appIntent, int version) {
        this.fileServiceId = fileServiceId;
        sourceUri = source;
        subscriptionId = sub;
        destinationUri = destination;
        serializedResultIntentForApp = appIntent;
        this.version = version;
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
     * @return The destination {@link Uri} of the downloaded file.
     */
    public Uri getDestinationUri() {
        return destinationUri;
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
     * This method returns a byte array that may be persisted to disk and restored to a
     * {@link DownloadRequest}. The instance of {@link DownloadRequest} persisted by this method
     * may be recovered via {@link Builder#fromSerializedRequest(byte[])}.
     * @return A byte array of data to persist.
     */
    public byte[] toByteArray() {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream stream = new ObjectOutputStream(byteArrayOutputStream);
            SerializationDataContainer container = new SerializationDataContainer(this);
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

    public static final @android.annotation.NonNull Parcelable.Creator<DownloadRequest> CREATOR =
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
            // Hash the source, destination, and the app intent
            digest.update(sourceUri.toString().getBytes(StandardCharsets.UTF_8));
            digest.update(destinationUri.toString().getBytes(StandardCharsets.UTF_8));
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
                Objects.equals(destinationUri, request.destinationUri) &&
                Objects.equals(serializedResultIntentForApp, request.serializedResultIntentForApp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileServiceId, sourceUri, destinationUri,
                subscriptionId, serializedResultIntentForApp, version);
    }
}
