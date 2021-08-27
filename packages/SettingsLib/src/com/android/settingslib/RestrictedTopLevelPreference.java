/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

import android.content.Context;
import android.os.UserHandle;
import android.util.AttributeSet;

import androidx.core.content.res.TypedArrayUtils;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;

/** Top level preference that can be disabled by a device admin using a user restriction. */
public class RestrictedTopLevelPreference extends Preference {
    private RestrictedPreferenceHelper mHelper;

    public RestrictedTopLevelPreference(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mHelper = new RestrictedPreferenceHelper(context, /* preference= */ this, attrs);
    }

    public RestrictedTopLevelPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, /* defStyleRes= */ 0);
    }

    public RestrictedTopLevelPreference(Context context, AttributeSet attrs) {
        this(context, attrs, TypedArrayUtils.getAttr(context, R.attr.preferenceStyle,
                android.R.attr.preferenceStyle));
    }

    public RestrictedTopLevelPreference(Context context) {
        this(context, /* attrs= */ null);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mHelper.onBindViewHolder(holder);
    }

    @Override
    public void performClick() {
        if (!mHelper.performClick()) {
            super.performClick();
        }
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        mHelper.onAttachedToHierarchy();
        super.onAttachedToHierarchy(preferenceManager);
    }

    /**
     * Set the user restriction and disable this preference.
     *
     * @param userRestriction constant from {@link android.os.UserManager}
     */
    public void checkRestrictionAndSetDisabled(String userRestriction) {
        mHelper.checkRestrictionAndSetDisabled(userRestriction, UserHandle.myUserId());
    }

    /**
     * Set the user restriction and disable this preference for the given user.
     *
     * @param userRestriction constant from {@link android.os.UserManager}
     * @param userId          user to check the restriction for.
     */
    public void checkRestrictionAndSetDisabled(String userRestriction, int userId) {
        mHelper.checkRestrictionAndSetDisabled(userRestriction, userId);
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled && isDisabledByAdmin()) {
            mHelper.setDisabledByAdmin(/* admin= */ null);
            return;
        }
        super.setEnabled(enabled);
    }

    /**
     * Check whether this preference is disabled by admin.
     *
     * @return true if this preference is disabled by admin.
     */
    public boolean isDisabledByAdmin() {
        return mHelper.isDisabledByAdmin();
    }

    /**
     * Disable preference based on the enforce admin.
     *
     * @param admin details of the admin who enforced the restriction. If it is {@code null}, then
     *              this preference will be enabled. Otherwise, it will be disabled.
     */
    public void setDisabledByAdmin(EnforcedAdmin admin) {
        if (mHelper.setDisabledByAdmin(admin)) {
            notifyChanged();
        }
    }
}
