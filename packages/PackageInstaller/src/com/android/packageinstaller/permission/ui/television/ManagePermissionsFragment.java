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
import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceClickListener;
import androidx.preference.PreferenceScreen;
import android.util.ArraySet;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;

import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.model.PermissionApps.PmCache;
import com.android.packageinstaller.permission.model.PermissionGroup;
import com.android.packageinstaller.permission.model.PermissionGroups;
import com.android.packageinstaller.permission.utils.Utils;

import java.util.List;

public final class ManagePermissionsFragment extends SettingsWithHeader
        implements PermissionGroups.PermissionsGroupsChangeCallback, OnPreferenceClickListener {
    private static final String LOG_TAG = "ManagePermissionsFragment";

    private static final String OS_PKG = "android";

    private static final String EXTRA_PREFS_KEY = "extra_prefs_key";

    private ArraySet<String> mLauncherPkgs;

    private PermissionGroups mPermissions;

    private PreferenceScreen mExtraScreen;

    public static ManagePermissionsFragment newInstance() {
        return new ManagePermissionsFragment();
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setLoading(true /* loading */, false /* animate */);
        setHasOptionsMenu(true);
        final ActionBar ab = getActivity().getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }
        mLauncherPkgs = Utils.getLauncherPackages(getContext());
        mPermissions = new PermissionGroups(getContext(), getLoaderManager(), this);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getActivity().finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();

        PermissionGroup group = mPermissions.getGroup(key);
        if (group == null) {
            return false;
        }

        Intent intent = new Intent(Intent.ACTION_MANAGE_PERMISSION_APPS)
                .putExtra(Intent.EXTRA_PERMISSION_NAME, key);
        try {
            getActivity().startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(LOG_TAG, "No app to handle " + intent);
        }

        return true;
    }

    @Override
    public void onPermissionGroupsChanged() {
        updatePermissionsUi();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindPermissionUi(this, getView());
    }

    private static void bindPermissionUi(SettingsWithHeader fragment, @Nullable View rootView) {
        if (fragment == null || rootView == null) {
            return;
        }
        fragment.setHeader(null, null, null, fragment.getString(
                R.string.manage_permissions_decor_title));
    }

    private void updatePermissionsUi() {
        Context context = getPreferenceManager().getContext();
        if (context == null) {
            return;
        }

        List<PermissionGroup> groups = mPermissions.getGroups();
        PreferenceScreen screen = getPreferenceScreen();

        // Use this to speed up getting the info for all of the PermissionApps below.
        // Create a new one for each refresh to make sure it has fresh data.
        PmCache cache = new PmCache(getContext().getPackageManager());
        for (PermissionGroup group : groups) {
            boolean isSystemPermission = group.getDeclaringPackage().equals(OS_PKG);

            Preference preference = findPreference(group.getName());
            if (preference == null && mExtraScreen != null) {
                preference = mExtraScreen.findPreference(group.getName());
            }
            if (preference == null) {
                preference = new Preference(context);
                preference.setOnPreferenceClickListener(this);
                preference.setKey(group.getName());
                preference.setIcon(Utils.applyTint(context, group.getIcon(),
                        android.R.attr.colorControlNormal));
                preference.setTitle(group.getLabel());
                // Set blank summary so that no resizing/jumping happens when the summary is loaded.
                preference.setSummary(" ");
                preference.setPersistent(false);
                if (isSystemPermission) {
                    screen.addPreference(preference);
                } else {
                    if (mExtraScreen == null) {
                        mExtraScreen = getPreferenceManager().createPreferenceScreen(context);
                    }
                    mExtraScreen.addPreference(preference);
                }
            }

            preference.setSummary(getString(R.string.app_permissions_group_summary,
                    group.getGranted(), group.getTotal()));
        }

        if (mExtraScreen != null && mExtraScreen.getPreferenceCount() > 0
                && screen.findPreference(EXTRA_PREFS_KEY) == null) {
            Preference extraScreenPreference = new Preference(context);
            extraScreenPreference.setKey(EXTRA_PREFS_KEY);
            extraScreenPreference.setIcon(Utils.applyTint(context,
                    R.drawable.ic_more_items,
                    android.R.attr.colorControlNormal));
            extraScreenPreference.setTitle(R.string.additional_permissions);
            extraScreenPreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    AdditionalPermissionsFragment frag = new AdditionalPermissionsFragment();
                    frag.setTargetFragment(ManagePermissionsFragment.this, 0);
                    FragmentTransaction ft = getFragmentManager().beginTransaction();
                    ft.replace(android.R.id.content, frag);
                    ft.addToBackStack(null);
                    ft.commit();
                    return true;
                }
            });
            int count = mExtraScreen.getPreferenceCount();
            extraScreenPreference.setSummary(getResources().getQuantityString(
                    R.plurals.additional_permissions_more, count, count));
            screen.addPreference(extraScreenPreference);
        }
        if (screen.getPreferenceCount() != 0) {
            setLoading(false /* loading */, true /* animate */);
        }
    }

    public static class AdditionalPermissionsFragment extends SettingsWithHeader {
        @Override
        public void onCreate(Bundle icicle) {
            setLoading(true /* loading */, false /* animate */);
            super.onCreate(icicle);
            getActivity().setTitle(R.string.additional_permissions);
            setHasOptionsMenu(true);
        }

        @Override
        public void onDestroy() {
            getActivity().setTitle(R.string.app_permissions);
            super.onDestroy();
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

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            bindPermissionUi(this, getView());
        }

        private static void bindPermissionUi(SettingsWithHeader fragment, @Nullable View rootView) {
            if (fragment == null || rootView == null) {
                return;
            }
            fragment.setHeader(null, null, null,
                    fragment.getString(R.string.additional_permissions_decor_title));
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferenceScreen(((ManagePermissionsFragment) getTargetFragment()).mExtraScreen);
            setLoading(false /* loading */, true /* animate */);
        }
    }
}
