/*
 * Copyright (C) 2024 The Android Open Source Project
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.SelectorWithWidgetPreference;

/**
 * Selector with widget preference that can be disabled by a device admin using a user restriction.
 */
public class RestrictedSelectorWithWidgetPreference extends SelectorWithWidgetPreference {
    private RestrictedPreferenceHelper mHelper;

    /**
     * Perform inflation from XML and apply a class-specific base style.
     *
     * @param context The {@link Context} this is associated with, through which it can access the
     *     current theme, resources, {@link SharedPreferences}, etc.
     * @param attrs The attributes of the XML tag that is inflating the preference
     * @param defStyle An attribute in the current theme that contains a reference to a style
     *     resource that supplies default values for the view. Can be 0 to not look for defaults.
     */
    public RestrictedSelectorWithWidgetPreference(
            @NonNull Context context, @NonNull AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mHelper = new RestrictedPreferenceHelper(context, /* preference= */ this, attrs);
    }

    /**
     * Perform inflation from XML and apply a class-specific base style.
     *
     * @param context The {@link Context} this is associated with, through which it can access the
     *     current theme, resources, {@link SharedPreferences}, etc.
     * @param attrs The attributes of the XML tag that is inflating the preference
     */
    public RestrictedSelectorWithWidgetPreference(
            @NonNull Context context, @NonNull AttributeSet attrs) {
        super(context, attrs);
        mHelper = new RestrictedPreferenceHelper(context, /* preference= */ this, attrs);
    }

    /**
     * Constructor to create a preference, which will display with a checkbox style.
     *
     * @param context The {@link Context} this is associated with.
     * @param isCheckbox Whether this preference should display as a checkbox.
     */
    public RestrictedSelectorWithWidgetPreference(@NonNull Context context, boolean isCheckbox) {
        super(context, null);
        mHelper =
                new RestrictedPreferenceHelper(context, /* preference= */ this, /* attrs= */ null);
    }

    /**
     * Constructor to create a preference.
     *
     * @param context The Context this is associated with.
     */
    public RestrictedSelectorWithWidgetPreference(@NonNull Context context) {
        this(context, null);
        mHelper =
                new RestrictedPreferenceHelper(context, /* preference= */ this, /* attrs= */ null);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
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
    protected void onAttachedToHierarchy(@NonNull PreferenceManager preferenceManager) {
        mHelper.onAttachedToHierarchy();
        super.onAttachedToHierarchy(preferenceManager);
    }

    /**
     * Set the user restriction and disable this preference.
     *
     * @param userRestriction constant from {@link android.os.UserManager}
     */
    public void checkRestrictionAndSetDisabled(@NonNull String userRestriction) {
        mHelper.checkRestrictionAndSetDisabled(userRestriction, UserHandle.myUserId());
    }

    /**
     * Set the user restriction and disable this preference for the given user.
     *
     * @param userRestriction constant from {@link android.os.UserManager}
     * @param userId user to check the restriction for.
     */
    public void checkRestrictionAndSetDisabled(@NonNull String userRestriction, int userId) {
        mHelper.checkRestrictionAndSetDisabled(userRestriction, userId);
    }

    /**
     * Checks if the given setting is subject to Enhanced Confirmation Mode restrictions for this
     * package. Marks the preference as disabled if so.
     *
     * @param settingIdentifier The key identifying the setting
     * @param packageName the package to check the settingIdentifier for
     */
    public void checkEcmRestrictionAndSetDisabled(
            @NonNull String settingIdentifier, @NonNull String packageName) {
        mHelper.checkEcmRestrictionAndSetDisabled(settingIdentifier, packageName);
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
     *     this preference will be enabled. Otherwise, it will be disabled.
     */
    public void setDisabledByAdmin(@Nullable EnforcedAdmin admin) {
        if (mHelper.setDisabledByAdmin(admin)) {
            notifyChanged();
        }
    }
}
