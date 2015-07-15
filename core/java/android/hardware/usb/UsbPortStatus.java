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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes the status of a USB port.
 * <p>
 * This object is immutable.
 * </p>
 *
 * @hide
 */
public final class UsbPortStatus implements Parcelable {
    private final int mCurrentMode;
    private final int mCurrentPowerRole;
    private final int mCurrentDataRole;
    private final int mSupportedRoleCombinations;

    /** @hide */
    public UsbPortStatus(int currentMode, int currentPowerRole, int currentDataRole,
            int supportedRoleCombinations) {
        mCurrentMode = currentMode;
        mCurrentPowerRole = currentPowerRole;
        mCurrentDataRole = currentDataRole;
        mSupportedRoleCombinations = supportedRoleCombinations;
    }

    /**
     * Returns true if there is anything connected to the port.
     *
     * @return True if there is anything connected to the port.
     */
    public boolean isConnected() {
        return mCurrentMode != 0;
    }

    /**
     * Gets the current mode of the port.
     *
     * @return The current mode: {@link UsbPort#MODE_DFP}, {@link UsbPort#MODE_UFP},
     * or 0 if nothing is connected.
     */
    public int getCurrentMode() {
        return mCurrentMode;
    }

    /**
     * Gets the current power role of the port.
     *
     * @return The current power role: {@link UsbPort#POWER_ROLE_SOURCE},
     * {@link UsbPort#POWER_ROLE_SINK}, or 0 if nothing is connected.
     */
    public int getCurrentPowerRole() {
        return mCurrentPowerRole;
    }

    /**
     * Gets the current data role of the port.
     *
     * @return The current data role: {@link UsbPort#DATA_ROLE_HOST},
     * {@link UsbPort#DATA_ROLE_DEVICE}, or 0 if nothing is connected.
     */
    public int getCurrentDataRole() {
        return mCurrentDataRole;
    }

    /**
     * Returns true if the specified power and data role combination is supported
     * given what is currently connected to the port.
     *
     * @param powerRole The power role to check: {@link UsbPort#POWER_ROLE_SOURCE}
     * or {@link UsbPort#POWER_ROLE_SINK}, or 0 if no power role.
     * @param dataRole The data role to check: either {@link UsbPort#DATA_ROLE_HOST}
     * or {@link UsbPort#DATA_ROLE_DEVICE}, or 0 if no data role.
     */
    public boolean isRoleCombinationSupported(int powerRole, int dataRole) {
        return (mSupportedRoleCombinations &
                UsbPort.combineRolesAsBit(powerRole, dataRole)) != 0;
    }

    /** @hide */
    public int getSupportedRoleCombinations() {
        return mSupportedRoleCombinations;
    }

    @Override
    public String toString() {
        return "UsbPortStatus{connected=" + isConnected()
                + ", currentMode=" + UsbPort.modeToString(mCurrentMode)
                + ", currentPowerRole=" + UsbPort.powerRoleToString(mCurrentPowerRole)
                + ", currentDataRole=" + UsbPort.dataRoleToString(mCurrentDataRole)
                + ", supportedRoleCombinations="
                        + UsbPort.roleCombinationsToString(mSupportedRoleCombinations)
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
    }

    public static final Parcelable.Creator<UsbPortStatus> CREATOR =
            new Parcelable.Creator<UsbPortStatus>() {
        @Override
        public UsbPortStatus createFromParcel(Parcel in) {
            int currentMode = in.readInt();
            int currentPowerRole = in.readInt();
            int currentDataRole = in.readInt();
            int supportedRoleCombinations = in.readInt();
            return new UsbPortStatus(currentMode, currentPowerRole, currentDataRole,
                    supportedRoleCombinations);
        }

        @Override
        public UsbPortStatus[] newArray(int size) {
            return new UsbPortStatus[size];
        }
    };
}
