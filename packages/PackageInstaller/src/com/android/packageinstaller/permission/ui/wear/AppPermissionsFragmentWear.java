/*
* Copyright (C) 2015 The Android Open Source Project
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

package com.android.packageinstaller.permission.ui.wear;

import android.app.Activity;
import android.app.Fragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.util.ArraySet;
import android.util.Log;
import android.widget.Toast;

import androidx.wear.ble.view.WearableDialogHelper;

import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissions;
import com.android.packageinstaller.permission.model.Permission;
import com.android.packageinstaller.permission.utils.ArrayUtils;
import com.android.packageinstaller.permission.utils.LocationUtils;
import com.android.packageinstaller.permission.utils.SafetyNetLogger;
import com.android.packageinstaller.permission.utils.Utils;
import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

import java.util.ArrayList;
import java.util.List;

public final class AppPermissionsFragmentWear extends PreferenceFragment {
    private static final String LOG_TAG = "AppPermFragWear";

    private static final String KEY_NO_PERMISSIONS = "no_permissions";

    public static AppPermissionsFragmentWear newInstance(String packageName) {
        return setPackageName(new AppPermissionsFragmentWear(), packageName);
    }

    private static <T extends Fragment> T setPackageName(T fragment, String packageName) {
        Bundle arguments = new Bundle();
        arguments.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
        fragment.setArguments(arguments);
        return fragment;
    }

    private PackageManager mPackageManager;
    private ArraySet<AppPermissionGroup> mToggledGroups;
    private AppPermissions mAppPermissions;

    private boolean mHasConfirmedRevoke;

    /**
     * Provides click behavior for disabled preferences.
     * We can't use {@link PreferenceFragment#onPreferenceTreeClick}, as the base
     * {@link SwitchPreference} doesn't delegate to that method if the preference is disabled.
     */
    private static class PermissionSwitchPreference extends SwitchPreference {

        private final Activity mActivity;

        public PermissionSwitchPreference(Activity activity) {
            super(activity);
            this.mActivity = activity;
        }

        @Override
        public void performClick(PreferenceScreen preferenceScreen) {
            super.performClick(preferenceScreen);
            if (!isEnabled()) {
                // If setting the permission is disabled, it must have been locked
                // by the device or profile owner. So get that info and pass it to
                // the support details dialog.
                EnforcedAdmin deviceOrProfileOwner = RestrictedLockUtils.getProfileOrDeviceOwner(
                    mActivity, UserHandle.myUserId());
                RestrictedLockUtils.sendShowAdminSupportDetailsIntent(
                    mActivity, deviceOrProfileOwner);
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String packageName = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
        Activity activity = getActivity();
        mPackageManager = activity.getPackageManager();

        PackageInfo packageInfo;

        try {
            packageInfo = mPackageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(LOG_TAG, "No package:" + activity.getCallingPackage(), e);
            packageInfo = null;
        }

        if (packageInfo == null) {
            Toast.makeText(activity, R.string.app_not_found_dlg_title, Toast.LENGTH_LONG).show();
            activity.finish();
            return;
        }

        mAppPermissions = new AppPermissions(
                activity, packageInfo, true, () -> getActivity().finish());

        addPreferencesFromResource(R.xml.watch_permissions);
        initializePermissionGroupList();
    }

    @Override
    public void onResume() {
        super.onResume();
        mAppPermissions.refresh();

        // Also refresh the UI
        for (final AppPermissionGroup group : mAppPermissions.getPermissionGroups()) {
            if (Utils.areGroupPermissionsIndividuallyControlled(getContext(), group.getName())) {
                for (PermissionInfo perm : getPermissionInfosFromGroup(group)) {
                    setPreferenceCheckedIfPresent(perm.name,
                            group.areRuntimePermissionsGranted(new String[]{ perm.name }));
                }
            } else {
                setPreferenceCheckedIfPresent(group.getName(), group.areRuntimePermissionsGranted());
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        logAndClearToggledGroups();
    }

    private void initializePermissionGroupList() {
        List<AppPermissionGroup> groups = mAppPermissions.getPermissionGroups();
        List<SwitchPreference> nonSystemPreferences = new ArrayList<>();

        if (!groups.isEmpty()) {
            getPreferenceScreen().removePreference(findPreference(KEY_NO_PERMISSIONS));
        }

        for (final AppPermissionGroup group : groups) {
            if (!Utils.shouldShowPermission(group)) {
                continue;
            }

            boolean isPlatform = group.getDeclaringPackage().equals(Utils.OS_PKG);

            if (Utils.areGroupPermissionsIndividuallyControlled(getContext(), group.getName())) {
                // If permission is controlled individually, we show all requested permission
                // inside this group.
                for (PermissionInfo perm : getPermissionInfosFromGroup(group)) {
                    final SwitchPreference pref = createSwitchPreferenceForPermission(group, perm);
                    showOrAddToNonSystemPreferences(pref, nonSystemPreferences, isPlatform);
                }
            } else {
                final SwitchPreference pref = createSwitchPreferenceForGroup(group);
                showOrAddToNonSystemPreferences(pref, nonSystemPreferences, isPlatform);
            }
        }

        // Now add the non-system settings to the end of the list
        for (SwitchPreference nonSystemPreference : nonSystemPreferences) {
            getPreferenceScreen().addPreference(nonSystemPreference);
        }
    }

    private void showOrAddToNonSystemPreferences(SwitchPreference pref,
            List<SwitchPreference> nonSystemPreferences, // Mutate
            boolean isPlatform) {
        // The UI shows System settings first, then non-system settings
        if (isPlatform) {
            getPreferenceScreen().addPreference(pref);
        } else {
            nonSystemPreferences.add(pref);
        }
    }

    private SwitchPreference createSwitchPreferenceForPermission(AppPermissionGroup group,
            PermissionInfo perm) {
        final SwitchPreference pref = new PermissionSwitchPreference(getActivity());
        pref.setKey(perm.name);
        pref.setTitle(perm.loadLabel(mPackageManager));
        pref.setChecked(group.areRuntimePermissionsGranted(new String[]{ perm.name }));
        pref.setOnPreferenceChangeListener((p, newVal) -> {
            if((Boolean) newVal) {
                group.grantRuntimePermissions(false, new String[]{ perm.name });

                if (Utils.areGroupPermissionsIndividuallyControlled(getContext(), group.getName())
                        && group.doesSupportRuntimePermissions()) {
                    // We are granting a permission from a group but since this is an
                    // individual permission control other permissions in the group may
                    // be revoked, hence we need to mark them user fixed to prevent the
                    // app from requesting a non-granted permission and it being granted
                    // because another permission in the group is granted. This applies
                    // only to apps that support runtime permissions.
                    String[] revokedPermissionsToFix = null;
                    final int permissionCount = group.getPermissions().size();

                    for (int i = 0; i < permissionCount; i++) {
                        Permission current = group.getPermissions().get(i);
                        if (!current.isGranted() && !current.isUserFixed()) {
                            revokedPermissionsToFix = ArrayUtils.appendString(
                                    revokedPermissionsToFix, current.getName());
                        }
                    }

                    if (revokedPermissionsToFix != null) {
                        // If some permissions were not granted then they should be fixed.
                        group.revokeRuntimePermissions(true, revokedPermissionsToFix);
                    }
                }
            } else {
                final Permission appPerm = getPermissionFromGroup(group, perm.name);
                if (appPerm == null) {
                    return false;
                }

                final boolean grantedByDefault = appPerm.isGrantedByDefault();
                if (grantedByDefault
                        || (!group.doesSupportRuntimePermissions() && !mHasConfirmedRevoke)) {
                    showRevocationWarningDialog(
                            (dialog, which) -> {
                                revokePermissionInGroup(group, perm.name);
                                pref.setChecked(false);
                                if (!appPerm.isGrantedByDefault()) {
                                    mHasConfirmedRevoke = true;
                                }
                            },
                            grantedByDefault
                                    ? R.string.system_warning
                                    : R.string.old_sdk_deny_warning);
                    return false;
                } else {
                    revokePermissionInGroup(group, perm.name);
                }
            }

            return true;
        });
        return pref;
    }

    private void showRevocationWarningDialog(
            DialogInterface.OnClickListener confirmListener,
            int warningMessageId) {
        new WearableDialogHelper.DialogBuilder(getContext())
                .setNegativeIcon(R.drawable.confirm_button)
                .setPositiveIcon(R.drawable.cancel_button)
                .setNegativeButton(R.string.grant_dialog_button_deny_anyway, confirmListener)
                .setPositiveButton(R.string.cancel, null)
                .setMessage(warningMessageId)
                .show();
    }

    private static Permission getPermissionFromGroup(AppPermissionGroup group, String permName) {
        final int permissionCount = group.getPermissions().size();

        for (int i = 0; i < permissionCount; i++) {
            Permission currentPerm = group.getPermissions().get(i);
            if(currentPerm.getName().equals(permName)) {
                return currentPerm;
            };
        }

        if ("user".equals(Build.TYPE)) {
            Log.e(LOG_TAG, String.format("The impossible happens, permission %s is not in group %s.",
                    permName, group.getName()));
            return null;
        } else {
            // This is impossible, throw a fatal error in non-user build.
            throw new IllegalArgumentException(
                    String.format("Permission %s is not in group %s", permName, group.getName()));
        }
    }

    private void revokePermissionInGroup(AppPermissionGroup group, String permName) {
        group.revokeRuntimePermissions(true, new String[]{ permName });

        if (Utils.areGroupPermissionsIndividuallyControlled(getContext(), group.getName())
                && group.doesSupportRuntimePermissions()
                && !group.areRuntimePermissionsGranted()) {
            // If we just revoked the last permission we need to clear
            // the user fixed state as now the app should be able to
            // request them at runtime if supported.
            group.revokeRuntimePermissions(false);
        }
    }

    private SwitchPreference createSwitchPreferenceForGroup(AppPermissionGroup group) {
        final SwitchPreference pref = new PermissionSwitchPreference(getActivity());

        pref.setKey(group.getName());
        pref.setTitle(group.getLabel());
        pref.setChecked(group.areRuntimePermissionsGranted());

        if (group.isPolicyFixed()) {
            pref.setEnabled(false);
        } else {
            pref.setOnPreferenceChangeListener((p, newVal) -> {
                if (LocationUtils.isLocationGroupAndProvider(
                        group.getName(), group.getApp().packageName)) {
                    LocationUtils.showLocationDialog(
                            getContext(), mAppPermissions.getAppLabel());
                    return false;
                }

                if ((Boolean) newVal) {
                    setPermission(group, pref, true);
                } else {
                    final boolean grantedByDefault = group.hasGrantedByDefaultPermission();
                    if (grantedByDefault
                            || (!group.doesSupportRuntimePermissions() && !mHasConfirmedRevoke)) {
                        showRevocationWarningDialog(
                                (dialog, which) -> {
                                    setPermission(group, pref, false);
                                    if (!group.hasGrantedByDefaultPermission()) {
                                        mHasConfirmedRevoke = true;
                                    }
                                },
                                grantedByDefault
                                        ? R.string.system_warning
                                        : R.string.old_sdk_deny_warning);
                        return false;
                    } else {
                        setPermission(group, pref, false);
                    }
                }

                return true;
            });
        }
        return pref;
    }

    private void setPermission(AppPermissionGroup group, SwitchPreference pref, boolean grant) {
        if (grant) {
            group.grantRuntimePermissions(false);
        } else {
            group.revokeRuntimePermissions(false);
        }
        addToggledGroup(group);
        pref.setChecked(grant);
    }

    private void addToggledGroup(AppPermissionGroup group) {
        if (mToggledGroups == null) {
            mToggledGroups = new ArraySet<>();
        }

        mToggledGroups.add(group);
    }

    private void logAndClearToggledGroups() {
        if (mToggledGroups != null) {
            SafetyNetLogger.logPermissionsToggled(mToggledGroups);
            mToggledGroups = null;
        }
    }

    private List<PermissionInfo> getPermissionInfosFromGroup(AppPermissionGroup group) {
        ArrayList<PermissionInfo> permInfos = new ArrayList<>(group.getPermissions().size());
        for(Permission perm : group.getPermissions()) {
            try {
                permInfos.add(mPackageManager.getPermissionInfo(perm.getName(), 0));
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(LOG_TAG, "No permission:" + perm.getName());
            }
        }
        return permInfos;
    }

    private void setPreferenceCheckedIfPresent(String preferenceKey, boolean checked) {
        Preference pref = findPreference(preferenceKey);
        if (pref instanceof SwitchPreference) {
            ((SwitchPreference) pref).setChecked(checked);
        }
    }
}
