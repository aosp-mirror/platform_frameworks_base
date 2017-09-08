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
import android.graphics.drawable.Drawable;
import android.nfc.NfcAdapter;
import android.provider.Settings;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.QSHost;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.tileimpl.QSTileImpl;

/** Quick settings tile: Enable/Disable NFC **/
public class NfcTile extends QSTileImpl<BooleanState> {

    private NfcAdapter mAdapter;

    private boolean mListening;

    public NfcTile(QSHost host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        mListening = listening;
        if (mListening) {
            mContext.registerReceiver(mNfcReceiver,
                    new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED));
        } else {
            mContext.unregisterReceiver(mNfcReceiver);
        }
    }

    @Override
    public boolean isAvailable() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC);
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_NFC_SETTINGS);
    }

    @Override
    protected void handleClick() {
        if (!getAdapter().isEnabled()) {
            getAdapter().enable();
        } else {
            getAdapter().disable();
        }
    }

    @Override
    protected void handleSecondaryClick() {
        handleClick();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_nfc_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final Drawable mEnable = mContext.getDrawable(R.drawable.ic_qs_nfc_enabled);
        final Drawable mDisable = mContext.getDrawable(R.drawable.ic_qs_nfc_disabled);
        state.value = getAdapter().isEnabled();
        state.label = mContext.getString(R.string.quick_settings_nfc_label);
        state.icon = new DrawableIcon(state.value ? mEnable : mDisable);
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.contentDescription = state.label;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_NFC;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.quick_settings_nfc_on);
        } else {
            return mContext.getString(R.string.quick_settings_nfc_off);
        }
    }

    private NfcAdapter getAdapter() {
        if (mAdapter == null) {
            try {
                mAdapter = NfcAdapter.getNfcAdapter(mContext);
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
