/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.hdmi;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Various utilities related to HDMI CEC.
 *
 * TODO(b/110094868): unhide for Q
 * @hide
 */
public final class HdmiUtils {
    /**
     * Return value of {@link #getLocalPortFromPhysicalAddress(int, int)}
     */
    static final int TARGET_NOT_UNDER_LOCAL_DEVICE = -1;
    static final int TARGET_SAME_PHYSICAL_ADDRESS = 0;

    private HdmiUtils() { /* cannot be instantiated */ }

    /**
     * Method to parse target physical address to the port number on the current device.
     *
     * <p>This check assumes target address is valid.
     *
     * @param targetPhysicalAddress is the physical address of the target device
     * @param myPhysicalAddress is the physical address of the current device
     * @return
     * If the target device is under the current device, return the port number of current device
     * that the target device is connected to. This also applies to the devices that are indirectly
     * connected to the current device.
     *
     * <p>If the target device has the same physical address as the current device, return
     * {@link #TARGET_SAME_PHYSICAL_ADDRESS}.
     *
     * <p>If the target device is not under the current device, return
     * {@link #TARGET_NOT_UNDER_LOCAL_DEVICE}.
     */
    public static int getLocalPortFromPhysicalAddress(
            int targetPhysicalAddress, int myPhysicalAddress) {
        if (myPhysicalAddress == targetPhysicalAddress) {
            return TARGET_SAME_PHYSICAL_ADDRESS;
        }

        int mask = 0xF000;
        int finalMask = 0xF000;
        int maskedAddress = myPhysicalAddress;

        while (maskedAddress != 0) {
            maskedAddress = myPhysicalAddress & mask;
            finalMask |= mask;
            mask >>= 4;
        }

        int portAddress = targetPhysicalAddress & finalMask;
        if ((portAddress & (finalMask << 4)) != myPhysicalAddress) {
            return TARGET_NOT_UNDER_LOCAL_DEVICE;
        }

        mask <<= 4;
        int port = portAddress & mask;
        while ((port >> 4) != 0) {
            port >>= 4;
        }
        return port;
    }

    /**
     * TODO(b/110094868): unhide for Q
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HDMI_RELATIVE_POSITION_UNKNOWN, HDMI_RELATIVE_POSITION_DIRECTLY_BELOW,
            HDMI_RELATIVE_POSITION_BELOW, HDMI_RELATIVE_POSITION_SAME,
            HDMI_RELATIVE_POSITION_DIRECTLY_ABOVE, HDMI_RELATIVE_POSITION_ABOVE,
            HDMI_RELATIVE_POSITION_SIBLING, HDMI_RELATIVE_POSITION_DIFFERENT_BRANCH})
    public @interface HdmiAddressRelativePosition {}
    /**
     * HDMI relative position is not determined.
     * TODO(b/110094868): unhide for Q
     * @hide
     */
    public static final int HDMI_RELATIVE_POSITION_UNKNOWN = 0;
    /**
     * HDMI relative position: directly blow the device.
     * TODO(b/110094868): unhide for Q
     * @hide
     */
    public static final int HDMI_RELATIVE_POSITION_DIRECTLY_BELOW = 1;
    /**
     * HDMI relative position: indirectly below the device.
     * TODO(b/110094868): unhide for Q
     * @hide
     */
    public static final int HDMI_RELATIVE_POSITION_BELOW = 2;
    /**
     * HDMI relative position: the same device.
     * TODO(b/110094868): unhide for Q
     * @hide
     */
    public static final int HDMI_RELATIVE_POSITION_SAME = 3;
    /**
     * HDMI relative position: directly above the device.
     * TODO(b/110094868): unhide for Q
     * @hide
     */
    public static final int HDMI_RELATIVE_POSITION_DIRECTLY_ABOVE = 4;
    /**
     * HDMI relative position: indirectly above the device.
     * TODO(b/110094868): unhide for Q
     * @hide
     */
    public static final int HDMI_RELATIVE_POSITION_ABOVE = 5;
    /**
     * HDMI relative position: directly below a same device.
     * TODO(b/110094868): unhide for Q
     * @hide
     */
    public static final int HDMI_RELATIVE_POSITION_SIBLING = 6;
    /**
     * HDMI relative position: different branch.
     * TODO(b/110094868): unhide for Q
     * @hide
     */
    public static final int HDMI_RELATIVE_POSITION_DIFFERENT_BRANCH = 7;

