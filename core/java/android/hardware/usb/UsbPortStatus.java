/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.hardware.usb;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.usb.V1_0.Constants;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.Immutable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes the status of a USB port.
 *
 * @hide
 */
@Immutable
@SystemApi
public final class UsbPortStatus implements Parcelable {
    private final int mCurrentMode;
    private final @UsbPowerRole int mCurrentPowerRole;
    private final @UsbDataRole int mCurrentDataRole;
    private final int mSupportedRoleCombinations;
    private final @ContaminantProtectionStatus int mContaminantProtectionStatus;
    private final @ContaminantDetectionStatus int mContaminantDetectionStatus;

    /**
     * Power role: This USB port does not have a power role.
     */
    public static final int POWER_ROLE_NONE = Constants.PortPowerRole.NONE;

    /**
     * Power role: This USB port can act as a source (provide power).
     */
    public static final int POWER_ROLE_SOURCE = Constants.PortPowerRole.SOURCE;

    /**
     * Power role: This USB port can act as a sink (receive power).
     */
    public static final int POWER_ROLE_SINK = Constants.PortPowerRole.SINK;

    @IntDef(prefix = { "POWER_ROLE_" }, value = {
            POWER_ROLE_NONE,
            POWER_ROLE_SOURCE,
            POWER_ROLE_SINK
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface UsbPowerRole{}

    /**
     * Power role: This USB port does not have a data role.
     */
    public static final int DATA_ROLE_NONE = Constants.PortDataRole.NONE;

    /**
     * Data role: This USB port can act as a host (access data services).
     */
    public static final int DATA_ROLE_HOST = Constants.PortDataRole.HOST;

    /**
     * Data role: This USB port can act as a device (offer data services).
     */
    public static final int DATA_ROLE_DEVICE = Constants.PortDataRole.DEVICE;

    @IntDef(prefix = { "DATA_ROLE_" }, value = {
            DATA_ROLE_NONE,
            DATA_ROLE_HOST,
            DATA_ROLE_DEVICE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface UsbDataRole{}

    /**
     * There is currently nothing connected to this USB port.
     */
    public static final int MODE_NONE = Constants.PortMode.NONE;

    /**
     * This USB port can act as a downstream facing port (host).
     *
     * <p> Implies that the port supports the {@link #POWER_ROLE_SOURCE} and
     * {@link #DATA_ROLE_HOST} combination of roles (and possibly others as well).
     */
    public static final int MODE_DFP = Constants.PortMode.DFP;

    /**
     * This USB port can act as an upstream facing port (device).
     *
     * <p> Implies that the port supports the {@link #POWER_ROLE_SINK} and
     * {@link #DATA_ROLE_DEVICE} combination of roles (and possibly others as well).
     */
    public static final int MODE_UFP = Constants.PortMode.UFP;

    /**
     * This USB port can act either as an downstream facing port (host) or as
     * an upstream facing port (device).
     *
     * <p> Implies that the port supports the {@link #POWER_ROLE_SOURCE} and
     * {@link #DATA_ROLE_HOST} combination of roles and the {@link #POWER_ROLE_SINK} and
     * {@link #DATA_ROLE_DEVICE} combination of roles (and possibly others as well).
     *
     * @hide
     */
    public static final int MODE_DUAL = Constants.PortMode.DRP;

    /**
     * This USB port can support USB Type-C Audio accessory.
     */
    public static final int MODE_AUDIO_ACCESSORY =
            android.hardware.usb.V1_1.Constants.PortMode_1_1.AUDIO_ACCESSORY;

    /**
     * This USB port can support USB Type-C debug accessory.
     */
    public static final int MODE_DEBUG_ACCESSORY =
            android.hardware.usb.V1_1.Constants.PortMode_1_1.DEBUG_ACCESSORY;

   /**
     * Contaminant presence detection not supported by the device.
     * @hide
     */
    public static final int CONTAMINANT_DETECTION_NOT_SUPPORTED =
            android.hardware.usb.V1_2.Constants.ContaminantDetectionStatus.NOT_SUPPORTED;

    /**
     * Contaminant presence detection supported but disabled.
     * @hide
     */
    public static final int CONTAMINANT_DETECTION_DISABLED =
            android.hardware.usb.V1_2.Constants.ContaminantDetectionStatus.DISABLED;

    /**
     * Contaminant presence enabled but not detected.
     * @hide
     */
    public static final int CONTAMINANT_DETECTION_NOT_DETECTED =
            android.hardware.usb.V1_2.Constants.ContaminantDetectionStatus.NOT_DETECTED;

    /**
     * Contaminant presence enabled and detected.
     * @hide
     */
    public static final int CONTAMINANT_DETECTION_DETECTED =
            android.hardware.usb.V1_2.Constants.ContaminantDetectionStatus.DETECTED;

    /**
     * Contaminant protection - No action performed upon detection of
     * contaminant presence.
     * @hide
     */
    public static final int CONTAMINANT_PROTECTION_NONE =
            android.hardware.usb.V1_2.Constants.ContaminantProtectionStatus.NONE;

    /**
     * Contaminant protection - Port is forced to sink upon detection of
     * contaminant presence.
     * @hide
     */
    public static final int CONTAMINANT_PROTECTION_SINK =
            android.hardware.usb.V1_2.Constants.ContaminantProtectionStatus.FORCE_SINK;

    /**
     * Contaminant protection - Port is forced to source upon detection of
     * contaminant presence.
     * @hide
     */
    public static final int CONTAMINANT_PROTECTION_SOURCE =
            android.hardware.usb.V1_2.Constants.ContaminantProtectionStatus.FORCE_SOURCE;

    /**
     * Contaminant protection - Port is disabled upon detection of
     * contaminant presence.
     * @hide
     */
    public static final int CONTAMINANT_PROTECTION_FORCE_DISABLE =
            android.hardware.usb.V1_2.Constants.ContaminantProtectionStatus.FORCE_DISABLE;

    /**
     * Contaminant protection - Port is disabled upon detection of
     * contaminant presence.
     * @hide
     */
    public static final int CONTAMINANT_PROTECTION_DISABLED =
            android.hardware.usb.V1_2.Constants.ContaminantProtectionStatus.DISABLED;

    @IntDef(prefix = { "CONTAMINANT_DETECTION_" }, value = {
            CONTAMINANT_DETECTION_NOT_SUPPORTED,
            CONTAMINANT_DETECTION_DISABLED,
            CONTAMINANT_DETECTION_NOT_DETECTED,
            CONTAMINANT_DETECTION_DETECTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ContaminantDetectionStatus{}

    @IntDef(prefix = { "CONTAMINANT_PROTECTION_" }, flag = true, value = {
            CONTAMINANT_PROTECTION_NONE,
            CONTAMINANT_PROTECTION_SINK,
            CONTAMINANT_PROTECTION_SOURCE,
            CONTAMINANT_PROTECTION_FORCE_DISABLE,
            CONTAMINANT_PROTECTION_DISABLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ContaminantProtectionStatus{}

    @IntDef(prefix = { "MODE_" }, value = {
            MODE_NONE,
            MODE_DFP,
            MODE_UFP,
            MODE_AUDIO_ACCESSORY,
            MODE_DEBUG_ACCESSORY,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface UsbPortMode{}

    /** @hide */
    public UsbPortStatus(int currentMode, int currentPowerRole, int currentDataRole,
            int supportedRoleCombinations, int contaminantProtectionStatus,
            int contaminantDetectionStatus) {
        mCurrentMode = currentMode;
        mCurrentPowerRole = currentPowerRole;
        mCurrentDataRole = currentDataRole;
        mSupportedRoleCombinations = supportedRoleCombinations;
        mContaminantProtectionStatus = contaminantProtectionStatus;
        mContaminantDetectionStatus = contaminantDetectionStatus;
    }

    /**
     * Returns true if there is anything connected to the port.
     *
     * @return {@code true} iff there is anything connected to the port.
     */
    public boolean isConnected() {
        return mCurrentMode != 0;
    }

    /**
     * Gets the current mode of the port.
     *
     * @return The current mode: {@link #MODE_DFP}, {@link #MODE_UFP},
     * {@link #MODE_AUDIO_ACCESSORY}, {@link #MODE_DEBUG_ACCESSORY}, or {@link {@link #MODE_NONE} if
     * nothing is connected.
     */
    public @UsbPortMode int getCurrentMode() {
        return mCurrentMode;
    }

    /**
     * Gets the current power role of the port.
     *
     * @return The current power role: {@link #POWER_ROLE_SOURCE}, {@link #POWER_ROLE_SINK}, or
     * {@link #POWER_ROLE_NONE} if nothing is connected.
     */
    public @UsbPowerRole int getCurrentPowerRole() {
        return mCurrentPowerRole;
    }

    /**
     * Gets the current data role of the port.
     *
     * @return The current data role: {@link #DATA_ROLE_HOST}, {@link #DATA_ROLE_DEVICE}, or
     * {@link #DATA_ROLE_NONE} if nothing is connected.
     */
    public @UsbDataRole int getCurrentDataRole() {
        return mCurrentDataRole;
    }

    /**
     * Returns true if the specified power and data role combination is supported
     * given what is currently connected to the port.
     *
     * @param powerRole The power role to check: {@link #POWER_ROLE_SOURCE}  or
     *                  {@link #POWER_ROLE_SINK}, or {@link #POWER_ROLE_NONE} if no power role.
     * @param dataRole  The data role to check: either {@link #DATA_ROLE_HOST} or
     *                  {@link #DATA_ROLE_DEVICE}, or {@link #DATA_ROLE_NONE} if no data role.
     */
    public boolean isRoleCombinationSupported(@UsbPowerRole int powerRole,
            @UsbDataRole int dataRole) {
        return (mSupportedRoleCombinations &
                UsbPort.combineRolesAsBit(powerRole, dataRole)) != 0;
    }

    /**
     * Get the supported role combinations.
     */
    public int getSupportedRoleCombinations() {
        return mSupportedRoleCombinations;
    }

    /**
     * Returns contaminant detection status.
     *
     * @hide
     */
    public @ContaminantDetectionStatus int getContaminantDetectionStatus() {
        return mContaminantDetectionStatus;
    }

    /**
     * Returns contamiant protection status.
     *
     * @hide
     */
    public @ContaminantProtectionStatus int getContaminantProtectionStatus() {
        return mContaminantProtectionStatus;
    }

    @NonNull
    @Override
    public String toString() {
        return "UsbPortStatus{connected=" + isConnected()
                + ", currentMode=" + UsbPort.modeToString(mCurrentMode)
                + ", currentPowerRole=" + UsbPort.powerRoleToString(mCurrentPowerRole)
                + ", currentDataRole=" + UsbPort.dataRoleToString(mCurrentDataRole)
                + ", supportedRoleCombinations="
                        + UsbPort.roleCombinationsToString(mSupportedRoleCombinations)
                + ", contaminantDetectionStatus="
                        + getContaminantDetectionStatus()
                + ", contaminantProtectionStatus="
                        + getContaminantProtectionStatus()
                + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCurrentMode);
        dest.writeInt(mCurrentPowerRole);
        dest.writeInt(mCurrentDataRole);
        dest.writeInt(mSupportedRoleCombinations);
        dest.writeInt(mContaminantProtectionStatus);
        dest.writeInt(mContaminantDetectionStatus);
    }

    public static final @NonNull Parcelable.Creator<UsbPortStatus> CREATOR =
            new Parcelable.Creator<UsbPortStatus>() {
        @Override
        public UsbPortStatus createFromParcel(Parcel in) {
            int currentMode = in.readInt();
            int currentPowerRole = in.readInt();
            int currentDataRole = in.readInt();
            int supportedRoleCombinations = in.readInt();
            int contaminantProtectionStatus = in.readInt();
            int contaminantDetectionStatus = in.readInt();
            return new UsbPortStatus(currentMode, currentPowerRole, currentDataRole,
                    supportedRoleCombinations, contaminantProtectionStatus,
                    contaminantDetectionStatus);
        }

        @Override
        public UsbPortStatus[] newArray(int size) {
            return new UsbPortStatus[size];
        }
    };
}
