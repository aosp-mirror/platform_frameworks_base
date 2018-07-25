/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.text.Html;
import android.text.Spanned;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;
import androidx.preference.TwoStatePreference;

import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissions;
import com.android.packageinstaller.permission.model.Permission;
import com.android.packageinstaller.permission.ui.ConfirmActionDialogFragment;
import com.android.packageinstaller.permission.ui.ManagePermissionsActivity;
import com.android.packageinstaller.permission.utils.ArrayUtils;
import com.android.packageinstaller.permission.utils.Utils;

import java.util.ArrayList;
import java.util.List;

public final class ReviewPermissionsFragment extends PreferenceFragmentCompat
        implements View.OnClickListener, Preference.OnPreferenceChangeListener,
        ConfirmActionDialogFragment.OnActionConfirmedListener {

    private static final String EXTRA_PACKAGE_INFO =
            "com.android.packageinstaller.permission.ui.extra.PACKAGE_INFO";

    private AppPermissions mAppPermissions;

    private Button mContinueButton;
    private Button mCancelButton;
    private Button mMoreInfoButton;

    private PreferenceCategory mNewPermissionsCategory;
    private PreferenceCategory mCurrentPermissionsCategory;

    private boolean mHasConfirmedRevoke;

    public static ReviewPermissionsFragment newInstance(PackageInfo packageInfo) {
        Bundle arguments = new Bundle();
        arguments.putParcelable(ReviewPermissionsFragment.EXTRA_PACKAGE_INFO, packageInfo);
        ReviewPermissionsFragment instance = new ReviewPermissionsFragment();
        instance.setArguments(arguments);
        instance.setRetainInstance(true);
        return instance;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        PackageInfo packageInfo = getArguments().getParcelable(EXTRA_PACKAGE_INFO);
        if (packageInfo == null) {
            activity.finish();
            return;
        }

        mAppPermissions = new AppPermissions(activity, packageInfo, false,
                () -> getActivity().finish());

        if (mAppPermissions.getPermissionGroups().isEmpty()) {
            activity.finish();
            return;
        }

        boolean reviewRequired = false;
        for (AppPermissionGroup group : mAppPermissions.getPermissionGroups()) {
            if (group.isReviewRequired()) {
                reviewRequired = true;
                break;
            }
        }

        if (!reviewRequired) {
            activity.finish();
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindUi();
    }

    @Override
    public void onResume() {
        super.onResume();
        mAppPermissions.refresh();
        loadPreferences();
    }

    @Override
    public void onClick(View view) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        if (view == mContinueButton) {
            confirmPermissionsReview();
            executeCallback(true);
        } else if (view == mCancelButton) {
            executeCallback(false);
            activity.setResult(Activity.RESULT_CANCELED);
        } else if (view == mMoreInfoButton) {
            Intent intent = new Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME,
                    mAppPermissions.getPackageInfo().packageName);
            intent.putExtra(ManagePermissionsActivity.EXTRA_ALL_PERMISSIONS, true);
            getActivity().startActivity(intent);
        }
        activity.finish();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (mHasConfirmedRevoke) {
            return true;
        }
        if (preference instanceof SwitchPreference) {
            SwitchPreference switchPreference = (SwitchPreference) preference;
            if (switchPreference.isChecked()) {
                showWarnRevokeDialog(switchPreference.getKey());
            } else {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onActionConfirmed(String action) {
        Preference preference = getPreferenceManager().findPreference(action);
        if (preference instanceof SwitchPreference) {
            SwitchPreference switchPreference = (SwitchPreference) preference;
            switchPreference.setChecked(false);
            mHasConfirmedRevoke = true;
        }
    }

    private void showWarnRevokeDialog(final String groupName) {
        DialogFragment fragment = ConfirmActionDialogFragment.newInstance(
                getString(R.string.old_sdk_deny_warning), groupName);
        fragment.show(getActivity().getSupportFragmentManager(), fragment.getClass().getName());
    }

    private void grantReviewedPermission(AppPermissionGroup group) {
        String[] permissionsToGrant = null;
        final int permissionCount = group.getPermissions().size();
        for (int j = 0; j < permissionCount; j++) {
            final Permission permission = group.getPermissions().get(j);
            if (permission.isReviewRequired()) {
                permissionsToGrant = ArrayUtils.appendString(
                        permissionsToGrant, permission.getName());
            }
        }
        if (permissionsToGrant != null) {
            group.grantRuntimePermissions(false, permissionsToGrant);
        }
    }

    private void confirmPermissionsReview() {
        final List<PreferenceGroup> preferenceGroups = new ArrayList<>();
        if (mNewPermissionsCategory != null) {
            preferenceGroups.add(mNewPermissionsCategory);
            preferenceGroups.add(mCurrentPermissionsCategory);
        } else {
            preferenceGroups.add(getPreferenceScreen());
        }

        final int preferenceGroupCount = preferenceGroups.size();
        for (int groupNum = 0; groupNum < preferenceGroupCount; groupNum++) {
            final PreferenceGroup preferenceGroup = preferenceGroups.get(groupNum);

            final int preferenceCount = preferenceGroup.getPreferenceCount();
            for (int prefNum = 0; prefNum < preferenceCount; prefNum++) {
                Preference preference = preferenceGroup.getPreference(prefNum);
                if (preference instanceof TwoStatePreference) {
                    TwoStatePreference twoStatePreference = (TwoStatePreference) preference;
                    String groupName = preference.getKey();
                    AppPermissionGroup group = mAppPermissions.getPermissionGroup(groupName);
                    if (twoStatePreference.isChecked()) {
                        grantReviewedPermission(group);

                        // TODO: Allow the user to only grant foreground permissions
                        if (group.getBackgroundPermissions() != null) {
                            grantReviewedPermission(group.getBackgroundPermissions());
                        }
                    } else {
                        group.revokeRuntimePermissions(false);
                        if (group.getBackgroundPermissions() != null) {
                            group.getBackgroundPermissions().revokeRuntimePermissions(false);
                        }
                    }
                    group.resetReviewRequired();
                }
            }
        }
    }

    private void bindUi() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        // Set icon
        Drawable icon = mAppPermissions.getPackageInfo().applicationInfo.loadIcon(
                activity.getPackageManager());
        ImageView iconView = activity.requireViewById(R.id.app_icon);
        iconView.setImageDrawable(icon);

        // Set message
        final int labelTemplateResId = isPackageUpdated()
                ? R.string.permission_review_title_template_update
                : R.string.permission_review_title_template_install;
        Spanned message = Html.fromHtml(getString(labelTemplateResId,
                mAppPermissions.getAppLabel()), 0);

        // Set the permission message as the title so it can be announced.
        activity.setTitle(message.toString());

        // Color the app name.
        TextView permissionsMessageView = activity.requireViewById(
                R.id.permissions_message);
        permissionsMessageView.setText(message);

        mContinueButton = getActivity().requireViewById(R.id.continue_button);
        mContinueButton.setOnClickListener(this);

        mCancelButton = getActivity().requireViewById(R.id.cancel_button);
        mCancelButton.setOnClickListener(this);

        if (activity.getPackageManager().arePermissionsIndividuallyControlled()) {
            mMoreInfoButton = getActivity().requireViewById(
                    R.id.permission_more_info_button);
            mMoreInfoButton.setOnClickListener(this);
            mMoreInfoButton.setVisibility(View.VISIBLE);
        }
    }

    private void loadPreferences() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            screen = getPreferenceManager().createPreferenceScreen(getActivity());
            setPreferenceScreen(screen);
        } else {
            screen.removeAll();
        }

        mCurrentPermissionsCategory = null;
        PreferenceGroup oldNewPermissionsCategory = mNewPermissionsCategory;
        mNewPermissionsCategory = null;

        final boolean isPackageUpdated = isPackageUpdated();

        for (AppPermissionGroup group : mAppPermissions.getPermissionGroups()) {
            if (!Utils.shouldShowPermission(group)
                    || !Utils.OS_PKG.equals(group.getDeclaringPackage())) {
                continue;
            }

            final SwitchPreference preference;
            Preference cachedPreference = oldNewPermissionsCategory != null
                    ? oldNewPermissionsCategory.findPreference(group.getName()) : null;
            if (cachedPreference instanceof SwitchPreference) {
                preference = (SwitchPreference) cachedPreference;
            } else {
                preference = new SwitchPreference(getActivity());

                preference.setKey(group.getName());
                Drawable icon = Utils.loadDrawable(activity.getPackageManager(),
                        group.getIconPkg(), group.getIconResId());
                preference.setIcon(Utils.applyTint(getContext(), icon,
                        android.R.attr.colorControlNormal));
                preference.setTitle(group.getLabel());
                preference.setSummary(group.getDescription());
                preference.setPersistent(false);

                preference.setOnPreferenceChangeListener(this);
            }

            preference.setChecked(group.areRuntimePermissionsGranted()
                    || group.isReviewRequired());

            // Mutable state
            if (group.isPolicyFixed()) {
                preference.setEnabled(false);
                preference.setSummary(getString(
                        R.string.permission_summary_enforced_by_policy));
            } else {
                preference.setEnabled(true);
            }

            if (group.isReviewRequired()) {
                if (!isPackageUpdated) {
                    screen.addPreference(preference);
                } else {
                    if (mNewPermissionsCategory == null) {
                        mNewPermissionsCategory = new PreferenceCategory(activity);
                        mNewPermissionsCategory.setTitle(R.string.new_permissions_category);
                        mNewPermissionsCategory.setOrder(1);
                        screen.addPreference(mNewPermissionsCategory);
                    }
                    mNewPermissionsCategory.addPreference(preference);
                }
            } else {
                if (mCurrentPermissionsCategory == null) {
                    mCurrentPermissionsCategory = new PreferenceCategory(activity);
                    mCurrentPermissionsCategory.setTitle(R.string.current_permissions_category);
                    mCurrentPermissionsCategory.setOrder(2);
                    screen.addPreference(mCurrentPermissionsCategory);
                }
                mCurrentPermissionsCategory.addPreference(preference);
            }
        }
    }

    private boolean isPackageUpdated() {
        List<AppPermissionGroup> groups = mAppPermissions.getPermissionGroups();
        final int groupCount = groups.size();
        for (int i = 0; i < groupCount; i++) {
            AppPermissionGroup group = groups.get(i);
            if (!group.isReviewRequired()) {
                return true;
            }
        }
        return false;
    }

    private void executeCallback(boolean success) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        if (success) {
            IntentSender intent = activity.getIntent().getParcelableExtra(Intent.EXTRA_INTENT);
            if (intent != null) {
                try {
                    int flagMask = 0;
                    int flagValues = 0;
                    if (activity.getIntent().getBooleanExtra(
                            Intent.EXTRA_RESULT_NEEDED, false)) {
                        flagMask = Intent.FLAG_ACTIVITY_FORWARD_RESULT;
                        flagValues = Intent.FLAG_ACTIVITY_FORWARD_RESULT;
                    }
                    activity.startIntentSenderForResult(intent, -1, null,
                            flagMask, flagValues, 0);
                } catch (IntentSender.SendIntentException e) {
                        /* ignore */
                }
                return;
            }
        }
        RemoteCallback callback = activity.getIntent().getParcelableExtra(
                Intent.EXTRA_REMOTE_CALLBACK);
        if (callback != null) {
            Bundle result = new Bundle();
            result.putBoolean(Intent.EXTRA_RETURN_RESULT, success);
            callback.sendResult(result);
        }
    }
}
