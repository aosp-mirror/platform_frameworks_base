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

import static android.hardware.usb.UsbPortStatus.CONTAMINANT_DETECTION_DETECTED;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_DETECTION_DISABLED;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_DETECTION_NOT_DETECTED;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_DETECTION_NOT_SUPPORTED;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_DEVICE;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_HOST;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_NONE;
import static android.hardware.usb.UsbPortStatus.MODE_AUDIO_ACCESSORY;
import static android.hardware.usb.UsbPortStatus.MODE_DEBUG_ACCESSORY;
import static android.hardware.usb.UsbPortStatus.MODE_DFP;
import static android.hardware.usb.UsbPortStatus.MODE_DUAL;
import static android.hardware.usb.UsbPortStatus.MODE_NONE;
import static android.hardware.usb.UsbPortStatus.MODE_UFP;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_NONE;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SINK;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SOURCE;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.hardware.usb.V1_0.Constants;

import com.android.internal.util.Preconditions;

/**
 * Represents a physical USB port and describes its characteristics.
 *
 * @hide
 */
@SystemApi
public final class UsbPort {
    private final String mId;
    private final int mSupportedModes;
    private final UsbManager mUsbManager;
    private final int mSupportedContaminantProtectionModes;
    private final boolean mSupportsEnableContaminantPresenceProtection;
    private final boolean mSupportsEnableContaminantPresenceDetection;

    private static final int NUM_DATA_ROLES = Constants.PortDataRole.NUM_DATA_ROLES;
    /**
     * Points to the first power role in the IUsb HAL.
     */
    private static final int POWER_ROLE_OFFSET = Constants.PortPowerRole.NONE;

    /** @hide */
    public UsbPort(@NonNull UsbManager usbManager, @NonNull String id, int supportedModes,
            int supportedContaminantProtectionModes,
            boolean supportsEnableContaminantPresenceProtection,
            boolean supportsEnableContaminantPresenceDetection) {
        Preconditions.checkNotNull(id);
        Preconditions.checkFlagsArgument(supportedModes,
                MODE_DFP | MODE_UFP | MODE_AUDIO_ACCESSORY | MODE_DEBUG_ACCESSORY);

        mUsbManager = usbManager;
        mId = id;
        mSupportedModes = supportedModes;
        mSupportedContaminantProtectionModes = supportedContaminantProtectionModes;
        mSupportsEnableContaminantPresenceProtection =
                supportsEnableContaminantPresenceProtection;
        mSupportsEnableContaminantPresenceDetection =
                supportsEnableContaminantPresenceDetection;
    }

    /**
     * Gets the unique id of the port.
     *
     * @return The unique id of the port; not intended for display.
     *
     * @hide
     */
    public String getId() {
        return mId;
    }

    /**
     * Gets the supported modes of the port.
     * <p>
     * The actual mode of the port may vary depending on what is plugged into it.
     * </p>
     *
     * @return The supported modes: one of {@link UsbPortStatus#MODE_DFP},
     * {@link UsbPortStatus#MODE_UFP}, or {@link UsbPortStatus#MODE_DUAL}.
     *
     * @hide
     */
    public int getSupportedModes() {
        return mSupportedModes;
    }

   /**
     * Gets the supported port proctection modes when the port is contaminated.
     * <p>
     * The actual mode of the port is decided by the hardware
     * </p>
     *
     * @hide
     */
    public int getSupportedContaminantProtectionModes() {
        return mSupportedContaminantProtectionModes;
    }

   /**
     * Tells if UsbService can enable/disable contaminant presence protection.
     *
     * @hide
     */
    public boolean supportsEnableContaminantPresenceProtection() {
        return mSupportsEnableContaminantPresenceProtection;
    }

   /**
     * Tells if UsbService can enable/disable contaminant presence detection.
     *
     * @hide
     */
    public boolean supportsEnableContaminantPresenceDetection() {
        return mSupportsEnableContaminantPresenceDetection;
    }

    /**
     * Gets the status of this USB port.
     *
     * @return The status of the this port, or {@code null} if port is unknown.
     */
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public @Nullable UsbPortStatus getStatus() {
        return mUsbManager.getPortStatus(this);
    }

    /**
     * Sets the desired role combination of the port.
     * <p>
     * The supported role combinations depend on what is connected to the port and may be
     * determined by consulting
     * {@link UsbPortStatus#isRoleCombinationSupported UsbPortStatus.isRoleCombinationSupported}.
     * </p><p>
     * Note: This function is asynchronous and may fail silently without applying
     * the requested changes.  If this function does cause a status change to occur then
     * a {@link UsbManager#ACTION_USB_PORT_CHANGED} broadcast will be sent.
     * </p>
     *
     * @param powerRole The desired power role: {@link UsbPortStatus#POWER_ROLE_SOURCE} or
     *                  {@link UsbPortStatus#POWER_ROLE_SINK}, or
     *                  {@link UsbPortStatus#POWER_ROLE_NONE} if no power role.
     * @param dataRole The desired data role: {@link UsbPortStatus#DATA_ROLE_HOST} or
     *                 {@link UsbPortStatus#DATA_ROLE_DEVICE}, or
     *                 {@link UsbPortStatus#DATA_ROLE_NONE} if no data role.
     */
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public void setRoles(@UsbPortStatus.UsbPowerRole int powerRole,
            @UsbPortStatus.UsbDataRole int dataRole) {
        UsbPort.checkRoles(powerRole, dataRole);

        mUsbManager.setPortRoles(this, powerRole, dataRole);
    }

