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
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.IconDrawableFactory;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissions;
import com.android.packageinstaller.permission.utils.SafetyNetLogger;
import com.android.packageinstaller.permission.utils.Utils;
import com.android.settingslib.HelpUtils;

/**
 * Show and manage permission groups for an app.
 *
 * <p>Shows the list of permission groups the app has requested at one permission for.
 */
public final class AppPermissionsFragment extends SettingsWithHeader
        implements PermissionPreference.PermissionPreferenceChangeListener,
        PermissionPreference.PermissionPreferenceOwnerFragment {

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

        mAppPermissions = new AppPermissions(activity, packageInfo, true, new Runnable() {
            @Override
            public void run() {
                getActivity().finish();
            }
        });
        updatePreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        mAppPermissions.refresh();
        updatePreferences();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                getActivity().finish();
                return true;
            }

            case MENU_ALL_PERMS: {
                showAllPermissions(null);
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
        HelpUtils.prepareHelpMenuItem(getActivity(), menu, R.string.help_app_permissions,
                getClass().getName());
    }

    private void showAllPermissions(String filterGroup) {
        Fragment frag = AllAppPermissionsFragment.newInstance(
                getArguments().getString(Intent.EXTRA_PACKAGE_NAME),
                filterGroup);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, frag)
                .addToBackStack("AllPerms")
                .commit();
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

        Drawable icon = IconDrawableFactory.newInstance(activity).getBadgedIcon(appInfo);
        CharSequence label = appInfo.loadLabel(pm);
        fragment.setHeader(icon, label, infoIntent);

        ActionBar ab = activity.getActionBar();
        if (ab != null) {
            ab.setTitle(R.string.app_permissions);
        }
    }

    private void updatePreferences() {
        Context context = getActivity();
        if (context == null) {
            return;
        }

        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            screen = getPreferenceManager().createPreferenceScreen(getActivity());
            setPreferenceScreen(screen);
        }

        screen.removeAll();

        if (mExtraScreen != null) {
            mExtraScreen.removeAll();
        }

        final Preference extraPerms = new Preference(context);
        extraPerms.setIcon(R.drawable.ic_toc);
        extraPerms.setTitle(R.string.additional_permissions);

        for (AppPermissionGroup group : mAppPermissions.getPermissionGroups()) {
            if (!Utils.shouldShowPermission(group)) {
                continue;
            }

            boolean isPlatform = group.getDeclaringPackage().equals(Utils.OS_PKG);

            PermissionPreference preference = new PermissionPreference(this, group, this);
            preference.setKey(group.getName());
            Drawable icon = Utils.loadDrawable(context.getPackageManager(),
                    group.getIconPkg(), group.getIconResId());
            preference.setIcon(Utils.applyTint(getContext(), icon,
                    android.R.attr.colorControlNormal));
            preference.setTitle(group.getLabel());

            if (isPlatform) {
                screen.addPreference(preference);
            } else {
                if (mExtraScreen == null) {
                    mExtraScreen = getPreferenceManager().createPreferenceScreen(context);
                }
                mExtraScreen.addPreference(preference);
            }
        }

        if (mExtraScreen != null) {
            extraPerms.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    AdditionalPermissionsFragment frag = new AdditionalPermissionsFragment();
                    setPackageName(frag, getArguments().getString(Intent.EXTRA_PACKAGE_NAME));
                    frag.setTargetFragment(AppPermissionsFragment.this, 0);
                    getFragmentManager().beginTransaction()
                            .replace(android.R.id.content, frag)
                            .addToBackStack(null)
                            .commit();
                    return true;
                }
            });
            int count = mExtraScreen.getPreferenceCount();
            extraPerms.setSummary(getResources().getQuantityString(
                    R.plurals.additional_permissions_more, count, count));
            screen.addPreference(extraPerms);
        }

        setLoading(false /* loading */, true /* animate */);
    }

    @Override
    public void onPreferenceChanged(String key) {
        if (mToggledGroups == null) {
            mToggledGroups = new ArraySet<>();
        }
        mToggledGroups.add(mAppPermissions.getPermissionGroup(key));
    }

    @Override
    public void onPause() {
        super.onPause();
        logToggledGroups();
    }

    private void logToggledGroups() {
        if (mToggledGroups != null) {
            SafetyNetLogger.logPermissionsToggled(mToggledGroups);
            mToggledGroups = null;
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

    @Override
    public boolean shouldConfirmDefaultPermissionRevoke() {
        return !mHasConfirmedRevoke;
    }

    @Override
    public void hasConfirmDefaultPermissionRevoke() {
        mHasConfirmedRevoke = true;
    }

    @Override
    public void onBackgroundAccessChosen(String key, int chosenItem) {
        ((PermissionPreference) getPreferenceScreen().findPreference(key))
                .onBackgroundAccessChosen(chosenItem);
    }

    @Override
    public void onDenyAnyWay(String key, @PermissionPreference.ChangeTarget int changeTarget) {
        ((PermissionPreference) getPreferenceScreen().findPreference(key)).onDenyAnyWay(
                changeTarget);
    }

    public static class AdditionalPermissionsFragment extends SettingsWithHeader {
        AppPermissionsFragment mOuterFragment;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            mOuterFragment = (AppPermissionsFragment) getTargetFragment();
            super.onCreate(savedInstanceState);
            setHeader(mOuterFragment.mIcon, mOuterFragment.mLabel, mOuterFragment.mInfoIntent);
            setHasOptionsMenu(true);
            setPreferenceScreen(mOuterFragment.mExtraScreen);
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            String packageName = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
            bindUi(this, getPackageInfo(getActivity(), packageName));
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
