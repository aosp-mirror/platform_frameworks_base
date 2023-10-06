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

package com.android.settingslib.bluetooth;

import android.annotation.IntDef;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.util.SparseIntArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/** Hearing aids information and constants that shared within hearing aids related profiles */
public class HearingAidInfo {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            DeviceSide.SIDE_INVALID,
            DeviceSide.SIDE_LEFT,
            DeviceSide.SIDE_RIGHT,
            DeviceSide.SIDE_LEFT_AND_RIGHT,
    })

    /** Side definition for hearing aids. */
    public @interface DeviceSide {
        int SIDE_INVALID = -1;
        int SIDE_LEFT = 0;
        int SIDE_RIGHT = 1;
        int SIDE_LEFT_AND_RIGHT = 2;
    }

    @Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @IntDef({
            DeviceMode.MODE_INVALID,
            DeviceMode.MODE_MONAURAL,
            DeviceMode.MODE_BINAURAL,
            DeviceMode.MODE_BANDED,
    })

    /** Mode definition for hearing aids. */
    public @interface DeviceMode {
        int MODE_INVALID = -1;
        int MODE_MONAURAL = 0;
        int MODE_BINAURAL = 1;
        int MODE_BANDED = 2;
    }

    private final int mSide;
    private final int mMode;
    private final long mHiSyncId;

    private HearingAidInfo(int side, int mode, long hiSyncId) {
        mSide = side;
        mMode = mode;
        mHiSyncId = hiSyncId;
    }

    @DeviceSide
    public int getSide() {
        return mSide;
    }

    @DeviceMode
    public int getMode() {
        return mMode;
    }

    public long getHiSyncId() {
        return mHiSyncId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HearingAidInfo)) {
            return false;
        }
        HearingAidInfo that = (HearingAidInfo) o;
        return mSide == that.mSide && mMode == that.mMode && mHiSyncId == that.mHiSyncId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSide, mMode, mHiSyncId);
    }

    @Override
    public String toString() {
        return "HearingAidInfo{"
                + "mSide=" + mSide
                + ", mMode=" + mMode
                + ", mHiSyncId=" + mHiSyncId
                + '}';
    }

    @DeviceSide
    private static int convertAshaDeviceSideToInternalSide(int ashaDeviceSide) {
        return ASHA_DEVICE_SIDE_TO_INTERNAL_SIDE_MAPPING.get(
                ashaDeviceSide, DeviceSide.SIDE_INVALID);
    }

    @DeviceMode
    private static int convertAshaDeviceModeToInternalMode(int ashaDeviceMode) {
        return ASHA_DEVICE_MODE_TO_INTERNAL_MODE_MAPPING.get(
                ashaDeviceMode, DeviceMode.MODE_INVALID);
    }

    @DeviceSide
    private static int convertLeAudioLocationToInternalSide(int leAudioLocation) {
        boolean isLeft = (leAudioLocation & LE_AUDIO_LOCATION_LEFT) != 0;
        boolean isRight = (leAudioLocation & LE_AUDIO_LOCATION_RIGHT) != 0;
        if (isLeft && isRight) {
            return DeviceSide.SIDE_LEFT_AND_RIGHT;
        } else if (isLeft) {
            return DeviceSide.SIDE_LEFT;
        } else if (isRight) {
            return DeviceSide.SIDE_RIGHT;
        }
        return DeviceSide.SIDE_INVALID;
    }

    @DeviceMode
    private static int convertHapDeviceTypeToInternalMode(int hapDeviceType) {
        return HAP_DEVICE_TYPE_TO_INTERNAL_MODE_MAPPING.get(hapDeviceType, DeviceMode.MODE_INVALID);
    }

    /** Builder class for constructing {@link HearingAidInfo} objects. */
    public static final class Builder {
        private int mSide = DeviceSide.SIDE_INVALID;
        private int mMode = DeviceMode.MODE_INVALID;
        private long mHiSyncId = BluetoothHearingAid.HI_SYNC_ID_INVALID;

        /**
         * Configure the hearing device mode.
         * @param ashaDeviceMode one of the hearing aid device modes defined in HearingAidProfile
         * {@link HearingAidProfile.DeviceMode}
         */
        public Builder setAshaDeviceMode(int ashaDeviceMode) {
            mMode = convertAshaDeviceModeToInternalMode(ashaDeviceMode);
            return this;
        }

        /**
         * Configure the hearing device mode.
         * @param hapDeviceType one of the hearing aid device types defined in HapClientProfile
         * {@link HapClientProfile.HearingAidType}
         */
        public Builder setHapDeviceType(int hapDeviceType) {
            mMode = convertHapDeviceTypeToInternalMode(hapDeviceType);
            return this;
        }

        /**
         * Configure the hearing device side.
         * @param ashaDeviceSide one of the hearing aid device sides defined in HearingAidProfile
         * {@link HearingAidProfile.DeviceSide}
         */
        public Builder setAshaDeviceSide(int ashaDeviceSide) {
            mSide = convertAshaDeviceSideToInternalSide(ashaDeviceSide);
            return this;
        }

        /**
         * Configure the hearing device side.
         * @param leAudioLocation one of the audio location defined in BluetoothLeAudio
         * {@link BluetoothLeAudio.AudioLocation}
         */
        public Builder setLeAudioLocation(int leAudioLocation) {
            mSide = convertLeAudioLocationToInternalSide(leAudioLocation);
            return this;
        }

        /**
         * Configure the hearing aid hiSyncId.
         * @param hiSyncId the ASHA hearing aid id
         */
        public Builder setHiSyncId(long hiSyncId) {
            mHiSyncId = hiSyncId;
            return this;
        }

        /** Build the configured {@link HearingAidInfo} */
        public HearingAidInfo build() {
            return new HearingAidInfo(mSide, mMode, mHiSyncId);
        }
    }

    private static final int LE_AUDIO_LOCATION_LEFT =
            BluetoothLeAudio.AUDIO_LOCATION_FRONT_LEFT
                    | BluetoothLeAudio.AUDIO_LOCATION_BACK_LEFT
                    | BluetoothLeAudio.AUDIO_LOCATION_FRONT_LEFT_OF_CENTER
                    | BluetoothLeAudio.AUDIO_LOCATION_SIDE_LEFT
                    | BluetoothLeAudio.AUDIO_LOCATION_TOP_FRONT_LEFT
                    | BluetoothLeAudio.AUDIO_LOCATION_TOP_BACK_LEFT
                    | BluetoothLeAudio.AUDIO_LOCATION_TOP_SIDE_LEFT
                    | BluetoothLeAudio.AUDIO_LOCATION_BOTTOM_FRONT_LEFT
                    | BluetoothLeAudio.AUDIO_LOCATION_FRONT_LEFT_WIDE
                    | BluetoothLeAudio.AUDIO_LOCATION_LEFT_SURROUND;

    private static final int LE_AUDIO_LOCATION_RIGHT =
            BluetoothLeAudio.AUDIO_LOCATION_FRONT_RIGHT
                    | BluetoothLeAudio.AUDIO_LOCATION_BACK_RIGHT
                    | BluetoothLeAudio.AUDIO_LOCATION_FRONT_RIGHT_OF_CENTER
                    | BluetoothLeAudio.AUDIO_LOCATION_SIDE_RIGHT
                    | BluetoothLeAudio.AUDIO_LOCATION_TOP_FRONT_RIGHT
                    | BluetoothLeAudio.AUDIO_LOCATION_TOP_BACK_RIGHT
                    | BluetoothLeAudio.AUDIO_LOCATION_TOP_SIDE_RIGHT
                    | BluetoothLeAudio.AUDIO_LOCATION_BOTTOM_FRONT_RIGHT
                    | BluetoothLeAudio.AUDIO_LOCATION_FRONT_RIGHT_WIDE
                    | BluetoothLeAudio.AUDIO_LOCATION_RIGHT_SURROUND;

    private static final SparseIntArray ASHA_DEVICE_SIDE_TO_INTERNAL_SIDE_MAPPING;
    private static final SparseIntArray ASHA_DEVICE_MODE_TO_INTERNAL_MODE_MAPPING;
    private static final SparseIntArray HAP_DEVICE_TYPE_TO_INTERNAL_MODE_MAPPING;

    static {
        ASHA_DEVICE_SIDE_TO_INTERNAL_SIDE_MAPPING = new SparseIntArray();
        ASHA_DEVICE_SIDE_TO_INTERNAL_SIDE_MAPPING.put(
                HearingAidProfile.DeviceSide.SIDE_INVALID, DeviceSide.SIDE_INVALID);
        ASHA_DEVICE_SIDE_TO_INTERNAL_SIDE_MAPPING.put(
                HearingAidProfile.DeviceSide.SIDE_LEFT, DeviceSide.SIDE_LEFT);
        ASHA_DEVICE_SIDE_TO_INTERNAL_SIDE_MAPPING.put(
                HearingAidProfile.DeviceSide.SIDE_RIGHT, DeviceSide.SIDE_RIGHT);

        ASHA_DEVICE_MODE_TO_INTERNAL_MODE_MAPPING = new SparseIntArray();
        ASHA_DEVICE_MODE_TO_INTERNAL_MODE_MAPPING.put(
                HearingAidProfile.DeviceMode.MODE_INVALID, DeviceMode.MODE_INVALID);
        ASHA_DEVICE_MODE_TO_INTERNAL_MODE_MAPPING.put(
                HearingAidProfile.DeviceMode.MODE_MONAURAL, DeviceMode.MODE_MONAURAL);
        ASHA_DEVICE_MODE_TO_INTERNAL_MODE_MAPPING.put(
                HearingAidProfile.DeviceMode.MODE_BINAURAL, DeviceMode.MODE_BINAURAL);

        HAP_DEVICE_TYPE_TO_INTERNAL_MODE_MAPPING = new SparseIntArray();
        HAP_DEVICE_TYPE_TO_INTERNAL_MODE_MAPPING.put(
                HapClientProfile.HearingAidType.TYPE_INVALID, DeviceMode.MODE_INVALID);
        HAP_DEVICE_TYPE_TO_INTERNAL_MODE_MAPPING.put(
                HapClientProfile.HearingAidType.TYPE_BINAURAL, DeviceMode.MODE_BINAURAL);
        HAP_DEVICE_TYPE_TO_INTERNAL_MODE_MAPPING.put(
                HapClientProfile.HearingAidType.TYPE_MONAURAL, DeviceMode.MODE_MONAURAL);
        HAP_DEVICE_TYPE_TO_INTERNAL_MODE_MAPPING.put(
                HapClientProfile.HearingAidType.TYPE_BANDED, DeviceMode.MODE_BANDED);
        HAP_DEVICE_TYPE_TO_INTERNAL_MODE_MAPPING.put(
                HapClientProfile.HearingAidType.TYPE_RFU, DeviceMode.MODE_INVALID);

    }
}
