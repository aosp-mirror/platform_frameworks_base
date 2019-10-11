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

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.graph.BluetoothDeviceLayerDrawable;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSDetailItems;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.BluetoothController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.inject.Inject;

/** Quick settings tile: Bluetooth **/
public class BluetoothTile extends QSTileImpl<BooleanState> {
    private static final Intent BLUETOOTH_SETTINGS = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);

    private final BluetoothController mController;
    private final BluetoothDetailAdapter mDetailAdapter;
    private final ActivityStarter mActivityStarter;

    @Inject
    public BluetoothTile(QSHost host,
            BluetoothController bluetoothController,
            ActivityStarter activityStarter) {
        super(host);
        mController = bluetoothController;
        mActivityStarter = activityStarter;
        mDetailAdapter = (BluetoothDetailAdapter) createDetailAdapter();
        mController.observe(getLifecycle(), mCallback);
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    @Override
    protected void handleClick() {
        // Secondary clicks are header clicks, just toggle.
        final boolean isEnabled = mState.value;
        // Immediately enter transient enabling state when turning bluetooth on.
        refreshState(isEnabled ? null : ARG_SHOW_TRANSIENT_ENABLING);
        mController.setBluetoothEnabled(!isEnabled);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    @Override
    protected void handleSecondaryClick() {
        if (!mController.canConfigBluetooth()) {
            mActivityStarter.postStartActivityDismissingKeyguard(
                    new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), 0);
            return;
        }
        showDetail(true);
        if (!mState.value) {
            mController.setBluetoothEnabled(true);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_bluetooth_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean transientEnabling = arg == ARG_SHOW_TRANSIENT_ENABLING;
        final boolean enabled = transientEnabling || mController.isBluetoothEnabled();
        final boolean connected = mController.isBluetoothConnected();
        final boolean connecting = mController.isBluetoothConnecting();
        state.isTransient = transientEnabling || connecting ||
                mController.getBluetoothState() == BluetoothAdapter.STATE_TURNING_ON;
        state.dualTarget = true;
        state.value = enabled;
        if (state.slash == null) {
            state.slash = new SlashState();
        }
        state.slash.isSlashed = !enabled;
        state.label = mContext.getString(R.string.quick_settings_bluetooth_label);
        state.secondaryLabel = TextUtils.emptyIfNull(
                getSecondaryLabel(enabled, connecting, connected, state.isTransient));
        if (enabled) {
            if (connected) {
                state.icon = new BluetoothConnectedTileIcon();
                if (!TextUtils.isEmpty(mController.getConnectedDeviceName())) {
                    state.label = mController.getConnectedDeviceName();
                }
                state.contentDescription =
                        mContext.getString(R.string.accessibility_bluetooth_name, state.label)
                                + ", " + state.secondaryLabel;
            } else if (state.isTransient) {
                state.icon = ResourceIcon.get(
                        com.android.internal.R.drawable.ic_bluetooth_transient_animation);
                state.contentDescription = state.secondaryLabel;
            } else {
                state.icon =
                        ResourceIcon.get(com.android.internal.R.drawable.ic_qs_bluetooth);
                state.contentDescription = mContext.getString(
                        R.string.accessibility_quick_settings_bluetooth) + ","
                        + mContext.getString(R.string.accessibility_not_connected);
            }
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.icon = ResourceIcon.get(com.android.internal.R.drawable.ic_qs_bluetooth);
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_bluetooth);
            state.state = Tile.STATE_INACTIVE;
        }

        state.dualLabelContentDescription = mContext.getResources().getString(
                R.string.accessibility_quick_settings_open_settings, getTileLabel());
        state.expandedAccessibilityClassName = Switch.class.getName();
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
                // TODO(b/76102598): add a new string for "X connected devices" after P
                return mContext.getResources().getQuantityString(
                        R.plurals.quick_settings_hotspot_secondary_label_num_devices,
                        connectedDevices.size(),
                        connectedDevices.size());
            }

            CachedBluetoothDevice lastDevice = connectedDevices.get(0);
            final int batteryLevel = lastDevice.getBatteryLevel();

            if (batteryLevel != BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
                return mContext.getString(
                        R.string.quick_settings_bluetooth_secondary_label_battery_level,
                        Utils.formatPercentage(batteryLevel));

            } else {
                final BluetoothClass bluetoothClass = lastDevice.getBtClass();
                if (bluetoothClass != null) {
                    if (lastDevice.isHearingAidDevice()) {
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
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_bluetooth_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_bluetooth_changed_off);
        }
    }

    @Override
    public boolean isAvailable() {
        return mController.isBluetoothSupported();
    }

    private final BluetoothController.Callback mCallback = new BluetoothController.Callback() {
        @Override
        public void onBluetoothStateChange(boolean enabled) {
            refreshState();
            if (isShowingDetail()) {
                mDetailAdapter.updateItems();
                fireToggleStateChanged(mDetailAdapter.getToggleState());
            }
        }

        @Override
        public void onBluetoothDevicesChanged() {
            refreshState();
            if (isShowingDetail()) {
                mDetailAdapter.updateItems();
            }
        }
    };

    @Override
    protected DetailAdapter createDetailAdapter() {
        return new BluetoothDetailAdapter();
    }

    /**
     * Bluetooth icon wrapper for Quick Settings with a battery indicator that reflects the
     * connected device's battery level. This is used instead of
     * {@link com.android.systemui.qs.tileimpl.QSTileImpl.DrawableIcon} in order to use a context
     * that reflects dark/light theme attributes.
     */
    private class BluetoothBatteryTileIcon extends Icon {
        private int mBatteryLevel;
        private float mIconScale;

        BluetoothBatteryTileIcon(int batteryLevel, float iconScale) {
            mBatteryLevel = batteryLevel;
            mIconScale = iconScale;
        }

        @Override
        public Drawable getDrawable(Context context) {
            // This method returns Pair<Drawable, String> while first value is the drawable
            return BluetoothDeviceLayerDrawable.createLayerDrawable(
                    context,
                    R.drawable.ic_bluetooth_connected,
                    mBatteryLevel,
                    mIconScale);
        }
    }


    /**
     * Bluetooth icon wrapper (when connected with no battery indicator) for Quick Settings. This is
     * used instead of {@link com.android.systemui.qs.tileimpl.QSTileImpl.DrawableIcon} in order to
     * use a context that reflects dark/light theme attributes.
     */
    private class BluetoothConnectedTileIcon extends Icon {

        BluetoothConnectedTileIcon() {
            // Do nothing. Default constructor to limit visibility.
        }

        @Override
        public Drawable getDrawable(Context context) {
            // This method returns Pair<Drawable, String> - the first value is the drawable.
            return context.getDrawable(R.drawable.ic_bluetooth_connected);
        }
    }

    protected class BluetoothDetailAdapter implements DetailAdapter, QSDetailItems.Callback {
        // We probably won't ever have space in the UI for more than 20 devices, so don't
        // get info for them.
        private static final int MAX_DEVICES = 20;
        private QSDetailItems mItems;

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_bluetooth_label);
        }

        @Override
        public Boolean getToggleState() {
            return mState.value;
        }

        @Override
        public boolean getToggleEnabled() {
            return mController.getBluetoothState() == BluetoothAdapter.STATE_OFF
                    || mController.getBluetoothState() == BluetoothAdapter.STATE_ON;
        }

        @Override
        public Intent getSettingsIntent() {
            return BLUETOOTH_SETTINGS;
        }

        @Override
        public void setToggleState(boolean state) {
            MetricsLogger.action(mContext, MetricsEvent.QS_BLUETOOTH_TOGGLE, state);
            mController.setBluetoothEnabled(state);
        }

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.QS_BLUETOOTH_DETAILS;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            mItems = QSDetailItems.convertOrInflate(context, convertView, parent);
            mItems.setTagSuffix("Bluetooth");
            mItems.setCallback(this);
            updateItems();
            setItemsVisible(mState.value);
            return mItems;
        }

        public void setItemsVisible(boolean visible) {
            if (mItems == null) return;
            mItems.setItemsVisible(visible);
        }

        private void updateItems() {
            if (mItems == null) return;
            if (mController.isBluetoothEnabled()) {
                mItems.setEmptyState(R.drawable.ic_qs_bluetooth_detail_empty,
                        R.string.quick_settings_bluetooth_detail_empty_text);
            } else {
                mItems.setEmptyState(R.drawable.ic_qs_bluetooth_detail_empty,
                        R.string.bt_is_off);
            }
            ArrayList<Item> items = new ArrayList<Item>();
            final Collection<CachedBluetoothDevice> devices = mController.getDevices();
            if (devices != null) {
                int connectedDevices = 0;
                int count = 0;
                for (CachedBluetoothDevice device : devices) {
                    if (mController.getBondState(device) == BluetoothDevice.BOND_NONE) continue;
                    final Item item = new Item();
                    item.iconResId = com.android.internal.R.drawable.ic_qs_bluetooth;
                    item.line1 = device.getName();
                    item.tag = device;
                    int state = device.getMaxConnectionState();
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        item.iconResId = R.drawable.ic_bluetooth_connected;
                        int batteryLevel = device.getBatteryLevel();
                        if (batteryLevel != BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
                            item.icon = new BluetoothBatteryTileIcon(batteryLevel,1 /* iconScale */);
                            item.line2 = mContext.getString(
                                    R.string.quick_settings_connected_battery_level,
                                    Utils.formatPercentage(batteryLevel));
                        } else {
                            item.line2 = mContext.getString(R.string.quick_settings_connected);
                        }
                        item.canDisconnect = true;
                        items.add(connectedDevices, item);
                        connectedDevices++;
                    } else if (state == BluetoothProfile.STATE_CONNECTING) {
                        item.iconResId = R.drawable.ic_qs_bluetooth_connecting;
                        item.line2 = mContext.getString(R.string.quick_settings_connecting);
                        items.add(connectedDevices, item);
                    } else {
                        items.add(item);
                    }
                    if (++count == MAX_DEVICES) {
                        break;
                    }
                }
            }
            mItems.setItems(items.toArray(new Item[items.size()]));
        }

        @Override
        public void onDetailItemClick(Item item) {
            if (item == null || item.tag == null) return;
            final CachedBluetoothDevice device = (CachedBluetoothDevice) item.tag;
            if (device != null && device.getMaxConnectionState()
                    == BluetoothProfile.STATE_DISCONNECTED) {
                mController.connect(device);
            }
        }

        @Override
        public void onDetailItemDisconnect(Item item) {
            if (item == null || item.tag == null) return;
            final CachedBluetoothDevice device = (CachedBluetoothDevice) item.tag;
            if (device != null) {
                mController.disconnect(device);
            }
        }
    }
}
