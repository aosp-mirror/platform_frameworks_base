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

import static android.app.admin.DevicePolicyResources.Strings.Settings.CONTROLLED_BY_ADMIN_SUMMARY;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.utils.BuildCompatUtils;

/**
 * Helper class for managing settings preferences that can be disabled
 * by device admins via user restrictions.
 */
public class RestrictedPreferenceHelper {
    private final Context mContext;
    private final Preference mPreference;
    String packageName;
    int uid;

    private boolean mDisabledByAdmin;
    @VisibleForTesting
    EnforcedAdmin mEnforcedAdmin;
    private String mAttrUserRestriction = null;
    private boolean mDisabledSummary = false;

    private boolean mDisabledByEcm;
    private Intent mDisabledByEcmIntent = null;

    public RestrictedPreferenceHelper(Context context, Preference preference,
            AttributeSet attrs, String packageName, int uid) {
        mContext = context;
        mPreference = preference;
        this.packageName = packageName;
        this.uid = uid;

        if (attrs != null) {
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
            // If the system has set the user restriction, then we shouldn't add the padlock.
            if (RestrictedLockUtilsInternal.hasBaseUserRestriction(mContext, mAttrUserRestriction,
                    UserHandle.myUserId())) {
                mAttrUserRestriction = null;
                return;
            }

            final TypedValue useAdminDisabledSummary =
                    attributes.peekValue(R.styleable.RestrictedPreference_useAdminDisabledSummary);
            if (useAdminDisabledSummary != null) {
                mDisabledSummary =
                        (useAdminDisabledSummary.type == TypedValue.TYPE_INT_BOOLEAN
                                && useAdminDisabledSummary.data != 0);
            }
        }
    }

    public RestrictedPreferenceHelper(Context context, Preference preference,
            AttributeSet attrs) {
        this(context, preference, attrs, null, android.os.Process.INVALID_UID);
    }

