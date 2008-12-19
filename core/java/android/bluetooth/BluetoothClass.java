/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.bluetooth;

/**
 * The Android Bluetooth API is not finalized, and *will* change. Use at your
 * own risk.
 *
 * Static helper methods and constants to decode the device class bit vector
 * returned by the Bluetooth API.
 *
 * The Android Bluetooth API returns a 32-bit integer to represent the class.
 * The format of these bits is defined at
 *   http://www.bluetooth.org/Technical/AssignedNumbers/baseband.htm
 * (login required). This class provides static helper methods and constants to
 * determine what Service Class(es) and Device Class are encoded in the 32-bit
 * class.
 *
 * Devices typically have zero or more service classes, and exactly one device
 * class. The device class is encoded as a major and minor device class, the
 * minor being a subset of the major.
 *
 * Class is useful to describe a device (for example to show an icon),
 * but does not reliably describe what profiles a device supports. To determine
 * profile support you usually need to perform SDP queries.
 *
 * Each of these helper methods takes the 32-bit integer class as an argument.
 *
 * @hide
 */
public class BluetoothClass {
    /** Indicates the Bluetooth API could not retrieve the class */
    public static final int ERROR = 0xFF000000;

    /** Every Bluetooth device has zero or more service classes */
    public static class Service {
        public static final int BITMASK                 = 0xFFE000;

        public static final int LIMITED_DISCOVERABILITY = 0x002000;
        public static final int POSITIONING             = 0x010000;
        public static final int NETWORKING              = 0x020000;
        public static final int RENDER                  = 0x040000;
        public static final int CAPTURE                 = 0x080000;
        public static final int OBJECT_TRANSFER         = 0x100000;
        public static final int AUDIO                   = 0x200000;
        public static final int TELEPHONY               = 0x400000;
        public static final int INFORMATION             = 0x800000;

        /** Returns true if the given class supports the given Service Class.
         * A bluetooth device can claim to support zero or more service classes.
         * @param btClass The bluetooth class.
         * @param serviceClass The service class constant to test for. For
         *                     example, Service.AUDIO. Must be one of the
         *                     Service.FOO constants.
         * @return True if the service class is supported.
         */
        public static boolean hasService(int btClass, int serviceClass) {
            if (btClass == ERROR) {
                return false;
            }
            return ((btClass & Service.BITMASK & serviceClass) != 0);
        }
    }

    /** Every Bluetooth device has exactly one device class, comprimised of
     *  major and minor components. We have not included the minor classes for
     *  major classes: NETWORKING, PERIPHERAL and IMAGING yet because they work
     *  a little differently. */
    public static class Device {
        public static final int BITMASK               = 0x1FFC;

        public static class Major {
            public static final int BITMASK           = 0x1F00;

            public static final int MISC              = 0x0000;
            public static final int COMPUTER          = 0x0100;
            public static final int PHONE             = 0x0200;
            public static final int NETWORKING        = 0x0300;
            public static final int AUDIO_VIDEO       = 0x0400;
            public static final int PERIPHERAL        = 0x0500;
            public static final int IMAGING           = 0x0600;
            public static final int WEARABLE          = 0x0700;
            public static final int TOY               = 0x0800;
            public static final int HEALTH            = 0x0900;
            public static final int UNCATEGORIZED     = 0x1F00;

            /** Returns the Major Device Class component of a bluetooth class.
             * Values returned from this function can be compared with the constants
             * Device.Major.FOO. A bluetooth device can only be associated
             * with one major class.
             */
            public static int getDeviceMajor(int btClass) {
                if (btClass == ERROR) {
                    return ERROR;
                }
                return (btClass & Device.Major.BITMASK);
            }
        }

        // Devices in the COMPUTER major class
        public static final int COMPUTER_UNCATEGORIZED              = 0x0100;
        public static final int COMPUTER_DESKTOP                    = 0x0104;
        public static final int COMPUTER_SERVER                     = 0x0108;
        public static final int COMPUTER_LAPTOP                     = 0x010C;
        public static final int COMPUTER_HANDHELD_PC_PDA            = 0x0110;
        public static final int COMPUTER_PALM_SIZE_PC_PDA           = 0x0114;
        public static final int COMPUTER_WEARABLE                   = 0x0118;

