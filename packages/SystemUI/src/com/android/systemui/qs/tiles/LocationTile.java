/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.os.UserManager;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationController.LocationChangeCallback;

import javax.inject.Inject;

/** Quick settings tile: Location **/
public class LocationTile extends QSTileImpl<BooleanState> {

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_location);

    private static final int BATTERY_SAVING = Settings.Secure.LOCATION_MODE_BATTERY_SAVING;
    private static final int SENSORS_ONLY = Settings.Secure.LOCATION_MODE_SENSORS_ONLY;
    private static final int HIGH_ACCURACY = Settings.Secure.LOCATION_MODE_HIGH_ACCURACY;
    private static final int OFF = Settings.Secure.LOCATION_MODE_OFF;

    private final LocationController mController;
    private final KeyguardMonitor mKeyguard;
    private final ActivityStarter mActivityStarter;
    private final Callback mCallback = new Callback();

    @Inject
    public LocationTile(QSHost host, LocationController locationController,
            KeyguardMonitor keyguardMonitor, ActivityStarter activityStarter) {
        super(host);
        mController = locationController;
        mKeyguard = keyguardMonitor;
        mActivityStarter = activityStarter;
        mController.observe(this, mCallback);
        mKeyguard.observe(this, mCallback);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
    }

    @Override
    protected void handleClick() {
        if (mKeyguard.isSecure() && mKeyguard.isShowing()) {
            mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                final boolean wasEnabled = mState.value;
                mHost.openPanels();
                switchMode();
            });
            return;
        }
        switchMode();
    }

    private void switchMode() {
        int currentMode = mController.getCurrentMode();
        if (currentMode == BATTERY_SAVING) {
            //from battery saving to off
            mController.setLocationEnabled(OFF);
        } else if (currentMode == SENSORS_ONLY) {
            //from sensor only to high precision
            mController.setLocationEnabled(HIGH_ACCURACY);
        } else if (currentMode == HIGH_ACCURACY) {
            //from high precision to battery saving
            mController.setLocationEnabled(BATTERY_SAVING);
        } else {
            //from off to sensor only
            mController.setLocationEnabled(SENSORS_ONLY);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_location_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean locationEnabled =  mController.isLocationEnabled();
        // Work around for bug 15916487: don't show location tile on top of lock screen. After the
        // bug is fixed, this should be reverted to only hiding it on secure lock screens:
        // state.visible = !(mKeyguard.isSecure() && mKeyguard.isShowing());
        checkIfRestrictionEnforcedByAdminOnly(state, UserManager.DISALLOW_SHARE_LOCATION);
        if (state.disabledByPolicy == false) {
            checkIfRestrictionEnforcedByAdminOnly(state, UserManager.DISALLOW_CONFIG_LOCATION);
        }
        int currentMode = mController.getCurrentMode();
        switch (currentMode) {
            case BATTERY_SAVING:
                state.value = true;
                state.contentDescription = mContext.getString(R.string.accessibility_quick_settings_location_battery_saving);
                state.label = mContext.getString(R.string.quick_settings_location_label);
                state.secondaryLabel = mContext.getString(R.string.quick_settings_location_secondary_battery_saving);
                state.icon = ResourceIcon.get(R.drawable.ic_qs_location_battery_saving);
                state.state = Tile.STATE_ACTIVE;
                break;
            case SENSORS_ONLY:
                state.value = true;
                state.contentDescription = mContext.getString(R.string.accessibility_quick_settings_location_gps_only);
                state.label = mContext.getString(R.string.quick_settings_location_label);
                state.secondaryLabel = mContext.getString(R.string.quick_settings_location_secondary_gps_only);
                state.icon = mIcon;
                state.state = Tile.STATE_ACTIVE;
                break;
            case HIGH_ACCURACY:
                state.value = true;
                state.contentDescription = mContext.getString(R.string.accessibility_quick_settings_location_high_accuracy);
                state.label = mContext.getString(R.string.quick_settings_location_label);
                state.secondaryLabel = mContext.getString(R.string.quick_settings_location_secondary_high_accuracy);
                state.icon = ResourceIcon.get(R.drawable.ic_qs_location_high_accuracy);
                state.state = Tile.STATE_ACTIVE;
                break;
            case OFF:
                state.value = false;
                state.contentDescription = mContext.getString(R.string.accessibility_quick_settings_location_off);
                state.label = mContext.getString(R.string.quick_settings_location_label);
                state.secondaryLabel = mContext.getString(R.string.quick_settings_secondary_location_off);
                state.icon = mIcon;
                state.state = Tile.STATE_INACTIVE;
                break;
        }
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_LOCATION;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_location_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_location_changed_off);
        }
    }

    private final class Callback implements LocationChangeCallback,
            KeyguardMonitor.Callback {
        @Override
        public void onLocationSettingsChanged(boolean enabled) {
            refreshState();
        }

        @Override
        public void onKeyguardShowingChanged() {
            refreshState();
        }
    };
}
