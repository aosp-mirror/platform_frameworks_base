/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.telecom;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.UUID;

/**
 * Encapsulates the endpoint where call media can flow
 */
public final class CallEndpoint implements Parcelable {
    /** @hide */
    public static final int ENDPOINT_OPERATION_SUCCESS = 0;
    /** @hide */
    public static final int ENDPOINT_OPERATION_FAILED = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_UNKNOWN, TYPE_EARPIECE, TYPE_BLUETOOTH, TYPE_WIRED_HEADSET, TYPE_SPEAKER,
            TYPE_STREAMING})
    public @interface EndpointType {}

    /** Indicates that the type of endpoint through which call media flows is unknown type. */
    public static final int TYPE_UNKNOWN       = -1;

    /** Indicates that the type of endpoint through which call media flows is an earpiece. */
    public static final int TYPE_EARPIECE      = 1;

    /** Indicates that the type of endpoint through which call media flows is a Bluetooth. */
    public static final int TYPE_BLUETOOTH     = 2;

    /** Indicates that the type of endpoint through which call media flows is a wired headset. */
    public static final int TYPE_WIRED_HEADSET = 3;

    /** Indicates that the type of endpoint through which call media flows is a speakerphone. */
    public static final int TYPE_SPEAKER       = 4;

    /** Indicates that the type of endpoint through which call media flows is an external. */
    public static final int TYPE_STREAMING     = 5;

    /**
     * Error message attached to IllegalArgumentException when the endpoint name or id is null.
     * @hide
     */
    private static final String CALLENDPOINT_NAME_ID_NULL_ERROR =
            "CallEndpoint name cannot be null.";

    private final CharSequence mName;
    private final int mType;
    private final ParcelUuid mIdentifier;

    /**
     * Constructor for a {@link CallEndpoint} object.
     *
     * @param name Human-readable name associated with the endpoint
     * @param type The type of endpoint through which call media being routed
     * Allowed values:
     * {@link #TYPE_EARPIECE}
     * {@link #TYPE_BLUETOOTH}
     * {@link #TYPE_WIRED_HEADSET}
     * {@link #TYPE_SPEAKER}
     * {@link #TYPE_STREAMING}
     * {@link #TYPE_UNKNOWN}
     * @param id A unique identifier for this endpoint on the device
     */
    public CallEndpoint(@NonNull CharSequence name, @EndpointType int type,
            @NonNull ParcelUuid id) {
        // Ensure that endpoint name and id are non-null.
        if (name == null || id == null) {
            throw new IllegalArgumentException(CALLENDPOINT_NAME_ID_NULL_ERROR);
        }

        this.mName = name;
        this.mType = type;
        this.mIdentifier = id;
    }

    /** @hide */
    public CallEndpoint(@NonNull CharSequence name, @EndpointType int type) {
        this(name, type, new ParcelUuid(UUID.randomUUID()));
    }

    /** @hide */
    public CallEndpoint(CallEndpoint endpoint) {
        // Ensure that endpoint name and id are non-null.
        if (endpoint.getEndpointName() == null || endpoint.getIdentifier() == null) {
            throw new IllegalArgumentException(CALLENDPOINT_NAME_ID_NULL_ERROR);
        }

        mName = endpoint.getEndpointName();
        mType = endpoint.getEndpointType();
        mIdentifier = endpoint.getIdentifier();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof CallEndpoint)) {
            return false;
        }
        CallEndpoint endpoint = (CallEndpoint) obj;
        return Objects.equals(getEndpointName(), endpoint.getEndpointName())
                && getEndpointType() == endpoint.getEndpointType()
                && getIdentifier().equals(endpoint.getIdentifier());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(mName, mType, mIdentifier);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return TextUtils.formatSimple("[CallEndpoint Name: %s, Type: %s, Identifier: %s]",
                mName.toString(), endpointTypeToString(mType), mIdentifier.toString());
    }

    /**
     * @return Human-readable name associated with the endpoint
     */
    @NonNull
    public CharSequence getEndpointName() {
        return mName;
    }

    /**
     * @return The type of endpoint through which call media being routed
     */
    @EndpointType
    public int getEndpointType() {
        return mType;
    }

    /**
     * @return A unique identifier for this endpoint on the device
     */
    @NonNull
    public ParcelUuid getIdentifier() {
        return mIdentifier;
    }

    /**
     * Converts the provided endpoint type into a human-readable string representation.
     *
     * @param endpointType to convert into a string.
     * @return String representation of the provided endpoint type.
     * @hide
     */
    @NonNull
    public static String endpointTypeToString(int endpointType) {
        switch (endpointType) {
            case TYPE_EARPIECE:
                return "EARPIECE";
            case TYPE_BLUETOOTH:
                return "BLUETOOTH";
            case TYPE_WIRED_HEADSET:
                return "WIRED_HEADSET";
            case TYPE_SPEAKER:
                return "SPEAKER";
            case TYPE_STREAMING:
                return "EXTERNAL";
            default:
                return "UNKNOWN (" + endpointType + ")";
        }
    }

    /**
     * Responsible for creating CallEndpoint objects for deserialized Parcels.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<CallEndpoint> CREATOR =
            new Parcelable.Creator<CallEndpoint>() {

        @Override
        public CallEndpoint createFromParcel(Parcel source) {
            CharSequence name = source.readCharSequence();
            int type = source.readInt();
            ParcelUuid id = ParcelUuid.CREATOR.createFromParcel(source);

            return new CallEndpoint(name, type, id);
        }

        @Override
        public CallEndpoint[] newArray(int size) {
            return new CallEndpoint[size];
        }
    };

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeToParcel(@NonNull Parcel destination, int flags) {
        destination.writeCharSequence(mName);
        destination.writeInt(mType);
        mIdentifier.writeToParcel(destination, flags);
    }
}