        // Devices in the PHONE major class
        public static final int PHONE_UNCATEGORIZED                 = 0x0200;
        public static final int PHONE_CELLULAR                      = 0x0204;
        public static final int PHONE_CORDLESS                      = 0x0208;
        public static final int PHONE_SMART                         = 0x020C;
        public static final int PHONE_MODEM_OR_GATEWAY              = 0x0210;
        public static final int PHONE_ISDN                          = 0x0214;

        // Minor classes for the AUDIO_VIDEO major class
        public static final int AUDIO_VIDEO_UNCATEGORIZED           = 0x0400;
        public static final int AUDIO_VIDEO_WEARABLE_HEADSET        = 0x0404;
        public static final int AUDIO_VIDEO_HANDSFREE               = 0x0408;
        //public static final int AUDIO_VIDEO_RESERVED              = 0x040C;
        public static final int AUDIO_VIDEO_MICROPHONE              = 0x0410;
        public static final int AUDIO_VIDEO_LOUDSPEAKER             = 0x0414;
        public static final int AUDIO_VIDEO_HEADPHONES              = 0x0418;
        public static final int AUDIO_VIDEO_PORTABLE_AUDIO          = 0x041C;
        public static final int AUDIO_VIDEO_CAR_AUDIO               = 0x0420;
        public static final int AUDIO_VIDEO_SET_TOP_BOX             = 0x0424;
        public static final int AUDIO_VIDEO_HIFI_AUDIO              = 0x0428;
        public static final int AUDIO_VIDEO_VCR                     = 0x042C;
        public static final int AUDIO_VIDEO_VIDEO_CAMERA            = 0x0430;
        public static final int AUDIO_VIDEO_CAMCORDER               = 0x0434;
        public static final int AUDIO_VIDEO_VIDEO_MONITOR           = 0x0438;
        public static final int AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER = 0x043C;
        public static final int AUDIO_VIDEO_VIDEO_CONFERENCING      = 0x0440;
        //public static final int AUDIO_VIDEO_RESERVED              = 0x0444;
        public static final int AUDIO_VIDEO_VIDEO_GAMING_TOY        = 0x0448;

        // Devices in the WEARABLE major class
        public static final int WEARABLE_UNCATEGORIZED              = 0x0700;
        public static final int WEARABLE_WRIST_WATCH                = 0x0704;
        public static final int WEARABLE_PAGER                      = 0x0708;
        public static final int WEARABLE_JACKET                     = 0x070C;
        public static final int WEARABLE_HELMET                     = 0x0710;
        public static final int WEARABLE_GLASSES                    = 0x0714;

        // Devices in the TOY major class
        public static final int TOY_UNCATEGORIZED                   = 0x0800;
        public static final int TOY_ROBOT                           = 0x0804;
        public static final int TOY_VEHICLE                         = 0x0808;
        public static final int TOY_DOLL_ACTION_FIGURE              = 0x080C;
        public static final int TOY_CONTROLLER                      = 0x0810;
        public static final int TOY_GAME                            = 0x0814;

        // Devices in the HEALTH major class
        public static final int HEALTH_UNCATEGORIZED                = 0x0900;
        public static final int HEALTH_BLOOD_PRESSURE               = 0x0904;
        public static final int HEALTH_THERMOMETER                  = 0x0908;
        public static final int HEALTH_WEIGHING                     = 0x090C;
        public static final int HEALTH_GLUCOSE                      = 0x0910;
        public static final int HEALTH_PULSE_OXIMETER               = 0x0914;
        public static final int HEALTH_PULSE_RATE                   = 0x0918;
        public static final int HEALTH_DATA_DISPLAY                 = 0x091C;

        /** Returns the Device Class component of a bluetooth class. This includes
         * both the major and minor device components. Values returned from this
         * function can be compared with the constants Device.FOO. A bluetooth
         * device can only be associated with one device class.
         */
        public static int getDevice(int btClass) {
            if (btClass == ERROR) {
                return ERROR;
            }
            return (btClass & Device.BITMASK);
        }
    }
}

