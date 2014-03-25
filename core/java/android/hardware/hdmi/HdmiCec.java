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
    public static final int MESSAGE_ACTIVE_SOURCE = 0x9D;

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