    /**
     * @hide
     **/
    public void enableContaminantDetection(boolean enable) {
        mUsbManager.enableContaminantDetection(this, enable);
    }
    /**
     * Combines one power and one data role together into a unique value with
     * exactly one bit set.  This can be used to efficiently determine whether
     * a combination of roles is supported by testing whether that bit is present
     * in a bit-field.
     *
     * @param powerRole The desired power role: {@link UsbPortStatus#POWER_ROLE_SOURCE}
     *                  or {@link UsbPortStatus#POWER_ROLE_SINK}, or 0 if no power role.
     * @param dataRole  The desired data role: {@link UsbPortStatus#DATA_ROLE_HOST}
     *                  or {@link UsbPortStatus#DATA_ROLE_DEVICE}, or 0 if no data role.
     * @hide
     */
    public static int combineRolesAsBit(int powerRole, int dataRole) {
        checkRoles(powerRole, dataRole);
        final int index = ((powerRole - POWER_ROLE_OFFSET) * NUM_DATA_ROLES) + dataRole;
        return 1 << index;
    }

    /** @hide */
    public static String modeToString(int mode) {
        StringBuilder modeString = new StringBuilder();
        if (mode == MODE_NONE) {
            return "none";
        }

        if ((mode & MODE_DUAL) == MODE_DUAL) {
            modeString.append("dual, ");
        } else {
            if ((mode & MODE_DFP) == MODE_DFP) {
                modeString.append("dfp, ");
            } else if ((mode & MODE_UFP) == MODE_UFP) {
                modeString.append("ufp, ");
            }
        }
        if ((mode & MODE_AUDIO_ACCESSORY) == MODE_AUDIO_ACCESSORY) {
            modeString.append("audio_acc, ");
        }
        if ((mode & MODE_DEBUG_ACCESSORY) == MODE_DEBUG_ACCESSORY) {
            modeString.append("debug_acc, ");
        }

        if (modeString.length() == 0) {
            return Integer.toString(mode);
        }
        return modeString.substring(0, modeString.length() - 2);
    }

    /** @hide */
    public static String powerRoleToString(int role) {
        switch (role) {
            case POWER_ROLE_NONE:
                return "no-power";
            case POWER_ROLE_SOURCE:
                return "source";
            case POWER_ROLE_SINK:
                return "sink";
            default:
                return Integer.toString(role);
        }
    }

    /** @hide */
    public static String dataRoleToString(int role) {
        switch (role) {
            case DATA_ROLE_NONE:
                return "no-data";
            case DATA_ROLE_HOST:
                return "host";
            case DATA_ROLE_DEVICE:
                return "device";
            default:
                return Integer.toString(role);
        }
    }

    /** @hide */
    public static String contaminantPresenceStatusToString(int contaminantPresenceStatus) {
        switch (contaminantPresenceStatus) {
            case CONTAMINANT_DETECTION_NOT_SUPPORTED:
                return "not-supported";
            case CONTAMINANT_DETECTION_DISABLED:
                return "disabled";
            case CONTAMINANT_DETECTION_DETECTED:
                return "detected";
            case CONTAMINANT_DETECTION_NOT_DETECTED:
                return "not detected";
            default:
                return Integer.toString(contaminantPresenceStatus);
        }
    }

    /** @hide */
    public static String roleCombinationsToString(int combo) {
        StringBuilder result = new StringBuilder();
        result.append("[");

        boolean first = true;
        while (combo != 0) {
            final int index = Integer.numberOfTrailingZeros(combo);
            combo &= ~(1 << index);
            final int powerRole = (index / NUM_DATA_ROLES + POWER_ROLE_OFFSET);
            final int dataRole = index % NUM_DATA_ROLES;
            if (first) {
                first = false;
            } else {
                result.append(", ");
            }
            result.append(powerRoleToString(powerRole));
            result.append(':');
            result.append(dataRoleToString(dataRole));
        }

        result.append("]");
        return result.toString();
    }

    /** @hide */
    public static void checkMode(int powerRole) {
        Preconditions.checkArgumentInRange(powerRole, Constants.PortMode.NONE,
                Constants.PortMode.NUM_MODES - 1, "portMode");
    }

    /** @hide */
    public static void checkPowerRole(int dataRole) {
        Preconditions.checkArgumentInRange(dataRole, Constants.PortPowerRole.NONE,
                Constants.PortPowerRole.NUM_POWER_ROLES - 1, "powerRole");
    }

    /** @hide */
    public static void checkDataRole(int mode) {
        Preconditions.checkArgumentInRange(mode, Constants.PortDataRole.NONE,
                Constants.PortDataRole.NUM_DATA_ROLES - 1, "powerRole");
    }

    /** @hide */
    public static void checkRoles(int powerRole, int dataRole) {
        Preconditions.checkArgumentInRange(powerRole, POWER_ROLE_NONE, POWER_ROLE_SINK,
                "powerRole");
        Preconditions.checkArgumentInRange(dataRole, DATA_ROLE_NONE, DATA_ROLE_DEVICE, "dataRole");
    }

    /** @hide */
    public boolean isModeSupported(int mode) {
        if ((mSupportedModes & mode) == mode) return true;
        return false;
    }

    @NonNull
    @Override
    public String toString() {
        return "UsbPort{id=" + mId + ", supportedModes=" + modeToString(mSupportedModes)
                + "supportedContaminantProtectionModes=" + mSupportedContaminantProtectionModes
                + "supportsEnableContaminantPresenceProtection="
                + mSupportsEnableContaminantPresenceProtection
                + "supportsEnableContaminantPresenceDetection="
                + mSupportsEnableContaminantPresenceDetection;
    }
}
