/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.usb.hal.port;

import android.hardware.usb.UsbPortStatus;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Used for storing the raw data from the HAL.
 * Values of the member variables mocked directly in case of emulation.
 */
public final class RawPortInfo implements Parcelable {
    public final String portId;
    public final int supportedModes;
    public final int supportedContaminantProtectionModes;
    public int currentMode;
    public boolean canChangeMode;
    public int currentPowerRole;
    public boolean canChangePowerRole;
    public int currentDataRole;
    public boolean canChangeDataRole;
    public boolean supportsEnableContaminantPresenceProtection;
    public int contaminantProtectionStatus;
    public boolean supportsEnableContaminantPresenceDetection;
    public int contaminantDetectionStatus;
    public int usbDataStatus;
    public boolean powerTransferLimited;
    public int powerBrickConnectionStatus;

    public RawPortInfo(String portId, int supportedModes) {
        this.portId = portId;
        this.supportedModes = supportedModes;
        this.supportedContaminantProtectionModes = UsbPortStatus.CONTAMINANT_PROTECTION_NONE;
        this.supportsEnableContaminantPresenceProtection = false;
        this.contaminantProtectionStatus = UsbPortStatus.CONTAMINANT_PROTECTION_NONE;
        this.supportsEnableContaminantPresenceDetection = false;
        this.contaminantDetectionStatus = UsbPortStatus.CONTAMINANT_DETECTION_NOT_SUPPORTED;
        this.usbDataStatus = UsbPortStatus.DATA_STATUS_UNKNOWN;

        this.powerTransferLimited = false;
        this.powerBrickConnectionStatus = UsbPortStatus.POWER_BRICK_STATUS_UNKNOWN;
    }

    public RawPortInfo(String portId, int supportedModes, int supportedContaminantProtectionModes,
            int currentMode, boolean canChangeMode,
            int currentPowerRole, boolean canChangePowerRole,
            int currentDataRole, boolean canChangeDataRole,
            boolean supportsEnableContaminantPresenceProtection,
            int contaminantProtectionStatus,
            boolean supportsEnableContaminantPresenceDetection,
            int contaminantDetectionStatus,
            int usbDataStatus,
            boolean powerTransferLimited,
            int powerBrickConnectionStatus) {
        this.portId = portId;
        this.supportedModes = supportedModes;
        this.supportedContaminantProtectionModes = supportedContaminantProtectionModes;
        this.currentMode = currentMode;
        this.canChangeMode = canChangeMode;
        this.currentPowerRole = currentPowerRole;
        this.canChangePowerRole = canChangePowerRole;
        this.currentDataRole = currentDataRole;
        this.canChangeDataRole = canChangeDataRole;
        this.supportsEnableContaminantPresenceProtection =
                supportsEnableContaminantPresenceProtection;
        this.contaminantProtectionStatus = contaminantProtectionStatus;
        this.supportsEnableContaminantPresenceDetection =
                supportsEnableContaminantPresenceDetection;
        this.contaminantDetectionStatus = contaminantDetectionStatus;
        this.usbDataStatus = usbDataStatus;
        this.powerTransferLimited = powerTransferLimited;
        this.powerBrickConnectionStatus = powerBrickConnectionStatus;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(portId);
        dest.writeInt(supportedModes);
        dest.writeInt(supportedContaminantProtectionModes);
        dest.writeInt(currentMode);
        dest.writeByte((byte) (canChangeMode ? 1 : 0));
        dest.writeInt(currentPowerRole);
        dest.writeByte((byte) (canChangePowerRole ? 1 : 0));
        dest.writeInt(currentDataRole);
        dest.writeByte((byte) (canChangeDataRole ? 1 : 0));
        dest.writeBoolean(supportsEnableContaminantPresenceProtection);
        dest.writeInt(contaminantProtectionStatus);
        dest.writeBoolean(supportsEnableContaminantPresenceDetection);
        dest.writeInt(contaminantDetectionStatus);
        dest.writeInt(usbDataStatus);
        dest.writeBoolean(powerTransferLimited);
        dest.writeInt(powerBrickConnectionStatus);
    }

    public static final Parcelable.Creator<RawPortInfo> CREATOR =
            new Parcelable.Creator<RawPortInfo>() {
        @Override
        public RawPortInfo createFromParcel(Parcel in) {
            String id = in.readString();
            int supportedModes = in.readInt();
            int supportedContaminantProtectionModes = in.readInt();
            int currentMode = in.readInt();
            boolean canChangeMode = in.readByte() != 0;
            int currentPowerRole = in.readInt();
            boolean canChangePowerRole = in.readByte() != 0;
            int currentDataRole = in.readInt();
            boolean canChangeDataRole = in.readByte() != 0;
            boolean supportsEnableContaminantPresenceProtection = in.readBoolean();
            int contaminantProtectionStatus = in.readInt();
            boolean supportsEnableContaminantPresenceDetection = in.readBoolean();
            int contaminantDetectionStatus = in.readInt();
            int usbDataStatus = in.readInt();
            boolean powerTransferLimited = in.readBoolean();
            int powerBrickConnectionStatus = in.readInt();
            return new RawPortInfo(id, supportedModes,
                    supportedContaminantProtectionModes, currentMode, canChangeMode,
                    currentPowerRole, canChangePowerRole,
                    currentDataRole, canChangeDataRole,
                    supportsEnableContaminantPresenceProtection,
                    contaminantProtectionStatus,
                    supportsEnableContaminantPresenceDetection,
                    contaminantDetectionStatus, usbDataStatus,
                    powerTransferLimited, powerBrickConnectionStatus);
        }

        @Override
        public RawPortInfo[] newArray(int size) {
            return new RawPortInfo[size];
        }
    };
}
