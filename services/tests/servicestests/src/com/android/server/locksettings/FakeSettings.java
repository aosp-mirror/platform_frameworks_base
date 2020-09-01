/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.locksettings;

import android.content.ContentResolver;
import android.os.UserHandle;
import android.provider.Settings;

public class FakeSettings {

    private int mDeviceProvisioned;
    private int mSecureFrpMode;
    private int mUserSetupComplete;

    public void setDeviceProvisioned(boolean provisioned) {
        mDeviceProvisioned = provisioned ? 1 : 0;
    }

    public void setSecureFrpMode(boolean secure) {
        mSecureFrpMode = secure ? 1 : 0;
    }

    public void setUserSetupComplete(boolean complete) {
        mUserSetupComplete = complete ? 1 : 0;
    }

    public int globalGetInt(String keyName) {
        switch (keyName) {
            case Settings.Global.DEVICE_PROVISIONED:
                return mDeviceProvisioned;
            default:
                throw new IllegalArgumentException("Unhandled global settings: " + keyName);
        }
    }

    public int secureGetInt(ContentResolver contentResolver, String keyName, int defaultValue,
            int userId) {
        if (Settings.Secure.SECURE_FRP_MODE.equals(keyName) && userId == UserHandle.USER_SYSTEM) {
            return mSecureFrpMode;
        }
        if (Settings.Secure.USER_SETUP_COMPLETE.equals(keyName)
                && userId == UserHandle.USER_SYSTEM) {
            return mUserSetupComplete;
        }
        return defaultValue;
    }
}
