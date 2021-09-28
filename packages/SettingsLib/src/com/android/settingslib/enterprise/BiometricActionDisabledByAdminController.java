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

import android.annotation.NonNull;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.settingslib.RestrictedLockUtils;

public class BiometricActionDisabledByAdminController extends BaseActionDisabledByAdminController {

    private static final String TAG = "BiometricActionDisabledByAdminController";

    // These MUST not change, as they are the stable API between here and device admin specified
    // by the component below.
    private static final String ACTION_LEARN_MORE =
            "android.intent.action.MANAGE_RESTRICTED_SETTING";
    private static final String EXTRA_SETTING_KEY = "extra_setting";
    private static final String EXTRA_SETTING_VALUE = "biometric_disabled_by_admin_controller";

    BiometricActionDisabledByAdminController(
            DeviceAdminStringProvider stringProvider) {
        super(stringProvider);
    }

    @Override
    public void setupLearnMoreButton(Context context) {

    }

    @Override
    public String getAdminSupportTitle(@Nullable String restriction) {
        return mStringProvider.getDisabledBiometricsParentConsentTitle();
    }

    @Override
    public CharSequence getAdminSupportContentString(Context context,
            @Nullable CharSequence supportMessage) {
        return mStringProvider.getDisabledBiometricsParentConsentContent();
    }

    @Override
    public DialogInterface.OnClickListener getPositiveButtonListener(@NonNull Context context,
            @NonNull RestrictedLockUtils.EnforcedAdmin enforcedAdmin) {
        return (dialog, which) -> {
            Log.d(TAG, "Positive button clicked, component: " + enforcedAdmin.component);
            final Intent intent = new Intent(ACTION_LEARN_MORE)
                    .putExtra(EXTRA_SETTING_KEY, EXTRA_SETTING_VALUE)
                    .setPackage(enforcedAdmin.component.getPackageName());
            context.startActivity(intent);
        };
    }
}
