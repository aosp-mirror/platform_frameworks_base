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

import android.os.Parcel;
import android.os.Parcelable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Represents a Bluetooth class, which describes general characteristics
 * and capabilities of a device. For example, a Bluetooth class will
 * specify the general device type such as a phone, a computer, or
 * headset, and whether it's capable of services such as audio or telephony.
 *
 * <p>Every Bluetooth class is composed of zero or more service classes, and
 * exactly one device class. The device class is further broken down into major
 * and minor device class components.
 *
 * <p>{@link BluetoothClass} is useful as a hint to roughly describe a device
 * (for example to show an icon in the UI), but does not reliably describe which
 * Bluetooth profiles or services are actually supported by a device. Accurate
 * service discovery is done through SDP requests, which are automatically
 * performed when creating an RFCOMM socket with {@link
 * BluetoothDevice#createRfcommSocketToServiceRecord} and {@link
 * BluetoothAdapter#listenUsingRfcommWithServiceRecord}</p>
 *
 * <p>Use {@link BluetoothDevice#getBluetoothClass} to retrieve the class for
 * a remote device.
 *
 * <!--
 * The Bluetooth class is a 32 bit field. The format of these bits is defined at
 * http://www.bluetooth.org/Technical/AssignedNumbers/baseband.htm
 * (login required). This class contains that 32 bit field, and provides
 * constants and methods to determine which Service Class(es) and Device Class
 * are encoded in that field.
 * -->
 */
public final class BluetoothClass implements Parcelable {
    /**
     * Legacy error value. Applications should use null instead.
     *
     * @hide
     */
    public static final int ERROR = 0xFF000000;

    private final int mClass;

    /** @hide */
    public BluetoothClass(int classInt) {
        mClass = classInt;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BluetoothClass) {
            return mClass == ((BluetoothClass) o).mClass;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mClass;
    }

    @Override
    public String toString() {
        return Integer.toHexString(mClass);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<BluetoothClass> CREATOR =
            new Parcelable.Creator<BluetoothClass>() {
                public BluetoothClass createFromParcel(Parcel in) {
                    return new BluetoothClass(in.readInt());
                }

                public BluetoothClass[] newArray(int size) {
                    return new BluetoothClass[size];
                }
            };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mClass);
    }

    /**
     * Defines all service class constants.
     * <p>Each {@link BluetoothClass} encodes zero or more service classes.
     */
    public static final class Service {
        private static final int BITMASK = 0xFFE000;

        public static final int LIMITED_DISCOVERABILITY = 0x002000;
        public static final int POSITIONING = 0x010000;
        public static final int NETWORKING = 0x020000;
        public static final int RENDER = 0x040000;
        public static final int CAPTURE = 0x080000;
        public static final int OBJECT_TRANSFER = 0x100000;
        public static final int AUDIO = 0x200000;
        public static final int TELEPHONY = 0x400000;
        public static final int INFORMATION = 0x800000;
    }

    /**
     * Return true if the specified service class is supported by this
     * {@link BluetoothClass}.
     * <p>Valid service classes are the public constants in
     * {@link BluetoothClass.Service}. For example, {@link
     * BluetoothClass.Service#AUDIO}.
     *
     * @param service valid service class
     * @return true if the service class is supported
     */
    public boolean hasService(int service) {
        return ((mClass & Service.BITMASK & service) != 0);
    }

    /**
     * Defines all device class constants.
     * <p>Each {@link BluetoothClass} encodes exactly one device class, with
     * major and minor components.
     * <p>The constants in {@link
     * BluetoothClass.Device} represent a combination of major and minor
     * device components (the complete device class). The constants in {@link
     * BluetoothClass.Device.Major} represent only major device classes.
     * <p>See {@link BluetoothClass.Service} for service class constants.
     */
    public static class Device {
        private static final int BITMASK = 0x1FFC;

        /**
         * Defines all major device class constants.
         * <p>See {@link BluetoothClass.Device} for minor classes.
         */
        public static class Major {
            private static final int BITMASK = 0x1F00;

            public static final int MISC = 0x0000;
            public static final int COMPUTER = 0x0100;
            public static final int PHONE = 0x0200;
            public static final int NETWORKING = 0x0300;
            public static final int AUDIO_VIDEO = 0x0400;
            public static final int PERIPHERAL = 0x0500;
            public static final int IMAGING = 0x0600;
            public static final int WEARABLE = 0x0700;
            public static final int TOY = 0x0800;
            public static final int HEALTH = 0x0900;
            public static final int UNCATEGORIZED = 0x1F00;
        }

        // Devices in the COMPUTER major class
        public static final int COMPUTER_UNCATEGORIZED = 0x0100;
        public static final int COMPUTER_DESKTOP = 0x0104;
        public static final int COMPUTER_SERVER = 0x0108;
        public static final int COMPUTER_LAPTOP = 0x010C;
        public static final int COMPUTER_HANDHELD_PC_PDA = 0x0110;
        public static final int COMPUTER_PALM_SIZE_PC_PDA = 0x0114;
        public static final int COMPUTER_WEARABLE = 0x0118;

        // Devices in the PHONE major class
        public static final int PHONE_UNCATEGORIZED = 0x0200;
        public static final int PHONE_CELLULAR = 0x0204;
        public static final int PHONE_CORDLESS = 0x0208;
        public static final int PHONE_SMART = 0x020C;
        public static final int PHONE_MODEM_OR_GATEWAY = 0x0210;
        public static final int PHONE_ISDN = 0x0214;

        // Minor classes for the AUDIO_VIDEO major class
        public static final int AUDIO_VIDEO_UNCATEGORIZED = 0x0400;
        public static final int AUDIO_VIDEO_WEARABLE_HEADSET = 0x0404;
        public static final int AUDIO_VIDEO_HANDSFREE = 0x0408;
        //public static final int AUDIO_VIDEO_RESERVED              = 0x040C;
        public static final int AUDIO_VIDEO_MICROPHONE = 0x0410;
        public static final int AUDIO_VIDEO_LOUDSPEAKER = 0x0414;
        public static final int AUDIO_VIDEO_HEADPHONES = 0x0418;
        public static final int AUDIO_VIDEO_PORTABLE_AUDIO = 0x041C;
        public static final int AUDIO_VIDEO_CAR_AUDIO = 0x0420;
        public static final int AUDIO_VIDEO_SET_TOP_BOX = 0x0424;
        public static final int AUDIO_VIDEO_HIFI_AUDIO = 0x0428;
        public static final int AUDIO_VIDEO_VCR = 0x042C;
        public static final int AUDIO_VIDEO_VIDEO_CAMERA = 0x0430;
        public static final int AUDIO_VIDEO_CAMCORDER = 0x0434;
        public static final int AUDIO_VIDEO_VIDEO_MONITOR = 0x0438;
        public static final int AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER = 0x043C;
        public static final int AUDIO_VIDEO_VIDEO_CONFERENCING = 0x0440;
        //public static final int AUDIO_VIDEO_RESERVED              = 0x0444;
        public static final int AUDIO_VIDEO_VIDEO_GAMING_TOY = 0x0448;

        // Devices in the WEARABLE major class
        public static final int WEARABLE_UNCATEGORIZED = 0x0700;
        public static final int WEARABLE_WRIST_WATCH = 0x0704;
        public static final int WEARABLE_PAGER = 0x0708;
        public static final int WEARABLE_JACKET = 0x070C;
        public static final int WEARABLE_HELMET = 0x0710;
        public static final int WEARABLE_GLASSES = 0x0714;

        // Devices in the TOY major class
        public static final int TOY_UNCATEGORIZED = 0x0800;
        public static final int TOY_ROBOT = 0x0804;
        public static final int TOY_VEHICLE = 0x0808;
        public static final int TOY_DOLL_ACTION_FIGURE = 0x080C;
        public static final int TOY_CONTROLLER = 0x0810;
        public static final int TOY_GAME = 0x0814;

        // Devices in the HEALTH major class
        public static final int HEALTH_UNCATEGORIZED = 0x0900;
        public static final int HEALTH_BLOOD_PRESSURE = 0x0904;
        public static final int HEALTH_THERMOMETER = 0x0908;
        public static final int HEALTH_WEIGHING = 0x090C;
        public static final int HEALTH_GLUCOSE = 0x0910;
        public static final int HEALTH_PULSE_OXIMETER = 0x0914;
        public static final int HEALTH_PULSE_RATE = 0x0918;
        public static final int HEALTH_DATA_DISPLAY = 0x091C;

        // Devices in PERIPHERAL major class
        /**
         * @hide
         */
        public static final int PERIPHERAL_NON_KEYBOARD_NON_POINTING = 0x0500;
        /**
         * @hide
         */
        public static final int PERIPHERAL_KEYBOARD = 0x0540;
        /**
         * @hide
         */
        public static final int PERIPHERAL_POINTING = 0x0580;
        /**
         * @hide
         */
        public static final int PERIPHERAL_KEYBOARD_POINTING = 0x05C0;
    }

    /**
     * Return the major device class component of this {@link BluetoothClass}.
     * <p>Values returned from this function can be compared with the
     * public constants in {@link BluetoothClass.Device.Major} to determine
     * which major class is encoded in this Bluetooth class.
     *
     * @return major device class component
     */
    public int getMajorDeviceClass() {
        return (mClass & Device.Major.BITMASK);
    }

    /**
     * Return the (major and minor) device class component of this
     * {@link BluetoothClass}.
     * <p>Values returned from this function can be compared with the
     * public constants in {@link BluetoothClass.Device} to determine which
     * device class is encoded in this Bluetooth class.
     *
     * @return device class component
     */
    public int getDeviceClass() {
        return (mClass & Device.BITMASK);
    }

    /**
     * Return the Bluetooth Class of Device (CoD) value including the
     * {@link BluetoothClass.Service}, {@link BluetoothClass.Device.Major} and
     * minor device fields.
     *
     * <p>This value is an integer representation of Bluetooth CoD as in
     * Bluetooth specification.
     *
     * @see <a href="Bluetooth CoD">https://www.bluetooth.com/specifications/assigned-numbers/baseband</a>
     *
     * @hide
     */
    public int getClassOfDevice() {
        return mClass;
    }

    /**
     * Return the Bluetooth Class of Device (CoD) value including the
     * {@link BluetoothClass.Service}, {@link BluetoothClass.Device.Major} and
     * minor device fields.
     *
     * <p>This value is a byte array representation of Bluetooth CoD as in
     * Bluetooth specification.
     *
     * <p>Bluetooth COD information is 3 bytes, but stored as an int. Hence the
     * MSB is useless and needs to be thrown away. The lower 3 bytes are
     * converted into a byte array MSB to LSB. Hence, using BIG_ENDIAN.
     *
     * @see <a href="Bluetooth CoD">https://www.bluetooth.com/specifications/assigned-numbers/baseband</a>
     *
     * @hide
     */
    public byte[] getClassOfDeviceBytes() {
        byte[] bytes = ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(mClass)
                .array();

        // Discard the top byte
        return Arrays.copyOfRange(bytes, 1, bytes.length);
    }

    /** @hide */
    public static final int PROFILE_HEADSET = 0;
    /** @hide */
    public static final int PROFILE_A2DP = 1;
    /** @hide */
    public static final int PROFILE_OPP = 2;
    /** @hide */
    public static final int PROFILE_HID = 3;
    /** @hide */
    public static final int PROFILE_PANU = 4;
    /** @hide */
    public static final int PROFILE_NAP = 5;
    /** @hide */
    public static final int PROFILE_A2DP_SINK = 6;

    /**
     * Check class bits for possible bluetooth profile support.
     * This is a simple heuristic that tries to guess if a device with the
     * given class bits might support specified profile. It is not accurate for all
     * devices. It tries to err on the side of false positives.
     *
     * @param profile The profile to be checked
     * @return True if this device might support specified profile.
     * @hide
     */
    public boolean doesClassMatch(int profile) {
        if (profile == PROFILE_A2DP) {
            if (hasService(Service.RENDER)) {
                return true;
            }
            // By the A2DP spec, sinks must indicate the RENDER service.
            // However we found some that do not (Chordette). So lets also
            // match on some other class bits.
            switch (getDeviceClass()) {
                case Device.AUDIO_VIDEO_HIFI_AUDIO:
                case Device.AUDIO_VIDEO_HEADPHONES:
                case Device.AUDIO_VIDEO_LOUDSPEAKER:
                case Device.AUDIO_VIDEO_CAR_AUDIO:
                    return true;
                default:
                    return false;
            }
        } else if (profile == PROFILE_A2DP_SINK) {
            if (hasService(Service.CAPTURE)) {
                return true;
            }
            // By the A2DP spec, srcs must indicate the CAPTURE service.
            // However if some device that do not, we try to
            // match on some other class bits.
            switch (getDeviceClass()) {
                case Device.AUDIO_VIDEO_HIFI_AUDIO:
                case Device.AUDIO_VIDEO_SET_TOP_BOX:
                case Device.AUDIO_VIDEO_VCR:
                    return true;
                default:
                    return false;
            }
        } else if (profile == PROFILE_HEADSET) {
            // The render service class is required by the spec for HFP, so is a
            // pretty good signal
            if (hasService(Service.RENDER)) {
                return true;
            }
            // Just in case they forgot the render service class
            switch (getDeviceClass()) {
                case Device.AUDIO_VIDEO_HANDSFREE:
                case Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                case Device.AUDIO_VIDEO_CAR_AUDIO:
                    return true;
                default:
                    return false;
            }
        } else if (profile == PROFILE_OPP) {
            if (hasService(Service.OBJECT_TRANSFER)) {
                return true;
            }

            switch (getDeviceClass()) {
                case Device.COMPUTER_UNCATEGORIZED:
                case Device.COMPUTER_DESKTOP:
                case Device.COMPUTER_SERVER:
                case Device.COMPUTER_LAPTOP:
                case Device.COMPUTER_HANDHELD_PC_PDA:
                case Device.COMPUTER_PALM_SIZE_PC_PDA:
                case Device.COMPUTER_WEARABLE:
                case Device.PHONE_UNCATEGORIZED:
                case Device.PHONE_CELLULAR:
                case Device.PHONE_CORDLESS:
                case Device.PHONE_SMART:
                case Device.PHONE_MODEM_OR_GATEWAY:
                case Device.PHONE_ISDN:
                    return true;
                default:
                    return false;
            }
        } else if (profile == PROFILE_HID) {
            return (getDeviceClass() & Device.Major.PERIPHERAL) == Device.Major.PERIPHERAL;
        } else if (profile == PROFILE_PANU || profile == PROFILE_NAP) {
            // No good way to distinguish between the two, based on class bits.
            if (hasService(Service.NETWORKING)) {
                return true;
            }
            return (getDeviceClass() & Device.Major.NETWORKING) == Device.Major.NETWORKING;
        } else {
            return false;
        }
    }
}
