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

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.view.MenuItem;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.DemoMode;
import com.android.systemui.R;

public class DemoModeFragment extends PreferenceFragment implements OnPreferenceChangeListener {

    private static final String DEMO_MODE_ON = "sysui_tuner_demo_on";

    private static final String[] STATUS_ICONS = {
        "volume",
        "bluetooth",
        "location",
        "alarm",
        "zen",
        "sync",
        "tty",
        "eri",
        "mute",
        "speakerphone",
        "managed_profile",
    };

    private SwitchPreference mEnabledSwitch;
    private SwitchPreference mOnSwitch;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getContext();
        mEnabledSwitch = new SwitchPreference(context);
        mEnabledSwitch.setTitle(R.string.enable_demo_mode);
        mEnabledSwitch.setOnPreferenceChangeListener(this);
        mOnSwitch = new SwitchPreference(context);
        mOnSwitch.setTitle(R.string.show_demo_mode);
        mOnSwitch.setEnabled(false);
        mOnSwitch.setOnPreferenceChangeListener(this);

        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        screen.addPreference(mEnabledSwitch);
        screen.addPreference(mOnSwitch);
        setPreferenceScreen(screen);

        updateDemoModeEnabled();
        updateDemoModeOn();
        ContentResolver contentResolver = getContext().getContentResolver();
        contentResolver.registerContentObserver(Settings.Global.getUriFor(
                DemoMode.DEMO_MODE_ALLOWED), false, mDemoModeObserver);
        contentResolver.registerContentObserver(Settings.Global.getUriFor(DEMO_MODE_ON), false,
                mDemoModeObserver);
        setHasOptionsMenu(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                getFragmentManager().popBackStack();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onResume() {
        super.onResume();
        MetricsLogger.visibility(getContext(), MetricsLogger.TUNER_DEMO_MODE, true);
    }

    @Override
    public void onPause() {
        super.onPause();
        MetricsLogger.visibility(getContext(), MetricsLogger.TUNER_DEMO_MODE, false);
    }

    @Override
    public void onDestroy() {
        getContext().getContentResolver().unregisterContentObserver(mDemoModeObserver);
        super.onDestroy();
    }

    private void updateDemoModeEnabled() {
        boolean enabled = Settings.Global.getInt(getContext().getContentResolver(),
                DemoMode.DEMO_MODE_ALLOWED, 0) != 0;
        mEnabledSwitch.setChecked(enabled);
        mOnSwitch.setEnabled(enabled);
    }

    private void updateDemoModeOn() {
        boolean enabled = Settings.Global.getInt(getContext().getContentResolver(),
                DEMO_MODE_ON, 0) != 0;
        mOnSwitch.setChecked(enabled);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        boolean enabled = newValue == Boolean.TRUE;
        if (preference == mEnabledSwitch) {
            if (!enabled) {
                // Make sure we aren't in demo mode when disabling it.
                mOnSwitch.setChecked(false);
                stopDemoMode();
            }
            MetricsLogger.action(getContext(), MetricsLogger.TUNER_DEMO_MODE_ENABLED, enabled);
            setGlobal(DemoMode.DEMO_MODE_ALLOWED, enabled ? 1 : 0);
        } else if (preference == mOnSwitch) {
            MetricsLogger.action(getContext(), MetricsLogger.TUNER_DEMO_MODE_ON, enabled);
            if (enabled) {
                startDemoMode();
            } else {
                stopDemoMode();
            }
        } else {
            return false;
        }
        return true;
    }

    private void startDemoMode() {
        Intent intent = new Intent(DemoMode.ACTION_DEMO);

        intent.putExtra(DemoMode.EXTRA_COMMAND, DemoMode.COMMAND_ENTER);
        getContext().sendBroadcast(intent);

        intent.putExtra(DemoMode.EXTRA_COMMAND, DemoMode.COMMAND_CLOCK);
        intent.putExtra("hhmm", "0600");
        getContext().sendBroadcast(intent);

        intent.putExtra(DemoMode.EXTRA_COMMAND, DemoMode.COMMAND_NETWORK);
        intent.putExtra("wifi", "show");
        intent.putExtra("mobile", "show");
        intent.putExtra("sims", "1");
        intent.putExtra("nosim", "false");
        intent.putExtra("level", "4");
        intent.putExtra("datatypel", "");
        getContext().sendBroadcast(intent);

        // Need to send this after so that the sim controller already exists.
        intent.putExtra("fully", "true");
        getContext().sendBroadcast(intent);

        intent.putExtra(DemoMode.EXTRA_COMMAND, DemoMode.COMMAND_BATTERY);
        intent.putExtra("level", "100");
        intent.putExtra("plugged", "false");
        getContext().sendBroadcast(intent);

        intent.putExtra(DemoMode.EXTRA_COMMAND, DemoMode.COMMAND_STATUS);
        for (String icon : STATUS_ICONS) {
            intent.putExtra(icon, "hide");
        }
        getContext().sendBroadcast(intent);

        intent.putExtra(DemoMode.EXTRA_COMMAND, DemoMode.COMMAND_NOTIFICATIONS);
        intent.putExtra("visible", "false");
        getContext().sendBroadcast(intent);

        setGlobal(DEMO_MODE_ON, 1);
    }

    private void stopDemoMode() {
        Intent intent = new Intent(DemoMode.ACTION_DEMO);
        intent.putExtra(DemoMode.EXTRA_COMMAND, DemoMode.COMMAND_EXIT);
        getContext().sendBroadcast(intent);
        setGlobal(DEMO_MODE_ON, 0);
    }

    private void setGlobal(String key, int value) {
        Settings.Global.putInt(getContext().getContentResolver(), key, value);
    }

    private final ContentObserver mDemoModeObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
        public void onChange(boolean selfChange) {
            updateDemoModeEnabled();
            updateDemoModeOn();
        };
    };
}
