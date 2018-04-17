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

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.UserHandle;
import androidx.preference.SwitchPreference;
import androidx.core.content.res.TypedArrayUtils;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * Version of SwitchPreference that can be disabled by a device admin
 * using a user restriction.
 */
public class RestrictedSwitchPreference extends SwitchPreference {
    RestrictedPreferenceHelper mHelper;
    boolean mUseAdditionalSummary = false;
    CharSequence mRestrictedSwitchSummary;

    public RestrictedSwitchPreference(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setWidgetLayoutResource(R.layout.restricted_switch_widget);
        mHelper = new RestrictedPreferenceHelper(context, this, attrs);
        if (attrs != null) {
            final TypedArray attributes = context.obtainStyledAttributes(attrs,
                    R.styleable.RestrictedSwitchPreference);
            final TypedValue useAdditionalSummary = attributes.peekValue(
                    R.styleable.RestrictedSwitchPreference_useAdditionalSummary);
            if (useAdditionalSummary != null) {
                mUseAdditionalSummary =
                        (useAdditionalSummary.type == TypedValue.TYPE_INT_BOOLEAN
                                && useAdditionalSummary.data != 0);
            }

            final TypedValue restrictedSwitchSummary = attributes.peekValue(
                    R.styleable.RestrictedSwitchPreference_restrictedSwitchSummary);
            if (restrictedSwitchSummary != null
                    && restrictedSwitchSummary.type == TypedValue.TYPE_STRING) {
                if (restrictedSwitchSummary.resourceId != 0) {
                    mRestrictedSwitchSummary =
                        context.getText(restrictedSwitchSummary.resourceId);
                } else {
                    mRestrictedSwitchSummary = restrictedSwitchSummary.string;
                }
            }
        }
        if (mUseAdditionalSummary) {
            setLayoutResource(R.layout.restricted_switch_preference);
            useAdminDisabledSummary(false);
        }
    }

    public RestrictedSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RestrictedSwitchPreference(Context context, AttributeSet attrs) {
        this(context, attrs, TypedArrayUtils.getAttr(context, R.attr.switchPreferenceStyle,
                android.R.attr.switchPreferenceStyle));
    }

    public RestrictedSwitchPreference(Context context) {
        this(context, null);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mHelper.onBindViewHolder(holder);

        CharSequence switchSummary;
        if (mRestrictedSwitchSummary == null) {
            switchSummary = getContext().getText(isChecked()
                ? R.string.enabled_by_admin : R.string.disabled_by_admin);
        } else {
            switchSummary = mRestrictedSwitchSummary;
        }

        final View restrictedIcon = holder.findViewById(R.id.restricted_icon);
        final View switchWidget = holder.findViewById(android.R.id.switch_widget);
        if (restrictedIcon != null) {
            restrictedIcon.setVisibility(isDisabledByAdmin() ? View.VISIBLE : View.GONE);
        }
        if (switchWidget != null) {
            switchWidget.setVisibility(isDisabledByAdmin() ? View.GONE : View.VISIBLE);
        }

        if (mUseAdditionalSummary) {
            final TextView additionalSummaryView = (TextView) holder.findViewById(
                    R.id.additional_summary);
            if (additionalSummaryView != null) {
                if (isDisabledByAdmin()) {
                    additionalSummaryView.setText(switchSummary);
                    additionalSummaryView.setVisibility(View.VISIBLE);
                } else {
                    additionalSummaryView.setVisibility(View.GONE);
                }
            }
        } else {
            final TextView summaryView = (TextView) holder.findViewById(android.R.id.summary);
            if (summaryView != null) {
                if (isDisabledByAdmin()) {
                    summaryView.setText(switchSummary);
                    summaryView.setVisibility(View.VISIBLE);
                }
                // No need to change the visibility to GONE in the else case here since Preference
                // class would have already changed it if there is no summary to display.
            }
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
