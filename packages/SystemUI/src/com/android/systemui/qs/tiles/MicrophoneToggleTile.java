/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.tiles;

import static android.content.pm.PackageManager.FEATURE_MICROPHONE_TOGGLE;
import static android.hardware.SensorPrivacyManager.Sensors.MICROPHONE;

import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.hardware.SensorPrivacyManager.Sensors.Sensor;
import android.os.Handler;
import android.os.Looper;
import android.provider.DeviceConfig;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

public class MicrophoneToggleTile extends SensorPrivacyToggleTile {

    @Inject
    protected MicrophoneToggleTile(QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            MetricsLogger metricsLogger,
            FalsingManager falsingManager,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            IndividualSensorPrivacyController sensorPrivacyController,
            KeyguardStateController keyguardStateController) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger, sensorPrivacyController,
                keyguardStateController);
    }

    @Override
    public boolean isAvailable() {
        return getHost().getContext().getPackageManager()
                .hasSystemFeature(FEATURE_MICROPHONE_TOGGLE)
                && whitelistIpcs(() -> DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                "mic_toggle_enabled",
                true));
    }

    @Override
    public @DrawableRes int getIconRes() {
        return com.android.internal.R.drawable.ic_mic_blocked;
    }

    @Override
    public @NonNull CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_mic_label);
    }

    @Override
    public @Sensor int getSensorId() {
        return MICROPHONE;
    }
}
