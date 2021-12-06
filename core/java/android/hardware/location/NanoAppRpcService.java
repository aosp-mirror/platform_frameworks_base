/*
 * Copyright 2021 The Android Open Source Project
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
package android.hardware.location;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * An RPC service published by a nanoapp.
 *
 * This class is meant to be informational and allows a nanoapp client to know if a given
 * nanoapp publishes a service which supports an RPC interface. The implementation of the RPC
 * interface is not specified by the Android APIs and is built by the platform implementation
 * over the message payloads transferred through the {@link ContextHubClient}.
 *
 * This class is instantiated as a part of {@link NanoAppState}, which is provided as a result
 * of {@link ContextHubManager.queryNanoApps(ContextHubInfo)}.
 *
 * See the chrePublishRpcServices() API for how this service is published by the nanoapp.
 *
 * @hide
 */
@SystemApi
public final class NanoAppRpcService implements Parcelable {
    private long mServiceId;

    private int mServiceVersion;

    /**
     * @param serviceId The unique ID of this service, see {#getId()}.
     * @param serviceVersion The software version of this service, see {#getVersion()}.
     */
    public NanoAppRpcService(long serviceId, int serviceVersion) {
        mServiceId = serviceId;
        mServiceVersion = serviceVersion;
    }

    /**
     * The unique 64-bit ID of an RPC service published by a nanoapp. Note that
     * the uniqueness is only required within the nanoapp's domain (i.e. the
     * combination of the nanoapp ID and service id must be unique).
     *
     * This ID must remain the same for the given nanoapp RPC service once
     * published on Android (i.e. must never change).
     *
     * @return The service ID.
     */
    public long getId() {
        return mServiceId;
    }

    /**
     * The software version of this service, which follows the sematic
     * versioning scheme (see semver.org). It follows the format
     * major.minor.patch, where major and minor versions take up one byte
     * each, and the patch version takes up the final 2 (lower) bytes.
     * I.e. the version is encoded as 0xMMmmpppp, where MM, mm, pppp are
     * the major, minor, patch versions, respectively.
     *
     * @return The service version.
     */
    public int getVersion() {
        return mServiceVersion;
    }

    /**
     * @return The service's major version.
     */
    private int getMajorVersion() {
        return (mServiceVersion & 0xFF000000) >>> 24;
    }

    /**
     * @return The service's minor version.
     */
    private int getMinorVersion() {
        return (mServiceVersion & 0x00FF0000) >>> 16;
    }

    /**
     * @return The service's patch version.
     */
    private int getPatchVersion() {
        return mServiceVersion & 0x0000FFFF;
    }

    private NanoAppRpcService(Parcel in) {
        mServiceId = in.readLong();
        mServiceVersion = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeLong(mServiceId);
        out.writeInt(mServiceVersion);
    }

    public static final @NonNull Creator<NanoAppRpcService> CREATOR =
            new Creator<NanoAppRpcService>() {
                @Override
                public NanoAppRpcService createFromParcel(Parcel in) {
                    return new NanoAppRpcService(in);
                }

                @Override
                public NanoAppRpcService[] newArray(int size) {
                    return new NanoAppRpcService[size];
                }
            };

    @NonNull
    @Override
    public String toString() {
        return "NanoAppRpcService[Id = " + Long.toHexString(mServiceId) + ", version = v"
                + getMajorVersion() + "." + getMinorVersion() + "." + getPatchVersion() + "]";
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (object == this) {
            return true;
        }

        boolean isEqual = false;
        if (object instanceof NanoAppRpcService) {
            NanoAppRpcService other = (NanoAppRpcService) object;
            isEqual = (other.getId() == mServiceId)
                    && (other.getVersion() == mServiceVersion);
        }

        return isEqual;
    }

    @Override
    public int hashCode() {
        return (int) getId();
    }
}
