/*
 * Copyright (C) 2014 The Android Open Source Project
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

/**
 * Various utilities to handle HDMI CEC messages.
 *
 * TODO(b/110094868): unhide for Q
 * @hide
 */
public class HdmiUtils {

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
}
