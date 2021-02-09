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

import static com.android.systemui.qs.dagger.QSFlagsModule.RBC_AVAILABLE;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;
import javax.inject.Named;

/** Quick settings tile: Reduce Bright Colors **/
public class ReduceBrightColorsTile extends QSTileImpl<QSTile.BooleanState> {

    //TODO(b/170973645): get icon drawable
    private final Icon mIcon = null;
    private final SecureSetting mActivatedSetting;
    private final boolean mIsAvailable;

    @Inject
    public ReduceBrightColorsTile(
            @Named(RBC_AVAILABLE) boolean isAvailable,
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            UserTracker userTracker,
            SecureSettings secureSettings
    ) {
        super(host, backgroundLooper, mainHandler, metricsLogger, statusBarStateController,
                activityStarter, qsLogger);

        mActivatedSetting = new SecureSetting(secureSettings, mainHandler,
                Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED, userTracker.getUserId()) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                refreshState();
            }
        };
        mIsAvailable = isAvailable;

    }
    @Override
    public boolean isAvailable() {
        return mIsAvailable;
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mActivatedSetting.setListening(false);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        mActivatedSetting.setListening(listening);
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        mActivatedSetting.setUserId(newUserId);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_REDUCE_BRIGHT_COLORS_SETTINGS);
    }

    @Override
    protected void handleClick() {
        mActivatedSetting.setValue(mState.value ? 0 : 1);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_reduce_bright_colors_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = mActivatedSetting.getValue() == 1;
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.label = mContext.getString(R.string.quick_settings_reduce_bright_colors_label);
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.contentDescription = state.label;
    }

    @Override
    public int getMetricsCategory() {
        return 0;
    }
}
