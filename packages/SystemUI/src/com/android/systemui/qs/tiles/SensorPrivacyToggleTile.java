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

import android.content.Intent;
import android.hardware.SensorPrivacyManager.Sensors.Sensor;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import androidx.annotation.DrawableRes;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

/**
 * Superclass to toggle individual sensor privacy via quick settings tiles
 */
public abstract class SensorPrivacyToggleTile extends QSTileImpl<QSTile.BooleanState> implements
        IndividualSensorPrivacyController.Callback {

    private final KeyguardStateController mKeyguard;
    private IndividualSensorPrivacyController mSensorPrivacyController;

    /**
     * @return Id of the sensor that will be toggled
     */
    public abstract @Sensor int getSensorId();

    /**
     * @return icon for the QS tile
     */
    public abstract @DrawableRes int getIconRes(boolean isBlocked);

    protected SensorPrivacyToggleTile(QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            IndividualSensorPrivacyController sensorPrivacyController,
            KeyguardStateController keyguardStateController) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mSensorPrivacyController = sensorPrivacyController;
        mKeyguard = keyguardStateController;
        mSensorPrivacyController.observe(getLifecycle(), this);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        if (mKeyguard.isMethodSecure() && mKeyguard.isShowing()) {
            mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                mSensorPrivacyController.setSensorBlocked(getSensorId(),
                        !mSensorPrivacyController.isSensorBlocked(getSensorId()));
            });
            return;
        }
        mSensorPrivacyController.setSensorBlocked(getSensorId(),
                !mSensorPrivacyController.isSensorBlocked(getSensorId()));
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        boolean isBlocked = arg == null ? mSensorPrivacyController.isSensorBlocked(getSensorId())
                : (boolean) arg;

        state.icon = ResourceIcon.get(getIconRes(isBlocked));
        state.state = isBlocked ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE;
        state.value = !isBlocked;
        state.label = getTileLabel();
        if (isBlocked) {
            state.secondaryLabel = mContext.getString(R.string.quick_settings_camera_mic_blocked);
        } else {
            state.secondaryLabel = mContext.getString(R.string.quick_settings_camera_mic_available);
        }
        state.handlesLongClick = false;
        state.contentDescription = state.label;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return 0;
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return super.getDetailAdapter();
    }

    @Override
    public void onSensorBlockedChanged(int sensor, boolean blocked) {
        if (sensor == getSensorId()) {
            refreshState(blocked);
        }
    }
}
