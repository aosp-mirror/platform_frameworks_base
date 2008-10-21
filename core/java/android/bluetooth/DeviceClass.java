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
 * The Android Bluetooth API returns a 32-bit integer to represent the device
 * class. This is actually a bit vector, the format defined at
 *   http://www.bluetooth.org/Technical/AssignedNumbers/baseband.htm
 * (login required). This class provides static helper methods and constants to
 * determine what Service Class(es), Major Class, and Minor Class are encoded
 * in a 32-bit device class.
 *
 * Each of the helper methods takes the 32-bit integer device class as an
 * argument.
 *
 * @hide
 */
public class DeviceClass {

    // Baseband class information
    // See http://www.bluetooth.org/Technical/AssignedNumbers/baseband.htm

    public static final int SERVICE_CLASS_BITMASK                 = 0xFFE000;
    public static final int SERVICE_CLASS_LIMITED_DISCOVERABILITY = 0x002000;
    public static final int SERVICE_CLASS_POSITIONING             = 0x010000;
    public static final int SERVICE_CLASS_NETWORKING              = 0x020000;
    public static final int SERVICE_CLASS_RENDER                  = 0x040000;
    public static final int SERVICE_CLASS_CAPTURE                 = 0x080000;
    public static final int SERVICE_CLASS_OBJECT_TRANSFER         = 0x100000;
    public static final int SERVICE_CLASS_AUDIO                   = 0x200000;
    public static final int SERVICE_CLASS_TELEPHONY               = 0x400000;
    public static final int SERVICE_CLASS_INFORMATION             = 0x800000;

    public static final int MAJOR_CLASS_BITMASK           = 0x001F00;
    public static final int MAJOR_CLASS_MISC              = 0x000000;
    public static final int MAJOR_CLASS_COMPUTER          = 0x000100;
    public static final int MAJOR_CLASS_PHONE             = 0x000200;
    public static final int MAJOR_CLASS_NETWORKING        = 0x000300;
    public static final int MAJOR_CLASS_AUDIO_VIDEO       = 0x000400;
    public static final int MAJOR_CLASS_PERIPHERAL        = 0x000500;
    public static final int MAJOR_CLASS_IMAGING           = 0x000600;
    public static final int MAJOR_CLASS_WEARABLE          = 0x000700;
    public static final int MAJOR_CLASS_TOY               = 0x000800;
    public static final int MAJOR_CLASS_MEDICAL           = 0x000900;
    public static final int MAJOR_CLASS_UNCATEGORIZED     = 0x001F00;

    // Minor classes for the AUDIO_VIDEO major class
    public static final int MINOR_CLASS_AUDIO_VIDEO_BITMASK                       = 0x0000FC;
    public static final int MINOR_CLASS_AUDIO_VIDEO_UNCATEGORIZED                 = 0x000000;
    public static final int MINOR_CLASS_AUDIO_VIDEO_HEADSET                       = 0x000004;
    public static final int MINOR_CLASS_AUDIO_VIDEO_HANDSFREE                     = 0x000008;
    public static final int MINOR_CLASS_AUDIO_VIDEO_MICROPHONE                    = 0x000010;
    public static final int MINOR_CLASS_AUDIO_VIDEO_LOUDSPEAKER                   = 0x000014;
    public static final int MINOR_CLASS_AUDIO_VIDEO_HEADPHONES                    = 0x000018;
    public static final int MINOR_CLASS_AUDIO_VIDEO_PORTABLE_AUDIO                = 0x00001C;
    public static final int MINOR_CLASS_AUDIO_VIDEO_CAR_AUDIO                     = 0x000020;
    public static final int MINOR_CLASS_AUDIO_VIDEO_SET_TOP_BOX                   = 0x000024;
    public static final int MINOR_CLASS_AUDIO_VIDEO_HIFI_AUDIO                    = 0x000028;
    public static final int MINOR_CLASS_AUDIO_VIDEO_VCR                           = 0x00002C;
    public static final int MINOR_CLASS_AUDIO_VIDEO_VIDEO_CAMERA                  = 0x000030;
    public static final int MINOR_CLASS_AUDIO_VIDEO_CAMCORDER                     = 0x000034;
    public static final int MINOR_CLASS_AUDIO_VIDEO_VIDEO_MONITOR                 = 0x000038;
    public static final int MINOR_CLASS_AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER = 0x00003C;
    public static final int MINOR_CLASS_AUDIO_VIDEO_VIDEO_CONFERENCING            = 0x000040;
    public static final int MINOR_CLASS_AUDIO_VIDEO_VIDEO_GAMING_TOY              = 0x000048;

    // Indicates the Bluetooth API could not retrieve the class
    public static final int CLASS_UNKNOWN = 0xFF000000;

    /** Returns true if the given device class supports the given Service Class.
     * A bluetooth device can claim to support zero or more service classes.
     * @param deviceClass      The bluetooth device class.
     * @param serviceClassType The service class constant to test for. For
     *                         example, DeviceClass.SERVICE_CLASS_AUDIO. This
     *                         must be one of the SERVICE_CLASS_xxx constants,
     *                         results of this function are undefined
     *                         otherwise.
     * @return If the deviceClass claims to support the serviceClassType.
     */
    public static boolean hasServiceClass(int deviceClass, int serviceClassType) {
        if (deviceClass == CLASS_UNKNOWN) {
            return false;
        }
        return ((deviceClass & SERVICE_CLASS_BITMASK & serviceClassType) != 0);
    }

    /** Returns the Major Class of a bluetooth device class.
     * Values returned from this function can be compared with the constants
     * MAJOR_CLASS_xxx. A bluetooth device can only be associated
     * with one major class.
     */
    public static int getMajorClass(int deviceClass) {
        if (deviceClass == CLASS_UNKNOWN) {
            return CLASS_UNKNOWN;
        }
        return (deviceClass & MAJOR_CLASS_BITMASK);
    }

    /** Returns the Minor Class of a bluetooth device class.
     * Values returned from this function can be compared with the constants
     * MINOR_CLASS_xxx_yyy, where xxx is the Major Class. A bluetooth
     * device can only be associated with one minor class within its major
     * class.
     */
    public static int getMinorClass(int deviceClass) {
        if (deviceClass == CLASS_UNKNOWN) {
            return CLASS_UNKNOWN;
        }
        return (deviceClass & MINOR_CLASS_AUDIO_VIDEO_BITMASK);
    }
}
