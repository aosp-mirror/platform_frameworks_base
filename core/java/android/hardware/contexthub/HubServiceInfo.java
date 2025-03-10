/*
 * Copyright 2024 The Android Open Source Project
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
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.Objects;

/**
 * A class describing services provided by endpoints.
 *
 * <p>An endpoint can provide zero or more service. See {@link
 * HubEndpoint.Builder#setServiceInfoCollection(Collection)} and {@link
 * HubEndpointInfo#getServiceInfoCollection()}.
 *
 * <p>An endpoint session can be service-less or associated to one service.See {@link
 * HubEndpointSession#getServiceInfo()}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public final class HubServiceInfo implements Parcelable {
    /** Customized format for messaging. Fully customized and opaque messaging format. */
    public static final int FORMAT_CUSTOM = 0;

    /**
     * Binder-based messaging. The host endpoint is defining this service in Stable AIDL. Messages
     * between endpoints that uses this service will be using the binder marhsalling format.
     */
    public static final int FORMAT_AIDL = 1;

    /**
     * Pigweed RPC messaging with Protobuf. This endpoint is a Pigweed RPC. Messages between
     * endpoints will use Pigweed RPC marshalling format (protobuf).
     */
    public static final int FORMAT_PW_RPC_PROTOBUF = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        FORMAT_CUSTOM,
        FORMAT_AIDL,
        FORMAT_PW_RPC_PROTOBUF,
    })
    public @interface ServiceFormat {}

    @NonNull private final String mServiceDescriptor;

    @ServiceFormat private final int mFormat;
    private final int mMajorVersion;
    private final int mMinorVersion;

    /** @hide */
    public HubServiceInfo(android.hardware.contexthub.Service service) {
        mServiceDescriptor = service.serviceDescriptor;
        mFormat = service.format;
        mMajorVersion = service.majorVersion;
        mMinorVersion = service.minorVersion;
    }

    private HubServiceInfo(Parcel in) {
        mServiceDescriptor = Objects.requireNonNull(in.readString());
        mFormat = in.readInt();
        mMajorVersion = in.readInt();
        mMinorVersion = in.readInt();
    }

    public HubServiceInfo(
            @NonNull String serviceDescriptor,
            @ServiceFormat int format,
            int majorVersion,
            int minorVersion) {
        mServiceDescriptor = serviceDescriptor;
        mFormat = format;
        mMajorVersion = majorVersion;
        mMinorVersion = minorVersion;
    }

    /** Get the unique identifier of this service. See {@link Builder} for more information. */
    @NonNull
    public String getServiceDescriptor() {
        return mServiceDescriptor;
    }

    /**
     * Get the type of the service.
     *
     * <p>The value can be one of {@link HubServiceInfo#FORMAT_CUSTOM}, {@link
     * HubServiceInfo#FORMAT_AIDL} or {@link HubServiceInfo#FORMAT_PW_RPC_PROTOBUF}.
     */
    public int getFormat() {
        return mFormat;
    }

    /** Get the major version of this service. */
    public int getMajorVersion() {
        return mMajorVersion;
    }

    /** Get the minor version of this service. */
    public int getMinorVersion() {
        return mMinorVersion;
    }

    /** Parcel implementation details */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Parcel implementation details */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mServiceDescriptor);
        dest.writeInt(mFormat);
        dest.writeInt(mMajorVersion);
        dest.writeInt(mMinorVersion);
    }

    /** Builder for a {@link HubServiceInfo} object. */
    public static final class Builder {
        @NonNull private final String mServiceDescriptor;

        @ServiceFormat private final int mFormat;
        private final int mMajorVersion;
        private final int mMinorVersion;

        /**
         * Create a builder for {@link HubServiceInfo} with a service descriptor.
         *
         * <p>Service descriptor should uniquely identify the interface (scoped to type). Convention
         * of the descriptor depend on interface type.
         *
         * <p>Examples:
         *
         * <ol>
         *   <li>AOSP-defined AIDL: android.hardware.something.IFoo/default
         *   <li>Vendor-defined AIDL: com.example.something.IBar/default
         *   <li>Pigweed RPC with Protobuf: com.example.proto.ExampleService
         * </ol>
         *
         * @param serviceDescriptor The service descriptor.
         * @param format One of {@link HubServiceInfo#FORMAT_CUSTOM}, {@link
         *     HubServiceInfo#FORMAT_AIDL} or {@link HubServiceInfo#FORMAT_PW_RPC_PROTOBUF}.
         * @param majorVersion Breaking changes should be a major version bump.
         * @param minorVersion Monotonically increasing minor version.
         * @throws IllegalArgumentException if one or more fields are not valid.
         */
        public Builder(
                @NonNull String serviceDescriptor,
                @ServiceFormat int format,
                int majorVersion,
                int minorVersion) {
            if (format != FORMAT_CUSTOM
                    && format != FORMAT_AIDL
                    && format != FORMAT_PW_RPC_PROTOBUF) {
                throw new IllegalArgumentException("Invalid format type.");
            }
            mFormat = format;

            if (majorVersion < 0) {
                throw new IllegalArgumentException(
                        "Major version cannot be set to negative number.");
            }
            mMajorVersion = majorVersion;

            if (minorVersion < 0) {
                throw new IllegalArgumentException(
                        "Minor version cannot be set to negative number.");
            }
            mMinorVersion = minorVersion;

            if (serviceDescriptor.isBlank()) {
                throw new IllegalArgumentException("Invalid service descriptor.");
            }
            mServiceDescriptor = serviceDescriptor;
        }

        /**
         * Build the {@link HubServiceInfo} object.
         *
         * @throws IllegalStateException if the Builder is missing required info.
         */
        @NonNull
        public HubServiceInfo build() {
            if (mMajorVersion < 0 || mMinorVersion < 0) {
                throw new IllegalStateException("Major and minor version must be set.");
            }
            return new HubServiceInfo(
                    mServiceDescriptor, mFormat, mMajorVersion, mMinorVersion);
        }
    }

    /** Parcel implementation details */
    @NonNull
    public static final Parcelable.Creator<HubServiceInfo> CREATOR =
            new Parcelable.Creator<>() {
                public HubServiceInfo createFromParcel(Parcel in) {
                    return new HubServiceInfo(in);
                }

                public HubServiceInfo[] newArray(int size) {
                    return new HubServiceInfo[size];
                }
            };
}
