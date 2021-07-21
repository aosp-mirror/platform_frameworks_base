/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.location.contexthub;

import android.annotation.Nullable;
import android.hardware.contexthub.V1_0.ContextHub;
import android.hardware.contexthub.V1_1.Setting;
import android.hardware.contexthub.V1_1.SettingValue;
import android.hardware.contexthub.V1_2.IContexthubCallback;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * @hide
 */
public abstract class IContextHubWrapper {
    private static final String TAG = "IContextHubWrapper";

    /**
     * Attempts to connect to the Contexthub HAL 1.0 service, if it exists.
     *
     * @return A valid IContextHubWrapper if the connection was successful, null otherwise.
     */
    @Nullable
    public static IContextHubWrapper maybeConnectTo1_0() {
        android.hardware.contexthub.V1_0.IContexthub proxy = null;
        try {
            proxy = android.hardware.contexthub.V1_0.IContexthub.getService(true /* retry */);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while attaching to Context Hub HAL proxy", e);
        } catch (NoSuchElementException e) {
            Log.i(TAG, "Context Hub HAL service not found");
        }

        return (proxy == null) ? null : new ContextHubWrapperV1_0(proxy);
    }

    /**
     * Attempts to connect to the Contexthub HAL 1.1 service, if it exists.
     *
     * @return A valid IContextHubWrapper if the connection was successful, null otherwise.
     */
    @Nullable
    public static IContextHubWrapper maybeConnectTo1_1() {
        android.hardware.contexthub.V1_1.IContexthub proxy = null;
        try {
            proxy = android.hardware.contexthub.V1_1.IContexthub.getService(true /* retry */);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while attaching to Context Hub HAL proxy", e);
        } catch (NoSuchElementException e) {
            Log.i(TAG, "Context Hub HAL service not found");
        }

        return (proxy == null) ? null : new ContextHubWrapperV1_1(proxy);
    }

    /**
     * Attempts to connect to the Contexthub HAL 1.2 service, if it exists.
     *
     * @return A valid IContextHubWrapper if the connection was successful, null otherwise.
     */
    @Nullable
    public static IContextHubWrapper maybeConnectTo1_2() {
        android.hardware.contexthub.V1_2.IContexthub proxy = null;
        try {
            proxy = android.hardware.contexthub.V1_2.IContexthub.getService(true /* retry */);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while attaching to Context Hub HAL proxy", e);
        } catch (NoSuchElementException e) {
            Log.i(TAG, "Context Hub HAL service not found");
        }

        return (proxy == null) ? null : new ContextHubWrapperV1_2(proxy);
    }

    /**
     * Calls the appropriate getHubs function depending on the HAL version.
     */
    public abstract Pair<List<ContextHub>, List<String>> getHubs() throws RemoteException;

    /**
     * Calls the appropriate registerCallback function depending on the HAL version.
     */
    public abstract void registerCallback(
            int hubId, IContexthubCallback callback) throws RemoteException;

    /**
     * @return A valid instance of Contexthub HAL 1.0.
     */
    public abstract android.hardware.contexthub.V1_0.IContexthub getHub();

    /**
     * @return True if this version of the Contexthub HAL supports Location setting notifications.
     */
    public abstract boolean supportsLocationSettingNotifications();

    /**
     * Notifies the Contexthub implementation of a user Location setting change.
     *
     * @param enabled True if the Location setting has been enabled.
     */
    public abstract void onLocationSettingChanged(boolean enabled);

    /**
     * @return True if this version of the Contexthub HAL supports WiFi availability setting
     * notifications.
     */
    public abstract boolean supportsWifiSettingNotifications();

    /**
     * Notifies the Contexthub implementation of a user WiFi availability setting change.
     *
     * @param enabled true if the WiFi availability setting has been enabled.
     */
    public abstract void onWifiSettingChanged(boolean enabled);

    /**
     * @return True if this version of the Contexthub HAL supports airplane mode setting
     * notifications.
     */
    public abstract boolean supportsAirplaneModeSettingNotifications();

    /**
     * Notifies the Contexthub implementation of an airplane mode setting change.
     *
     * @param enabled true if the airplane mode setting has been enabled.
     */
    public abstract void onAirplaneModeSettingChanged(boolean enabled);

    /**
     * @return True if this version of the Contexthub HAL supports microphone
     * disable setting notifications.
     */
    public abstract boolean supportsMicrophoneDisableSettingNotifications();

    /**
     * Notifies the Contexthub implementation of a microphone disable setting
     * change.
     */
    public abstract void onMicrophoneDisableSettingChanged(boolean enabled);

    private static class ContextHubWrapperV1_0 extends IContextHubWrapper {
        private android.hardware.contexthub.V1_0.IContexthub mHub;

        ContextHubWrapperV1_0(android.hardware.contexthub.V1_0.IContexthub hub) {
            mHub = hub;
        }

        public Pair<List<ContextHub>, List<String>> getHubs() throws RemoteException {
            return new Pair(mHub.getHubs(), new ArrayList<String>());
        }

