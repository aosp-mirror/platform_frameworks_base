/*
 * Copyright (C) 2017 The LineageOS Project
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

import android.content.Context;
import android.net.ConnectivityManager;
import android.provider.Settings;
import android.os.Bundle;
import android.os.UserHandle;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;
import androidx.preference.PreferenceFragment;
import android.view.MenuItem;

import androidx.preference.PreferenceFragment;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;

public class StatusBarTuner extends PreferenceFragment {

    private static final String SHOW_FOURG = "show_fourg";

    private SwitchPreference mShowFourG;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setHasOptionsMenu(true);
        getActivity().getActionBar().setDisplayHomeAsUpEnabled(true);
        mShowFourG = (SwitchPreference) findPreference(SHOW_FOURG);
        if (isWifiOnly()) {
            getPreferenceScreen().removePreference(mShowFourG);
        } else {
            mShowFourG.setChecked(Settings.System.getIntForUser(getActivity().getContentResolver(),
                Settings.System.SHOW_FOURG, 0,
                UserHandle.USER_CURRENT) == 1);
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.status_bar_prefs);
    }

    @Override
    public void onResume() {
        super.onResume();
        MetricsLogger.visibility(getContext(), MetricsEvent.TUNER, true);
    }

    @Override
    public void onPause() {
        super.onPause();
        MetricsLogger.visibility(getContext(), MetricsEvent.TUNER, false);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getActivity().onBackPressed();
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference == mShowFourG) {
            boolean checked = ((SwitchPreference)preference).isChecked();
            Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.SHOW_FOURG, checked ? 1 : 0);
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    private boolean isWifiOnly() {
        ConnectivityManager cm = (ConnectivityManager)getActivity().getSystemService(
                Context.CONNECTIVITY_SERVICE);
        return (cm != null && cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE) == false);
    }
}
