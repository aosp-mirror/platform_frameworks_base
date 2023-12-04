/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * @hide
 * Class to represent the attributes of an audio device: its type (speaker, headset...), address
 * (if known) and role (input, output).
 * <p>Unlike {@link AudioDeviceInfo}, the device
 * doesn't need to be connected to be uniquely identified, it can
 * for instance represent a specific A2DP headset even after a
 * disconnection, whereas the corresponding <code>AudioDeviceInfo</code>
 * would then be invalid.
 * <p>While creating / obtaining an instance is not protected by a
 * permission, APIs using one rely on MODIFY_AUDIO_ROUTING.
 */
@SystemApi
public final class AudioDeviceAttributes implements Parcelable {

    /**
     * A role identifying input devices, such as microphones.
     */
    public static final int ROLE_INPUT = AudioPort.ROLE_SOURCE;
    /**
     * A role identifying output devices, such as speakers or headphones.
     */
    public static final int ROLE_OUTPUT = AudioPort.ROLE_SINK;

    /** @hide */
    @IntDef(flag = false, prefix = "ROLE_", value = {
            ROLE_INPUT, ROLE_OUTPUT }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface Role {}

    /**
     * The audio device type, as defined in {@link AudioDeviceInfo}
     */
    private final @AudioDeviceInfo.AudioDeviceType int mType;
    /**
     * The unique address of the device. Some devices don't have addresses, only an empty string.
     */
    private @NonNull String mAddress;
    /**
     * Is input or output device
     */
    private final @Role int mRole;

    /**
     * The internal audio device type
     */
    private final int mNativeType;

    /**
     * @hide
     * Constructor from a valid {@link AudioDeviceInfo}
     * @param deviceInfo the connected audio device from which to obtain the device-identifying
     *                   type and address.
     */
    @SystemApi
    public AudioDeviceAttributes(@NonNull AudioDeviceInfo deviceInfo) {
        Objects.requireNonNull(deviceInfo);
        mRole = deviceInfo.isSink() ? ROLE_OUTPUT : ROLE_INPUT;
        mType = deviceInfo.getType();
        mAddress = deviceInfo.getAddress();
        mNativeType = deviceInfo.getInternalType();
    }

    /**
     * @hide
     * Constructor from role, device type and address
     * @param role indicates input or output role
     * @param type the device type, as defined in {@link AudioDeviceInfo}
     * @param address the address of the device, or an empty string for devices without one
     */
    @SystemApi
    public AudioDeviceAttributes(@Role int role, @AudioDeviceInfo.AudioDeviceType int type,
                              @NonNull String address) {
        Objects.requireNonNull(address);
        if (role != ROLE_OUTPUT && role != ROLE_INPUT) {
            throw new IllegalArgumentException("Invalid role " + role);
        }
        if (role == ROLE_OUTPUT) {
            AudioDeviceInfo.enforceValidAudioDeviceTypeOut(type);
            mNativeType = AudioDeviceInfo.convertDeviceTypeToInternalDevice(type);
        } else if (role == ROLE_INPUT) {
            AudioDeviceInfo.enforceValidAudioDeviceTypeIn(type);
            mNativeType = AudioDeviceInfo.convertDeviceTypeToInternalInputDevice(type);
        } else {
            mNativeType = AudioSystem.DEVICE_NONE;
        }

        mRole = role;
        mType = type;
        mAddress = address;
    }

    /**
     * @hide
     * Constructor from internal device type and address
     * @param type the internal device type, as defined in {@link AudioSystem}
     * @param address the address of the device, or an empty string for devices without one
     */
    public AudioDeviceAttributes(int nativeType, @NonNull String address) {
        mRole = (nativeType & AudioSystem.DEVICE_BIT_IN) != 0 ? ROLE_INPUT : ROLE_OUTPUT;
        mType = AudioDeviceInfo.convertInternalDeviceToDeviceType(nativeType);
        mAddress = address;
        mNativeType = nativeType;
    }

    /**
     * @hide
     * Copy Constructor.
     * @param ada the copied AudioDeviceAttributes
     */
    public AudioDeviceAttributes(AudioDeviceAttributes ada) {
        mRole = ada.getRole();
        mType = ada.getType();
        mAddress = ada.getAddress();
        mNativeType = ada.getInternalType();
    }

    /**
     * @hide
     * Returns the role of a device
     * @return the role
     */
    @SystemApi
    public @Role int getRole() {
        return mRole;
    }

    /**
     * @hide
     * Returns the audio device type of a device
     * @return the type, as defined in {@link AudioDeviceInfo}
     */
    @SystemApi
    public @AudioDeviceInfo.AudioDeviceType int getType() {
        return mType;
    }

    /**
     * @hide
     * Returns the address of the audio device, or an empty string for devices without one
     * @return the device address
     */
    @SystemApi
    public @NonNull String getAddress() {
        return mAddress;
    }

    /**
     * @hide
     * Sets the device address. Only used by audio service.
     */
    public void setAddress(@NonNull String address) {
        Objects.requireNonNull(address);
        mAddress = address;
    }

    /**
     * @hide
     * Returns the internal device type of a device
     * @return the internal device type
     */
    public int getInternalType() {
        return mNativeType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRole, mType, mAddress);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioDeviceAttributes that = (AudioDeviceAttributes) o;
        return ((mRole == that.mRole)
                && (mType == that.mType)
                && mAddress.equals(that.mAddress));
    }

    /** @hide */
    public static String roleToString(@Role int role) {
        return (role == ROLE_OUTPUT ? "output" : "input");
    }

    @Override
    public String toString() {
        return new String("AudioDeviceAttributes:"
                + " role:" + roleToString(mRole)
                + " type:" + (mRole == ROLE_OUTPUT ? AudioSystem.getOutputDeviceName(mNativeType)
                        : AudioSystem.getInputDeviceName(mNativeType))
                + " addr:" + mAddress);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mRole);
        dest.writeInt(mType);
        dest.writeString(mAddress);
        dest.writeInt(mNativeType);
    }

    private AudioDeviceAttributes(@NonNull Parcel in) {
        mRole = in.readInt();
        mType = in.readInt();
        mAddress = in.readString();
        mNativeType = in.readInt();
    }

    public static final @NonNull Parcelable.Creator<AudioDeviceAttributes> CREATOR =
            new Parcelable.Creator<AudioDeviceAttributes>() {
        /**
         * Rebuilds an AudioDeviceAttributes previously stored with writeToParcel().
         * @param p Parcel object to read the AudioDeviceAttributes from
         * @return a new AudioDeviceAttributes created from the data in the parcel
         */
        public AudioDeviceAttributes createFromParcel(Parcel p) {
            return new AudioDeviceAttributes(p);
        }

        public AudioDeviceAttributes[] newArray(int size) {
            return new AudioDeviceAttributes[size];
        }
    };
}
