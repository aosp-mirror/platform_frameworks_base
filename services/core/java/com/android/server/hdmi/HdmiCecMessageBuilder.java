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

import java.io.UnsupportedEncodingException;

/**
 * A helper class to build {@link HdmiCecMessage} from various cec commands.
 */
public class HdmiCecMessageBuilder {
    // TODO: move these values to HdmiCec.java once make it internal constant class.
    // CEC's ABORT reason values.
    static final int ABORT_UNRECOGNIZED_MODE = 0;
    static final int ABORT_NOT_IN_CORRECT_MODE = 1;
    static final int ABORT_CANNOT_PROVIDE_SOURCE = 2;
    static final int ABORT_INVALID_OPERAND = 3;
    static final int ABORT_REFUSED = 4;
    static final int ABORT_UNABLE_TO_DETERMINE = 5;

    private static final int OSD_NAME_MAX_LENGTH = 13;

    private HdmiCecMessageBuilder() {}

    /**
     * Build &lt;Feature Abort&gt; command. &lt;Feature Abort&gt; consists of
     * 1 byte original opcode and 1 byte reason fields with basic fields.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param originalOpcode original opcode causing feature abort
     * @param reason reason of feature abort
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildFeatureAbortCommand(int src, int dest, int originalOpcode,
            int reason) {
        byte[] params = new byte[] {
                (byte) originalOpcode,
                (byte) reason,
        };
        return buildCommand(src, dest, HdmiCec.MESSAGE_FEATURE_ABORT, params);
    }

    /**
     * Build &lt;Get Osd Name&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildGetOsdNameCommand(int src, int dest) {
        return buildCommand(src, dest, HdmiCec.MESSAGE_GET_OSD_NAME);
    }

    /**
     * Build &lt;Give Vendor Id Command&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildGiveDeviceVendorIdCommand(int src, int dest) {
        return buildCommand(src, dest, HdmiCec.MESSAGE_GIVE_DEVICE_VENDOR_ID);
    }

    /**
     * Build &lt;Set Menu Language &gt; command.
     *
     * <p>This is a broadcast message sent to all devices on the bus.
     *
     * @param src source address of command
     * @param language 3-letter ISO639-2 based language code
     * @return newly created {@link HdmiCecMessage} if language is valid.
     *         Otherwise, return null
     */
    static HdmiCecMessage buildSetMenuLanguageCommand(int src, String language) {
        if (language.length() != 3) {
            return null;
        }
        // Hdmi CEC uses lower-cased ISO 639-2 (3 letters code).
        String normalized = language.toLowerCase();
        byte[] params = new byte[] {
                (byte) normalized.charAt(0),
                (byte) normalized.charAt(1),
                (byte) normalized.charAt(2),
        };
        // <Set Menu Language> is broadcast message.
        return buildCommand(src, HdmiCec.ADDR_BROADCAST, HdmiCec.MESSAGE_SET_MENU_LANGUAGE,
                params);
    }

    /**
     * Build &lt;Set Osd Name &gt; command.
     *
     * @param src source address of command
     * @param name display (OSD) name of device
     * @return newly created {@link HdmiCecMessage} if valid name. Otherwise,
     *         return null
     */
    static HdmiCecMessage buildSetOsdNameCommand(int src, int dest, String name) {
        int length = Math.min(name.length(), OSD_NAME_MAX_LENGTH);
        byte[] params;
        try {
            params = name.substring(0, length).getBytes("US-ASCII");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
        return buildCommand(src, dest, HdmiCec.MESSAGE_SET_OSD_NAME, params);
    }

    /**
     * Build &lt;Report Physical Address&gt; command. It has two bytes physical
     * address and one byte device type as parameter.
     *
     * <p>This is a broadcast message sent to all devices on the bus.
     *
     * @param src source address of command
     * @param address physical address of device
     * @param deviceType type of device
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildReportPhysicalAddressCommand(int src, int address, int deviceType) {
        byte[] params = new byte[] {
                // Two bytes for physical address
                (byte) ((address >> 8) & 0xFF),
                (byte) (address & 0xFF),
                // One byte device type
                (byte) deviceType
        };
        // <Report Physical Address> is broadcast message.
        return buildCommand(src, HdmiCec.ADDR_BROADCAST, HdmiCec.MESSAGE_REPORT_PHYSICAL_ADDRESS,
                params);
    }

    /**
     * Build &lt;Device Vendor Id&gt; command. It has three bytes vendor id as
     * parameter.
     *
     * <p>This is a broadcast message sent to all devices on the bus.
     *
     * @param src source address of command
     * @param vendorId device's vendor id
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildDeviceVendorIdCommand(int src, int vendorId) {
        byte[] params = new byte[] {
                (byte) ((vendorId >> 16) & 0xFF),
                (byte) ((vendorId >> 8) & 0xFF),
                (byte) (vendorId & 0xFF)
        };
        // <Device Vendor Id> is broadcast message.
        return buildCommand(src, HdmiCec.ADDR_BROADCAST, HdmiCec.MESSAGE_DEVICE_VENDOR_ID,
                params);
    }

    /**
     * Build &lt;Device Vendor Id&gt; command. It has one byte cec version as parameter.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param version version of cec. Use 0x04 for "Version 1.3a" and 0x05 for
     *                "Version 1.4 or 1.4a or 1.4b
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildCecVersion(int src, int dest, int version) {
        byte[] params = new byte[] {
                (byte) version
        };
        return buildCommand(src, dest, HdmiCec.MESSAGE_CEC_VERSION, params);
    }

    /**
     * Build a {@link HdmiCecMessage} without extra parameter.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param opcode opcode for a message
     * @return newly created {@link HdmiCecMessage}
     */
    private static HdmiCecMessage buildCommand(int src, int dest, int opcode) {
        return new HdmiCecMessage(src, dest, opcode, HdmiCecMessage.EMPTY_PARAM);
    }

    /**
     * Build a {@link HdmiCecMessage} with given values.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param opcode opcode for a message
     * @param params extra parameters for command
     * @return newly created {@link HdmiCecMessage}
     */
    private static HdmiCecMessage buildCommand(int src, int dest, int opcode, byte[] params) {
        return new HdmiCecMessage(src, dest, opcode, params);
    }
}
