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

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreference;

import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissions;
import com.android.packageinstaller.permission.ui.ReviewPermissionsActivity;
import com.android.packageinstaller.permission.utils.LocationUtils;
import com.android.packageinstaller.permission.utils.SafetyNetLogger;
import com.android.packageinstaller.permission.utils.Utils;

public final class AppPermissionsFragment extends SettingsWithHeader
        implements OnPreferenceChangeListener {

    private static final String LOG_TAG = "ManagePermsFragment";

    static final String EXTRA_HIDE_INFO_BUTTON = "hideInfoButton";

    private static final int MENU_ALL_PERMS = 0;

    private ArraySet<AppPermissionGroup> mToggledGroups;
    private AppPermissions mAppPermissions;
    private PreferenceScreen mExtraScreen;

    private boolean mHasConfirmedRevoke;

    public static AppPermissionsFragment newInstance(String packageName) {
        return setPackageName(new AppPermissionsFragment(), packageName);
    }

    private static <T extends Fragment> T setPackageName(T fragment, String packageName) {
        Bundle arguments = new Bundle();
        arguments.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setLoading(true /* loading */, false /* animate */);
        setHasOptionsMenu(true);
        final ActionBar ab = getActivity().getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }

        String packageName = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
        Activity activity = getActivity();
        PackageInfo packageInfo = getPackageInfo(activity, packageName);
        if (packageInfo == null) {
            Toast.makeText(activity, R.string.app_not_found_dlg_title, Toast.LENGTH_LONG).show();
            activity.finish();
            return;
        }


        mAppPermissions = new AppPermissions(activity, packageInfo, true,
                () -> getActivity().finish());

        if (mAppPermissions.isReviewRequired()) {
            Intent intent = new Intent(getActivity(), ReviewPermissionsActivity.class);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
            startActivity(intent);
            getActivity().finish();
            return;
        }

        loadPreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        mAppPermissions.refresh();
        loadPreferences();
        setPreferencesCheckedState();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                getActivity().finish();
                return true;
            }

            case MENU_ALL_PERMS: {
                Fragment frag = AllAppPermissionsFragment.newInstance(
                        getArguments().getString(Intent.EXTRA_PACKAGE_NAME));
                getFragmentManager().beginTransaction()
                        .replace(android.R.id.content, frag)
                        .addToBackStack("AllPerms")
                        .commit();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mAppPermissions != null) {
            bindUi(this, mAppPermissions.getPackageInfo());
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        menu.add(Menu.NONE, MENU_ALL_PERMS, Menu.NONE, R.string.all_permissions);
    }

    private static void bindUi(SettingsWithHeader fragment, PackageInfo packageInfo) {
        Activity activity = fragment.getActivity();
        PackageManager pm = activity.getPackageManager();
        ApplicationInfo appInfo = packageInfo.applicationInfo;
        Intent infoIntent = null;
        if (!activity.getIntent().getBooleanExtra(EXTRA_HIDE_INFO_BUTTON, false)) {
            infoIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageInfo.packageName, null));
        }

        Drawable icon = appInfo.loadIcon(pm);
        CharSequence label = appInfo.loadLabel(pm);
        fragment.setHeader(icon, label, infoIntent, fragment.getString(
                R.string.app_permissions_decor_title));
    }

    private void loadPreferences() {
        Context context = getPreferenceManager().getContext();
        if (context == null) {
            return;
        }

        PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        screen.addPreference(createHeaderLineTwoPreference(context));

        if (mExtraScreen != null) {
            mExtraScreen.removeAll();
            mExtraScreen = null;
        }

        final Preference extraPerms = new Preference(context);
        extraPerms.setIcon(R.drawable.ic_toc);
        extraPerms.setTitle(R.string.additional_permissions);

        for (AppPermissionGroup group : mAppPermissions.getPermissionGroups()) {
            if (!Utils.shouldShowPermission(group)) {
                continue;
            }

            boolean isPlatform = group.getDeclaringPackage().equals(Utils.OS_PKG);

            SwitchPreference preference = new SwitchPreference(context);
            preference.setOnPreferenceChangeListener(this);
            preference.setKey(group.getName());
            Drawable icon = Utils.loadDrawable(context.getPackageManager(),
                    group.getIconPkg(), group.getIconResId());
            preference.setIcon(Utils.applyTint(getContext(), icon,
                    android.R.attr.colorControlNormal));
            preference.setTitle(group.getLabel());
            if (group.isPolicyFixed()) {
                preference.setSummary(getString(R.string.permission_summary_enforced_by_policy));
            }
            preference.setPersistent(false);
            preference.setEnabled(!group.isPolicyFixed());
            preference.setChecked(group.areRuntimePermissionsGranted());

            if (isPlatform) {
                screen.addPreference(preference);
            } else {
                if (mExtraScreen == null) {
                    mExtraScreen = getPreferenceManager().createPreferenceScreen(context);
                    mExtraScreen.addPreference(createHeaderLineTwoPreference(context));
                }
                mExtraScreen.addPreference(preference);
            }
        }

        if (mExtraScreen != null) {
            extraPerms.setOnPreferenceClickListener(preference -> {
                AdditionalPermissionsFragment frag = new AdditionalPermissionsFragment();
                setPackageName(frag, getArguments().getString(Intent.EXTRA_PACKAGE_NAME));
                frag.setTargetFragment(AppPermissionsFragment.this, 0);
                getFragmentManager().beginTransaction()
                        .replace(android.R.id.content, frag)
                        .addToBackStack(null)
                        .commit();
                return true;
            });
            int count = mExtraScreen.getPreferenceCount() - 1;
            extraPerms.setSummary(getResources().getQuantityString(
                    R.plurals.additional_permissions_more, count, count));
            screen.addPreference(extraPerms);
        }

        setLoading(false /* loading */, true /* animate */);
    }

    /**
     * Creates a heading below decor_title and above the rest of the preferences. This heading
     * displays the app name and banner icon. It's used in both system and additional permissions
     * fragments for each app. The styling used is the same as a leanback preference with a
     * customized background color
     * @param context The context the preferences created on
     * @return The preference header to be inserted as the first preference in the list.
     */
    private Preference createHeaderLineTwoPreference(Context context) {
        Preference headerLineTwo = new Preference(context) {
            @Override
            public void onBindViewHolder(PreferenceViewHolder holder) {
                super.onBindViewHolder(holder);
                holder.itemView.setBackgroundColor(
                        getResources().getColor(R.color.lb_header_banner_color));
            }
        };
        headerLineTwo.setKey(HEADER_PREFERENCE_KEY);
        headerLineTwo.setSelectable(false);
        headerLineTwo.setTitle(mLabel);
        headerLineTwo.setIcon(mIcon);
        return headerLineTwo;
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, Object newValue) {
        String groupName = preference.getKey();
        final AppPermissionGroup group = mAppPermissions.getPermissionGroup(groupName);

        if (group == null) {
            return false;
        }

        addToggledGroup(group);

        if (LocationUtils.isLocationGroupAndProvider(group.getName(), group.getApp().packageName)) {
            LocationUtils.showLocationDialog(getContext(), mAppPermissions.getAppLabel());
            return false;
        }
        if (newValue == Boolean.TRUE) {
            group.grantRuntimePermissions(false);
        } else {
            final boolean grantedByDefault = group.hasGrantedByDefaultPermission();
            if (grantedByDefault || (!group.doesSupportRuntimePermissions()
                    && !mHasConfirmedRevoke)) {
                new AlertDialog.Builder(getContext())
                        .setMessage(grantedByDefault ? R.string.system_warning
                                : R.string.old_sdk_deny_warning)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.grant_dialog_button_deny_anyway,
                                (dialog, which) -> {
                                    ((SwitchPreference) preference).setChecked(false);
                                    group.revokeRuntimePermissions(false);
                                    if (!grantedByDefault) {
                                        mHasConfirmedRevoke = true;
                                    }
                                })
                        .show();
                return false;
            } else {
                group.revokeRuntimePermissions(false);
            }
        }

        return true;
    }

    @Override
    public void onPause() {
        super.onPause();
        logToggledGroups();
    }

    private void addToggledGroup(AppPermissionGroup group) {
        if (mToggledGroups == null) {
            mToggledGroups = new ArraySet<>();
        }
        mToggledGroups.add(group);
    }

    private void logToggledGroups() {
        if (mToggledGroups != null) {
            SafetyNetLogger.logPermissionsToggled(mToggledGroups);
            mToggledGroups = null;
        }
    }

    private void setPreferencesCheckedState() {
        setPreferencesCheckedState(getPreferenceScreen());
        if (mExtraScreen != null) {
            setPreferencesCheckedState(mExtraScreen);
        }
    }

    private void setPreferencesCheckedState(PreferenceScreen screen) {
        int preferenceCount = screen.getPreferenceCount();
        for (int i = 0; i < preferenceCount; i++) {
            Preference preference = screen.getPreference(i);
            if (preference instanceof SwitchPreference) {
                SwitchPreference switchPref = (SwitchPreference) preference;
                AppPermissionGroup group = mAppPermissions.getPermissionGroup(switchPref.getKey());
                if (group != null) {
                    switchPref.setChecked(group.areRuntimePermissionsGranted());
                }
            }
        }
    }

    private static PackageInfo getPackageInfo(Activity activity, String packageName) {
        try {
            return activity.getPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(LOG_TAG, "No package:" + activity.getCallingPackage(), e);
            return null;
        }
    }

    public static class AdditionalPermissionsFragment extends SettingsWithHeader {
        AppPermissionsFragment mOuterFragment;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            mOuterFragment = (AppPermissionsFragment) getTargetFragment();
            super.onCreate(savedInstanceState);
            setHasOptionsMenu(true);
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferenceScreen(mOuterFragment.mExtraScreen);
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            String packageName = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
            bindUi(this, getPackageInfo(getActivity(), packageName));
        }

        private static void bindUi(SettingsWithHeader fragment, PackageInfo packageInfo) {
            Activity activity = fragment.getActivity();
            PackageManager pm = activity.getPackageManager();
            ApplicationInfo appInfo = packageInfo.applicationInfo;
            Intent infoIntent = null;
            if (!activity.getIntent().getBooleanExtra(EXTRA_HIDE_INFO_BUTTON, false)) {
                infoIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageInfo.packageName, null));
            }

            Drawable icon = appInfo.loadIcon(pm);
            CharSequence label = appInfo.loadLabel(pm);
            fragment.setHeader(icon, label, infoIntent, fragment.getString(
                    R.string.additional_permissions_decor_title));
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            switch (item.getItemId()) {
            case android.R.id.home:
                getFragmentManager().popBackStack();
                return true;
            }
            return super.onOptionsItemSelected(item);
        }
    }
}
