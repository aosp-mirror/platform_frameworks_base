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

import android.hardware.usb.V1_0.Constants;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Represents a physical USB port and describes its characteristics.
 * <p>
 * This object is immutable.
 * </p>
 *
 * @hide
 */
public final class UsbPort implements Parcelable {
    private final String mId;
    private final int mSupportedModes;

    public static final int MODE_NONE = Constants.PortMode.NONE;
    /**
     * Mode bit: This USB port can act as a downstream facing port (host).
     * <p>
     * Implies that the port supports the {@link #POWER_ROLE_SOURCE} and {@link #DATA_ROLE_HOST}
     * combination of roles (and possibly others as well).
     * </p>
     */
    public static final int MODE_DFP = Constants.PortMode.DFP;

    /**
     * Mode bit: This USB port can act as an upstream facing port (device).
     * <p>
     * Implies that the port supports the {@link #POWER_ROLE_SINK} and {@link #DATA_ROLE_DEVICE}
     * combination of roles (and possibly others as well).
     * </p>
     */
    public static final int MODE_UFP = Constants.PortMode.UFP;

    /**
     * Mode bit: This USB port can act either as an downstream facing port (host) or as
     * an upstream facing port (device).
     * <p>
     * Implies that the port supports the {@link #POWER_ROLE_SOURCE} and {@link #DATA_ROLE_HOST}
     * combination of roles and the {@link #POWER_ROLE_SINK} and {@link #DATA_ROLE_DEVICE}
     * combination of roles (and possibly others as well).
     * </p>
     */
    public static final int MODE_DUAL = Constants.PortMode.DRP;

    /**
     * Mode bit: This USB port can support USB Type-C Audio accessory.
     */
    public static final int MODE_AUDIO_ACCESSORY =
            android.hardware.usb.V1_1.Constants.PortMode_1_1.AUDIO_ACCESSORY;

    /**
     * Mode bit: This USB port can support USB Type-C debug accessory.
     */
    public static final int MODE_DEBUG_ACCESSORY =
            android.hardware.usb.V1_1.Constants.PortMode_1_1.DEBUG_ACCESSORY;

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

    private static final int NUM_DATA_ROLES = Constants.PortDataRole.NUM_DATA_ROLES;
    /**
     * Points to the first power role in the IUsb HAL.
     */
    private static final int POWER_ROLE_OFFSET = Constants.PortPowerRole.NONE;

    /** @hide */
    public UsbPort(String id, int supportedModes) {
        mId = id;
        mSupportedModes = supportedModes;
    }

    /**
     * Gets the unique id of the port.
     *
     * @return The unique id of the port; not intended for display.
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
     * @return The supported modes: one of {@link #MODE_DFP}, {@link #MODE_UFP}, or
     * {@link #MODE_DUAL}.
     */
    public int getSupportedModes() {
        return mSupportedModes;
    }

    /**
     * Combines one power and one data role together into a unique value with
     * exactly one bit set.  This can be used to efficiently determine whether
     * a combination of roles is supported by testing whether that bit is present
     * in a bit-field.
     *
     * @param powerRole The desired power role: {@link UsbPort#POWER_ROLE_SOURCE}
     *                  or {@link UsbPort#POWER_ROLE_SINK}, or 0 if no power role.
     * @param dataRole  The desired data role: {@link UsbPort#DATA_ROLE_HOST}
     *                  or {@link UsbPort#DATA_ROLE_DEVICE}, or 0 if no data role.
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


    @Override
    public String toString() {
        return "UsbPort{id=" + mId + ", supportedModes=" + modeToString(mSupportedModes) + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeInt(mSupportedModes);
    }

    public static final Parcelable.Creator<UsbPort> CREATOR =
            new Parcelable.Creator<UsbPort>() {
                @Override
                public UsbPort createFromParcel(Parcel in) {
                    String id = in.readString();
                    int supportedModes = in.readInt();
                    return new UsbPort(id, supportedModes);
                }

                @Override
                public UsbPort[] newArray(int size) {
                    return new UsbPort[size];
                }
            };
}
