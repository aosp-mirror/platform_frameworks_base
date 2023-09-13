/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.accessibilitymenu.activity;

import android.app.ActionBar;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Browser;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.android.systemui.accessibility.accessibilitymenu.Flags;
import com.android.systemui.accessibility.accessibilitymenu.R;

/**
 * Settings activity for AccessibilityMenu.
 */
public class A11yMenuSettingsActivity extends FragmentActivity {
    private OnBackInvokedCallback mCallback = () -> {
        finish();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, new A11yMenuPreferenceFragment())
                .commit();

        ActionBar actionBar = getActionBar();
        actionBar.setDisplayShowCustomEnabled(true);

        if (Flags.a11yMenuSettingsBackButtonFixAndLargeButtonSizing()) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        actionBar.setCustomView(R.layout.preferences_action_bar);
        ((TextView) findViewById(R.id.action_bar_title)).setText(
                getResources().getString(R.string.accessibility_menu_settings_name)
        );
        actionBar.setDisplayOptions(
                ActionBar.DISPLAY_TITLE_MULTIPLE_LINES
                        | ActionBar.DISPLAY_SHOW_TITLE
                        | ActionBar.DISPLAY_HOME_AS_UP);
    }

    @Override
    public boolean onNavigateUp() {
        if (Flags.a11yMenuSettingsBackButtonFixAndLargeButtonSizing()) {
            mCallback.onBackInvoked();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Settings/preferences fragment for AccessibilityMenu.
     */
    public static class A11yMenuPreferenceFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle bundle, String s) {
            setPreferencesFromResource(R.xml.accessibilitymenu_preferences, s);
            initializeHelpAndFeedbackPreference();
        }

        @Override
        public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            view.setLayoutDirection(
                    view.getResources().getConfiguration().getLayoutDirection());
        }

        /**
         * Returns large buttons settings state.
         *
         * @param context The parent context
         * @return {@code true} large button is enabled; {@code false} large button is disabled
         */
        public static boolean isLargeButtonsEnabled(Context context) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String key = context.getResources().getString(R.string.pref_large_buttons);
            return prefs.getBoolean(key, false);
        }

        private void initializeHelpAndFeedbackPreference() {
            final Preference prefHelp = findPreference(getString(R.string.pref_help));
            if (prefHelp != null) {
                // Do not allow access to web during setup.
                if (Settings.Secure.getInt(
                        getContext().getContentResolver(),
                        Settings.Secure.USER_SETUP_COMPLETE, 0) != 1) {
                    return;
                }

                // Configure preference to open the help page in the default web browser.
                // If the system has no browser, hide the preference.
                Uri uri = Uri.parse(getResources().getString(R.string.help_url));
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.putExtra(Browser.EXTRA_APPLICATION_ID, getContext().getPackageName());
                if (getActivity().getPackageManager().queryIntentActivities(
                        intent, PackageManager.ResolveInfoFlags.of(0)).isEmpty()) {
                    prefHelp.setVisible(false);
                    return;
                }
                prefHelp.setIntent(intent);
            }
        }
    }
}
