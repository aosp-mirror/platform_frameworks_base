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

/**
 * This structure defines various representations for the audio device
 * address.
 *
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
union AudioDeviceAddress {
    /**
     * String uniquely identifying the device among other devices
     * of the same type. Can be empty in case there is only one device
     * of this type.
     *
     * Depending on the device type, its id may be assigned by the framework
     * (one case used at the time of writing is REMOTE_SUBMIX), or assigned by
     * the HAL service (the canonical examples are BUS and MIC devices). In any
     * case, both framework and HAL must never attempt to parse the value of the
     * 'id' field, regardless of whom has generated it. If the address must be
     * parsed, one of the members below must be used instead of 'id'.
     */
    @utf8InCpp String id;
    /**
     * IEEE 802 MAC address. Set for Bluetooth devices. The array must have
     * exactly 6 elements.
     */
    byte[] mac;
    /**
     * IPv4 Address. Set for IPv4 devices. The array must have exactly 4
     * elements.
     */
    byte[] ipv4;
    /**
     * IPv6 Address. Set for IPv6 devices. The array must have exactly 8
     * elements.
     */
    int[] ipv6;
    /**
     * PCI bus Address. Set for USB devices. The array must have exactly 2
     * elements, in the following order: the card id, and the device id.
     */
    int[] alsa;
}
