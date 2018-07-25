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

package com.android.packageinstaller.permission.ui.television;

import android.Manifest;
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
import android.provider.Settings;
import android.util.Log;
import android.view.MenuItem;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.Preference.OnPreferenceClickListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.SwitchPreference;

import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissions;
import com.android.packageinstaller.permission.utils.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public final class AllAppPermissionsFragment extends SettingsWithHeader {

    private static final String LOG_TAG = "AllAppPermissionsFragment";

    private static final String KEY_OTHER = "other_perms";

    private PackageInfo mPackageInfo;

    private AppPermissions mAppPermissions;

    public static AllAppPermissionsFragment newInstance(String packageName) {
        AllAppPermissionsFragment instance = new AllAppPermissionsFragment();
        Bundle arguments = new Bundle();
        arguments.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
        instance.setArguments(arguments);
        return instance;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        final ActionBar ab = getActivity().getActionBar();
        if (ab != null) {
            ab.setTitle(R.string.all_permissions);
            ab.setDisplayHomeAsUpEnabled(true);
        }

        String pkg = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
        try {
            mPackageInfo = getActivity().getPackageManager().getPackageInfo(pkg,
                    PackageManager.GET_PERMISSIONS);
        } catch (NameNotFoundException e) {
            getActivity().finish();
        }

        mAppPermissions = new AppPermissions(getActivity(), mPackageInfo, false,
                new Runnable() {
            @Override
            public void run() {
                getActivity().finish();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
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

    private PreferenceGroup getOtherGroup() {
        PreferenceGroup otherGroup = (PreferenceGroup) findPreference(KEY_OTHER);
        if (otherGroup == null) {
            otherGroup = new PreferenceCategory(getPreferenceManager().getContext());
            otherGroup.setKey(KEY_OTHER);
            otherGroup.setTitle(getString(R.string.other_permissions));
            getPreferenceScreen().addPreference(otherGroup);
        }
        return otherGroup;
    }

    private void updateUi() {
        getPreferenceScreen().removeAll();

        ArrayList<Preference> prefs = new ArrayList<>(); // Used for sorting.
        PackageManager pm = getActivity().getPackageManager();

        ApplicationInfo appInfo = mPackageInfo.applicationInfo;
        final Drawable icon = appInfo.loadIcon(pm);
        final CharSequence label = appInfo.loadLabel(pm);
        Intent infoIntent = null;
        if (!getActivity().getIntent().getBooleanExtra(
                AppPermissionsFragment.EXTRA_HIDE_INFO_BUTTON, false)) {
            infoIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", mPackageInfo.packageName, null));
        }
        setHeader(icon, label, infoIntent, null);

        if (mPackageInfo.requestedPermissions != null) {
            for (int i = 0; i < mPackageInfo.requestedPermissions.length; i++) {
                PermissionInfo perm;
                try {
                    perm = pm.getPermissionInfo(mPackageInfo.requestedPermissions[i], 0);
                } catch (NameNotFoundException e) {
                    Log.e(LOG_TAG, "Can't get permission info for "
                            + mPackageInfo.requestedPermissions[i], e);
                    continue;
                }

                if ((perm.flags & PermissionInfo.FLAG_INSTALLED) == 0
                        || (perm.flags & PermissionInfo.FLAG_REMOVED) != 0) {
                    continue;
                }
                if (appInfo.isInstantApp()
                        && (perm.protectionLevel & PermissionInfo.PROTECTION_FLAG_INSTANT) == 0) {
                    continue;
                }
                if (appInfo.targetSdkVersion < Build.VERSION_CODES.M
                        && (perm.protectionLevel & PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY)
                        != 0) {
                    continue;
                }


                PermissionGroupInfo group = getGroup(perm.group, pm);
                if ((perm.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                        == PermissionInfo.PROTECTION_DANGEROUS) {
                    PreferenceGroup pref = findOrCreate(group != null ? group : perm, pm, prefs);
                    pref.addPreference(getPreference(perm, group));
                } else if ((perm.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                        == PermissionInfo.PROTECTION_NORMAL) {
                    PreferenceGroup otherGroup = getOtherGroup();
                    if (prefs.indexOf(otherGroup) < 0) {
                        prefs.add(otherGroup);
                    }
                    getOtherGroup().addPreference(getPreference(perm, group));
                }
            }
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
            pref = new PreferenceCategory(getActivity());
            pref.setKey(group.name);
            pref.setLayoutResource(R.layout.preference_category_material);
            pref.setTitle(group.loadLabel(pm));
            prefs.add(pref);
            getPreferenceScreen().addPreference(pref);
        }
        return pref;
    }

    private Preference getPreference(final PermissionInfo perm, final PermissionGroupInfo group) {
        if (isMutableGranularPermission(perm.name)) {
            return getMutablePreference(perm, group);
        } else {
            return getImmutablePreference(perm, group);
        }
    }

    private Preference getMutablePreference(final PermissionInfo perm, PermissionGroupInfo group) {
        final AppPermissionGroup permGroup = mAppPermissions.getPermissionGroup(group.name);
        final String[] filterPermissions = new String[]{perm.name};

        // TODO: No hardcoded layouts
        SwitchPreference pref = new SwitchPreference(getPreferenceManager().getContext());
        pref.setLayoutResource(R.layout.preference_permissions);
        pref.setChecked(permGroup.areRuntimePermissionsGranted(filterPermissions));
        pref.setIcon(getTintedPermissionIcon(getActivity(), perm, group));
        pref.setTitle(perm.loadLabel(getActivity().getPackageManager()));
        pref.setPersistent(false);

        pref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                if (value == Boolean.TRUE) {
                    permGroup.grantRuntimePermissions(false, filterPermissions);
                } else {
                    permGroup.revokeRuntimePermissions(false, filterPermissions);
                }
                return true;
            }
        });

        return pref;
    }

    private Preference getImmutablePreference(final PermissionInfo perm,
            PermissionGroupInfo group) {
        final PackageManager pm = getActivity().getPackageManager();

        // TODO: No hardcoded layouts
        Preference pref = new Preference(getActivity());
        pref.setLayoutResource(R.layout.preference_permissions);
        pref.setIcon(getTintedPermissionIcon(getActivity(), perm, group));
        pref.setTitle(perm.loadLabel(pm));
        pref.setPersistent(false);

        pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                new AlertDialog.Builder(getActivity())
                        .setMessage(perm.loadDescription(pm))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return true;
            }
        });

        return pref;
    }

    private static Drawable getTintedPermissionIcon(Context context, PermissionInfo perm,
            PermissionGroupInfo group) {
        final Drawable icon;
        if (perm.icon != 0) {
            icon = perm.loadIcon(context.getPackageManager());
        } else if (group != null && group.icon != 0) {
            icon = group.loadIcon(context.getPackageManager());
        } else {
            icon =  context.getDrawable(R.drawable.ic_perm_device_info);
        }
        return Utils.applyTint(context, icon, android.R.attr.colorControlNormal);
    }

    private boolean isMutableGranularPermission(String name) {
        if (!getContext().getPackageManager().arePermissionsIndividuallyControlled()) {
            return false;
        }
        switch (name) {
            case Manifest.permission.READ_CONTACTS:
            case Manifest.permission.WRITE_CONTACTS:
            case Manifest.permission.READ_SMS:
            case Manifest.permission.READ_CALL_LOG:
            case Manifest.permission.CALL_PHONE: {
                return true;
            }
        }
        return false;
    }
}
