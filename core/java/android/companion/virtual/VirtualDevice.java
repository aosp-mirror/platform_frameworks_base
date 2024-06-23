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

package android.companion.virtual;

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_AUDIO;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_CAMERA;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_SENSORS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.companion.virtual.flags.Flags;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

/**
 * Details of a particular virtual device.
 *
 * <p>Read-only device representation exposing the properties of an existing virtual device.
 */
// TODO(b/310912420): Link to VirtualDeviceManager#registerVirtualDeviceListener from the docs
public final class VirtualDevice implements Parcelable {

    private final @NonNull IVirtualDevice mVirtualDevice;
    private final int mId;
    private final @Nullable String mPersistentId;
    private final @Nullable String mName;
    private final @Nullable CharSequence mDisplayName;

    /**
     * Creates a new instance of {@link VirtualDevice}.
     * Only to be used by the VirtualDeviceManagerService.
     *
     * @hide
     */
    public VirtualDevice(@NonNull IVirtualDevice virtualDevice, int id,
            @Nullable String persistentId, @Nullable String name) {
        this(virtualDevice, id, persistentId, name, null);
    }

    /**
     * Creates a new instance of {@link VirtualDevice}. Only to be used by the
     * VirtualDeviceManagerService.
     *
     * @hide
     */
    public VirtualDevice(@NonNull IVirtualDevice virtualDevice, int id,
            @Nullable String persistentId, @Nullable String name,
            @Nullable CharSequence displayName) {
        if (id <= Context.DEVICE_ID_DEFAULT) {
            throw new IllegalArgumentException("VirtualDevice ID must be greater than "
                    + Context.DEVICE_ID_DEFAULT);
        }
        mVirtualDevice = virtualDevice;
        mId = id;
        mPersistentId = persistentId;
        mName = name;
        mDisplayName = displayName;
    }

    private VirtualDevice(@NonNull Parcel parcel) {
        mVirtualDevice = IVirtualDevice.Stub.asInterface(parcel.readStrongBinder());
        mId = parcel.readInt();
        mPersistentId = parcel.readString8();
        mName = parcel.readString8();
        mDisplayName = parcel.readCharSequence();
    }

    /**
     * Returns the unique ID of the virtual device.
     *
     * <p>This identifier corresponds to {@link Context#getDeviceId()} and can be used to access
     * device-specific system capabilities.
     *
     * <p class="note">This identifier is ephemeral and should not be used for persisting any data
     * per device.
     *
     * @see Context#createDeviceContext
     */
    // TODO(b/310912420): Link to #getPersistentDeviceId from the docs
    public int getDeviceId() {
        return mId;
    }

    /**
     * Returns the persistent identifier of this virtual device, if any.
     *
     * <p> If there is no stable identifier for this virtual device, then this returns {@code null}.

     * <p>This identifier may correspond to a physical device. In that case it remains valid for as
     * long as that physical device is associated with the host device and may be used to persist
     * data per device.
     *
     * <p class="note">This identifier may not be unique across virtual devices, in case there are
     * more than one virtual devices corresponding to the same physical device.
     */
    @FlaggedApi(Flags.FLAG_VDM_PUBLIC_APIS)
    public @Nullable String getPersistentDeviceId() {
        return mPersistentId;
    }

    /**
     * Returns the name of the virtual device (optionally) provided during its creation.
     */
    public @Nullable String getName() {
        return mName;
    }

    /**
     * Returns the human-readable name of the virtual device, if defined, which is suitable to be
     * shown in UI.
     */
    @FlaggedApi(Flags.FLAG_VDM_PUBLIC_APIS)
    public @Nullable CharSequence getDisplayName() {
        return mDisplayName;
    }

    /**
     * Returns the IDs of all virtual displays that belong to this device, if any.
     *
     * <p>The actual {@link android.view.Display} objects can be obtained by passing the returned
     * IDs to {@link android.hardware.display.DisplayManager#getDisplay(int)}.</p>
     */
    @FlaggedApi(Flags.FLAG_VDM_PUBLIC_APIS)
    public @NonNull int[] getDisplayIds() {
        try {
            return mVirtualDevice.getDisplayIds();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether this device may have custom sensors.
     *
     * <p>Returning {@code true} does not necessarily mean that this device has sensors, it only
     * means that a {@link android.hardware.SensorManager} instance created from a {@link Context}
     * associated with this device will return this device's sensors, if any.</p>
     *
     * @see Context#getDeviceId()
     * @see Context#createDeviceContext(int)
     */
    @FlaggedApi(Flags.FLAG_VDM_PUBLIC_APIS)
    public boolean hasCustomSensorSupport() {
        try {
            return mVirtualDevice.getDevicePolicy(POLICY_TYPE_SENSORS) == DEVICE_POLICY_CUSTOM;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether this device may have custom audio input device.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_VDM_PUBLIC_APIS)
    public boolean hasCustomAudioInputSupport() {
        try {
            return mVirtualDevice.getDevicePolicy(POLICY_TYPE_AUDIO) == DEVICE_POLICY_CUSTOM;
            // TODO(b/291735254): also check for a custom audio injection mix for this device id.
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether this device may have custom cameras.
     *
     * <p>Returning {@code true} does not necessarily mean that this device has cameras, it only
     * means that a {@link android.hardware.camera2.CameraManager} instance created from a
     * {@link Context} associated with this device will return this device's cameras, if any.</p>
     *
     * @see Context#getDeviceId()
     * @see Context#createDeviceContext(int)
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_VDM_PUBLIC_APIS)
    public boolean hasCustomCameraSupport() {
        try {
            return mVirtualDevice.getDevicePolicy(POLICY_TYPE_CAMERA) == DEVICE_POLICY_CUSTOM;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mVirtualDevice.asBinder());
        dest.writeInt(mId);
        dest.writeString8(mPersistentId);
        dest.writeString8(mName);
        dest.writeCharSequence(mDisplayName);
    }

    @Override
    @NonNull
    public String toString() {
        return "VirtualDevice("
                + " mId=" + mId
                + " mPersistentId=" + mPersistentId
                + " mName=" + mName
                + " mDisplayName=" + mDisplayName
                + ")";
    }

    @NonNull
    public static final Parcelable.Creator<VirtualDevice> CREATOR =
            new Parcelable.Creator<VirtualDevice>() {
                public VirtualDevice createFromParcel(Parcel in) {
                    return new VirtualDevice(in);
                }

                public VirtualDevice[] newArray(int size) {
                    return new VirtualDevice[size];
                }
            };
}
