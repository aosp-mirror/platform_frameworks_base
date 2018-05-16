/*
 * Copyright (C) 2018 The LineageOS Project
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
import android.service.quicksettings.Tile;

import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import com.android.internal.custom.hardware.LineageHardwareManager;

import javax.inject.Inject;

public class ReadingModeTile extends QSTileImpl<BooleanState> {

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_reader);

    private static final Intent LIVEDISPLAY_SETTINGS =
            new Intent("com.android.settings.LIVEDISPLAY_SETTINGS");

    private LineageHardwareManager mHardware;

    @Inject
    public ReadingModeTile(QSHost host) {
        super(host);
        mHardware = LineageHardwareManager.getInstance(mContext);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        boolean newStatus = !isReadingModeEnabled();
        mHardware.set(LineageHardwareManager.FEATURE_READING_ENHANCEMENT, newStatus);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return LIVEDISPLAY_SETTINGS;
    }

    @Override
    public boolean isAvailable() {
        return mHardware.isSupported(LineageHardwareManager.FEATURE_READING_ENHANCEMENT);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = isReadingModeEnabled();
        state.icon = mIcon;
        if (state.value) {
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_reading_mode_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_reading_mode_off);
            state.state = Tile.STATE_INACTIVE;
        }
        state.label = getTileLabel();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_reading_mode);
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(
                    R.string.accessibility_quick_settings_reading_mode_changed_on);
        } else {
            return mContext.getString(
                    R.string.accessibility_quick_settings_reading_mode_changed_off);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CUSTOM_SETTINGS;
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    private boolean isReadingModeEnabled() {
        return mHardware.get(LineageHardwareManager.FEATURE_READING_ENHANCEMENT);
    }
}
