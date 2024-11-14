/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.hardware.contexthub;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Parcelable representing an endpoint from ContextHub or VendorHub.
 *
 * <p>HubEndpointInfo contains information about an endpoint, including its name, tag and other
 * information. A HubEndpointInfo object can be used to accurately identify a specific endpoint.
 * Application can use this object to identify and describe an endpoint.
 *
 * <p>See: {@link android.hardware.location.ContextHubManager#findEndpoints} for how to retrieve
 * {@link HubEndpointInfo} for endpoints on a hub.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public final class HubEndpointInfo implements Parcelable {
    /**
     * A unique identifier for one endpoint. A unique identifier for one endpoint consists of two
     * parts: (1) a unique long number for a hub and (2) a long number for the endpoint, unique
     * within a hub. This class overrides equality methods and can be used to compare if two
     * endpoints are the same.
     */
    public static class HubEndpointIdentifier {
        private final long mEndpointId;
        private final long mHubId;

        /** @hide */
        public HubEndpointIdentifier(long hubId, long endpointId) {
            mEndpointId = endpointId;
            mHubId = hubId;
        }

        /** @hide */
        public HubEndpointIdentifier(android.hardware.contexthub.EndpointId halEndpointId) {
            mEndpointId = halEndpointId.id;
            mHubId = halEndpointId.hubId;
        }

        /** Get the endpoint portion of the identifier. */
        public long getEndpoint() {
            return mEndpointId;
        }

        /** Get the hub portion of the identifier. */
        public long getHub() {
            return mHubId;
        }

        /**
         * Create an invalid endpoint id, to represent endpoint that are not yet registered with the
         * HAL.
         *
         * @hide
         */
        public static HubEndpointIdentifier invalid() {
            return new HubEndpointIdentifier(
                    android.hardware.contexthub.HubInfo.HUB_ID_INVALID,
                    android.hardware.contexthub.EndpointId.ENDPOINT_ID_INVALID);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mEndpointId, mHubId);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof HubEndpointIdentifier other)) {
                return false;
            }
            if (other.mHubId != mHubId) {
                return false;
            }
            return other.mEndpointId == mEndpointId;
        }
    }

    private final HubEndpointIdentifier mId;
    private final String mName;
    @Nullable private final String mTag;

    // TODO(b/375487784): Add Service/version and other information to this object

    /** @hide */
    public HubEndpointInfo(android.hardware.contexthub.EndpointInfo endpointInfo) {
        mId = new HubEndpointIdentifier(endpointInfo.id.hubId, endpointInfo.id.id);
        mName = endpointInfo.name;
        mTag = endpointInfo.tag;
    }

    private HubEndpointInfo(Parcel in) {
        long hubId = in.readLong();
        long endpointId = in.readLong();
        mName = in.readString();
        mTag = in.readString();

        mId = new HubEndpointIdentifier(hubId, endpointId);
    }

    /** Parcel implementation details */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Parcel implementation details */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mId.getHub());
        dest.writeLong(mId.getEndpoint());
        dest.writeString(mName);
        dest.writeString(mTag);
    }

    /** Get a unique identifier for this endpoint. */
    @NonNull
    public HubEndpointIdentifier getIdentifier() {
        return mId;
    }

    /** Get the human-readable name of this endpoint (for debugging purposes). */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Get the tag that further identifies the submodule that created this endpoint. For example, a
     * single application could provide multiple endpoints. These endpoints will share the same
     * name, but will have different tags. This tag can be used to identify the submodule within the
     * application that provided the endpoint.
     */
    @Nullable
    public String getTag() {
        return mTag;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append("Endpoint [0x");
        out.append(Long.toHexString(mId.getEndpoint()));
        out.append("@ Hub 0x");
        out.append(Long.toHexString(mId.getHub()));
        out.append("] Name=");
        out.append(mName);
        out.append(", Tag=");
        out.append(mTag);
        return out.toString();
    }

    public static final @android.annotation.NonNull Creator<HubEndpointInfo> CREATOR =
            new Creator<>() {
                public HubEndpointInfo createFromParcel(Parcel in) {
                    return new HubEndpointInfo(in);
                }

                public HubEndpointInfo[] newArray(int size) {
                    return new HubEndpointInfo[size];
                }
            };
}
