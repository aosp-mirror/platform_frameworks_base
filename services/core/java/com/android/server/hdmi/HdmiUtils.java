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

package com.android.server.hdmi;

import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecMessage;
import android.util.Slog;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Various utilities to handle HDMI CEC messages.
 */
final class HdmiUtils {

    private HdmiUtils() { /* cannot be instantiated */ }

    /**
     * Verify if the given address is for the given device type.  If not it will throw
     * {@link IllegalArgumentException}.
     *
     * @param logicalAddress the logical address to verify
     * @param deviceType the device type to check
     * @throw IllegalArgumentException
     */
    static void verifyAddressType(int logicalAddress, int deviceType) {
        int actualDeviceType = HdmiCec.getTypeFromAddress(logicalAddress);
        if (actualDeviceType != deviceType) {
            throw new IllegalArgumentException("Device type missmatch:[Expected:" + deviceType
                    + ", Actual:" + actualDeviceType);
        }
    }

    /**
     * Check if the given CEC message come from the given address.
     *
     * @param cmd the CEC message to check
     * @param expectedAddress the expected source address of the given message
     * @param tag the tag of caller module (for log message)
     * @return true if the CEC message comes from the given address
     */
    static boolean checkCommandSource(HdmiCecMessage cmd, int expectedAddress, String tag) {
        int src = cmd.getSource();
        if (src != expectedAddress) {
            Slog.w(tag, "Invalid source [Expected:" + expectedAddress + ", Actual:" + src + "]");
            return false;
        }
        return true;
    }

    /**
     * Parse the parameter block of CEC message as [System Audio Status].
     *
     * @param cmd the CEC message to parse
     * @return true if the given parameter has [ON] value
     */
    static boolean parseCommandParamSystemAudioStatus(HdmiCecMessage cmd) {
        // TODO: Handle the exception when the length is wrong.
        return cmd.getParams().length > 0
                && cmd.getParams()[0] == HdmiConstants.SYSTEM_AUDIO_STATUS_ON;
    }

    /**
     * Convert integer array to list of {@link Integer}.
     *
     * <p>The result is immutable.
     *
     * @param is integer array
     * @return {@link List} instance containing the elements in the given array
     */
    static List<Integer> asImmutableList(final int[] is) {
        ArrayList<Integer> list = new ArrayList<>(is.length);
        for (int type : is) {
            list.add(type);
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * Assemble two bytes into single integer value.
     *
     * @param data to be assembled
     * @return assembled value
     */
    static int twoBytesToInt(byte[] data) {
        return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    }

    /**
     * Assemble three bytes into single integer value.
     *
     * @param data to be assembled
     * @return assembled value
     */
    static int threeBytesToInt(byte[] data) {
        return ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
    }

    static <T> List<T> sparseArrayToList(SparseArray<T> array) {
        ArrayList<T> list = new ArrayList<>();
        for (int i = 0; i < array.size(); ++i) {
            list.add(array.valueAt(i));
        }
        return list;
    }

}