    /**
     * Modify PreferenceViewHolder to add padlock if restriction is disabled.
     */
    public void onBindViewHolder(PreferenceViewHolder holder) {
        if (mDisabledByAdmin || mDisabledByEcm) {
            holder.itemView.setEnabled(true);
        }
        if (mDisabledSummary) {
            final TextView summaryView = (TextView) holder.findViewById(android.R.id.summary);
            if (summaryView != null) {
                final CharSequence disabledText = BuildCompatUtils.isAtLeastT()
                        ? getDisabledByAdminUpdatableString()
                        : mContext.getString(R.string.disabled_by_admin_summary_text);
                if (mDisabledByAdmin) {
                    summaryView.setText(disabledText);
                } else if (mDisabledByEcm) {
                    summaryView.setText(R.string.disabled_by_app_ops_text);
                } else if (TextUtils.equals(disabledText, summaryView.getText())) {
                    // It's previously set to disabled text, clear it.
                    summaryView.setText(null);
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private String getDisabledByAdminUpdatableString() {
        return mContext.getSystemService(DevicePolicyManager.class).getResources().getString(
                CONTROLLED_BY_ADMIN_SUMMARY,
                () -> mContext.getString(R.string.disabled_by_admin_summary_text));
    }

    public void useAdminDisabledSummary(boolean useSummary) {
        mDisabledSummary = useSummary;
    }

    /**
     * Check if the preference is disabled if so handle the click by informing the user.
     *
     * @return true if the method handled the click.
     */
    @SuppressWarnings("NewApi")
    public boolean performClick() {
        if (mDisabledByAdmin) {
            RestrictedLockUtils.sendShowAdminSupportDetailsIntent(mContext, mEnforcedAdmin);
            return true;
        }
        if (mDisabledByEcm) {
            if (android.security.Flags.extendEcmToAllSettings()) {
                mContext.startActivity(mDisabledByEcmIntent);
                return true;
            }

            RestrictedLockUtilsInternal.sendShowRestrictedSettingDialogIntent(mContext, packageName,
                    uid);
            return true;
        }
        return false;
    }

    /**
     * Disable / enable if we have been passed the restriction in the xml.
     */
    public void onAttachedToHierarchy() {
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
        EnforcedAdmin admin = RestrictedLockUtilsInternal.checkIfRestrictionEnforced(mContext,
                userRestriction, userId);
        setDisabledByAdmin(admin);
    }

    /**
     * Checks if the given setting is subject to Enhanced Confirmation Mode restrictions for this
     * package. Marks the preference as disabled if so.
     * @param restriction The key identifying the setting
     * @param packageName the package to check the restriction for
     * @param uid the uid of the package
     */
    public void checkEcmRestrictionAndSetDisabled(String restriction, String packageName, int uid) {
        updatePackageDetails(packageName, uid);
        Intent intent = RestrictedLockUtilsInternal.checkIfRequiresEnhancedConfirmation(
                mContext, restriction, uid, packageName);
        setDisabledByEcm(intent);
    }

    /**
     * @return EnforcedAdmin if we have been passed the restriction in the xml.
     */
    public EnforcedAdmin checkRestrictionEnforced() {
        if (mAttrUserRestriction == null) {
            return null;
        }
        return RestrictedLockUtilsInternal.checkIfRestrictionEnforced(mContext,
                mAttrUserRestriction, UserHandle.myUserId());
    }

    /**
     * Disable this preference based on the enforce admin.
     *
     * @param admin details of the admin who enforced the restriction. If it is
     * {@code null}, then this preference will be enabled. Otherwise, it will be disabled.
     * Only gray out the preference which is not {@link RestrictedTopLevelPreference}.
     * @return true if the disabled state was changed.
     */
    public boolean setDisabledByAdmin(EnforcedAdmin admin) {
        boolean disabled = false;
        mEnforcedAdmin = null;
        if (admin != null) {
            disabled = true;
            // Copy the received instance to prevent pass be reference being overwritten.
            mEnforcedAdmin = new EnforcedAdmin(admin);
        }

        boolean changed = false;
        if (mDisabledByAdmin != disabled) {
            mDisabledByAdmin = disabled;
            changed = true;
            updateDisabledState();
        }

        return changed;
    }

    /**
     * Disable the preference based on the passed in Intent
     * @param disabledIntent The intent which is started when the user clicks the disabled
     * preference. If it is {@code null}, then this preference will be enabled. Otherwise, it will
     * be disabled.
     * @return true if the disabled state was changed.
     */
    public boolean setDisabledByEcm(Intent disabledIntent) {
        boolean disabled = disabledIntent != null;
        boolean changed = false;
        if (mDisabledByEcm != disabled) {
            mDisabledByEcmIntent = disabledIntent;
            mDisabledByEcm = disabled;
            changed = true;
            updateDisabledState();
        }

        return changed;
    }

    public boolean isDisabledByAdmin() {
        return mDisabledByAdmin;
    }

    public boolean isDisabledByEcm() {
        return mDisabledByEcm;
    }

    public void updatePackageDetails(String packageName, int uid) {
        this.packageName = packageName;
        this.uid = uid;
    }

    private void updateDisabledState() {
        boolean isEnabled = !(mDisabledByAdmin || mDisabledByEcm);
        if (!(mPreference instanceof RestrictedTopLevelPreference)) {
            mPreference.setEnabled(isEnabled);
        }

        if (mPreference instanceof PrimarySwitchPreference) {
            ((PrimarySwitchPreference) mPreference).setSwitchEnabled(isEnabled);
        }
    }


    /**
     * @deprecated TODO(b/308921175): This will be deleted with the
     * {@link android.security.Flags#extendEcmToAllSettings} feature flag. Do not use for any new
     * code.
     */
    @Deprecated
    public boolean setDisabledByAppOps(boolean disabled) {
        boolean changed = false;
        if (mDisabledByEcm != disabled) {
            mDisabledByEcm = disabled;
            changed = true;
            updateDisabledState();
        }

        return changed;
    }
}
