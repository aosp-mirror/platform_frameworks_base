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

import static java.util.Objects.requireNonNull;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.Objects;


/**
 * An {@link ActionDisabledByAdminController} to be used with managed devices.
 */
final class ManagedDeviceActionDisabledByAdminController
        extends BaseActionDisabledByAdminController {

    private final UserHandle mUserHandle;

    /**
     * Constructs a {@link ManagedDeviceActionDisabledByAdminController}
     * @param userHandle - user on which to launch the help web page, if necessary
     */
    ManagedDeviceActionDisabledByAdminController(
            DeviceAdminStringProvider stringProvider,
            UserHandle userHandle) {
        super(stringProvider);
        mUserHandle = requireNonNull(userHandle);
    }

    @Override
    public void setupLearnMoreButton(Context context) {
        assertInitialized();

        String url = mStringProvider.getLearnMoreHelpPageUrl();
        if (TextUtils.isEmpty(url)) {
            mLauncher.setupLearnMoreButtonToShowAdminPolicies(context, mEnforcementAdminUserId,
                    mEnforcedAdmin);
        } else {
            mLauncher.setupLearnMoreButtonToLaunchHelpPage(context, url, mUserHandle);
        }
    }

    @Override
    public String getAdminSupportTitle(@Nullable String restriction) {
        if (restriction == null) {
            return mStringProvider.getDefaultDisabledByPolicyTitle();
        }
        switch (restriction) {
            case UserManager.DISALLOW_ADJUST_VOLUME:
                return mStringProvider.getDisallowAdjustVolumeTitle();
            case UserManager.DISALLOW_OUTGOING_CALLS:
                return mStringProvider.getDisallowOutgoingCallsTitle();
            case UserManager.DISALLOW_SMS:
                return mStringProvider.getDisallowSmsTitle();
            case DevicePolicyManager.POLICY_DISABLE_CAMERA:
                return mStringProvider.getDisableCameraTitle();
            case DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE:
                return mStringProvider.getDisableScreenCaptureTitle();
            case DevicePolicyManager.POLICY_SUSPEND_PACKAGES:
                return mStringProvider.getSuspendPackagesTitle();
            default:
                return mStringProvider.getDefaultDisabledByPolicyTitle();
        }
    }

    @Override
    public CharSequence getAdminSupportContentString(Context context, CharSequence supportMessage) {
        return supportMessage != null
                ? supportMessage
                : mStringProvider.getDefaultDisabledByPolicyContent();
    }
}
