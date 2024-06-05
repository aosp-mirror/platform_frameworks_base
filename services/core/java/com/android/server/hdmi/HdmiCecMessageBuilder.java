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

import com.android.server.hdmi.Constants.AudioCodec;

import java.io.UnsupportedEncodingException;

/**
 * A helper class to build {@link HdmiCecMessage} from various cec commands.
 *
 * If a message type has its own specific subclass of {@link HdmiCecMessage},
 * its static factory method is instead declared in that subclass.
 */
public class HdmiCecMessageBuilder {
    private static final int OSD_NAME_MAX_LENGTH = 14;

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
                (byte) (originalOpcode & 0xFF),
                (byte) (reason & 0xFF),
        };
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_FEATURE_ABORT, params);
    }

    /**
     * Build &lt;Give Physical Address&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildGivePhysicalAddress(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_GIVE_PHYSICAL_ADDRESS);
    }

    /**
     * Build &lt;Give Osd Name&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildGiveOsdNameCommand(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_GIVE_OSD_NAME);
    }

    /**
     * Build &lt;Give Vendor Id Command&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildGiveDeviceVendorIdCommand(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_GIVE_DEVICE_VENDOR_ID);
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
                (byte) (normalized.charAt(0) & 0xFF),
                (byte) (normalized.charAt(1) & 0xFF),
                (byte) (normalized.charAt(2) & 0xFF),
        };
        // <Set Menu Language> is broadcast message.
        return HdmiCecMessage.build(src, Constants.ADDR_BROADCAST,
                Constants.MESSAGE_SET_MENU_LANGUAGE, params);
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
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_SET_OSD_NAME, params);
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
                (byte) (deviceType & 0xFF)
        };
        // <Report Physical Address> is broadcast message.
        return HdmiCecMessage.build(src, Constants.ADDR_BROADCAST,
                Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS, params);
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
        return HdmiCecMessage.build(src, Constants.ADDR_BROADCAST,
                Constants.MESSAGE_DEVICE_VENDOR_ID, params);
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
                (byte) (version & 0xFF)
        };
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_CEC_VERSION, params);
    }

    /**
     * Build &lt;Request Arc Initiation&gt;
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildRequestArcInitiation(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_REQUEST_ARC_INITIATION);
    }

    /**
     * Build &lt;Initiate Arc&gt;
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildInitiateArc(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_INITIATE_ARC);
    }

    /**
     * Build &lt;Terminate Arc&gt;
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildTerminateArc(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_TERMINATE_ARC);
    }

    /**
     * Build &lt;Request Arc Termination&gt;
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildRequestArcTermination(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_REQUEST_ARC_TERMINATION);
    }

    /**
     * Build &lt;Report Arc Initiated&gt;
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildReportArcInitiated(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_REPORT_ARC_INITIATED);
    }

    /**
     * Build &lt;Report Arc Terminated&gt;
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildReportArcTerminated(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_REPORT_ARC_TERMINATED);
    }


    /**
     * Build &lt;Request Short Audio Descriptor&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param audioFormats the {@link AudioCodec}s desired
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildRequestShortAudioDescriptor(int src, int dest,
            @AudioCodec int[] audioFormats) {
        byte[] params = new byte[Math.min(audioFormats.length,4)] ;
        for (int i = 0; i < params.length ; i++){
            params[i] = (byte) (audioFormats[i] & 0xff);
        }
        return HdmiCecMessage.build(
                src, dest, Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR, params);
    }


    /**
     * Build &lt;Text View On&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildTextViewOn(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_TEXT_VIEW_ON);
    }

    /**
     * Build &lt;Image View On&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildImageViewOn(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_IMAGE_VIEW_ON);
    }

    /**
     * Build &lt;Request Active Source&gt; command.
     *
     * @param src source address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildRequestActiveSource(int src) {
        return HdmiCecMessage.build(
                src, Constants.ADDR_BROADCAST, Constants.MESSAGE_REQUEST_ACTIVE_SOURCE);
    }

    /**
     * Build &lt;Active Source&gt; command.
     *
     * @param src source address of command
     * @param physicalAddress physical address of the device to become active
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildActiveSource(int src, int physicalAddress) {
        return HdmiCecMessage.build(src, Constants.ADDR_BROADCAST, Constants.MESSAGE_ACTIVE_SOURCE,
                physicalAddressToParam(physicalAddress));
    }

    /**
     * Build &lt;Inactive Source&gt; command.
     *
     * @param src source address of command
     * @param physicalAddress physical address of the device to become inactive
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildInactiveSource(int src, int physicalAddress) {
        return HdmiCecMessage.build(src, Constants.ADDR_TV,
                Constants.MESSAGE_INACTIVE_SOURCE, physicalAddressToParam(physicalAddress));
    }

    /**
     * Build &lt;Set Stream Path&gt; command.
     *
     * <p>This is a broadcast message sent to all devices on the bus.
     *
     * @param src source address of command
     * @param streamPath physical address of the device to start streaming
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildSetStreamPath(int src, int streamPath) {
        return HdmiCecMessage.build(src, Constants.ADDR_BROADCAST,
                Constants.MESSAGE_SET_STREAM_PATH, physicalAddressToParam(streamPath));
    }

    /**
     * Build &lt;Routing Change&gt; command.
     *
     * <p>This is a broadcast message sent to all devices on the bus.
     *
     * @param src source address of command
     * @param oldPath physical address of the currently active routing path
     * @param newPath physical address of the new active routing path
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildRoutingChange(int src, int oldPath, int newPath) {
        byte[] param = new byte[] {
            (byte) ((oldPath >> 8) & 0xFF), (byte) (oldPath & 0xFF),
            (byte) ((newPath >> 8) & 0xFF), (byte) (newPath & 0xFF)
        };
        return HdmiCecMessage.build(src, Constants.ADDR_BROADCAST, Constants.MESSAGE_ROUTING_CHANGE,
                param);
    }

    /**
     * Build &lt;Routing Information&gt; command.
     *
     * <p>This is a broadcast message sent to all devices on the bus.
     *
     * @param src source address of command
     * @param physicalAddress physical address of the new active routing path
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildRoutingInformation(int src, int physicalAddress) {
        return HdmiCecMessage.build(src, Constants.ADDR_BROADCAST,
            Constants.MESSAGE_ROUTING_INFORMATION, physicalAddressToParam(physicalAddress));
    }

    /**
     * Build &lt;Give Device Power Status&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildGiveDevicePowerStatus(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_GIVE_DEVICE_POWER_STATUS);
    }

    /**
     * Build &lt;Report Power Status&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param powerStatus power status of the device
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildReportPowerStatus(int src, int dest, int powerStatus) {
        byte[] param = new byte[] {
                (byte) (powerStatus & 0xFF)
        };
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_REPORT_POWER_STATUS, param);
    }

    /**
     * Build &lt;Report Menu Status&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param menuStatus menu status of the device
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildReportMenuStatus(int src, int dest, int menuStatus) {
        byte[] param = new byte[] {
                (byte) (menuStatus & 0xFF)
        };
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_MENU_STATUS, param);
    }

    /**
     * Build &lt;System Audio Mode Request&gt; command.
     *
     * @param src source address of command
     * @param avr destination address of command, it should be AVR
     * @param avrPhysicalAddress physical address of AVR
     * @param enableSystemAudio whether to enable System Audio Mode or not
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildSystemAudioModeRequest(int src, int avr, int avrPhysicalAddress,
            boolean enableSystemAudio) {
        if (enableSystemAudio) {
            return HdmiCecMessage.build(src, avr, Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST,
                    physicalAddressToParam(avrPhysicalAddress));
        } else {
            return HdmiCecMessage.build(src, avr, Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST);
        }
    }

    /**
     * Build &lt;Set System Audio Mode&gt; command.
     *
     * @param src source address of command
     * @param des destination address of command
     * @param systemAudioStatus whether to set System Audio Mode on or off
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildSetSystemAudioMode(int src, int des, boolean systemAudioStatus) {
        return buildCommandWithBooleanParam(src, des, Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE,
            systemAudioStatus
        );
    }

    /**
     * Build &lt;Report System Audio Mode&gt; command.
     *
     * @param src source address of command
     * @param des destination address of command
     * @param systemAudioStatus whether System Audio Mode is on or off
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildReportSystemAudioMode(int src, int des, boolean systemAudioStatus) {
        return buildCommandWithBooleanParam(src, des, Constants.MESSAGE_SYSTEM_AUDIO_MODE_STATUS,
            systemAudioStatus
        );
    }

    /**
     * Build &lt;Report Short Audio Descriptor&gt; command.
     *
     * @param src source address of command
     * @param des destination address of command
     * @param sadBytes Short Audio Descriptor in bytes
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildReportShortAudioDescriptor(int src, int des, byte[] sadBytes) {
        return HdmiCecMessage.build(
                src, des, Constants.MESSAGE_REPORT_SHORT_AUDIO_DESCRIPTOR, sadBytes);
    }

    /**
     * Build &lt;Give Audio Status&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildGiveAudioStatus(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_GIVE_AUDIO_STATUS);
    }

    /**
     * Build &lt;Report Audio Status&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param volume volume level of current device in param
     * @param mute mute status of current device in param
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildReportAudioStatus(int src, int dest, int volume, boolean mute) {
        byte status = (byte) ((byte) (mute ? 1 << 7 : 0) | ((byte) volume & 0x7F));
        byte[] params = new byte[] { status };
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_REPORT_AUDIO_STATUS, params);
    }

    /**
     * Build &lt;User Control Pressed&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param uiCommand keycode that user pressed
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildUserControlPressed(int src, int dest, int uiCommand) {
        return buildUserControlPressed(src, dest, new byte[] { (byte) (uiCommand & 0xFF) });
    }

    /**
     * Build &lt;User Control Pressed&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param commandParam uiCommand and the additional parameter
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildUserControlPressed(int src, int dest, byte[] commandParam) {
        return HdmiCecMessage.build(
                src, dest, Constants.MESSAGE_USER_CONTROL_PRESSED, commandParam);
    }

    /**
     * Build &lt;User Control Released&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildUserControlReleased(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_USER_CONTROL_RELEASED);
    }

    /**
     * Build &lt;Give System Audio Mode Status&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildGiveSystemAudioModeStatus(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_GIVE_SYSTEM_AUDIO_MODE_STATUS);
    }

    /**
     * Build &lt;Standby&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    public static HdmiCecMessage buildStandby(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_STANDBY);
    }

    /**
     * Build &lt;Vendor Command&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param params vendor-specific parameters
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildVendorCommand(int src, int dest, byte[] params) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_VENDOR_COMMAND, params);
    }

    /**
     * Build &lt;Vendor Command With ID&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param vendorId vendor ID
     * @param operands vendor-specific parameters
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildVendorCommandWithId(int src, int dest, int vendorId,
            byte[] operands) {
        byte[] params = new byte[operands.length + 3];  // parameter plus len(vendorId)
        params[0] = (byte) ((vendorId >> 16) & 0xFF);
        params[1] = (byte) ((vendorId >> 8) & 0xFF);
        params[2] = (byte) (vendorId & 0xFF);
        System.arraycopy(operands, 0, params, 3, operands.length);
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_VENDOR_COMMAND_WITH_ID, params);
    }

    /**
     * Build &lt;Record On&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param params parameter of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildRecordOn(int src, int dest, byte[] params) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_RECORD_ON, params);
    }

    /**
     * Build &lt;Record Off&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildRecordOff(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_RECORD_OFF);
    }

    /**
     * Build &lt;Set Digital Timer&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param params byte array of timing information and digital service information to be recorded
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildSetDigitalTimer(int src, int dest, byte[] params) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_SET_DIGITAL_TIMER, params);
    }

    /**
     * Build &lt;Set Analogue Timer&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param params byte array of timing information and analog service information to be recorded
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildSetAnalogueTimer(int src, int dest, byte[] params) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_SET_ANALOG_TIMER, params);
    }

    /**
     * Build &lt;Set External Timer&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param params byte array of timing information and external source information to be recorded
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildSetExternalTimer(int src, int dest, byte[] params) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_SET_EXTERNAL_TIMER, params);
    }

    /**
     * Build &lt;Clear Digital Timer&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param params byte array of timing information and digital service information to be cleared
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildClearDigitalTimer(int src, int dest, byte[] params) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_CLEAR_DIGITAL_TIMER, params);
    }

    /**
     * Build &lt;Clear Analog Timer&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param params byte array of timing information and analog service information to be cleared
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildClearAnalogueTimer(int src, int dest, byte[] params) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_CLEAR_ANALOG_TIMER, params);
    }

    /**
     * Build &lt;Clear Digital Timer&gt; command.
     *
     * @param src source address of command
     * @param dest destination address of command
     * @param params byte array of timing information and external source information to be cleared
     * @return newly created {@link HdmiCecMessage}
     */
    static HdmiCecMessage buildClearExternalTimer(int src, int dest, byte[] params) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_CLEAR_EXTERNAL_TIMER, params);
    }

    static HdmiCecMessage buildGiveFeatures(int src, int dest) {
        return HdmiCecMessage.build(src, dest, Constants.MESSAGE_GIVE_FEATURES);
    }

    /***** Please ADD new buildXXX() methods above. ******/

    /**
     * Build a {@link HdmiCecMessage} with a boolean param and other given values.
     *
     * @param src source address of command
     * @param des destination address of command
     * @param opcode opcode for a message
     * @param param boolean param for building the command
     * @return newly created {@link HdmiCecMessage}
     */
    private static HdmiCecMessage buildCommandWithBooleanParam(int src, int des,
        int opcode, boolean param) {
        byte[] params = new byte[]{
            param ? (byte) 0b1 : 0b0
        };
        return HdmiCecMessage.build(src, des, opcode, params);
    }

    private static byte[] physicalAddressToParam(int physicalAddress) {
        return new byte[] {
                (byte) ((physicalAddress >> 8) & 0xFF),
                (byte) (physicalAddress & 0xFF)
        };
    }
}
