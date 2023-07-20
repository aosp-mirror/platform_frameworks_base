/*
 * Copyright (C) 2018 The OmniROM Project
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
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.xtended.XtendedUtils;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

public class AODTile extends QSTileImpl<State> {
    private boolean mListening;
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_aod);

    @Inject
    public AODTile(QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BroadcastDispatcher broadcastDispatcher,
            KeyguardStateController keyguardStateController
        ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
    }

    private int getAodState() {
        int aodState = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON, 0, UserHandle.USER_CURRENT);
        if (aodState == 0) {
            aodState = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.DOZE_ON_CHARGE, 0, UserHandle.USER_CURRENT) == 1 ? 2 : 0;
        }
        return aodState;
    }

    @Override
    public boolean isAvailable() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dozeAlwaysOnDisplayAvailable);
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    public void handleClick(@Nullable View view) {
        int aodState = getAodState();
        if (aodState < 2) {
            aodState++;
        } else {
            aodState = 0;
        }
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON, aodState == 2 ? 0 : aodState,
                UserHandle.USER_CURRENT);
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.DOZE_ON_CHARGE, aodState == 2 ? 1 : 0, UserHandle.USER_CURRENT);
        refreshState();
    }

    @Override
    protected void handleLongClick(@Nullable View view) {
        // always toggle on/off on long click
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON, getAodState() != 0 ? 0 : 1,
                UserHandle.USER_CURRENT);
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.DOZE_ON_CHARGE, 0, UserHandle.USER_CURRENT);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        switch (getAodState()) {
            case 1:
                return mContext.getString(R.string.quick_settings_aod_label);
            case 2:
                return mContext.getString(R.string.quick_settings_aod_on_charge_label);
            default:
                return mContext.getString(R.string.quick_settings_aod_off_label);
        }
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.icon = mIcon;
        state.label = getTileLabel();
        state.state = getAodState() == 0 ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.XTENDED;
    }

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refreshState();
        }
    };

    @Override
    public void handleSetListening(boolean listening) {
        if (mObserver == null) {
            return;
        }
        if (mListening != listening) {
            mListening = listening;
            if (listening) {
                mContext.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.DOZE_ALWAYS_ON), false, mObserver);
            } else {
                mContext.getContentResolver().unregisterContentObserver(mObserver);
            }
        }
    }
}
