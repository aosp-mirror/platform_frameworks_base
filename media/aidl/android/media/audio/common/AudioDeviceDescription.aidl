/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.audio.common;

import android.media.audio.common.AudioDeviceType;

/**
 * Describes the kind of an audio device.
 *
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
parcelable AudioDeviceDescription {
    /**
     * Type and directionality of the device. For bidirectional audio devices
     * two descriptions need to be created, having the same value for
     * the 'connection' field.
     *
     * See 'AudioDeviceType' for the list of supported values.
     */
    AudioDeviceType type = AudioDeviceType.NONE;
    /**
     * Specifies the type of the connection of the device to the audio system.
     * Usually it's some kind of a communication protocol, e.g. Bluetooth SCO or
     * USB. There is a list of connection types recognized by the framework,
     * defined using 'CONNECTION_' constants. Vendors can add their own
     * connection types with "VX_<vendor>_" prefix, where the "vendor" part
     * must consist of at least 3 letters or numbers.
     *
     * When the 'connection' field is left empty and 'type != NONE | DEFAULT',
     * it is assumed that the device is permanently attached to the audio
     * system, e.g. a built-in speaker or microphone.
     *
     * The 'connection' field must be left empty if 'type' is 'NONE' or
     * '{IN|OUT}_DEFAULT'.
     */
    @utf8InCpp String connection;
    /**
     * Analog connection, for example, via 3.5 mm analog jack,
     * or a low-end (analog) desk dock.
     */
    const @utf8InCpp String CONNECTION_ANALOG = "analog";
    /**
     * Bluetooth A2DP connection.
     */
    const @utf8InCpp String CONNECTION_BT_A2DP = "bt-a2dp";
    /**
     * Bluetooth Low Energy (LE) connection.
     */
    const @utf8InCpp String CONNECTION_BT_LE = "bt-le";
    /**
     * Bluetooth SCO connection.
     */
    const @utf8InCpp String CONNECTION_BT_SCO = "bt-sco";
    /**
     * Bus connection. Mostly used in automotive scenarios.
     */
    const @utf8InCpp String CONNECTION_BUS = "bus";
    /**
     * HDMI connection.
     */
    const @utf8InCpp String CONNECTION_HDMI = "hdmi";
    /**
     * HDMI ARC connection.
     */
    const @utf8InCpp String CONNECTION_HDMI_ARC = "hdmi-arc";
    /**
     * HDMI eARC connection.
     */
    const @utf8InCpp String CONNECTION_HDMI_EARC = "hdmi-earc";
    /**
     * IP v4 connection.
     */
    const @utf8InCpp String CONNECTION_IP_V4 = "ip-v4";
    /**
     * SPDIF connection.
     */
    const @utf8InCpp String CONNECTION_SPDIF = "spdif";
    /**
     * A wireless connection when the actual protocol is unspecified.
     */
    const @utf8InCpp String CONNECTION_WIRELESS = "wireless";
    /**
     * USB connection. The Android device is the USB Host.
     */
    const @utf8InCpp String CONNECTION_USB = "usb";
}
