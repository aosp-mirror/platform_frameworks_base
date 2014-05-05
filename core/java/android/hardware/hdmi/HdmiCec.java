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
 * Defines constants and utility methods related to HDMI-CEC protocol.
 *
 * @hide
 */
public final class HdmiCec {

    /** TV device type. */
    public static final int DEVICE_TV = 0;

    /** Recording device type. */
    public static final int DEVICE_RECORDER = 1;

    /** Device type reserved for future usage. */
    public static final int DEVICE_RESERVED = 2;

    /** Tuner device type. */
    public static final int DEVICE_TUNER = 3;

    /** Playback device type. */
    public static final int DEVICE_PLAYBACK = 4;

    /** Audio system device type. */
    public static final int DEVICE_AUDIO_SYSTEM = 5;

    // Value indicating the device is not an active source.
    public static final int DEVICE_INACTIVE = -1;

    /** Logical address for TV */
    public static final int ADDR_TV = 0;

    /** Logical address for recorder 1 */
    public static final int ADDR_RECORDER_1 = 1;

    /** Logical address for recorder 2 */
    public static final int ADDR_RECORDER_2 = 2;

    /** Logical address for tuner 1 */
    public static final int ADDR_TUNER_1 = 3;

    /** Logical address for playback 1 */
    public static final int ADDR_PLAYBACK_1 = 4;

    /** Logical address for audio system */
    public static final int ADDR_AUDIO_SYSTEM = 5;

    /** Logical address for tuner 2 */
    public static final int ADDR_TUNER_2 = 6;

    /** Logical address for tuner 3 */
    public static final int ADDR_TUNER_3 = 7;

    /** Logical address for playback 2 */
    public static final int ADDR_PLAYBACK_2 = 8;

    /** Logical address for recorder 3 */
    public static final int ADDR_RECORDER_3 = 9;

    /** Logical address for tuner 4 */
    public static final int ADDR_TUNER_4 = 10;

    /** Logical address for playback 3 */
    public static final int ADDR_PLAYBACK_3 = 11;

    /** Logical address reserved for future usage */
    public static final int ADDR_RESERVED_1 = 12;

    /** Logical address reserved for future usage */
    public static final int ADDR_RESERVED_2 = 13;

    /** Logical address for TV other than the one assigned with {@link #ADDR_TV} */
    public static final int ADDR_FREE_USE = 14;

    /** Logical address for devices to which address cannot be allocated */
    public static final int ADDR_UNREGISTERED = 15;

    /** Logical address used in the destination address field for broadcast messages */
    public static final int ADDR_BROADCAST = 15;

    /** Logical address used to indicate it is not initialized or invalid. */
    public static final int ADDR_INVALID = -1;

    // TODO: Complete the list of CEC messages definition.
    public static final int MESSAGE_FEATURE_ABORT = 0x00;
    public static final int MESSAGE_IMAGE_VIEW_ON = 0x04;
    public static final int MESSAGE_TUNER_STEP_INCREMENT = 0x05;
    public static final int MESSAGE_TUNER_STEP_DECREMENT = 0x06;
    public static final int MESSAGE_TUNER_DEVICE_STATUS = 0x07;
    public static final int MESSAGE_GIVE_TUNER_DEVICE_STATUS = 0x08;
    public static final int MESSAGE_RECORD_ON = 0x09;
    public static final int MESSAGE_RECORD_STATUS = 0x0A;
    public static final int MESSAGE_RECORD_OFF = 0x0B;
    public static final int MESSAGE_TEXT_VIEW_ON = 0x0D;
    public static final int MESSAGE_RECORD_TV_SCREEN = 0x0F;
    public static final int MESSAGE_GIVE_DECK_STATUS = 0x1A;
    public static final int MESSAGE_DECK_STATUS = 0x1B;
    public static final int MESSAGE_SET_MENU_LANGUAGE = 0x32;
    public static final int MESSAGE_CLEAR_ANALOG_TIMER = 0x33;
    public static final int MESSAGE_SET_ANALOG_TIMER = 0x34;
    public static final int MESSAGE_TIMER_STATUS = 0x35;
    public static final int MESSAGE_STANDBY = 0x36;
    public static final int MESSAGE_PLAY = 0x41;
    public static final int MESSAGE_DECK_CONTROL = 0x42;
    public static final int MESSAGE_TIMER_CLEARED_STATUS = 0x043;
    public static final int MESSAGE_USER_CONTROL_PRESSED = 0x44;
    public static final int MESSAGE_USER_CONTROL_RELEASED = 0x45;
    public static final int MESSAGE_GET_OSD_NAME = 0x46;
    public static final int MESSAGE_SET_OSD_NAME = 0x47;
    public static final int MESSAGE_SET_OSD_STRING = 0x64;
    public static final int MESSAGE_SET_TIMER_PROGRAM_TITLE = 0x67;
    public static final int MESSAGE_SYSTEM_AUDIO_MODE_REQUEST = 0x70;
    public static final int MESSAGE_GIVE_AUDIO_STATUS = 0x71;
    public static final int MESSAGE_SET_SYSTEM_AUDIO_MODE = 0x72;
    public static final int MESSAGE_REPORT_AUDIO_STATUS = 0x7A;
    public static final int MESSAGE_GIVE_SYSTEM_AUDIO_MODE_STATUS = 0x7D;
    public static final int MESSAGE_SYSTEM_AUDIO_MODE_STATUS = 0x7E;
    public static final int MESSAGE_ROUTING_CHANGE = 0x80;
    public static final int MESSAGE_ROUTING_INFORMATION = 0x81;
    public static final int MESSAGE_ACTIVE_SOURCE = 0x82;
    public static final int MESSAGE_GIVE_PHYSICAL_ADDRESS = 0x83;
    public static final int MESSAGE_REPORT_PHYSICAL_ADDRESS = 0x84;
    public static final int MESSAGE_REQUEST_ACTIVE_SOURCE = 0x85;
    public static final int MESSAGE_SET_STREAM_PATH = 0x86;
    public static final int MESSAGE_DEVICE_VENDOR_ID = 0x87;
    public static final int MESSAGE_VENDOR_COMMAND = 0x89;
    public static final int MESSAGE_VENDOR_REMOTE_BUTTON_DOWN = 0x8A;
    public static final int MESSAGE_VENDOR_REMOTE_BUTTON_UP = 0x8B;
    public static final int MESSAGE_GIVE_DEVICE_VENDOR_ID = 0x8C;
    public static final int MESSAGE_MENU_REQUEST = 0x8D;
    public static final int MESSAGE_MENU_STATUS = 0x8E;
    public static final int MESSAGE_GIVE_DEVICE_POWER_STATUS = 0x8F;
    public static final int MESSAGE_REPORT_POWER_STATUS = 0x90;
    public static final int MESSAGE_GET_MENU_LANGUAGE = 0x91;
    public static final int MESSAGE_SELECT_ANALOG_SERVICE = 0x92;
    public static final int MESSAGE_SELECT_DIGITAL_SERVICE = 0x93;
    public static final int MESSAGE_SET_DIGITAL_TIMER = 0x97;
    public static final int MESSAGE_CLEAR_DIGITAL_TIMER = 0x99;
    public static final int MESSAGE_SET_AUDIO_RATE = 0x9A;
    public static final int MESSAGE_INACTIVE_SOURCE = 0x9D;
    public static final int MESSAGE_CEC_VERSION = 0x9E;
    public static final int MESSAGE_GET_CEC_VERSION = 0x9F;
    public static final int MESSAGE_VENDOR_COMMAND_WITH_ID = 0xA0;
    public static final int MESSAGE_CLEAR_EXTERNAL_TIMER = 0xA1;
    public static final int MESSAGE_SET_EXTERNAL_TIMER = 0xA2;
    public static final int MESSAGE_ABORT = 0xFF;