        public void registerCallback(
                int hubId, IContexthubCallback callback) throws RemoteException {
            mHub.registerCallback(hubId, callback);
        }

        public android.hardware.contexthub.V1_0.IContexthub getHub() {
            return mHub;
        }

        public boolean supportsLocationSettingNotifications() {
            return false;
        }

        public boolean supportsWifiSettingNotifications() {
            return false;
        }

        public boolean supportsAirplaneModeSettingNotifications() {
            return false;
        }

        public boolean supportsMicrophoneDisableSettingNotifications() {
            return false;
        }

        public void onLocationSettingChanged(boolean enabled) {
        }

        public void onWifiSettingChanged(boolean enabled) {
        }

        public void onAirplaneModeSettingChanged(boolean enabled) {
        }

        public void onMicrophoneDisableSettingChanged(boolean enabled) {
        }
    }

    private static class ContextHubWrapperV1_1 extends IContextHubWrapper {
        private android.hardware.contexthub.V1_1.IContexthub mHub;

        ContextHubWrapperV1_1(android.hardware.contexthub.V1_1.IContexthub hub) {
            mHub = hub;
        }

        public Pair<List<ContextHub>, List<String>> getHubs() throws RemoteException {
            return new Pair(mHub.getHubs(), new ArrayList<String>());
        }

        public void registerCallback(
                int hubId, IContexthubCallback callback) throws RemoteException {
            mHub.registerCallback(hubId, callback);
        }

        public android.hardware.contexthub.V1_0.IContexthub getHub() {
            return mHub;
        }

        public boolean supportsLocationSettingNotifications() {
            return true;
        }

        public boolean supportsWifiSettingNotifications() {
            return false;
        }

        public boolean supportsAirplaneModeSettingNotifications() {
            return false;
        }

        public boolean supportsMicrophoneDisableSettingNotifications() {
            return false;
        }

        public void onLocationSettingChanged(boolean enabled) {
            try {
                mHub.onSettingChanged(Setting.LOCATION,
                        enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send setting change to Contexthub", e);
            }
        }

        public void onWifiSettingChanged(boolean enabled) {
        }

        public void onAirplaneModeSettingChanged(boolean enabled) {
        }

        public void onMicrophoneDisableSettingChanged(boolean enabled) {
        }
    }

    private static class ContextHubWrapperV1_2 extends IContextHubWrapper
            implements android.hardware.contexthub.V1_2.IContexthub.getHubs_1_2Callback {
        private final android.hardware.contexthub.V1_2.IContexthub mHub;

        private Pair<List<ContextHub>, List<String>> mHubInfo =
                new Pair<>(Collections.emptyList(), Collections.emptyList());

        ContextHubWrapperV1_2(android.hardware.contexthub.V1_2.IContexthub hub) {
            mHub = hub;
        }

        @Override
        public void onValues(ArrayList<ContextHub> hubs, ArrayList<String> supportedPermissions) {
            mHubInfo = new Pair(hubs, supportedPermissions);
        }

        public Pair<List<ContextHub>, List<String>> getHubs() throws RemoteException {
            mHub.getHubs_1_2(this);
            return mHubInfo;
        }

        public void registerCallback(
                int hubId, IContexthubCallback callback) throws RemoteException {
            mHub.registerCallback_1_2(hubId, callback);
        }

        public android.hardware.contexthub.V1_0.IContexthub getHub() {
            return mHub;
        }

        public boolean supportsLocationSettingNotifications() {
            return true;
        }

        public boolean supportsWifiSettingNotifications() {
            return true;
        }

        public boolean supportsAirplaneModeSettingNotifications() {
            return true;
        }

        public boolean supportsMicrophoneDisableSettingNotifications() {
            return true;
        }

        public void onLocationSettingChanged(boolean enabled) {
            sendSettingChanged(Setting.LOCATION,
                    enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
        }

        public void onWifiSettingChanged(boolean enabled) {
            sendSettingChanged(android.hardware.contexthub.V1_2.Setting.WIFI_AVAILABLE,
                    enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
        }

        public void onAirplaneModeSettingChanged(boolean enabled) {
            sendSettingChanged(android.hardware.contexthub.V1_2.Setting.AIRPLANE_MODE,
                    enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
        }

        public void onMicrophoneDisableSettingChanged(boolean enabled) {
            // The SensorPrivacyManager reports if microphone privacy was enabled,
            // which translates to microphone access being disabled (and vice-versa).
            // With this in mind, we flip the argument before piping it to CHRE.
            sendSettingChanged(android.hardware.contexthub.V1_2.Setting.MICROPHONE,
                    enabled ? SettingValue.DISABLED : SettingValue.ENABLED);
        }

        private void sendSettingChanged(byte setting, byte newValue) {
            try {
                mHub.onSettingChanged_1_2(setting, newValue);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send setting change to Contexthub", e);
            }
        }
    }
}
