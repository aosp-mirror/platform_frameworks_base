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
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceViewHolder;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.TextView;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

/**
 * Helper class for managing settings preferences that can be disabled
 * by device admins via user restrictions.
 */
public class RestrictedPreferenceHelper {
    private final Context mContext;
    private final Preference mPreference;
    private final Drawable mRestrictedPadlock;
    private final int mRestrictedPadlockPadding;

    private boolean mDisabledByAdmin;
    private EnforcedAdmin mEnforcedAdmin;
    private String mAttrUserRestriction = null;

    RestrictedPreferenceHelper(Context context, Preference preference,
            AttributeSet attrs) {
        mContext = context;
        mPreference = preference;

        mRestrictedPadlock = RestrictedLockUtils.getRestrictedPadlock(mContext);
        mRestrictedPadlockPadding = mContext.getResources().getDimensionPixelSize(
                R.dimen.restricted_lock_icon_padding);

        mAttrUserRestriction = attrs.getAttributeValue(
                R.styleable.RestrictedPreference_userRestriction);
        final TypedArray attributes = context.obtainStyledAttributes(attrs,
                R.styleable.RestrictedPreference);
        final TypedValue userRestriction =
                attributes.peekValue(R.styleable.RestrictedPreference_userRestriction);
        CharSequence data = null;
        if (userRestriction != null && userRestriction.type == TypedValue.TYPE_STRING) {
            if (userRestriction.resourceId != 0) {
                data = context.getText(userRestriction.resourceId);
            } else {
                data = userRestriction.string;
            }
        }
        mAttrUserRestriction = data == null ? null : data.toString();
    }

    /**
     * Modify PreferenceViewHolder to add padlock if restriction is disabled.
     */
    public void onBindViewHolder(PreferenceViewHolder holder) {
        final TextView titleView = (TextView) holder.findViewById(android.R.id.title);
        if (titleView != null) {
            RestrictedLockUtils.setTextViewPadlock(mContext, titleView, mDisabledByAdmin);
            if (mDisabledByAdmin) {
                holder.itemView.setEnabled(true);
            }
        }
    }

    /**
     * Check if the preference is disabled if so handle the click by informing the user.
     *
     * @return true if the method handled the click.
     */
    public boolean performClick() {
        if (mDisabledByAdmin) {
            RestrictedLockUtils.sendShowAdminSupportDetailsIntent(mContext, mEnforcedAdmin);
            return true;
        }
        return false;
    }

    /**
     * Disable / enable if we have been passed the restriction in the xml.
     */
    protected void onAttachedToHierarchy() {
        if (mAttrUserRestriction != null) {
            checkRestrictionAndSetDisabled(mAttrUserRestriction, UserHandle.myUserId());
        }
    }

    /**
     * Set the user restriction that is used to disable this preference.
     *
     * @param userRestriction constant from {@link android.os.UserManager}
     * @param userId user to check the restriction for.
     */
    public void checkRestrictionAndSetDisabled(String userRestriction, int userId) {
        EnforcedAdmin admin = RestrictedLockUtils.checkIfRestrictionEnforced(mContext,
                userRestriction, userId);
        setDisabledByAdmin(admin);
    }

    /**
     * Disable this preference based on the enforce admin.
     *
     * @param EnforcedAdmin Details of the admin who enforced the restriction. If it
     * is {@code null}, then this preference will be enabled. Otherwise, it will be disabled.
     * @return true if the disabled state was changed.
     */
    public boolean setDisabledByAdmin(EnforcedAdmin admin) {
        final boolean disabled = (admin != null ? true : false);
        mEnforcedAdmin = (disabled ? admin : null);
        if (mDisabledByAdmin != disabled) {
            mDisabledByAdmin = disabled;
            mPreference.setEnabled(!disabled);
            return true;
        }
        return false;
    }

    public boolean isDisabledByAdmin() {
        return mDisabledByAdmin;
    }
}
