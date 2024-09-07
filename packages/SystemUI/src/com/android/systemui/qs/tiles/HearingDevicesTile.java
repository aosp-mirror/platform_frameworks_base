/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.os.UserManager;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Flags;
import com.android.systemui.accessibility.hearingaid.HearingDevicesChecker;
import com.android.systemui.accessibility.hearingaid.HearingDevicesDialogManager;
import com.android.systemui.animation.Expandable;
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
import com.android.systemui.statusbar.policy.BluetoothController;

import javax.inject.Inject;

/** Quick settings tile: Hearing Devices **/
public class HearingDevicesTile extends QSTileImpl<BooleanState> {
    //TODO(b/338520598): Transform the current implementation into new QS architecture
    // and use Kotlin except Tile class.
    public static final String TILE_SPEC = "hearing_devices";

    private final HearingDevicesDialogManager mDialogManager;
    private final HearingDevicesChecker mDevicesChecker;
    private final BluetoothController mBluetoothController;

    private final BluetoothController.Callback mCallback = new BluetoothController.Callback() {
        @Override
        public void onBluetoothStateChange(boolean enabled) {
            refreshState();
        }

        @Override
        public void onBluetoothDevicesChanged() {
            refreshState();
        }
    };

    @Inject
    public HearingDevicesTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            HearingDevicesDialogManager hearingDevicesDialogManager,
            HearingDevicesChecker hearingDevicesChecker,
            BluetoothController bluetoothController) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mDialogManager = hearingDevicesDialogManager;
        mDevicesChecker = hearingDevicesChecker;
        mBluetoothController = bluetoothController;
        mBluetoothController.observe(getLifecycle(), mCallback);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        mUiHandler.post(() -> mDialogManager.showDialog(expandable));
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        checkIfRestrictionEnforcedByAdminOnly(state, UserManager.DISALLOW_BLUETOOTH);

        state.label = mContext.getString(R.string.quick_settings_hearing_devices_label);
        state.icon = ResourceIcon.get(R.drawable.qs_hearing_devices_icon);
        state.forceExpandIcon = true;

        boolean isBonded = mDevicesChecker.isAnyPairedHearingDevice();
        boolean isActive = mDevicesChecker.isAnyActiveHearingDevice();

        if (isActive) {
            state.state = Tile.STATE_ACTIVE;
            state.secondaryLabel = mContext.getString(
                    R.string.quick_settings_hearing_devices_connected);
        } else if (isBonded) {
            state.state = Tile.STATE_INACTIVE;
            state.secondaryLabel = mContext.getString(
                    R.string.quick_settings_hearing_devices_disconnected);
        } else {
            state.state = Tile.STATE_INACTIVE;
            state.secondaryLabel = "";
        }
    }

    @Nullable
    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_HEARING_DEVICES_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_hearing_devices_label);
    }

    @Override
    public boolean isAvailable() {
        return Flags.hearingAidsQsTileDialog();
    }
}
