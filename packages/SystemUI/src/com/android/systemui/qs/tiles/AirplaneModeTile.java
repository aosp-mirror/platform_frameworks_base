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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.quicksettings.Tile;
import android.sysprop.TelephonyProperties;
import android.telephony.TelephonyManager;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.GlobalSetting;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

/** Quick settings tile: Airplane mode **/
public class AirplaneModeTile extends QSTileImpl<BooleanState> {
    private final Icon mIcon = ResourceIcon.get(com.android.internal.R.drawable.ic_qs_airplane);
    private final GlobalSetting mSetting;
    private final ActivityStarter mActivityStarter;
    private final BroadcastDispatcher mBroadcastDispatcher;

    private final KeyguardStateController mKeyguard;

    private boolean mListening;

    @Inject
    public AirplaneModeTile(QSHost host, ActivityStarter activityStarter,
            BroadcastDispatcher broadcastDispatcher,
            KeyguardStateController keyguardStateController) {
        super(host);
        mActivityStarter = activityStarter;
        mBroadcastDispatcher = broadcastDispatcher;

        mSetting = new GlobalSetting(mContext, mHandler, Global.AIRPLANE_MODE_ON) {
            @Override
            protected void handleValueChanged(int value) {
                handleRefreshState(value);
            }
        };

        mKeyguard = keyguardStateController;
        final KeyguardStateController.Callback callback = new KeyguardStateController.Callback() {
            @Override
            public void onKeyguardShowingChanged() {
                refreshState();
            }
        };
        mKeyguard.observe(this, callback);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    private void handleClickInner() {
        boolean airplaneModeEnabled = mState.value;
        MetricsLogger.action(mContext, getMetricsCategory(), !airplaneModeEnabled);
        if (!airplaneModeEnabled && TelephonyProperties.in_ecm_mode().orElse(false)) {
            mActivityStarter.postStartActivityDismissingKeyguard(
                    new Intent(TelephonyManager.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS), 0);
            return;
        }
        setEnabled(!airplaneModeEnabled);
    }

    @Override
    public void handleClick() {
        if (mKeyguard.isMethodSecure() && mKeyguard.isShowing()) {
            mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                mHost.openPanels();
                handleClickInner();
            });
            return;
        }
        handleClickInner();
    }

    private void setEnabled(boolean enabled) {
        final ConnectivityManager mgr =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mgr.setAirplaneMode(enabled);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.airplane_mode);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        checkIfRestrictionEnforcedByAdminOnly(state, UserManager.DISALLOW_AIRPLANE_MODE);
        final int value = arg instanceof Integer ? (Integer)arg : mSetting.getValue();
        final boolean airplaneMode = value != 0;
        state.value = airplaneMode;
        state.label = mContext.getString(R.string.airplane_mode);
        state.icon = mIcon;
        if (state.slash == null) {
            state.slash = new SlashState();
        }
        state.slash.isSlashed = !airplaneMode;
        state.state = airplaneMode ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.contentDescription = state.label;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_AIRPLANEMODE;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_airplane_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_airplane_changed_off);
        }
    }

    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            mBroadcastDispatcher.registerReceiver(mReceiver, filter);
        } else {
            mBroadcastDispatcher.unregisterReceiver(mReceiver);
        }
        mSetting.setListening(listening);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction())) {
                refreshState();
            }
        }
    };
}
