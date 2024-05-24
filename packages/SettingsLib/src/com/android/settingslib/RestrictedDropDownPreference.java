/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.preference.DropDownPreference;
import androidx.preference.PreferenceViewHolder;

public class RestrictedDropDownPreference extends DropDownPreference {
    RestrictedPreferenceHelper mHelper;

    public RestrictedDropDownPreference(@NonNull Context context) {
        super(context);
        mHelper = new RestrictedPreferenceHelper(context, this, null);
    }

    /**
     * Checks if the given setting is subject to Enhanced Confirmation Mode restrictions for this
     * package. Marks the preference as disabled if so.
     * @param settingIdentifier The key identifying the setting
     * @param packageName the package to check the settingIdentifier for
     */
    public void checkEcmRestrictionAndSetDisabled(@NonNull String settingIdentifier,
            @NonNull String packageName) {
        mHelper.checkEcmRestrictionAndSetDisabled(settingIdentifier, packageName);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mHelper.onBindViewHolder(holder);
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled && isDisabledByEcm()) {
            mHelper.setDisabledByEcm(null);
            return;
        }

        super.setEnabled(enabled);
    }

    @Override
    public void performClick() {
        if (!mHelper.performClick()) {
            super.performClick();
        }
    }

    public boolean isDisabledByEcm() {
        return mHelper.isDisabledByEcm();
    }
}
