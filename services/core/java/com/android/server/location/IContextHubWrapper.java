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
package com.android.server.location;

import android.annotation.Nullable;
import android.hardware.contexthub.V1_1.Setting;
import android.hardware.contexthub.V1_1.SettingValue;
import android.os.RemoteException;
import android.util.Log;

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

    private static class ContextHubWrapperV1_0 extends IContextHubWrapper {
        private android.hardware.contexthub.V1_0.IContexthub mHub;

        ContextHubWrapperV1_0(android.hardware.contexthub.V1_0.IContexthub hub) {
            mHub = hub;
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

        public void onLocationSettingChanged(boolean enabled) {}

        public void onWifiSettingChanged(boolean enabled) {}
    }

    private static class ContextHubWrapperV1_1 extends IContextHubWrapper {
        private android.hardware.contexthub.V1_1.IContexthub mHub;

        ContextHubWrapperV1_1(android.hardware.contexthub.V1_1.IContexthub hub) {
            mHub = hub;
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

        public void onLocationSettingChanged(boolean enabled) {
            try {
                mHub.onSettingChanged(Setting.LOCATION,
                        enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
            } catch  (RemoteException e) {
                Log.e(TAG, "Failed to send setting change to Contexthub", e);
            }
        }

        public void onWifiSettingChanged(boolean enabled) {}
    }

    private static class ContextHubWrapperV1_2 extends IContextHubWrapper {
        private android.hardware.contexthub.V1_2.IContexthub mHub;

        ContextHubWrapperV1_2(android.hardware.contexthub.V1_2.IContexthub hub) {
            mHub = hub;
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

        public void onLocationSettingChanged(boolean enabled) {
            sendSettingChanged(Setting.LOCATION,
                    enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
        }

        public void onWifiSettingChanged(boolean enabled) {
            sendSettingChanged(android.hardware.contexthub.V1_2.Setting.WIFI_AVAILABLE,
                    enabled ? SettingValue.ENABLED : SettingValue.DISABLED);
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
