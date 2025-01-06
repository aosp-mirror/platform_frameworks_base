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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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

    /** This endpoint is from the Android framework */
    public static final int TYPE_FRAMEWORK = 1;

    /** This endpoint is from an Android app */
    public static final int TYPE_APP = 2;

    /** This endpoint is from an Android native program. */
    public static final int TYPE_NATIVE = 3;

    /** This endpoint is from a nanoapp. */
    public static final int TYPE_NANOAPP = 4;

    /** This endpoint is a generic endpoint served by a hub (not from a nanoapp). */
    public static final int TYPE_HUB_ENDPOINT = 5;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        TYPE_FRAMEWORK,
        TYPE_APP,
        TYPE_NATIVE,
        TYPE_NANOAPP,
        TYPE_HUB_ENDPOINT,
    })
    public @interface EndpointType {}

    private final HubEndpointIdentifier mId;
    @EndpointType private final int mType;
    private final String mName;
    private final int mVersion;
    @Nullable private final String mTag;

    @NonNull private final List<String> mRequiredPermissions;
    @NonNull private final List<HubServiceInfo> mHubServiceInfos;

    /** @hide */
    public HubEndpointInfo(android.hardware.contexthub.EndpointInfo endpointInfo) {
        mId = new HubEndpointIdentifier(endpointInfo.id.hubId, endpointInfo.id.id);
        mType = endpointInfo.type;
        mName = endpointInfo.name;
        mVersion = endpointInfo.version;
        mTag = endpointInfo.tag;
        mRequiredPermissions = Arrays.asList(endpointInfo.requiredPermissions);
        mHubServiceInfos = new ArrayList<>(endpointInfo.services.length);
        for (int i = 0; i < endpointInfo.services.length; i++) {
            mHubServiceInfos.add(new HubServiceInfo(endpointInfo.services[i]));
        }
    }

    /** @hide */
    public HubEndpointInfo(
            String name,
            int version,
            @Nullable String tag,
            @NonNull List<HubServiceInfo> hubServiceInfos) {
        mId = HubEndpointIdentifier.invalid();
        mType = TYPE_APP;
        mName = name;
        mVersion = version;
        mTag = tag;
        mRequiredPermissions = Collections.emptyList();
        mHubServiceInfos = hubServiceInfos;
    }

    private HubEndpointInfo(Parcel in) {
        long hubId = in.readLong();
        long endpointId = in.readLong();
        mId = new HubEndpointIdentifier(hubId, endpointId);
        mType = in.readInt();
        mName = in.readString();
        mVersion = in.readInt();
        mTag = in.readString();
        mRequiredPermissions = new ArrayList<>();
        in.readStringList(mRequiredPermissions);
        mHubServiceInfos = new ArrayList<>();
        in.readTypedList(mHubServiceInfos, HubServiceInfo.CREATOR);
    }

    /** Parcel implementation details */
    @Override
    public int describeContents() {
        int flags = 0;
        for (HubServiceInfo serviceInfo : mHubServiceInfos) {
            flags |= serviceInfo.describeContents();
        }
        return flags;
    }

    /** Parcel implementation details */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mId.getHub());
        dest.writeLong(mId.getEndpoint());
        dest.writeInt(mType);
        dest.writeString(mName);
        dest.writeInt(mVersion);
        dest.writeString(mTag);
        dest.writeStringList(mRequiredPermissions);
        dest.writeTypedList(mHubServiceInfos, flags);
    }

    /** Get a unique identifier for this endpoint. */
    @NonNull
    public HubEndpointIdentifier getIdentifier() {
        return mId;
    }

    /**
     * Get the type of this endpoint. Application may use this field to get more information about
     * who registered this endpoint for diagnostic purposes.
     *
     * <p>Type can be one of {@link HubEndpointInfo#TYPE_APP}, {@link
     * HubEndpointInfo#TYPE_FRAMEWORK}, {@link HubEndpointInfo#TYPE_NANOAPP}, {@link
     * HubEndpointInfo#TYPE_NATIVE} or {@link HubEndpointInfo#TYPE_HUB_ENDPOINT}.
     */
    public int getType() {
        return mType;
    }

    /** Get the human-readable name of this endpoint (for debugging purposes). */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Get the version of this endpoint.
     *
     * <p>Monotonically increasing version number. The two sides of an endpoint session can use this
     * version number to identify the other side and determine compatibility with each other. The
     * interpretation of the version number is specific to the implementation of an endpoint.
     *
     * <p>The version number should not be used to compare endpoints implementation freshness for
     * different endpoint types.
     *
     * <p>Depending on type of the endpoint, the following values (and formats) are used:
     *
     * <ol>
     *   <li>{@link #TYPE_FRAMEWORK}: android.os.Build.VERSION.SDK_INT_FULL
     *   <li>{@link #TYPE_APP}: versionCode
     *   <li>{@link #TYPE_NATIVE}: unspecified format (supplied by endpoint code)
     *   <li>{@link #TYPE_NANOAPP}: nanoapp version, typically following 0xMMmmpppp scheme where MM
     *       = major version, mm = minor version, pppp = patch version
     *   <li>{@link #TYPE_HUB_ENDPOINT}: unspecified format (supplied by endpoint code), following
     *       nanoapp versioning scheme is recommended
     * </ol>
     */
    public int getVersion() {
        return mVersion;
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

    /**
     * Get the list of required permissions in order to talk to this endpoint.
     *
     * <p>This list is enforced by the Context Hub Service. The app would need to have the required
     * permissions list to open a session with this particular endpoint. Otherwise this will be
     * rejected by as permission failures.
     *
     * <p>This is mostly for allowing app to check what permission it needs first internally. App
     * will need to request permissions grant at runtime if not already granted. See {@link
     * android.content.Context#checkPermission} for more details.
     *
     * <p>See {@link android.Manifest.permission} for a list of standard Android permissions as
     * possible values.
     */
    @SuppressLint("RequiresPermission")
    @NonNull
    public Collection<String> getRequiredPermissions() {
        return Collections.unmodifiableList(mRequiredPermissions);
    }

    /**
     * Get the list of services provided by this endpoint.
     *
     * <p>See {@link HubServiceInfo} for more information.
     */
    @NonNull
    public Collection<HubServiceInfo> getServiceInfoCollection() {
        return Collections.unmodifiableList(mHubServiceInfos);
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
