/*
 * Copyright (C) 2019 Benzo Rom
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

import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.service.quicksettings.Tile;

import com.android.systemui.R;
import com.android.systemui.R.drawable;
import com.android.systemui.xtended.onthego.OnTheGoService;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.logging.QSLogger;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.xtended.OnTheGoUtils;

import javax.inject.Inject;

/** Quick settings tile: Enable/Disable OnTheGo Mode **/
public class OnTheGoTile extends QSTileImpl<BooleanState> {

    private final Icon mIcon = ResourceIcon.get(drawable.ic_qs_onthego);
    private boolean mIsEnabled;

    @Inject
    public OnTheGoTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mIsEnabled = isOnTheGoEnabled();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        // nothing
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    protected void handleClick(@Nullable View view) {
        toggleService();
    }

    protected void toggleService() {
        ComponentName cn = new ComponentName("com.android.systemui",
                "com.android.systemui.xtended.onthego.OnTheGoService");
        Intent startIntent = new Intent();
        startIntent.setComponent(cn);
        if (isOnTheGoEnabled()) {
            startIntent.setAction("stop");
            mIsEnabled = false;
        } else {
            startIntent.setAction("start");
            mIsEnabled = true;
        }
        mContext.startService(startIntent);
        refreshState();
    }

    protected boolean isOnTheGoEnabled() {
        String service = OnTheGoService.class.getName();
        return OnTheGoUtils.isServiceRunning(mContext, service);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_onthego_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = mIsEnabled;
        state.label = mContext.getString(R.string.quick_settings_onthego_label);
        state.icon = mIcon;
        if (state.value) {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_onthego_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_onthego_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.XTENDED;
    }
}
