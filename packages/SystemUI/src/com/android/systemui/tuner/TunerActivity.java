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
package com.android.systemui.tuner;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toolbar;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragment;
import androidx.preference.PreferenceScreen;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.fragments.FragmentService;

public class TunerActivity extends Activity implements
        PreferenceFragment.OnPreferenceStartFragmentCallback,
        PreferenceFragment.OnPreferenceStartScreenCallback {

    private static final String TAG_TUNER = "tuner";

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.tuner_activity);
        Toolbar toolbar = findViewById(R.id.action_bar);
        if (toolbar != null) {
            setActionBar(toolbar);
        }

        Dependency.initDependencies(SystemUIFactory.getInstance().getRootComponent());

        if (getFragmentManager().findFragmentByTag(TAG_TUNER) == null) {
            final String action = getIntent().getAction();
            final Fragment fragment;
            if ("com.android.settings.action.DEMO_MODE".equals(action)) {
                fragment = new DemoModeFragment();
            } else if ("com.android.settings.action.STATUS_BAR_TUNER".equals(action)) {
                fragment = new StatusBarTuner();
            } else {
                fragment = new TunerFragment();
            }

            getFragmentManager().beginTransaction().replace(R.id.content_frame,
                    fragment, TAG_TUNER).commit();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Dependency.destroy(FragmentService.class, s -> s.destroyAll());
        Dependency.clearDependencies();
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onBackPressed() {
        if (!getFragmentManager().popBackStackImmediate()) {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onPreferenceStartFragment(PreferenceFragment caller, Preference pref) {
        try {
            Class<?> cls = Class.forName(pref.getFragment());
            Fragment fragment = (Fragment) cls.newInstance();
            final Bundle b = new Bundle(1);
            b.putString(PreferenceFragment.ARG_PREFERENCE_ROOT, pref.getKey());
            fragment.setArguments(b);
            FragmentTransaction transaction = getFragmentManager().beginTransaction();
            setTitle(pref.getTitle());
            transaction.replace(R.id.content_frame, fragment);
            transaction.addToBackStack("PreferenceFragment");
            transaction.commit();
            return true;
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            Log.d("TunerActivity", "Problem launching fragment", e);
            return false;
        }
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragment caller, PreferenceScreen pref) {
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        SubSettingsFragment fragment = new SubSettingsFragment();
        final Bundle b = new Bundle(1);
        b.putString(PreferenceFragment.ARG_PREFERENCE_ROOT, pref.getKey());
        fragment.setArguments(b);
        fragment.setTargetFragment(caller, 0);
        transaction.replace(R.id.content_frame, fragment);
        transaction.addToBackStack("PreferenceFragment");
        transaction.commit();
        return true;
    }

    public static class SubSettingsFragment extends PreferenceFragment {
        private PreferenceScreen mParentScreen;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            mParentScreen =
                    (PreferenceScreen) ((PreferenceFragment) getTargetFragment())
                            .getPreferenceScreen().findPreference(rootKey);
            PreferenceScreen screen =
                    getPreferenceManager().createPreferenceScreen(
                            getPreferenceManager().getContext());
            setPreferenceScreen(screen);
            // Copy all the preferences over to this screen so they go into the attached state.
            while (mParentScreen.getPreferenceCount() > 0) {
                Preference p = mParentScreen.getPreference(0);
                mParentScreen.removePreference(p);
                screen.addPreference(p);
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            // Copy all the preferences back so we don't lose them.
            PreferenceScreen screen = getPreferenceScreen();
            while (screen.getPreferenceCount() > 0) {
                Preference p = screen.getPreference(0);
                screen.removePreference(p);
                mParentScreen.addPreference(p);
            }
        }
    }

}
