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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.service.quicksettings.Tile;
import android.view.View;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.R.drawable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.SettingObserver;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

/** Quick settings tile: Invert colors **/
public class ColorInversionTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "inversion";
    private final SettingObserver mSetting;

    @Inject
    public ColorInversionTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            UserTracker userTracker,
            SecureSettings secureSettings
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mSetting = new SettingObserver(secureSettings, mHandler,
                Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, userTracker.getUserId()) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                // mHandler is the background handler so calling this is OK
                handleRefreshState(value);
            }
        };
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mSetting.setListening(false);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        mSetting.setListening(listening);
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        mSetting.setUserId(newUserId);
        handleRefreshState(mSetting.getValue());
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_COLOR_INVERSION_SETTINGS);
    }

    @Override
    protected void handleClick(@Nullable View view) {
        mSetting.setValue(mState.value ? 0 : 1);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_inversion_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer) arg : mSetting.getValue();
        final boolean enabled = value != 0;
        state.value = enabled;
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.label = mContext.getString(R.string.quick_settings_inversion_label);
        state.icon = ResourceIcon.get(state.value
                ? drawable.qs_invert_colors_icon_on
                : drawable.qs_invert_colors_icon_off);
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.contentDescription = state.label;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_COLORINVERSION;
    }
}
