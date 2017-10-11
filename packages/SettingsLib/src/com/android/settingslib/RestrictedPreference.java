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
import android.util.AttributeSet;
import android.view.View;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

/**
 * Preference class that supports being disabled by a user restriction
 * set by a device admin.
 */
public class RestrictedPreference extends TwoTargetPreference {
    RestrictedPreferenceHelper mHelper;

    public RestrictedPreference(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mHelper = new RestrictedPreferenceHelper(context, this, attrs);
    }

    public RestrictedPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RestrictedPreference(Context context, AttributeSet attrs) {
        this(context, attrs, TypedArrayUtils.getAttr(context,
                android.support.v7.preference.R.attr.preferenceStyle,
                android.R.attr.preferenceStyle));
    }

    public RestrictedPreference(Context context) {
        this(context, null);
    }

    @Override
    protected int getSecondTargetResId() {
        return R.layout.restricted_icon;
    }

    @Override
    protected boolean shouldHideSecondTarget() {
        return !isDisabledByAdmin();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mHelper.onBindViewHolder(holder);
        final View restrictedIcon = holder.findViewById(R.id.restricted_icon);
        if (restrictedIcon != null) {
            restrictedIcon.setVisibility(isDisabledByAdmin() ? View.VISIBLE : View.GONE);
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
