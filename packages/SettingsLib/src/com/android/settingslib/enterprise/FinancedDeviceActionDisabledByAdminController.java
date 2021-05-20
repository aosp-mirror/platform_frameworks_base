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
import android.content.Context;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog.Builder;

import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

/**
 * An {@link ActionDisabledByAdminController} to be used with financed devices.
 */
public class FinancedDeviceActionDisabledByAdminController
        implements ActionDisabledByAdminController {

    private @UserIdInt int mEnforcementAdminUserId;
    private EnforcedAdmin mEnforcedAdmin;
    private final ActionDisabledLearnMoreButtonLauncher mHelper;
    private final DeviceAdminStringProvider mDeviceAdminStringProvider;

    FinancedDeviceActionDisabledByAdminController(
            ActionDisabledLearnMoreButtonLauncher helper,
            DeviceAdminStringProvider deviceAdminStringProvider) {
        mHelper = requireNonNull(helper);
        mDeviceAdminStringProvider = requireNonNull(deviceAdminStringProvider);
    }

    @Override
    public void updateEnforcedAdmin(EnforcedAdmin admin, int adminUserId) {
        mEnforcementAdminUserId = adminUserId;
        mEnforcedAdmin = requireNonNull(admin);
    }

    @Override
    public void setupLearnMoreButton(Activity activity, Builder builder) {
        mHelper.setupLearnMoreButtonToShowAdminPolicies(
                activity,
                builder,
                mEnforcementAdminUserId,
                mEnforcedAdmin);
    }

    @Override
    public String getAdminSupportTitle(@Nullable String restriction) {
        return mDeviceAdminStringProvider.getDisabledByPolicyTitleForFinancedDevice();
    }

    @Override
    public CharSequence getAdminSupportContentString(Context context, CharSequence supportMessage) {
        return supportMessage;
    }
}
