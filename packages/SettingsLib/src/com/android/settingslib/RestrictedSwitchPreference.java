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

import static android.app.admin.DevicePolicyResources.Strings.Settings.DISABLED_BY_ADMIN_SWITCH_SUMMARY;
import static android.app.admin.DevicePolicyResources.Strings.Settings.ENABLED_BY_ADMIN_SWITCH_SUMMARY;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Process;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.utils.BuildCompatUtils;

/**
 * Version of SwitchPreferenceCompat that can be disabled by a device admin
 * using a user restriction.
 */
public class RestrictedSwitchPreference extends SwitchPreferenceCompat {
    RestrictedPreferenceHelper mHelper;
    AppOpsManager mAppOpsManager;
    boolean mUseAdditionalSummary = false;
    CharSequence mRestrictedSwitchSummary;
    private int mIconSize;

    public RestrictedSwitchPreference(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
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
            attributes.recycle();
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
        this(context, attrs, androidx.preference.R.attr.switchPreferenceCompatStyle);
    }

    public RestrictedSwitchPreference(Context context) {
        this(context, null);
    }

    @VisibleForTesting
    public void setAppOps(AppOpsManager appOps) {
        mAppOpsManager = appOps;
    }

    public void setIconSize(int iconSize) {
        mIconSize = iconSize;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View switchView = holder.findViewById(androidx.preference.R.id.switchWidget);
        if (switchView != null) {
            final View rootView = switchView.getRootView();
            rootView.setFilterTouchesWhenObscured(true);
        }

        mHelper.onBindViewHolder(holder);

        CharSequence switchSummary;
        if (mRestrictedSwitchSummary == null) {
            switchSummary = isChecked()
                    ? getUpdatableEnterpriseString(
                            getContext(), ENABLED_BY_ADMIN_SWITCH_SUMMARY,
                            com.android.settingslib.widget.restricted.R.string.enabled_by_admin)
                    : getUpdatableEnterpriseString(
                            getContext(), DISABLED_BY_ADMIN_SWITCH_SUMMARY,
                            com.android.settingslib.widget.restricted.R.string.disabled_by_admin);
        } else {
            switchSummary = mRestrictedSwitchSummary;
        }

        final ImageView icon = holder.itemView.findViewById(android.R.id.icon);

        if (mIconSize > 0) {
            icon.setLayoutParams(new LinearLayout.LayoutParams(mIconSize, mIconSize));
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

    private static String getUpdatableEnterpriseString(
            Context context, String updatableStringId, int resId) {
        if (!BuildCompatUtils.isAtLeastT()) {
            return context.getString(resId);
        }
        return context.getSystemService(DevicePolicyManager.class).getResources().getString(
                updatableStringId,
                () -> context.getString(resId));
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

    /**
     * Checks if the given setting is subject to Enhanced Confirmation Mode restrictions for this
     * package. Marks the preference as disabled if so.
     * @param settingIdentifier The key identifying the setting
     * @param packageName the package to check the settingIdentifier for
     */
    public void checkEcmRestrictionAndSetDisabled(@NonNull String settingIdentifier,
            @NonNull  String packageName) {
        mHelper.checkEcmRestrictionAndSetDisabled(settingIdentifier, packageName);
    }

    @Override
    public void setEnabled(boolean enabled) {
        boolean changed = false;
        if (enabled && isDisabledByAdmin()) {
            mHelper.setDisabledByAdmin(null);
            changed = true;
        }
        if (enabled && isDisabledByEcm()) {
            mHelper.setDisabledByEcm(null);
            changed = true;
        }
        if (!changed) {
            super.setEnabled(enabled);
        }
    }

    public void setDisabledByAdmin(EnforcedAdmin admin) {
        if (mHelper.setDisabledByAdmin(admin)) {
            notifyChanged();
        }
    }

    public boolean isDisabledByAdmin() {
        return mHelper.isDisabledByAdmin();
    }

    public boolean isDisabledByEcm() {
        return mHelper.isDisabledByEcm();
    }

    /**
     * @deprecated TODO(b/308921175): This will be deleted with the
     * {@link android.security.Flags#extendEcmToAllSettings} feature flag. Do not use for any new
     * code.
     */
    @Deprecated
    private void setDisabledByAppOps(boolean disabled) {
        if (mHelper.setDisabledByAppOps(disabled)) {
            notifyChanged();
        }
    }

    /**
     * @deprecated TODO(b/308921175): This will be deleted with the
     * {@link android.security.Flags#extendEcmToAllSettings} feature flag. Do not use for any new
     * code.
     */
    @Deprecated
    public int getUid() {
        return mHelper != null ? mHelper.uid : Process.INVALID_UID;
    }

    /**
     * @deprecated TODO(b/308921175): This will be deleted with the
     * {@link android.security.Flags#extendEcmToAllSettings} feature flag. Do not use for any new
     * code.
     */
    @Deprecated
    public String getPackageName() {
        return mHelper != null ? mHelper.packageName : null;
    }

    /**
     * Updates enabled state based on associated package
     *
     * @deprecated TODO(b/308921175): This will be deleted with the
     * {@link android.security.Flags#extendEcmToAllSettings} feature flag. Do not use for any new
     * code.
     */
    @Deprecated
    public void updateState(
            @NonNull String packageName, int uid, boolean isEnableAllowed, boolean isEnabled) {
        mHelper.updatePackageDetails(packageName, uid);
        if (mAppOpsManager == null) {
            mAppOpsManager = getContext().getSystemService(AppOpsManager.class);
        }
        final int mode = mAppOpsManager.noteOpNoThrow(
                AppOpsManager.OP_ACCESS_RESTRICTED_SETTINGS,
                uid, packageName);
        final boolean ecmEnabled = getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_enhancedConfirmationModeEnabled);
        final boolean appOpsAllowed = !ecmEnabled || mode == AppOpsManager.MODE_ALLOWED;
        if (!isEnableAllowed && !isEnabled) {
            setEnabled(false);
        } else if (isEnabled) {
            setEnabled(true);
        } else if (appOpsAllowed && isDisabledByEcm()) {
            setEnabled(true);
        } else if (!appOpsAllowed){
            setDisabledByAppOps(true);
        }
    }
}
