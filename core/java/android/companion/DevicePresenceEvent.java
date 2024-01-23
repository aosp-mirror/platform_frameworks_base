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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Event for observing device presence.
 *
 * @see CompanionDeviceManager#startObservingDevicePresence(ObservingDevicePresenceRequest)
 * @see ObservingDevicePresenceRequest.Builder#setUuid(ParcelUuid)
 * @see ObservingDevicePresenceRequest.Builder#setAssociationId(int)
 */
@FlaggedApi(Flags.FLAG_DEVICE_PRESENCE)
public final class DevicePresenceEvent implements Parcelable {

    /** @hide */
    @IntDef(prefix = {"EVENT"}, value = {
            EVENT_BLE_APPEARED,
            EVENT_BLE_DISAPPEARED,
            EVENT_BT_CONNECTED,
            EVENT_BT_DISCONNECTED,
            EVENT_SELF_MANAGED_APPEARED,
            EVENT_SELF_MANAGED_DISAPPEARED
    })

    @Retention(RetentionPolicy.SOURCE)
    public @interface Event {}

    /**
     * Indicate observing device presence base on the ParcelUuid but not association id.
     */
    public static final int NO_ASSOCIATION = -1;

    /**
     * Companion app receives
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)} callback
     * with this event if the device comes into BLE range.
     */
    public static final int EVENT_BLE_APPEARED = 0;

    /**
     * Companion app receives
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)} callback
     * with this event if the device is no longer in BLE range.
     */
    public static final int EVENT_BLE_DISAPPEARED = 1;

    /**
     * Companion app receives
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)} callback
     * with this event when the bluetooth device is connected.
     */
    public static final int EVENT_BT_CONNECTED = 2;

    /**
     * Companion app receives
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)} callback
     * with this event if the bluetooth device is disconnected.
     */
    public static final int EVENT_BT_DISCONNECTED = 3;

    /**
     * A companion app for a self-managed device will receive the callback
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)}
     * if it reports that a device has appeared on its
     * own.
     */
    public static final int EVENT_SELF_MANAGED_APPEARED = 4;

    /**
     * A companion app for a self-managed device will receive the callback
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)} if it reports
     * that a device has disappeared on its own.
     */
    public static final int EVENT_SELF_MANAGED_DISAPPEARED = 5;
    private final int mAssociationId;
    private final int mEvent;
    @Nullable
    private final ParcelUuid mUuid;

    private static final int PARCEL_UUID_NULL = 0;

    private static final int PARCEL_UUID_NOT_NULL = 1;

    /**
     * Create a new DevicePresenceEvent.
     */
    public DevicePresenceEvent(
            int associationId, @Event int event, @Nullable ParcelUuid uuid) {
        mAssociationId = associationId;
        mEvent = event;
        mUuid = uuid;
    }

    /**
     * @return The association id has been used to observe device presence.
     *
     * Caller will receive the valid association id if only if using
     * {@link ObservingDevicePresenceRequest.Builder#setAssociationId(int)}, otherwise
     * return {@link #NO_ASSOCIATION}.
     *
     * @see ObservingDevicePresenceRequest.Builder#setAssociationId(int)
     */
    public int getAssociationId() {
        return mAssociationId;
    }

    /**
     * @return Associated companion device's event.
     */
    public int getEvent() {
        return mEvent;
    }

    /**
     * @return The ParcelUuid has been used to observe device presence.
     *
     * Caller will receive the ParcelUuid if only if using
     * {@link ObservingDevicePresenceRequest.Builder#setUuid(ParcelUuid)}, otherwise return null.
     *
     * @see ObservingDevicePresenceRequest.Builder#setUuid(ParcelUuid)
     */

    @Nullable
    public ParcelUuid getUuid() {
        return mUuid;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAssociationId);
        dest.writeInt(mEvent);
        if (mUuid == null) {
            // Write 0 to the parcel to indicate the ParcelUuid is null.
            dest.writeInt(PARCEL_UUID_NULL);
        } else {
            dest.writeInt(PARCEL_UUID_NOT_NULL);
            mUuid.writeToParcel(dest, flags);
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof DevicePresenceEvent that)) return false;

        return Objects.equals(mUuid, that.mUuid)
                && mAssociationId == that.mAssociationId
                && mEvent == that.mEvent;
    }

    @Override
    public String toString() {
        return "ObservingDevicePresenceResult { "
                + "Association Id= " + mAssociationId + ","
                + "ParcelUuid= " + mUuid + ","
                + "Event= " + mEvent + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAssociationId, mEvent, mUuid);
    }

    @NonNull
    public static final Parcelable.Creator<DevicePresenceEvent> CREATOR =
            new Parcelable.Creator<DevicePresenceEvent>() {
                @Override
                public DevicePresenceEvent[] newArray(int size) {
                    return new DevicePresenceEvent[size];
                }

                @Override
                public DevicePresenceEvent createFromParcel(@NonNull Parcel in) {
                    return new DevicePresenceEvent(in);
                }
            };

    private DevicePresenceEvent(@NonNull Parcel in) {
        mAssociationId = in.readInt();
        mEvent = in.readInt();
        if (in.readInt() == PARCEL_UUID_NULL) {
            mUuid = null;
        } else {
            mUuid = ParcelUuid.CREATOR.createFromParcel(in);
        }
    }
}
