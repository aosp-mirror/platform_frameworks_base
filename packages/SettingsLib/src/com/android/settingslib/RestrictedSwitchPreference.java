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
 * limitations under the License
 */

package com.android.settingslib;

import android.content.Context;
import android.os.UserHandle;
import android.support.v4.content.res.TypedArrayUtils;
import android.support.v7.preference.PreferenceManager;
import android.support.v7.preference.PreferenceViewHolder;
import android.support.v14.preference.SwitchPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

/**
 * Version of SwitchPreference that can be disabled by a device admin
 * using a user restriction.
 */
public class RestrictedSwitchPreference extends SwitchPreference {
    RestrictedPreferenceHelper mHelper;

    public RestrictedSwitchPreference(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setWidgetLayoutResource(R.layout.restricted_switch_widget);
        mHelper = new RestrictedPreferenceHelper(context, this, attrs);
    }

    public RestrictedSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RestrictedSwitchPreference(Context context, AttributeSet attrs) {
        this(context, attrs, TypedArrayUtils.getAttr(context,
                android.support.v7.preference.R.attr.switchPreferenceStyle,
                android.R.attr.switchPreferenceStyle));
    }

    public RestrictedSwitchPreference(Context context) {
        this(context, null);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mHelper.onBindViewHolder(holder);
        final View restrictedIcon = holder.findViewById(R.id.restricted_icon);
        final View switchWidget = holder.findViewById(android.R.id.switch_widget);
        if (restrictedIcon != null) {
            restrictedIcon.setVisibility(isDisabledByAdmin() ? View.VISIBLE : View.GONE);
        }
        if (switchWidget != null) {
            switchWidget.setVisibility(isDisabledByAdmin() ? View.GONE : View.VISIBLE);
        }
        final TextView summaryView = (TextView) holder.findViewById(android.R.id.summary);
        if (summaryView != null && isDisabledByAdmin()) {
            summaryView.setText(
                    isChecked() ? R.string.enabled_by_admin : R.string.disabled_by_admin);
            summaryView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void performClick() {
        if (!mHelper.performClick()) {
            super.performClick();
        }
    }

    public void useAdminDisabledSummary(boolean useSummary) {
        mHelper.useAdminDisabledSummary(useSummary);
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        mHelper.onAttachedToHierarchy();
        super.onAttachedToHierarchy(preferenceManager);
    }

    public void checkRestrictionAndSetDisabled(String userRestriction) {
        mHelper.checkRestrictionAndSetDisabled(userRestriction, UserHandle.myUserId());
    }

    public void checkRestrictionAndSetDisabled(String userRestriction, int userId) {
        mHelper.checkRestrictionAndSetDisabled(userRestriction, userId);
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled && isDisabledByAdmin()) {
            mHelper.setDisabledByAdmin(null);
            return;
        }
        super.setEnabled(enabled);
    }

    public void setDisabledByAdmin(EnforcedAdmin admin) {
        if (mHelper.setDisabledByAdmin(admin)) {
            notifyChanged();
        }
    }

    public boolean isDisabledByAdmin() {
        return mHelper.isDisabledByAdmin();
    }
}
