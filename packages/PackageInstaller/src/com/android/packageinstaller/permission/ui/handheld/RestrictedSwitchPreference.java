/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.packageinstaller.permission.ui.handheld;

import android.content.Context;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.TextView;

import com.android.packageinstaller.R;
import com.android.settingslib.RestrictedLockUtils;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

public class RestrictedSwitchPreference extends MultiTargetSwitchPreference {
    private final Context mContext;
    private boolean mDisabledByAdmin;
    private EnforcedAdmin mEnforcedAdmin;
    private final int mSwitchWidgetResId;

    public RestrictedSwitchPreference(Context context) {
        super(context);
        mSwitchWidgetResId = getWidgetLayoutResource();
        mContext = context;
    }

    @Override
    public void onBindView(View view) {
        super.onBindView(view);
        if (mDisabledByAdmin) {
            view.setEnabled(true);
        }
        if (mDisabledByAdmin) {
            final TextView summaryView = (TextView) view.findViewById(android.R.id.summary);
            if (summaryView != null) {
                summaryView.setText(
                        isChecked() ? R.string.enabled_by_admin : R.string.disabled_by_admin);
                summaryView.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled && mDisabledByAdmin) {
            setDisabledByAdmin(null);
        } else {
            super.setEnabled(enabled);
        }
    }

    public void setDisabledByAdmin(EnforcedAdmin admin) {
        final boolean disabled = (admin != null ? true : false);
        mEnforcedAdmin = admin;
        if (mDisabledByAdmin != disabled) {
            mDisabledByAdmin = disabled;
            setWidgetLayoutResource(disabled ? R.layout.restricted_icon : mSwitchWidgetResId);
            setEnabled(!disabled);
        }
    }

    @Override
    public void performClick(PreferenceScreen preferenceScreen) {
        if (mDisabledByAdmin) {
            RestrictedLockUtils.sendShowAdminSupportDetailsIntent(mContext, mEnforcedAdmin);
        } else {
            super.performClick(preferenceScreen);
        }
    }
}