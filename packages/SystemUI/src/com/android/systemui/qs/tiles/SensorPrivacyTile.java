/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.hardware.SensorPrivacyManager;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import javax.inject.Inject;

/** Quick settings tile: SensorPrivacy mode **/
public class SensorPrivacyTile extends QSTileImpl<BooleanState> implements
        SensorPrivacyManager.OnSensorPrivacyChangedListener {
    private static final String TAG = "SensorPrivacy";
    private final Icon mIcon =
            ResourceIcon.get(R.drawable.ic_signal_sensors);
    private final KeyguardMonitor mKeyguard;
    private final SensorPrivacyManager mSensorPrivacyManager;
    private final ActivityStarter mActivityStarter;

    @Inject
    public SensorPrivacyTile(QSHost host, SensorPrivacyManager sensorPrivacyManager,
            KeyguardMonitor keyguardMonitor, ActivityStarter activityStarter) {
        super(host);

        mSensorPrivacyManager = sensorPrivacyManager;
        mKeyguard = keyguardMonitor;
        mActivityStarter = activityStarter;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        final boolean wasEnabled = mState.value;
        // Don't allow disabling from the lockscreen.
        if (wasEnabled && mKeyguard.isSecure() && mKeyguard.isShowing()) {
            mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                MetricsLogger.action(mContext, getMetricsCategory(), !wasEnabled);
                setEnabled(!wasEnabled);
            });
            return;
        }

        MetricsLogger.action(mContext, getMetricsCategory(), !wasEnabled);
        setEnabled(!wasEnabled);
    }

    private void setEnabled(boolean enabled) {
        mSensorPrivacyManager.setSensorPrivacy(enabled);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.sensor_privacy_mode);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean enabled = arg instanceof Boolean ? (Boolean) arg
                : mSensorPrivacyManager.isSensorPrivacyEnabled();
        state.value = enabled;
        state.label = mContext.getString(R.string.sensor_privacy_mode);
        state.icon = mIcon;
        state.state = enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.contentDescription = state.label;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_SENSOR_PRIVACY;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext
                    .getString(R.string.accessibility_quick_settings_sensor_privacy_changed_on);
        } else {
            return mContext
                    .getString(R.string.accessibility_quick_settings_sensor_privacy_changed_off);
        }
    }

    @Override
    protected void handleSetListening(boolean listening) {
        if (listening) {
            mSensorPrivacyManager.addSensorPrivacyListener(this);
        } else {
            mSensorPrivacyManager.removeSensorPrivacyListener(this);
        }
    }

    @Override
    public void onSensorPrivacyChanged(boolean enabled) {
        refreshState(enabled);
    }
}
