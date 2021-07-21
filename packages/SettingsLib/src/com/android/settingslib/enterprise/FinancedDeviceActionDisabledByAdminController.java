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

import android.content.Context;

import androidx.annotation.Nullable;

/**
 * An {@link ActionDisabledByAdminController} to be used with financed devices.
 */
final class FinancedDeviceActionDisabledByAdminController
        extends BaseActionDisabledByAdminController {

    FinancedDeviceActionDisabledByAdminController(DeviceAdminStringProvider stringProvider) {
        super(stringProvider);
    }

    @Override
    public void setupLearnMoreButton(Context context) {
        assertInitialized();

        mLauncher.setupLearnMoreButtonToShowAdminPolicies(context, mEnforcementAdminUserId,
                mEnforcedAdmin);
    }

    @Override
    public String getAdminSupportTitle(@Nullable String restriction) {
        return mStringProvider.getDisabledByPolicyTitleForFinancedDevice();
    }

    @Override
    public CharSequence getAdminSupportContentString(Context context, CharSequence supportMessage) {
        return supportMessage;
    }
}
