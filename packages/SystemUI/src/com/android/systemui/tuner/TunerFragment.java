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

import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.provider.Settings.System;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.tuner.TunerService.Tunable;

import static com.android.systemui.BatteryMeterView.SHOW_PERCENT_SETTING;

public class TunerFragment extends PreferenceFragment {

    private static final String TAG = "TunerFragment";

    private static final String KEY_QS_TUNER = "qs_tuner";
    private static final String KEY_DEMO_MODE = "demo_mode";
    private static final String KEY_BATTERY_PCT = "battery_pct";

    public static final String SETTING_SEEN_TUNER_WARNING = "seen_tuner_warning";

    private static final int MENU_REMOVE = Menu.FIRST + 1;

    private final SettingObserver mSettingObserver = new SettingObserver();

    private SwitchPreference mBatteryPct;

    private Preference mQsTuner;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.tuner_prefs);
        getActivity().getActionBar().setDisplayHomeAsUpEnabled(true);
        setHasOptionsMenu(true);

        mQsTuner = findPreference(KEY_QS_TUNER);
        mQsTuner.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                FragmentTransaction ft = getFragmentManager().beginTransaction();
                ft.replace(android.R.id.content, new QsTuner(), "QsTuner");
                ft.addToBackStack(null);
                ft.commit();
                return true;
            }
        });
        findPreference(KEY_DEMO_MODE).setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                FragmentTransaction ft = getFragmentManager().beginTransaction();
                ft.replace(android.R.id.content, new DemoModeFragment(), "DemoMode");
                ft.addToBackStack(null);
                ft.commit();
                return true;
            }
        });
        mBatteryPct = (SwitchPreference) findPreference(KEY_BATTERY_PCT);
        if (Settings.Secure.getInt(getContext().getContentResolver(), SETTING_SEEN_TUNER_WARNING,
                0) == 0) {
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.tuner_warning_title)
                    .setMessage(R.string.tuner_warning)
                    .setPositiveButton(R.string.got_it, new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Settings.Secure.putInt(getContext().getContentResolver(),
                                    SETTING_SEEN_TUNER_WARNING, 1);
                        }
                    }).show();
        }
        TunerService.get(getContext()).addTunable(mQsPaging, QSPanel.QS_PAGED_PANEL);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        TunerService.get(getContext()).removeTunable(mQsPaging);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateBatteryPct();
        getContext().getContentResolver().registerContentObserver(
                System.getUriFor(SHOW_PERCENT_SETTING), false, mSettingObserver);

        MetricsLogger.visibility(getContext(), MetricsLogger.TUNER, true);
    }

    @Override
    public void onPause() {
        super.onPause();
        getContext().getContentResolver().unregisterContentObserver(mSettingObserver);

        MetricsLogger.visibility(getContext(), MetricsLogger.TUNER, false);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.add(Menu.NONE, MENU_REMOVE, Menu.NONE, R.string.remove_from_settings);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                getActivity().finish();
                return true;
            case MENU_REMOVE:
                TunerService.showResetRequest(getContext(), new Runnable() {
                    @Override
                    public void run() {
                        getActivity().finish();
                    }
                });
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateBatteryPct() {
        mBatteryPct.setOnPreferenceChangeListener(null);
        mBatteryPct.setChecked(System.getInt(getContext().getContentResolver(),
                SHOW_PERCENT_SETTING, 0) != 0);
        mBatteryPct.setOnPreferenceChangeListener(mBatteryPctChange);
    }

    private final class SettingObserver extends ContentObserver {
        public SettingObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            super.onChange(selfChange, uri, userId);
            updateBatteryPct();
        }
    }

    private final OnPreferenceChangeListener mBatteryPctChange = new OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            final boolean v = (Boolean) newValue;
            MetricsLogger.action(getContext(), MetricsLogger.TUNER_BATTERY_PERCENTAGE, v);
            System.putInt(getContext().getContentResolver(), SHOW_PERCENT_SETTING, v ? 1 : 0);
            return true;
        }
    };

    private final Tunable mQsPaging = new Tunable() {
        @Override
        public void onTuningChanged(String key, String newValue) {
            // Only enable QS rearranging when paging is off, because its very broken.
            mQsTuner.setEnabled(newValue == null || Integer.parseInt(newValue) == 0);
        }
    };
}
