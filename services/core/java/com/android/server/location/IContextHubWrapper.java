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

        ContextHubWrapperV1_0 wrapper = null;
        if (proxy != null) {
            wrapper = new ContextHubWrapperV1_0(proxy);
        }

        return wrapper;
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

        ContextHubWrapperV1_1 wrapper = null;
        if (proxy != null) {
            wrapper = new ContextHubWrapperV1_1(proxy);
        }

        return wrapper;
    }

    /**
     * @return A valid instance of Contexthub HAL 1.0.
     */
    public abstract android.hardware.contexthub.V1_0.IContexthub getHub();

    /**
     * @return True if this version of the Contexthub HAL supports setting notifications.
     */
    public abstract boolean supportsSettingNotifications();

    /**
     * Notifies the Contexthub implementation of a user setting change.
     *
     * @param setting The user setting that has changed. MUST be one of the values from the
     *     {@link Setting} enum
     * @param newValue The value of the user setting that changed. MUST be one of the values
     *     from the {@link SettingValue} enum.
     */
    public abstract void onSettingChanged(byte setting, byte newValue);

    private static class ContextHubWrapperV1_0 extends IContextHubWrapper {
        private android.hardware.contexthub.V1_0.IContexthub mHub;

        ContextHubWrapperV1_0(android.hardware.contexthub.V1_0.IContexthub hub) {
            mHub = hub;
        }

        public android.hardware.contexthub.V1_0.IContexthub getHub() {
            return mHub;
        }

        public boolean supportsSettingNotifications() {
            return false;
        }

        public void onSettingChanged(byte setting, byte newValue) {}
    }

    private static class ContextHubWrapperV1_1 extends IContextHubWrapper {
        private android.hardware.contexthub.V1_1.IContexthub mHub;

        ContextHubWrapperV1_1(android.hardware.contexthub.V1_1.IContexthub hub) {
            mHub = hub;
        }

        public android.hardware.contexthub.V1_0.IContexthub getHub() {
            return mHub;
        }

        public boolean supportsSettingNotifications() {
            return true;
        }

        public void onSettingChanged(byte setting, byte newValue) {
            try {
                mHub.onSettingChanged(setting, newValue);
            } catch  (RemoteException e) {
                Log.e(TAG, "Failed to send setting change to Contexthub", e);
            }
        }
    }
}
