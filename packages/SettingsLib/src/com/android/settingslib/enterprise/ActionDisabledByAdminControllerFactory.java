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

package com.android.settingslib.enterprise;

import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_FINANCED;

import static com.android.settingslib.enterprise.ActionDisabledLearnMoreButtonLauncher.DEFAULT_RESOLVE_ACTIVITY_CHECKER;
import static com.android.settingslib.enterprise.ManagedDeviceActionDisabledByAdminController.DEFAULT_FOREGROUND_USER_CHECKER;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.ParentalControlsUtilsInternal;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;

/**
 * A factory that returns the relevant instance of {@link ActionDisabledByAdminController}.
 */
public final class ActionDisabledByAdminControllerFactory {

    /**
     * Returns the relevant instance of {@link ActionDisabledByAdminController}.
     * @param userHandle user on which to launch the help page, if necessary
     */
    public static ActionDisabledByAdminController createInstance(Context context,
            String restriction, DeviceAdminStringProvider stringProvider,
            UserHandle userHandle) {
        if (doesBiometricRequireParentalConsent(context, restriction)) {
            return new BiometricActionDisabledByAdminController(stringProvider);
        } else if (isFinancedDevice(context)) {
            return new FinancedDeviceActionDisabledByAdminController(stringProvider);
        } else {
            return new ManagedDeviceActionDisabledByAdminController(
                    stringProvider,
                    userHandle,
                    DEFAULT_FOREGROUND_USER_CHECKER,
                    DEFAULT_RESOLVE_ACTIVITY_CHECKER);
        }
    }

    /**
     * @return true if the restriction == UserManager.DISALLOW_BIOMETRIC and parental consent
     * is required.
     */
    private static boolean doesBiometricRequireParentalConsent(Context context,
            String restriction) {
        if (!TextUtils.equals(UserManager.DISALLOW_BIOMETRIC, restriction)) {
            return false;
        }
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return ParentalControlsUtilsInternal.parentConsentRequired(context, dpm,
                BiometricAuthenticator.TYPE_ANY_BIOMETRIC, new UserHandle(UserHandle.myUserId()));
    }

    private static boolean isFinancedDevice(Context context) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return dpm.isDeviceManaged() && dpm.getDeviceOwnerType(
                dpm.getDeviceOwnerComponentOnAnyUser()) == DEVICE_OWNER_TYPE_FINANCED;
    }

    private ActionDisabledByAdminControllerFactory() {
        throw new UnsupportedOperationException("provides only static methods");
    }
}
