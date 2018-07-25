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
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.ArraySet;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.Preference.OnPreferenceClickListener;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.android.packageinstaller.DeviceUtils;
import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.PermissionApps;
import com.android.packageinstaller.permission.model.PermissionApps.Callback;
import com.android.packageinstaller.permission.model.PermissionApps.PermissionApp;
import com.android.packageinstaller.permission.ui.ReviewPermissionsActivity;
import com.android.packageinstaller.permission.utils.LocationUtils;
import com.android.packageinstaller.permission.utils.SafetyNetLogger;
import com.android.packageinstaller.permission.utils.Utils;

public final class PermissionAppsFragment extends SettingsWithHeader implements Callback,
        OnPreferenceChangeListener {

    private static final int MENU_SHOW_SYSTEM = Menu.FIRST;
    private static final int MENU_HIDE_SYSTEM = Menu.FIRST + 1;
    private static final String KEY_SHOW_SYSTEM_PREFS = "_showSystem";

    public static PermissionAppsFragment newInstance(String permissionName) {
        return setPermissionName(new PermissionAppsFragment(), permissionName);
    }

    private static <T extends Fragment> T setPermissionName(T fragment, String permissionName) {
        Bundle arguments = new Bundle();
        arguments.putString(Intent.EXTRA_PERMISSION_NAME, permissionName);
        fragment.setArguments(arguments);
        return fragment;
    }

    private PermissionApps mPermissionApps;

    private PreferenceScreen mExtraScreen;

    private ArraySet<AppPermissionGroup> mToggledGroups;
    private ArraySet<String> mLauncherPkgs;
    private boolean mHasConfirmedRevoke;

    private boolean mShowSystem;
    private boolean mHasSystemApps;
    private MenuItem mShowSystemMenu;
    private MenuItem mHideSystemMenu;

    private Callback mOnPermissionsLoadedListener;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setLoading(true /* loading */, false /* animate */);
        mLauncherPkgs = Utils.getLauncherPackages(getContext());
        String groupName = getArguments().getString(Intent.EXTRA_PERMISSION_NAME);
        mPermissionApps = new PermissionApps(getActivity(), groupName, this);
    }

    @Override
    public void onStart() {
        super.onStart();

        setHasOptionsMenu(true);
        final ActionBar ab = getActivity().getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }

        mPermissionApps.refresh(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (mHasSystemApps) {
            mShowSystemMenu = menu.add(Menu.NONE, MENU_SHOW_SYSTEM, Menu.NONE,
                    R.string.menu_show_system);
            mHideSystemMenu = menu.add(Menu.NONE, MENU_HIDE_SYSTEM, Menu.NONE,
                    R.string.menu_hide_system);
            updateMenu();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                getActivity().finish();
                return true;
            case MENU_SHOW_SYSTEM:
            case MENU_HIDE_SYSTEM:
                mShowSystem = item.getItemId() == MENU_SHOW_SYSTEM;
                if (mPermissionApps.getApps() != null) {
                    onPermissionsLoaded(mPermissionApps);
                }
                updateMenu();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateMenu() {
        mShowSystemMenu.setVisible(!mShowSystem);
        mHideSystemMenu.setVisible(mShowSystem);
    }

    @Override
    protected void onSetEmptyText(TextView textView) {
        textView.setText(R.string.no_apps);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindUi(this, mPermissionApps);
    }

    private static void bindUi(SettingsWithHeader fragment, PermissionApps permissionApps) {
        final Drawable icon = permissionApps.getIcon();
        final CharSequence label = permissionApps.getLabel();

        fragment.setHeader(null, null, null,
                fragment.getString(R.string.permission_apps_decor_title, label));
    }

    private void setOnPermissionsLoadedListener(Callback callback) {
        mOnPermissionsLoadedListener = callback;
    }

    @Override
    public void onPermissionsLoaded(PermissionApps permissionApps) {
        Context context = getPreferenceManager().getContext();

        if (context == null) {
            return;
        }

        boolean isTelevision = DeviceUtils.isTelevision(context);
        PreferenceScreen screen = getPreferenceScreen();

        ArraySet<String> preferencesToRemove = new ArraySet<>();
        for (int i = 0, n = screen.getPreferenceCount(); i < n; i++) {
            preferencesToRemove.add(screen.getPreference(i).getKey());
        }
        if (mExtraScreen != null) {
            for (int i = 0, n = mExtraScreen.getPreferenceCount(); i < n; i++) {
                preferencesToRemove.add(mExtraScreen.getPreference(i).getKey());
            }
        }

        mHasSystemApps = false;
        boolean menuOptionsInvalided = false;

        for (PermissionApp app : permissionApps.getApps()) {
            if (!Utils.shouldShowPermission(app.getPermissionGroup())) {
                continue;
            }

            String key = app.getKey();
            preferencesToRemove.remove(key);
            Preference existingPref = screen.findPreference(key);
            if (existingPref == null && mExtraScreen != null) {
                existingPref = mExtraScreen.findPreference(key);
            }

            boolean isSystemApp = Utils.isSystem(app, mLauncherPkgs);

            if (isSystemApp && !menuOptionsInvalided) {
                mHasSystemApps = true;
                getActivity().invalidateOptionsMenu();
                menuOptionsInvalided = true;
            }

            if (isSystemApp && !isTelevision && !mShowSystem) {
                if (existingPref != null) {
                    screen.removePreference(existingPref);
                }
                continue;
            }

            if (existingPref != null) {
                // If existing preference - only update its state.
                if (app.isPolicyFixed()) {
                    existingPref.setSummary(getString(
                            R.string.permission_summary_enforced_by_policy));
                }
                existingPref.setPersistent(false);
                existingPref.setEnabled(!app.isPolicyFixed());
                if (existingPref instanceof SwitchPreference) {
                    ((SwitchPreference) existingPref)
                            .setChecked(app.areRuntimePermissionsGranted());
                }
                continue;
            }

            SwitchPreference pref = new SwitchPreference(context);
            pref.setOnPreferenceChangeListener(this);
            pref.setKey(app.getKey());
            pref.setIcon(app.getIcon());
            pref.setTitle(app.getLabel());
            if (app.isPolicyFixed()) {
                pref.setSummary(getString(R.string.permission_summary_enforced_by_policy));
            }
            pref.setPersistent(false);
            pref.setEnabled(!app.isPolicyFixed());
            pref.setChecked(app.areRuntimePermissionsGranted());

            if (isSystemApp && isTelevision) {
                if (mExtraScreen == null) {
                    mExtraScreen = getPreferenceManager().createPreferenceScreen(context);
                }
                mExtraScreen.addPreference(pref);
            } else {
                screen.addPreference(pref);
            }
        }

        if (mExtraScreen != null) {
            preferencesToRemove.remove(KEY_SHOW_SYSTEM_PREFS);
            Preference pref = screen.findPreference(KEY_SHOW_SYSTEM_PREFS);

            if (pref == null) {
                pref = new Preference(context);
                pref.setKey(KEY_SHOW_SYSTEM_PREFS);
                pref.setIcon(Utils.applyTint(context, R.drawable.ic_toc,
                        android.R.attr.colorControlNormal));
                pref.setTitle(R.string.preference_show_system_apps);
                pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        SystemAppsFragment frag = new SystemAppsFragment();
                        setPermissionName(frag, getArguments().getString(
                                Intent.EXTRA_PERMISSION_NAME));
                        frag.setTargetFragment(PermissionAppsFragment.this, 0);
                        getFragmentManager().beginTransaction()
                            .replace(android.R.id.content, frag)
                            .addToBackStack("SystemApps")
                            .commit();
                        return true;
                    }
                });
                screen.addPreference(pref);
            }

            int grantedCount = 0;
            for (int i = 0, n = mExtraScreen.getPreferenceCount(); i < n; i++) {
                if (((SwitchPreference) mExtraScreen.getPreference(i)).isChecked()) {
                    grantedCount++;
                }
            }
            pref.setSummary(getString(R.string.app_permissions_group_summary,
                    grantedCount, mExtraScreen.getPreferenceCount()));
        }

        for (String key : preferencesToRemove) {
            Preference pref = screen.findPreference(key);
            if (pref != null) {
                screen.removePreference(pref);
            } else if (mExtraScreen != null) {
                pref = mExtraScreen.findPreference(key);
                if (pref != null) {
                    mExtraScreen.removePreference(pref);
                }
            }
        }

        setLoading(false /* loading */, true /* animate */);

        if (mOnPermissionsLoadedListener != null) {
            mOnPermissionsLoadedListener.onPermissionsLoaded(permissionApps);
        }
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, Object newValue) {
        String pkg = preference.getKey();
        final PermissionApp app = mPermissionApps.getApp(pkg);

        if (app == null) {
            return false;
        }

        if (LocationUtils.isLocationGroupAndProvider(mPermissionApps.getGroupName(),
                app.getPackageName())) {
            LocationUtils.showLocationDialog(getContext(), app.getLabel());
            return false;
        }

        addToggledGroup(app.getPackageName(), app.getPermissionGroup());

        if (app.isReviewRequired()) {
            Intent intent = new Intent(getActivity(), ReviewPermissionsActivity.class);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, app.getPackageName());
            startActivity(intent);
            return false;
        }

        if (newValue == Boolean.TRUE) {
            app.grantRuntimePermissions();
        } else {
            final boolean grantedByDefault = app.hasGrantedByDefaultPermissions();
            if (grantedByDefault || (!app.doesSupportRuntimePermissions()
                    && !mHasConfirmedRevoke)) {
                new AlertDialog.Builder(getContext())
                        .setMessage(grantedByDefault ? R.string.system_warning
                                : R.string.old_sdk_deny_warning)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.grant_dialog_button_deny_anyway,
                                new OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                ((SwitchPreference) preference).setChecked(false);
                                app.revokeRuntimePermissions();
                                if (!grantedByDefault) {
                                    mHasConfirmedRevoke = true;
                                }
                            }
                        })
                        .show();
                return false;
            } else {
                app.revokeRuntimePermissions();
            }
        }
        return true;
    }

    @Override
    public void onStop() {
        super.onStop();
        logToggledGroups();
    }

    private void addToggledGroup(String packageName, AppPermissionGroup group) {
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

    public static class SystemAppsFragment extends SettingsWithHeader implements Callback {
        PermissionAppsFragment mOuterFragment;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            mOuterFragment = (PermissionAppsFragment) getTargetFragment();
            setLoading(true /* loading */, false /* animate */);
            super.onCreate(savedInstanceState);
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            if (mOuterFragment.mExtraScreen != null) {
                setPreferenceScreen();
            } else {
                mOuterFragment.setOnPermissionsLoadedListener(this);
            }
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            String groupName = getArguments().getString(Intent.EXTRA_PERMISSION_NAME);
            PermissionApps permissionApps = new PermissionApps(getActivity(), groupName, null);
            bindUi(this, permissionApps);
        }

        @Override
        public void onResume() {
            super.onResume();
            mOuterFragment.mPermissionApps.refresh(true);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            mOuterFragment.setOnPermissionsLoadedListener(null);
        }


        private static void bindUi(SettingsWithHeader fragment, PermissionApps permissionApps) {
            final CharSequence label = permissionApps.getLabel();
            fragment.setHeader(null, null, null,
                    fragment.getString(R.string.system_apps_decor_title, label));
        }

        @Override
        public void onPermissionsLoaded(PermissionApps permissionApps) {
            setPreferenceScreen();
        }

        private void setPreferenceScreen() {
            setPreferenceScreen(mOuterFragment.mExtraScreen);
            setLoading(false /* loading */, true /* animate */);
        }
    }
}
