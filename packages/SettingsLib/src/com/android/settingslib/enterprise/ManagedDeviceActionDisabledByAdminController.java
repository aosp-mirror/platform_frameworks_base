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

import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.UserManager;
import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog.Builder;

import com.android.settingslib.RestrictedLockUtils;

/**
 * An {@link ActionDisabledByAdminController} to be used with managed devices.
 */
class ManagedDeviceActionDisabledByAdminController implements
        ActionDisabledByAdminController {
    private @UserIdInt int mEnforcementAdminUserId;
    private RestrictedLockUtils.EnforcedAdmin mEnforcedAdmin;
    private final ActionDisabledLearnMoreButtonLauncher mHelper;
    private final DeviceAdminStringProvider mStringProvider;

    ManagedDeviceActionDisabledByAdminController(
            ActionDisabledLearnMoreButtonLauncher helper,
            DeviceAdminStringProvider stringProvider) {
        mHelper = requireNonNull(helper);
        mStringProvider = requireNonNull(stringProvider);
    }

    @Override
    public void updateEnforcedAdmin(RestrictedLockUtils.EnforcedAdmin admin, int adminUserId) {
        mEnforcementAdminUserId = adminUserId;
        mEnforcedAdmin = requireNonNull(admin);
    }

    @Override
    public void setupLearnMoreButton(Activity activity, Builder builder) {
        String url = mStringProvider.getLearnMoreHelpPageUrl();
        if (TextUtils.isEmpty(url)) {
            mHelper.setupLearnMoreButtonToShowAdminPolicies(
                    activity,
                    builder,
                    mEnforcementAdminUserId,
                    mEnforcedAdmin);
        } else {
            mHelper.setupLearnMoreButtonToLaunchHelpPage(activity, builder, url);
        }
    }

    @Override
    public String getAdminSupportTitle(String restriction) {
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
        if (supportMessage != null) {
            return supportMessage;
        }
        return mStringProvider.getDefaultDisabledByPolicyContent();
    }
}
