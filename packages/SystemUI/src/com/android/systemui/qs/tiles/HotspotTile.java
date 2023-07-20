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

import static com.android.systemui.util.PluralMessageFormaterKt.icuMessageFormat;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.View;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.HotspotController;

import javax.inject.Inject;

/** Quick settings tile: Hotspot **/
public class HotspotTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "hotspot";
    private final HotspotController mHotspotController;
    private final DataSaverController mDataSaverController;

    private final HotspotAndDataSaverCallbacks mCallbacks = new HotspotAndDataSaverCallbacks();
    private boolean mListening;

    @Inject
    public HotspotTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            HotspotController hotspotController,
            DataSaverController dataSaverController
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mHotspotController = hotspotController;
        mDataSaverController = dataSaverController;
        mHotspotController.observe(this, mCallbacks);
        mDataSaverController.observe(this, mCallbacks);
    }

    @Override
    public boolean isAvailable() {
        return mHotspotController.isHotspotSupported();
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            refreshState();
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_WIFI_TETHER_SETTING);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable View view) {
        final boolean isEnabled = mState.value;
        if (!isEnabled && mDataSaverController.isDataSaverEnabled()) {
            return;
        }
        // Immediately enter transient enabling state when turning hotspot on.
        refreshState(isEnabled ? null : ARG_SHOW_TRANSIENT_ENABLING);
        mHotspotController.setHotspotEnabled(!isEnabled);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_hotspot_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean transientEnabling = arg == ARG_SHOW_TRANSIENT_ENABLING;

        final int numConnectedDevices;
        final boolean isTransient = transientEnabling || mHotspotController.isHotspotTransient();
        final boolean isDataSaverEnabled;

        checkIfRestrictionEnforcedByAdminOnly(state, UserManager.DISALLOW_CONFIG_TETHERING);

        if (arg instanceof CallbackInfo) {
            final CallbackInfo info = (CallbackInfo) arg;
            state.value = transientEnabling || info.isHotspotEnabled;
            numConnectedDevices = info.numConnectedDevices;
            isDataSaverEnabled = info.isDataSaverEnabled;
        } else {
            state.value = transientEnabling || mHotspotController.isHotspotEnabled();
            numConnectedDevices = mHotspotController.getNumConnectedDevices();
            isDataSaverEnabled = mDataSaverController.isDataSaverEnabled();
        }

        state.label = mContext.getString(R.string.quick_settings_hotspot_label);
        state.isTransient = isTransient;
        if (state.isTransient) {
            state.icon = ResourceIcon.get(
                    R.drawable.qs_hotspot_icon_search);
        } else {
            state.icon = ResourceIcon.get(state.value
                    ? R.drawable.qs_hotspot_icon_on : R.drawable.qs_hotspot_icon_off);
        }
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.contentDescription = state.label;

        final boolean isWifiTetheringAllowed =
                WifiEnterpriseRestrictionUtils.isWifiTetheringAllowed(mHost.getUserContext());
        final boolean isTileUnavailable = isDataSaverEnabled || !isWifiTetheringAllowed;
        final boolean isTileActive = (state.value || state.isTransient);

        if (isTileUnavailable) {
            state.state = Tile.STATE_UNAVAILABLE;
        } else {
            state.state = isTileActive ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        }

        state.secondaryLabel = getSecondaryLabel(isTileActive, isTransient, isDataSaverEnabled,
                numConnectedDevices, isWifiTetheringAllowed);
        state.stateDescription = state.secondaryLabel;
    }

    @Nullable
    private String getSecondaryLabel(boolean isActive, boolean isTransient,
            boolean isDataSaverEnabled, int numConnectedDevices, boolean isWifiTetheringAllowed) {
        if (!isWifiTetheringAllowed) {
            return mContext.getString(R.string.wifitrackerlib_admin_restricted_network);
        } else if (isTransient) {
            return mContext.getString(R.string.quick_settings_hotspot_secondary_label_transient);
        } else if (isDataSaverEnabled) {
            return mContext.getString(
                    R.string.quick_settings_hotspot_secondary_label_data_saver_enabled);
        } else if (numConnectedDevices > 0 && isActive) {
            return icuMessageFormat(mContext.getResources(),
                    R.string.quick_settings_hotspot_secondary_label_num_devices,
                    numConnectedDevices);
        }

        return null;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_HOTSPOT;
    }

    /**
     * Listens to changes made to hotspot and data saver states (to toggle tile availability).
     */
    private final class HotspotAndDataSaverCallbacks implements HotspotController.Callback,
            DataSaverController.Listener {
        CallbackInfo mCallbackInfo = new CallbackInfo();

        @Override
        public void onDataSaverChanged(boolean isDataSaving) {
            mCallbackInfo.isDataSaverEnabled = isDataSaving;
            refreshState(mCallbackInfo);
        }

        @Override
        public void onHotspotChanged(boolean enabled, int numDevices) {
            mCallbackInfo.isHotspotEnabled = enabled;
            mCallbackInfo.numConnectedDevices = numDevices;
            refreshState(mCallbackInfo);
        }

        @Override
        public void onHotspotAvailabilityChanged(boolean available) {
            if (!available) {
                Log.d(TAG, "Tile removed. Hotspot no longer available");
                mHost.removeTile(getTileSpec());
            }
        }
    }

    /**
     * Holder for any hotspot state info that needs to passed from the callback to
     * {@link #handleUpdateState(State, Object)}.
     */
    protected static final class CallbackInfo {
        boolean isHotspotEnabled;
        int numConnectedDevices;
        boolean isDataSaverEnabled;

        @Override
        public String toString() {
            return new StringBuilder("CallbackInfo[")
                    .append("isHotspotEnabled=").append(isHotspotEnabled)
                    .append(",numConnectedDevices=").append(numConnectedDevices)
                    .append(",isDataSaverEnabled=").append(isDataSaverEnabled)
                    .append(']').toString();
        }
    }
}
