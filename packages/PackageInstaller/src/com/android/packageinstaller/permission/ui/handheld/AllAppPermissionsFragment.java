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

package com.android.packageinstaller.permission.ui.handheld;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.provider.Settings;
import android.util.IconDrawableFactory;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Switch;

import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.Permission;
import com.android.packageinstaller.permission.utils.ArrayUtils;
import com.android.packageinstaller.permission.utils.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Show and manage individual permissions for an app.
 *
 * <p>Shows the list of individual runtime and non-runtime permissions the app has requested.
 */
public final class AllAppPermissionsFragment extends SettingsWithHeader {

    private static final String LOG_TAG = "AllAppPermissionsFragment";

    private static final String KEY_OTHER = "other_perms";

    private static final String EXTRA_FILTER_GROUP =
            "com.android.packageinstaller.extra.FILTER_GROUP";

    private List<AppPermissionGroup> mGroups;

    public static AllAppPermissionsFragment newInstance(String packageName) {
        return newInstance(packageName, null);
    }

    public static AllAppPermissionsFragment newInstance(String packageName, String filterGroup) {
        AllAppPermissionsFragment instance = new AllAppPermissionsFragment();
        Bundle arguments = new Bundle();
        arguments.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
        arguments.putString(EXTRA_FILTER_GROUP, filterGroup);
        instance.setArguments(arguments);
        return instance;
    }