    private static final int NPOS = -1;

    /**
     * Check if the given physical address is valid.
     *
     * @param address physical address
     * @return {@code true} if the given address is valid
     */
    public static boolean isValidPhysicalAddress(int address) {
        if (address < 0 || address >= 0xFFFF) {
            return false;
        }
        int mask = 0xF000;
        boolean hasZero = false;
        for (int i = 0; i < 4; i++) {
            if ((address & mask) == 0) {
                hasZero = true;
            } else if (hasZero) {
                // only 0s are valid after a 0.
                // e.g. 0x1012 is not valid.
                return false;
            }
            mask >>= 4;
        }
        return true;
    }


    /**
     * Returns the relative position of two physical addresses.
     */
    @HdmiAddressRelativePosition
    public static int getHdmiAddressRelativePosition(int src, int dest) {
        if (src == 0xFFFF || dest == 0xFFFF) {
            // address not assigned
            return HDMI_RELATIVE_POSITION_UNKNOWN;
        }
        try {
            int firstDiffPos = physicalAddressFirstDifferentDigitPos(src, dest);
            if (firstDiffPos == NPOS) {
                return HDMI_RELATIVE_POSITION_SAME;
            }
            int mask = (0xF000 >> (firstDiffPos * 4));
            int nextPos = firstDiffPos + 1;
            if ((src & mask) == 0) {
                // src is above dest
                if (nextPos == 4) {
                    // last digits are different
                    return HDMI_RELATIVE_POSITION_DIRECTLY_ABOVE;
                }
                if (((0xF000 >> (nextPos * 4)) & dest) == 0) {
                    // next digit is 0
                    return HDMI_RELATIVE_POSITION_DIRECTLY_ABOVE;
                }
                return HDMI_RELATIVE_POSITION_ABOVE;
            }

            if ((dest & mask) == 0) {
                // src is below dest
                if (nextPos == 4) {
                    // last digits are different
                    return HDMI_RELATIVE_POSITION_DIRECTLY_BELOW;
                }
                if (((0xF000 >> (nextPos * 4)) & src) == 0) {
                    // next digit is 0
                    return HDMI_RELATIVE_POSITION_DIRECTLY_BELOW;
                }
                return HDMI_RELATIVE_POSITION_BELOW;
            }
            if (nextPos == 4) {
                // last digits are different
                return HDMI_RELATIVE_POSITION_SIBLING;
            }
            if (((0xF000 >> (nextPos * 4)) & src) == 0 && ((0xF000 >> (nextPos * 4)) & dest) == 0) {
                return HDMI_RELATIVE_POSITION_SIBLING;
            }
            return HDMI_RELATIVE_POSITION_DIFFERENT_BRANCH;
        } catch (IllegalArgumentException e) {
            // invalid address
            return HDMI_RELATIVE_POSITION_UNKNOWN;
        }
    }

    private static int physicalAddressFirstDifferentDigitPos(int address1, int address2)
            throws IllegalArgumentException {
        if (!isValidPhysicalAddress(address1)) {
            throw new IllegalArgumentException(address1 + " is not a valid address.");
        }
        if (!isValidPhysicalAddress(address2)) {
            throw new IllegalArgumentException(address2 + " is not a valid address.");
        }
        int mask = 0xF000;
        for (int i = 0; i < 4; i++) {
            if ((address1 & mask) != (address2 & mask)) {
                return i;
            }
            mask = mask >> 4;
        }
        return NPOS;
    }
}
