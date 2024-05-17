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

import static com.android.settingslib.satellite.SatelliteDialogUtils.TYPE_IS_AIRPLANE_MODE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.quicksettings.Tile;
import android.sysprop.TelephonyProperties;
import android.telephony.TelephonyManager;
import android.widget.Switch;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.telephony.flags.Flags;
import com.android.settingslib.satellite.SatelliteDialogUtils;
import com.android.systemui.animation.Expandable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.SettingObserver;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.GlobalSettings;

import dagger.Lazy;

import kotlinx.coroutines.Job;

import javax.inject.Inject;

/** Quick settings tile: Airplane mode **/
public class AirplaneModeTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "airplane";

    private final SettingObserver mSetting;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Lazy<ConnectivityManager> mLazyConnectivityManager;

    private boolean mListening;
    @Nullable
    @VisibleForTesting
    Job mClickJob;

    @Inject
    public AirplaneModeTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BroadcastDispatcher broadcastDispatcher,
            Lazy<ConnectivityManager> lazyConnectivityManager,
            GlobalSettings globalSettings,
            UserTracker userTracker
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mBroadcastDispatcher = broadcastDispatcher;
        mLazyConnectivityManager = lazyConnectivityManager;

        mSetting = new SettingObserver(globalSettings, mHandler, Global.AIRPLANE_MODE_ON) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                // mHandler is the background handler so calling this is OK
                handleRefreshState(value);
            }
        };
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick(@Nullable Expandable expandable) {
        boolean airplaneModeEnabled = mState.value;
        MetricsLogger.action(mContext, getMetricsCategory(), !airplaneModeEnabled);
        if (!airplaneModeEnabled && TelephonyProperties.in_ecm_mode().orElse(false)) {
            mActivityStarter.postStartActivityDismissingKeyguard(
                    new Intent(TelephonyManager.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS), 0);
            return;
        }

        if (Flags.oemEnabledSatelliteFlag()) {
            if (mClickJob != null && !mClickJob.isCompleted()) {
                return;
            }
            mClickJob = SatelliteDialogUtils.mayStartSatelliteWarningDialog(
                    mContext, this, TYPE_IS_AIRPLANE_MODE, isAllowClick -> {
                        if (isAllowClick) {
                            setEnabled(!airplaneModeEnabled);
                        }
                        return null;
                    });
            return;
        }

        setEnabled(!airplaneModeEnabled);
    }

    private void setEnabled(boolean enabled) {
        mLazyConnectivityManager.get().setAirplaneMode(enabled);
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
        state.icon = ResourceIcon.get(state.value
                ? R.drawable.qs_airplane_icon_on : R.drawable.qs_airplane_icon_off);
        state.state = airplaneMode ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.contentDescription = state.label;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_AIRPLANEMODE;
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
