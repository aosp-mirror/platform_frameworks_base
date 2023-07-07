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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeAvailabilityTracker;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.util.settings.GlobalSettings;

public class DemoModeFragment extends PreferenceFragment implements OnPreferenceChangeListener {

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

    private DemoModeController mDemoModeController;
    private GlobalSettings mGlobalSettings;
    private Tracker mDemoModeTracker;

    // We are the only ones who ever call this constructor, so don't worry about the warning
    @SuppressLint("ValidFragment")
    public DemoModeFragment(DemoModeController demoModeController, GlobalSettings globalSettings) {
        super();
        mDemoModeController = demoModeController;
        mGlobalSettings = globalSettings;
    }


    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
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

        mDemoModeTracker = new Tracker(context, mGlobalSettings);
        mDemoModeTracker.startTracking();
        updateDemoModeEnabled();
        updateDemoModeOn();

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
        MetricsLogger.visibility(getContext(), MetricsEvent.TUNER_DEMO_MODE, true);
    }

    @Override
    public void onPause() {
        super.onPause();
        MetricsLogger.visibility(getContext(), MetricsEvent.TUNER_DEMO_MODE, false);
    }

    @Override
    public void onDestroy() {
        mDemoModeTracker.stopTracking();
        super.onDestroy();
    }

    private void updateDemoModeEnabled() {
        mEnabledSwitch.setChecked(mDemoModeTracker.isDemoModeAvailable());
        mOnSwitch.setEnabled(mDemoModeTracker.isDemoModeAvailable());
    }

    private void updateDemoModeOn() {
        mOnSwitch.setChecked(mDemoModeTracker.isInDemoMode());
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
            MetricsLogger.action(getContext(), MetricsEvent.TUNER_DEMO_MODE_ENABLED, enabled);
            mDemoModeController.requestSetDemoModeAllowed(enabled);
        } else if (preference == mOnSwitch) {
            MetricsLogger.action(getContext(), MetricsEvent.TUNER_DEMO_MODE_ON, enabled);
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

        mDemoModeController.requestStartDemoMode();

        intent.putExtra(DemoMode.EXTRA_COMMAND, DemoMode.COMMAND_CLOCK);

        //TODO: everything below should move to DemoModeController, or some `initialState` command
        String demoTime = "1010"; // 10:10, a classic choice of horologists
        try {
            String[] versionParts = android.os.Build.VERSION.RELEASE_OR_CODENAME.split("\\.");
            int majorVersion = Integer.valueOf(versionParts[0]);
            demoTime = String.format("%02d00", majorVersion % 24);
        } catch (IllegalArgumentException ex) {
        }
        intent.putExtra("hhmm", demoTime);
        getContext().sendBroadcast(intent);

        intent.putExtra(DemoMode.EXTRA_COMMAND, DemoMode.COMMAND_NETWORK);
        intent.putExtra("wifi", "show");
        intent.putExtra("mobile", "show");
        intent.putExtra("sims", "1");
        intent.putExtra("nosim", "false");
        intent.putExtra("level", "4");
        intent.putExtra("datatype", "lte");
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

    }

    private void stopDemoMode() {
        mDemoModeController.requestFinishDemoMode();
    }

    private class Tracker extends DemoModeAvailabilityTracker {
        Tracker(Context context, GlobalSettings globalSettings) {
            super(context, globalSettings);
        }

        @Override
        public void onDemoModeAvailabilityChanged() {
            updateDemoModeEnabled();
            updateDemoModeOn();
        }

        @Override
        public void onDemoModeStarted() {
            updateDemoModeOn();
        }

        @Override
        public void onDemoModeFinished() {
            updateDemoModeOn();
        }
    };
}