    public static final int POWER_STATUS_UNKNOWN = -1;
    public static final int POWER_STATUS_ON = 0;
    public static final int POWER_STATUS_STANDBY = 1;
    public static final int POWER_TRANSIENT_TO_ON = 2;
    public static final int POWER_TRANSIENT_TO_STANDBY = 3;

    private static final int[] ADDRESS_TO_TYPE = {
        DEVICE_TV,  // ADDR_TV
        DEVICE_RECORDER,  // ADDR_RECORDER_1
        DEVICE_RECORDER,  // ADDR_RECORDER_2
        DEVICE_TUNER,  // ADDR_TUNER_1
        DEVICE_PLAYBACK,  // ADDR_PLAYBACK_1
        DEVICE_AUDIO_SYSTEM,  // ADDR_AUDIO_SYSTEM
        DEVICE_TUNER,  // ADDR_TUNER_2
        DEVICE_TUNER,  // ADDR_TUNER_3
        DEVICE_PLAYBACK,  // ADDR_PLAYBACK_2
        DEVICE_RECORDER,  // ADDR_RECORDER_3
        DEVICE_TUNER,  // ADDR_TUNER_4
        DEVICE_PLAYBACK,  // ADDR_PLAYBACK_3
    };

    private static final String[] DEFAULT_NAMES = {
        "TV",
        "Recorder_1",
        "Recorder_2",
        "Tuner_1",
        "Playback_1",
        "AudioSystem",
        "Tuner_2",
        "Tuner_3",
        "Playback_2",
        "Recorder_3",
        "Tuner_4",
        "Playback_3",
    };

    private HdmiCec() { }  // Prevents instantiation.

    /**
     * Check if the given type is valid. A valid type is one of the actual
     * logical device types defined in the standard ({@link #DEVICE_TV},
     * {@link #DEVICE_PLAYBACK}, {@link #DEVICE_TUNER}, {@link #DEVICE_RECORDER},
     * and {@link #DEVICE_AUDIO_SYSTEM}).
     *
     * @param type device type
     * @return true if the given type is valid
     */
    public static boolean isValidType(int type) {
        return (DEVICE_TV <= type && type <= DEVICE_AUDIO_SYSTEM)
                && type != DEVICE_RESERVED;
    }

    /**
     * Check if the given logical address is valid. A logical address is valid
     * if it is one allocated for an actual device which allows communication
     * with other logical devices.
     *
     * @param address logical address
     * @return true if the given address is valid
     */
    public static boolean isValidAddress(int address) {
        // TODO: We leave out the address 'free use(14)' for now. Check this later
        //       again to make sure it is a valid address for communication.
        return (ADDR_TV <= address && address <= ADDR_PLAYBACK_3);
    }

    /**
     * Return the device type for the given logical address.
     *
     * @param address logical address
     * @return device type for the given logical address; DEVICE_INACTIVE
     *         if the address is not valid.
     */
    public static int getTypeFromAddress(int address) {
        if (isValidAddress(address)) {
            return ADDRESS_TO_TYPE[address];
        }
        return DEVICE_INACTIVE;
    }

    /**
     * Return the default device name for a logical address. This is the name
     * by which the logical device is known to others until a name is
     * set explicitly using HdmiCecService.setOsdName.
     *
     * @param address logical address
     * @return default device name; empty string if the address is not valid
     */
    public static String getDefaultDeviceName(int address) {
        if (isValidAddress(address)) {
            return DEFAULT_NAMES[address];
        }
        return "";
    }
}
