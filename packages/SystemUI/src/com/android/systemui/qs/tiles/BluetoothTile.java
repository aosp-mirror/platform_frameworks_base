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

import android.bluetooth.BluetoothAdapter.BluetoothStateChangeCallback;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.BluetoothController;

/** Quick settings tile: Bluetooth **/
public class BluetoothTile extends QSTile<QSTile.BooleanState>  {
    private static final Intent BLUETOOTH_SETTINGS = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);

    private final BluetoothController mController;

    public BluetoothTile(Host host) {
        super(host);
        mController = host.getBluetoothController();
    }

    @Override
    public boolean supportsDualTargets() {
        return true;
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addStateChangedCallback(mCallback);
        } else {
            mController.removeStateChangedCallback(mCallback);
        }
    }

    @Override
    protected void handleClick() {
        final boolean isEnabled = (Boolean)mState.value;
        mController.setBluetoothEnabled(!isEnabled);
    }

    @Override
    protected void handleSecondaryClick() {
        mHost.startSettingsActivity(BLUETOOTH_SETTINGS);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean supported = mController.isBluetoothSupported();
        final boolean enabled = mController.isBluetoothEnabled();
        final boolean connected = mController.isBluetoothConnected();
        final boolean connecting = mController.isBluetoothConnecting();
        state.visible = supported;
        state.value = enabled;
        final String stateContentDescription;
        if (enabled) {
            state.label = null;
            if (connected) {
                state.iconId = R.drawable.ic_qs_bluetooth_connected;
                stateContentDescription = mContext.getString(R.string.accessibility_desc_connected);
                state.label = mController.getLastDeviceName();
            } else if (connecting) {
                state.iconId = R.drawable.ic_qs_bluetooth_connecting;
                stateContentDescription = mContext.getString(R.string.accessibility_desc_connecting);
                state.label = mContext.getString(R.string.quick_settings_bluetooth_label);
            } else {
                state.iconId = R.drawable.ic_qs_bluetooth_on;
                stateContentDescription = mContext.getString(R.string.accessibility_desc_on);
            }
            if (TextUtils.isEmpty(state.label)) {
                state.label = mContext.getString(R.string.quick_settings_bluetooth_label);
            }
        } else {
            state.iconId = R.drawable.ic_qs_bluetooth_off;
            state.label = mContext.getString(R.string.quick_settings_bluetooth_label);
            stateContentDescription = mContext.getString(R.string.accessibility_desc_off);
        }
        state.contentDescription = mContext.getString(
                R.string.accessibility_quick_settings_bluetooth, stateContentDescription);
    }

    private final BluetoothController.Callback mCallback = new BluetoothController.Callback() {
        @Override
        public void onBluetoothStateChange(boolean enabled, boolean connecting) {
            refreshState();
        }
    };
}
