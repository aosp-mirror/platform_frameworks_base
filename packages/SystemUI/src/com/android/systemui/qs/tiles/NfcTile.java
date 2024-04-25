/*
 * Copyright (c) 2016, The Android Open Source Project
 * Contributed by the Paranoid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.view.View;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import javax.inject.Inject;

/** Quick settings tile: Enable/Disable NFC **/
public class NfcTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "nfc";

    private static final String NFC = TILE_SPEC;
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_nfc);

    @Nullable
    private NfcAdapter mAdapter;
    private BroadcastDispatcher mBroadcastDispatcher;

    private boolean mListening;

    @Inject
    public NfcTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BroadcastDispatcher broadcastDispatcher
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mBroadcastDispatcher = broadcastDispatcher;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        mListening = listening;
        if (mListening) {
            mBroadcastDispatcher.registerReceiver(mNfcReceiver,
                    new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED));
        } else {
            mBroadcastDispatcher.unregisterReceiver(mNfcReceiver);
        }
    }

    @Override
    public boolean isAvailable() {
        String stockTiles = mContext.getString(R.string.quick_settings_tiles_stock);
        // For the restore from backup case
        // Return false when "nfc" is not listed in quick_settings_tiles_stock.
        if (stockTiles.contains(NFC)) {
            return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC);
        }
        return false;
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_NFC_SETTINGS);
    }

    @Override
    protected void handleClick(@Nullable View view) {
        if (getAdapter() == null) {
            return;
        }
        if (!getAdapter().isEnabled()) {
            getAdapter().enable();
        } else {
            getAdapter().disable();
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_nfc_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = getAdapter() != null && getAdapter().isEnabled();
        state.state = getAdapter() == null
                ? Tile.STATE_UNAVAILABLE
                : state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.icon = mIcon;
        state.label = mContext.getString(R.string.quick_settings_nfc_label);
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.contentDescription = state.label;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_NFC;
    }

    private NfcAdapter getAdapter() {
        if (mAdapter == null) {
            try {
                mAdapter = NfcAdapter.getDefaultAdapter(mContext);
            } catch (UnsupportedOperationException e) {
                mAdapter = null;
            }
        }
        return mAdapter;
    }

    private BroadcastReceiver mNfcReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState();
        }
    };
}
