/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.debug;

/**
 * Interface to communicate remotely with the {@code AdbService} in the system server.
 *
 * @hide
 */
interface IAdbManager {
    /**
     * Allow ADB debugging from the attached host. If {@code alwaysAllow} is
     * {@code true}, add {@code publicKey} to list of host keys that the
     * user has approved.
     *
     * @param alwaysAllow if true, add permanently to list of allowed keys
     * @param publicKey RSA key in mincrypt format and Base64-encoded
     */
    void allowDebugging(boolean alwaysAllow, String publicKey);

    /**
     * Deny ADB debugging from the attached host.
     */
    void denyDebugging();

    /**
     * Clear all public keys installed for secure ADB debugging.
     */
    void clearDebuggingKeys();

    /**
     * Allow ADB wireless debugging on the connected network. If {@code alwaysAllow}
     * is {@code true}, add {@code bssid} to list of networks that the user has
     * approved.
     *
     * @param alwaysAllow if true, add permanently to list of allowed networks
     * @param bssid BSSID of the network
     */
    void allowWirelessDebugging(boolean alwaysAllow, String bssid);

    /**
     * Deny ADB wireless debugging on the connected network.
     */
    void denyWirelessDebugging();

    /**
     * Returns a Map<String, PairDevice> with the key fingerprint mapped to the device information.
     */
    Map getPairedDevices();

    /**
     * Unpair the device identified by the key fingerprint it uses.
     *
     * @param fingerprint fingerprint of the key the device is using.
     */
    void unpairDevice(String fingerprint);

    /**
     * Enables pairing by pairing code. The result of the enable will be sent via intent action
     * {@link android.debug.AdbManager#WIRELESS_DEBUG_ENABLE_DISCOVER_ACTION}. Furthermore, the
     * pairing code will also be sent in the intent as an extra
     * @{link android.debug.AdbManager#WIRELESS_PAIRING_CODE_EXTRA}. Note that only one
     * pairing method can be enabled at a time, either by pairing code, or by QR code.
     */
    void enablePairingByPairingCode();

    /**
     * Enables pairing by QR code. The result of the enable will be sent via intent action
     * {@link android.debug.AdbManager#WIRELESS_DEBUG_ENABLE_DISCOVER_ACTION}. Note that only one
     * pairing method can be enabled at a time, either by pairing code, or by QR code.
     *
     * @param serviceName The MDNS service name parsed from the QR code.
     * @param password The password parsed from the QR code.
     */
    void enablePairingByQrCode(String serviceName, String password);

    /**
     * Returns the network port that adb wireless server is running on.
     */
    int getAdbWirelessPort();

    /**
     * Disables pairing.
     */
    void disablePairing();

    /**
     * Returns true if device supports secure Adb over Wi-Fi.
     */
    boolean isAdbWifiSupported();

    /**
     * Returns true if device supports secure Adb over Wi-Fi and device pairing by
     * QR code.
     */
    boolean isAdbWifiQrSupported();
}
