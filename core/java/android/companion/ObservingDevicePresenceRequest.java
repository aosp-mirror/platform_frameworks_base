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

package android.companion;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.provider.OneTimeUseBuilder;

import java.util.Objects;

/**
 * A request for setting the types of device for observing device presence.
 *
 * <p>Only supports association id or ParcelUuid and calling app must declare uses-permission
 * {@link android.Manifest.permission#REQUEST_OBSERVE_DEVICE_UUID_PRESENCE} if using
 * {@link Builder#setUuid(ParcelUuid)}.</p>
 *
 * Calling apps must use either ObservingDevicePresenceRequest.Builder#setUuid(ParcelUuid) or
 * ObservingDevicePresenceRequest.Builder#setAssociationId(int), but not both.
 *
 * @see Builder#setUuid(ParcelUuid)
 * @see Builder#setAssociationId(int)
 * @see CompanionDeviceManager#startObservingDevicePresence(ObservingDevicePresenceRequest)
 */
@FlaggedApi(Flags.FLAG_DEVICE_PRESENCE)
public final class ObservingDevicePresenceRequest implements Parcelable {
    private final int mAssociationId;
    @Nullable private final ParcelUuid mUuid;

    private static final int PARCEL_UUID_NULL = 0;

    private static final int PARCEL_UUID_NOT_NULL = 1;

    private ObservingDevicePresenceRequest(int associationId, ParcelUuid uuid) {
        mAssociationId = associationId;
        mUuid = uuid;
    }

    private ObservingDevicePresenceRequest(@NonNull Parcel in) {
        mAssociationId = in.readInt();
        if (in.readInt() == PARCEL_UUID_NULL) {
            mUuid = null;
        } else {
            mUuid = ParcelUuid.CREATOR.createFromParcel(in);
        }
    }

    /**
     * @return the association id for observing device presence. It will return
     * {@link DevicePresenceEvent#NO_ASSOCIATION} if using
     * {@link Builder#setUuid(ParcelUuid)}.
     */
    public int getAssociationId() {
        return mAssociationId;
    }

    /**
     * @return the ParcelUuid for observing device presence.
     */
    @Nullable
    public ParcelUuid getUuid() {
        return mUuid;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAssociationId);
        if (mUuid == null) {
            // Write 0 to the parcel to indicate the ParcelUuid is null.
            dest.writeInt(PARCEL_UUID_NULL);
        } else {
            dest.writeInt(PARCEL_UUID_NOT_NULL);
            mUuid.writeToParcel(dest, flags);
        }

    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Parcelable.Creator<ObservingDevicePresenceRequest> CREATOR =
            new Parcelable.Creator<ObservingDevicePresenceRequest>() {
                @Override
                public ObservingDevicePresenceRequest[] newArray(int size) {
                    return new ObservingDevicePresenceRequest[size];
                }

                @Override
                public ObservingDevicePresenceRequest createFromParcel(@NonNull Parcel in) {
                    return new ObservingDevicePresenceRequest(in);
                }
            };

    @Override
    public String toString() {
        return "ObservingDevicePresenceRequest { "
                + "Association Id= " + mAssociationId + ","
                + "ParcelUuid= " + mUuid + "}";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof ObservingDevicePresenceRequest that)) return false;

        return Objects.equals(mUuid, that.mUuid) && mAssociationId == that.mAssociationId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAssociationId, mUuid);
    }

    /**
     * A builder for {@link ObservingDevicePresenceRequest}
     */
    public static final class Builder extends OneTimeUseBuilder<ObservingDevicePresenceRequest> {
        // Initial the association id to {@link DevicePresenceEvent.NO_ASSOCIATION}
        // to indicate the value is not set yet.
        private int mAssociationId = DevicePresenceEvent.NO_ASSOCIATION;
        private ParcelUuid mUuid;

        public Builder() {}

        /**
         * Set the association id to be observed for device presence.
         *
         * <p>The provided device must be {@link CompanionDeviceManager#associate associated}
         * with the calling app before calling this method if using this API.
         *
         * Caller must implement a single {@link CompanionDeviceService} which will be bound to and
         * receive callbacks to
         * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)}.</p>
         *
         * <p>Calling apps must use either {@link #setUuid(ParcelUuid)}
         * or this API, but not both.</p>
         *
         * @param associationId The association id for observing device presence.
         */
        @NonNull
        public Builder setAssociationId(int associationId) {
            checkNotUsed();
            this.mAssociationId = associationId;
            return this;
        }

        /**
         * Set the ParcelUuid to be observed for device presence.
         *
         * <p>It does not require to create the association before calling this API.
         * This only supports classic Bluetooth scan and caller must implement
         * a single {@link CompanionDeviceService} which will be bound to and receive callbacks to
         * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)}.</p>
         *
         * <p>The Uuid should be matching one of the ParcelUuid form
         * {@link android.bluetooth.BluetoothDevice#getUuids()}</p>
         *
         * <p>Calling apps must use either this API or {@link #setAssociationId(int)},
         * but not both.</p>
         *
         * @param uuid The ParcelUuid for observing device presence.
         */
        @NonNull
        @RequiresPermission(android.Manifest.permission.REQUEST_OBSERVE_DEVICE_UUID_PRESENCE)
        public Builder setUuid(@NonNull ParcelUuid uuid) {
            checkNotUsed();
            this.mUuid = uuid;
            return this;
        }

        @NonNull
        @Override
        public ObservingDevicePresenceRequest build() {
            markUsed();
            if (mUuid != null && mAssociationId != DevicePresenceEvent.NO_ASSOCIATION) {
                throw new IllegalStateException("Cannot observe device presence based on "
                        + "both ParcelUuid and association ID. Choose one or the other.");
            } else if (mUuid == null && mAssociationId <= 0) {
                throw new IllegalStateException("Must provide either a ParcelUuid or "
                        + "a valid association ID to observe device presence.");
            }

            return new ObservingDevicePresenceRequest(mAssociationId, mUuid);
        }
    }
}
