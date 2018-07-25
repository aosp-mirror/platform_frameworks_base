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
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.util.ArraySet;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.android.packageinstaller.DeviceUtils;
import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.PermissionApps;
import com.android.packageinstaller.permission.model.PermissionApps.Callback;
import com.android.packageinstaller.permission.model.PermissionApps.PermissionApp;
import com.android.packageinstaller.permission.utils.SafetyNetLogger;
import com.android.packageinstaller.permission.utils.Utils;
import com.android.settingslib.HelpUtils;

/**
 * Show and manage apps which request a single permission group.
 *
 * <p>Shows a list of apps which request at least on permission of this group.
 */
public final class PermissionAppsFragment extends PermissionsFrameFragment implements Callback,
        PermissionPreference.PermissionPreferenceOwnerFragment,
        PermissionPreference.PermissionPreferenceChangeListener {

    private static final int MENU_SHOW_SYSTEM = Menu.FIRST;
    private static final int MENU_HIDE_SYSTEM = Menu.FIRST + 1;
    private static final String KEY_SHOW_SYSTEM_PREFS = "_showSystem";

    private static final String SHOW_SYSTEM_KEY = PermissionAppsFragment.class.getName()
            + KEY_SHOW_SYSTEM_PREFS;

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

        if (savedInstanceState != null) {
            mShowSystem = savedInstanceState.getBoolean(SHOW_SYSTEM_KEY);
        }

        setLoading(true /* loading */, false /* animate */);
        setHasOptionsMenu(true);
        final ActionBar ab = getActivity().getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }
        mLauncherPkgs = Utils.getLauncherPackages(getContext());

        String groupName = getArguments().getString(Intent.EXTRA_PERMISSION_NAME);
        mPermissionApps = new PermissionApps(getActivity(), groupName, this);
        mPermissionApps.refresh(true);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(SHOW_SYSTEM_KEY, mShowSystem);
    }

    @Override
    public void onResume() {
        super.onResume();
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

        HelpUtils.prepareHelpMenuItem(getActivity(), menu, R.string.help_app_permissions,
                getClass().getName());
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
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindUi(this, mPermissionApps);
    }

    private static void bindUi(Fragment fragment, PermissionApps permissionApps) {
        final Drawable icon = permissionApps.getIcon();
        final CharSequence label = permissionApps.getLabel();
        final ActionBar ab = fragment.getActivity().getActionBar();
        if (ab != null) {
            ab.setTitle(label);
        }
    }

    private void setOnPermissionsLoadedListener(Callback callback) {
        mOnPermissionsLoadedListener = callback;
    }

    @Override
    public void onPermissionsLoaded(PermissionApps permissionApps) {
        Context context = getActivity();

        if (context == null) {
            return;
        }

        boolean isTelevision = DeviceUtils.isTelevision(context);
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            screen = getPreferenceManager().createPreferenceScreen(getActivity());
            setPreferenceScreen(screen);
        }

        screen.setOrderingAsAdded(false);

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

            if (!app.getAppInfo().enabled) {
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
                ((PermissionPreference) existingPref).updateUi();
                continue;
            }

            PermissionPreference pref = new PermissionPreference(this, app.getPermissionGroup(),
                    this);
            pref.setKey(app.getKey());
            pref.setIcon(app.getIcon());
            pref.setTitle(app.getLabel());

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
                        setPermissionName(frag, getArguments().getString(Intent.EXTRA_PERMISSION_NAME));
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
    public void onPreferenceChanged(String key) {
        if (mToggledGroups == null) {
            mToggledGroups = new ArraySet<>();
        }
        mToggledGroups.add(mPermissionApps.getApp(key).getPermissionGroup());
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

    @Override
    public boolean shouldConfirmDefaultPermissionRevoke() {
        return !mHasConfirmedRevoke;
    }

    @Override
    public void hasConfirmDefaultPermissionRevoke() {
        mHasConfirmedRevoke = true;
    }

    public static class SystemAppsFragment extends PermissionsFrameFragment implements Callback {
        PermissionAppsFragment mOuterFragment;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            mOuterFragment = (PermissionAppsFragment) getTargetFragment();
            setLoading(true /* loading */, false /* animate */);
            super.onCreate(savedInstanceState);
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
        public void onPermissionsLoaded(PermissionApps permissionApps) {
            setPreferenceScreen();
            mOuterFragment.setOnPermissionsLoadedListener(null);
        }

        private void setPreferenceScreen() {
            setPreferenceScreen(mOuterFragment.mExtraScreen);
            setLoading(false /* loading */, true /* animate */);
        }
    }
}
