/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.tuner;

import android.annotation.Nullable;
import android.app.Fragment;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;

public class PowerNotificationControlsFragment extends Fragment {

    private static final String KEY_SHOW_PNC = "show_importance_slider";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.power_notification_controls_settings, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final View switchBar = view.findViewById(R.id.switch_bar);
        final Switch switchWidget = (Switch) switchBar.findViewById(android.R.id.switch_widget);
        final TextView switchText = (TextView) switchBar.findViewById(R.id.switch_text);
        switchWidget.setChecked(isEnabled());
        switchText.setText(isEnabled()
                ? getString(R.string.switch_bar_on)
                : getString(R.string.switch_bar_off));

        switchWidget.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean newState = !isEnabled();
                MetricsLogger.action(getContext(),
                        MetricsEvent.ACTION_TUNER_POWER_NOTIFICATION_CONTROLS, newState);
                Settings.Secure.putInt(getContext().getContentResolver(),
                        KEY_SHOW_PNC, newState ? 1 : 0);
                switchWidget.setChecked(newState);
                switchText.setText(newState
                        ? getString(R.string.switch_bar_on)
                        : getString(R.string.switch_bar_off));
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        MetricsLogger.visibility(
                getContext(), MetricsEvent.TUNER_POWER_NOTIFICATION_CONTROLS, true);
    }

    @Override
    public void onPause() {
        super.onPause();
        MetricsLogger.visibility(
                getContext(), MetricsEvent.TUNER_POWER_NOTIFICATION_CONTROLS, false);
    }

    private boolean isEnabled() {
        int setting = Settings.Secure.getInt(getContext().getContentResolver(), KEY_SHOW_PNC, 0);
        return setting == 1;
    }

}
