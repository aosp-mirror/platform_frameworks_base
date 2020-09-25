/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.app.UiModeManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.widget.Switch;

import com.android.internal.logging.nano.MetricsProto;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.LocationController;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.inject.Inject;

/**
 * Quick Settings tile for: Night Mode / Dark Theme / Dark Mode.
 *
 * The string id of this tile is "dark" because "night" was already
 * taken by {@link NightDisplayTile}.
 */
public class UiModeNightTile extends QSTileImpl<QSTile.BooleanState> implements
        ConfigurationController.ConfigurationListener,
        BatteryController.BatteryStateChangeCallback {
    public static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm a");
    private final Icon mIcon = ResourceIcon.get(
            com.android.internal.R.drawable.ic_qs_ui_mode_night);
    private UiModeManager mUiModeManager;
    private final BatteryController mBatteryController;
    private final LocationController mLocationController;
    @Inject
    public UiModeNightTile(QSHost host, ConfigurationController configurationController,
            BatteryController batteryController, LocationController locationController) {
        super(host);
        mBatteryController = batteryController;
        mUiModeManager = host.getUserContext().getSystemService(UiModeManager.class);
        mLocationController = locationController;
        configurationController.observe(getLifecycle(), this);
        batteryController.observe(getLifecycle(), this);
    }

    @Override
    public void onUiModeChanged() {
        refreshState();
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        refreshState();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        if (getState().state == Tile.STATE_UNAVAILABLE) {
            return;
        }
        boolean newState = !mState.value;
        mUiModeManager.setNightModeActivated(newState);
        refreshState(newState);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        int uiMode = mUiModeManager.getNightMode();
        boolean powerSave = mBatteryController.isPowerSave();
        boolean nightMode = (mContext.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        if (powerSave) {
            state.secondaryLabel = mContext.getResources().getString(
                    R.string.quick_settings_dark_mode_secondary_label_battery_saver);
        } else if (uiMode == UiModeManager.MODE_NIGHT_AUTO
                && mLocationController.isLocationEnabled()) {
            state.secondaryLabel = mContext.getResources().getString(nightMode
                    ? R.string.quick_settings_dark_mode_secondary_label_until_sunrise
                    : R.string.quick_settings_dark_mode_secondary_label_on_at_sunset);
        } else if (uiMode == UiModeManager.MODE_NIGHT_CUSTOM) {
            final boolean use24HourFormat = android.text.format.DateFormat.is24HourFormat(mContext);
            final LocalTime time;
            if (nightMode) {
                time = mUiModeManager.getCustomNightModeEnd();
            } else {
                time = mUiModeManager.getCustomNightModeStart();
            }
            state.secondaryLabel = mContext.getResources().getString(nightMode
                    ? R.string.quick_settings_dark_mode_secondary_label_until
                    : R.string.quick_settings_dark_mode_secondary_label_on_at,
                    use24HourFormat ? time.toString() : formatter.format(time));
        } else {
            state.secondaryLabel = null;
        }
        state.value = nightMode;
        state.label = mContext.getString(R.string.quick_settings_ui_mode_night_label);
        state.icon = mIcon;
        state.contentDescription = TextUtils.isEmpty(state.secondaryLabel)
                ? state.label
                : TextUtils.concat(state.label, ", ", state.secondaryLabel);
        if (powerSave) {
            state.state = Tile.STATE_UNAVAILABLE;
        } else {
            state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        }
        state.showRippleEffect = false;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.QS_UI_MODE_NIGHT;
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_DARK_THEME_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return getState().label;
    }
}
