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

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.settingslib.bluetooth.BluetoothUtils;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothTileDialogViewModel;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.BluetoothController;

import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** Quick settings tile: Bluetooth **/
public class BluetoothTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "bt";

    private static final Intent BLUETOOTH_SETTINGS = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);

    private static final String TAG = BluetoothTile.class.getSimpleName();

    private final BluetoothController mController;

    private CachedBluetoothDevice mMetadataRegisteredDevice = null;

    private final Executor mExecutor;

    private final BluetoothTileDialogViewModel mDialogViewModel;

    private final FeatureFlags mFeatureFlags;

    @Inject
    public BluetoothTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BluetoothController bluetoothController,
            FeatureFlags featureFlags,
            BluetoothTileDialogViewModel dialogViewModel
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mController = bluetoothController;
        mController.observe(getLifecycle(), mCallback);
        mExecutor = new HandlerExecutor(mainHandler);
        mFeatureFlags = featureFlags;
        mDialogViewModel = dialogViewModel;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable View view) {
        if (mFeatureFlags.isEnabled(Flags.BLUETOOTH_QS_TILE_DIALOG)) {
            mDialogViewModel.showDialog(view);
        } else {
            // Secondary clicks are header clicks, just toggle.
            final boolean isEnabled = mState.value;
            // Immediately enter transient enabling state when turning bluetooth on.
            refreshState(isEnabled ? null : ARG_SHOW_TRANSIENT_ENABLING);
            mController.setBluetoothEnabled(!isEnabled);
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    @Override
    protected void handleSecondaryClick(@Nullable View view) {
        if (!mController.canConfigBluetooth()) {
            mActivityStarter.postStartActivityDismissingKeyguard(
                    new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), 0);
            return;
        }
        if (!mState.value) {
            mController.setBluetoothEnabled(true);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_bluetooth_label);
    }

    @Override
    protected void handleSetListening(boolean listening) {
        super.handleSetListening(listening);

        if (!listening) {
            stopListeningToStaleDeviceMetadata();
        }
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        checkIfRestrictionEnforcedByAdminOnly(state, UserManager.DISALLOW_BLUETOOTH);
        final boolean transientEnabling = arg == ARG_SHOW_TRANSIENT_ENABLING;
        final boolean enabled = transientEnabling || mController.isBluetoothEnabled();
        final boolean connected = mController.isBluetoothConnected();
        final boolean connecting = mController.isBluetoothConnecting();
        state.isTransient = transientEnabling || connecting ||
                mController.getBluetoothState() == BluetoothAdapter.STATE_TURNING_ON;
        if (!enabled || !connected || state.isTransient) {
            stopListeningToStaleDeviceMetadata();
        }
        state.dualTarget = true;
        state.value = enabled;
        state.label = mContext.getString(R.string.quick_settings_bluetooth_label);
        state.secondaryLabel = TextUtils.emptyIfNull(
                getSecondaryLabel(enabled, connecting, connected, state.isTransient));
        state.contentDescription = mContext.getString(
                R.string.accessibility_quick_settings_bluetooth);
        state.stateDescription = "";

        if (enabled) {
            if (connected) {
                state.icon = ResourceIcon.get(R.drawable.qs_bluetooth_icon_on);
                if (!TextUtils.isEmpty(mController.getConnectedDeviceName())) {
                    state.label = mController.getConnectedDeviceName();
                }
                state.stateDescription =
                        mContext.getString(R.string.accessibility_bluetooth_name, state.label)
                                + ", " + state.secondaryLabel;
            } else if (state.isTransient) {
                state.icon = ResourceIcon.get(
                        R.drawable.qs_bluetooth_icon_search);
                state.stateDescription = state.secondaryLabel;
            } else {
                state.icon =
                        ResourceIcon.get(R.drawable.qs_bluetooth_icon_off);
                state.stateDescription = mContext.getString(R.string.accessibility_not_connected);
            }
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.icon = ResourceIcon.get(R.drawable.qs_bluetooth_icon_off);
            state.state = Tile.STATE_INACTIVE;
        }

        state.expandedAccessibilityClassName = Switch.class.getName();
        state.forceExpandIcon = mFeatureFlags.isEnabled(Flags.BLUETOOTH_QS_TILE_DIALOG);
    }

    /**
     * Returns the secondary label to use for the given bluetooth connection in the form of the
     * battery level or bluetooth profile name. If the bluetooth is disabled, there's no connected
     * devices, or we can't map the bluetooth class to a profile, this instead returns {@code null}.
     * @param enabled whether bluetooth is enabled
     * @param connecting whether bluetooth is connecting to a device
     * @param connected whether there's a device connected via bluetooth
     * @param isTransient whether bluetooth is currently in a transient state turning on
     */
    @Nullable
    private String getSecondaryLabel(boolean enabled, boolean connecting, boolean connected,
            boolean isTransient) {
        if (connecting) {
            return mContext.getString(R.string.quick_settings_connecting);
        }
        if (isTransient) {
            return mContext.getString(R.string.quick_settings_bluetooth_secondary_label_transient);
        }

        List<CachedBluetoothDevice> connectedDevices = mController.getConnectedDevices();
        if (enabled && connected && !connectedDevices.isEmpty()) {
            if (connectedDevices.size() > 1) {
                stopListeningToStaleDeviceMetadata();
                return icuMessageFormat(mContext.getResources(),
                        R.string.quick_settings_hotspot_secondary_label_num_devices,
                        connectedDevices.size());
            }

            CachedBluetoothDevice device = connectedDevices.get(0);

            // Use battery level provided by FastPair metadata if available.
            // If not, fallback to the default battery level from bluetooth.
            int batteryLevel = getMetadataBatteryLevel(device);
            if (batteryLevel > BluetoothUtils.META_INT_ERROR) {
                listenToMetadata(device);
            } else {
                stopListeningToStaleDeviceMetadata();
                batteryLevel = device.getMinBatteryLevelWithMemberDevices();
            }

            if (batteryLevel > BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
                return mContext.getString(
                        R.string.quick_settings_bluetooth_secondary_label_battery_level,
                        Utils.formatPercentage(batteryLevel));
            } else {
                final BluetoothClass bluetoothClass = device.getBtClass();
                if (bluetoothClass != null) {
                    if (device.isHearingAidDevice()) {
                        return mContext.getString(
                                R.string.quick_settings_bluetooth_secondary_label_hearing_aids);
                    } else if (bluetoothClass.doesClassMatch(BluetoothClass.PROFILE_A2DP)) {
                        return mContext.getString(
                                R.string.quick_settings_bluetooth_secondary_label_audio);
                    } else if (bluetoothClass.doesClassMatch(BluetoothClass.PROFILE_HEADSET)) {
                        return mContext.getString(
                                R.string.quick_settings_bluetooth_secondary_label_headset);
                    } else if (bluetoothClass.doesClassMatch(BluetoothClass.PROFILE_HID)) {
                        return mContext.getString(
                                R.string.quick_settings_bluetooth_secondary_label_input);
                    }
                }
            }
        }

        return null;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_BLUETOOTH;
    }

    @Override
    public boolean isAvailable() {
        return mController.isBluetoothSupported();
    }

    private int getMetadataBatteryLevel(CachedBluetoothDevice device) {
        return BluetoothUtils.getIntMetaData(device.getDevice(),
                BluetoothDevice.METADATA_MAIN_BATTERY);
    }

    private void listenToMetadata(CachedBluetoothDevice cachedDevice) {
        if (cachedDevice == mMetadataRegisteredDevice) return;
        stopListeningToStaleDeviceMetadata();
        try {
            mController.addOnMetadataChangedListener(cachedDevice,
                    mExecutor,
                    mMetadataChangedListener);
            mMetadataRegisteredDevice = cachedDevice;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Battery metadata listener already registered for device.");
        }
    }

    private void stopListeningToStaleDeviceMetadata() {
        if (mMetadataRegisteredDevice == null) return;
        try {
            mController.removeOnMetadataChangedListener(
                    mMetadataRegisteredDevice,
                    mMetadataChangedListener);
            mMetadataRegisteredDevice = null;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Battery metadata listener already unregistered for device.");
        }
    }

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

    private final BluetoothAdapter.OnMetadataChangedListener mMetadataChangedListener =
            (device, key, value) -> {
                if (key == BluetoothDevice.METADATA_MAIN_BATTERY) refreshState();
            };
}