    @Override
    public void onStart() {
        super.onStart();

        final ActionBar ab = getActivity().getActionBar();
        if (ab != null) {
            // If we target a group make this look like app permissions.
            if (getArguments().getString(EXTRA_FILTER_GROUP) == null) {
                ab.setTitle(R.string.all_permissions);
            } else {
                ab.setTitle(R.string.app_permissions);
            }
            ab.setDisplayHomeAsUpEnabled(true);
        }

        setHasOptionsMenu(true);

        updateUi();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                getFragmentManager().popBackStack();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateUi() {
        if (getPreferenceScreen() != null) {
            getPreferenceScreen().removeAll();
        }
        addPreferencesFromResource(R.xml.all_permissions);
        PreferenceGroup otherGroup = (PreferenceGroup) findPreference(KEY_OTHER);
        ArrayList<Preference> prefs = new ArrayList<>(); // Used for sorting.
        prefs.add(otherGroup);
        String pkg = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
        String filterGroup = getArguments().getString(EXTRA_FILTER_GROUP);
        otherGroup.removeAll();
        PackageManager pm = getContext().getPackageManager();

        try {
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);

            ApplicationInfo appInfo = info.applicationInfo;
            final Drawable icon =
                    IconDrawableFactory.newInstance(getContext()).getBadgedIcon(appInfo);
            final CharSequence label = appInfo.loadLabel(pm);
            Intent infoIntent = null;
            if (!getActivity().getIntent().getBooleanExtra(
                    AppPermissionsFragment.EXTRA_HIDE_INFO_BUTTON, false)) {
                infoIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", pkg, null));
            }
            setHeader(icon, label, infoIntent);

            if (info.requestedPermissions != null) {
                for (int i = 0; i < info.requestedPermissions.length; i++) {
                    PermissionInfo perm;
                    try {
                        perm = pm.getPermissionInfo(info.requestedPermissions[i], 0);
                    } catch (NameNotFoundException e) {
                        Log.e(LOG_TAG,
                                "Can't get permission info for " + info.requestedPermissions[i], e);
                        continue;
                    }

                    if ((perm.flags & PermissionInfo.FLAG_INSTALLED) == 0
                            || (perm.flags & PermissionInfo.FLAG_REMOVED) != 0) {
                        continue;
                    }

                    if (appInfo.isInstantApp()
                            && (perm.protectionLevel & PermissionInfo.PROTECTION_FLAG_INSTANT)
                                == 0) {
                        continue;
                    }
                    if (appInfo.targetSdkVersion < Build.VERSION_CODES.M
                            && (perm.protectionLevel & PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY)
                                != 0) {
                        continue;
                    }

                    if ((perm.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                            == PermissionInfo.PROTECTION_DANGEROUS) {
                        PackageItemInfo group = getGroup(perm.group, pm);
                        if (group == null) {
                            group = perm;
                        }
                        // If we show a targeted group, then ignore everything else.
                        if (filterGroup != null && !group.name.equals(filterGroup)) {
                            continue;
                        }
                        PreferenceGroup pref = findOrCreate(group, pm, prefs);
                        pref.addPreference(getPreference(info, perm, group, pm));
                    } else if (filterGroup == null) {
                        if ((perm.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                                == PermissionInfo.PROTECTION_NORMAL) {
                            PermissionGroupInfo group = getGroup(perm.group, pm);
                            otherGroup.addPreference(getPreference(info,
                                    perm, group, pm));
                        }
                    }

                    // If we show a targeted group, then don't show 'other' permissions.
                    if (filterGroup != null) {
                        getPreferenceScreen().removePreference(otherGroup);
                    }
                }
            }
        } catch (NameNotFoundException e) {
            Log.e(LOG_TAG, "Problem getting package info for " + pkg, e);
        }
        // Sort an ArrayList of the groups and then set the order from the sorting.
        Collections.sort(prefs, new Comparator<Preference>() {
            @Override
            public int compare(Preference lhs, Preference rhs) {
                String lKey = lhs.getKey();
                String rKey = rhs.getKey();
                if (lKey.equals(KEY_OTHER)) {
                    return 1;
                } else if (rKey.equals(KEY_OTHER)) {
                    return -1;
                } else if (Utils.isModernPermissionGroup(lKey)
                        != Utils.isModernPermissionGroup(rKey)) {
                    return Utils.isModernPermissionGroup(lKey) ? -1 : 1;
                }
                return lhs.getTitle().toString().compareTo(rhs.getTitle().toString());
            }
        });
        for (int i = 0; i < prefs.size(); i++) {
            prefs.get(i).setOrder(i);
        }
    }

    private PermissionGroupInfo getGroup(String group, PackageManager pm) {
        try {
            return pm.getPermissionGroupInfo(group, 0);
        } catch (NameNotFoundException e) {
            return null;
        }
    }

    private PreferenceGroup findOrCreate(PackageItemInfo group, PackageManager pm,
            ArrayList<Preference> prefs) {
        PreferenceGroup pref = (PreferenceGroup) findPreference(group.name);
        if (pref == null) {
            pref = new PreferenceCategory(getContext());
            pref.setKey(group.name);
            pref.setTitle(group.loadLabel(pm));
            prefs.add(pref);
            getPreferenceScreen().addPreference(pref);
        }
        return pref;
    }

    private Preference getPreference(PackageInfo packageInfo, PermissionInfo perm,
            PackageItemInfo group, PackageManager pm) {
        final Preference pref;

        // We allow individual permission control for some permissions if review enabled
        final boolean mutable = Utils.isPermissionIndividuallyControlled(getContext(), perm.name);
        if (mutable) {
            pref = new MyMultiTargetSwitchPreference(getContext(), perm.name,
                    getPermissionForegroundGroup(packageInfo, perm.name));
        } else {
            pref = new Preference(getContext());
        }

        Drawable icon = null;
        if (perm.icon != 0) {
            icon = perm.loadIcon(pm);
        } else if (group != null && group.icon != 0) {
            icon = group.loadIcon(pm);
        } else {
            icon = getContext().getDrawable(R.drawable.ic_perm_device_info);
        }
        pref.setIcon(Utils.applyTint(getContext(), icon, android.R.attr.colorControlNormal));
        pref.setTitle(perm.loadSafeLabel(pm, 20000, PackageItemInfo.SAFE_LABEL_FLAG_TRIM));
        pref.setSingleLineTitle(false);
        final CharSequence desc = perm.loadDescription(pm);

        pref.setOnPreferenceClickListener((Preference preference) -> {
            new AlertDialog.Builder(getContext())
                    .setMessage(desc)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return mutable;
        });

        return pref;
    }

    /**
     * Return the (foreground-) {@link AppPermissionGroup group} a permission belongs to.
     *
     * <p>For foreground or non background-foreground permissions this returns the group
     * {@link AppPermissionGroup} the permission is in. For background permisisons this returns
     * the group the matching foreground
     *
     * @param packageInfo Package information about the app
     * @param permission The permission that belongs to a group
     *
     * @return the group the permissions belongs to
     */
    private AppPermissionGroup getPermissionForegroundGroup(PackageInfo packageInfo,
            String permission) {
        AppPermissionGroup appPermissionGroup = null;
        if (mGroups != null) {
            final int groupCount = mGroups.size();
            for (int i = 0; i < groupCount; i++) {
                AppPermissionGroup currentPermissionGroup = mGroups.get(i);
                if (currentPermissionGroup.hasPermission(permission)) {
                    appPermissionGroup = currentPermissionGroup;
                    break;
                }
                if (currentPermissionGroup.getBackgroundPermissions() != null
                        && currentPermissionGroup.getBackgroundPermissions().hasPermission(
                        permission)) {
                    appPermissionGroup = currentPermissionGroup.getBackgroundPermissions();
                    break;
                }
            }
        }
        if (appPermissionGroup == null) {
            appPermissionGroup = AppPermissionGroup.create(
                    getContext(), packageInfo, permission);
            if (mGroups == null) {
                mGroups = new ArrayList<>();
            }
            mGroups.add(appPermissionGroup);
        }
        return appPermissionGroup;
    }

    private static final class MyMultiTargetSwitchPreference extends MultiTargetSwitchPreference {
        MyMultiTargetSwitchPreference(Context context, String permission,
                AppPermissionGroup appPermissionGroup) {
            super(context);

            setChecked(appPermissionGroup.areRuntimePermissionsGranted(
                    new String[] {permission}));

            setSwitchOnClickListener(v -> {
                Switch switchView = (Switch) v;
                if (switchView.isChecked()) {
                    appPermissionGroup.grantRuntimePermissions(false,
                            new String[]{permission});
                    // We are granting a permission from a group but since this is an
                    // individual permission control other permissions in the group may
                    // be revoked, hence we need to mark them user fixed to prevent the
                    // app from requesting a non-granted permission and it being granted
                    // because another permission in the group is granted. This applies
                    // only to apps that support runtime permissions.
                    if (appPermissionGroup.doesSupportRuntimePermissions()) {
                        int grantedCount = 0;
                        String[] revokedPermissionsToFix = null;
                        final int permissionCount = appPermissionGroup.getPermissions().size();
                        for (int i = 0; i < permissionCount; i++) {
                            Permission current = appPermissionGroup.getPermissions().get(i);
                            if (!current.isGranted()) {
                                if (!current.isUserFixed()) {
                                    revokedPermissionsToFix = ArrayUtils.appendString(
                                            revokedPermissionsToFix, current.getName());
                                }
                            } else {
                                grantedCount++;
                            }
                        }
                        if (revokedPermissionsToFix != null) {
                            // If some permissions were not granted then they should be fixed.
                            appPermissionGroup.revokeRuntimePermissions(true,
                                    revokedPermissionsToFix);
                        } else if (appPermissionGroup.getPermissions().size() == grantedCount) {
                            // If all permissions are granted then they should not be fixed.
                            appPermissionGroup.grantRuntimePermissions(false);
                        }
                    }
                } else {
                    appPermissionGroup.revokeRuntimePermissions(true,
                            new String[]{permission});
                    // If we just revoked the last permission we need to clear
                    // the user fixed state as now the app should be able to
                    // request them at runtime if supported.
                    if (appPermissionGroup.doesSupportRuntimePermissions()
                            && !appPermissionGroup.areRuntimePermissionsGranted()) {
                        appPermissionGroup.revokeRuntimePermissions(false);
                    }
                }
            });
        }
    }
}
