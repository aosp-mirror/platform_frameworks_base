/*
 * Copyright (C) 2022 The Android Open Source Project
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


import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.RestrictedLockUtils;


final class SupervisedDeviceActionDisabledByAdminController
        extends BaseActionDisabledByAdminController {
    private static final String TAG = "SupervisedDeviceActionDisabledByAdminController";
    private final String mRestriction;

    SupervisedDeviceActionDisabledByAdminController(
            DeviceAdminStringProvider stringProvider, String restriction) {
        super(stringProvider);
        mRestriction = restriction;
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
        return mStringProvider.getDisabledByParentContent();
    }

    @Nullable
    @Override
    public DialogInterface.OnClickListener getPositiveButtonListener(@NonNull Context context,
            @NonNull RestrictedLockUtils.EnforcedAdmin enforcedAdmin) {
        if (enforcedAdmin.component == null
                || TextUtils.isEmpty(enforcedAdmin.component.getPackageName())) {
            return null;
        }

        final Intent intent = new Intent(Settings.ACTION_MANAGE_SUPERVISOR_RESTRICTED_SETTING)
                .setData(new Uri.Builder()
                        .scheme("policy")
                        .appendPath("user_restrictions")
                        .appendPath(mRestriction)
                        .build())
                .setPackage(enforcedAdmin.component.getPackageName());
        ComponentName resolvedSupervisionActivity =
                intent.resolveActivity(context.getPackageManager());
        if (resolvedSupervisionActivity == null) {
            return null;
        }
        return (dialog, which) -> {
            context.startActivity(intent);
        };
    }
}
