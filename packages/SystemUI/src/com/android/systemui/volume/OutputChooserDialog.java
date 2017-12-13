/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.volume;

import static com.android.settingslib.bluetooth.Utils.getBtClassDrawableWithDescription;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;

import com.android.settingslib.Utils;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.BluetoothController;

import java.util.ArrayList;
import java.util.Collection;

public class OutputChooserDialog extends SystemUIDialog
        implements DialogInterface.OnDismissListener, OutputChooserLayout.Callback {

    private static final String TAG = Util.logTag(OutputChooserDialog.class);
    private static final int MAX_DEVICES = 10;

    private final Context mContext;
    private final BluetoothController mController;
    private OutputChooserLayout mView;


    public OutputChooserDialog(Context context) {
        super(context);
        mContext = context;
        mController = Dependency.get(BluetoothController.class);

        final IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mReceiver, filter);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.output_chooser);
        setCanceledOnTouchOutside(true);
        setOnDismissListener(this::onDismiss);
        mView = findViewById(R.id.output_chooser);
        mView.setCallback(this);
        updateItems();
        mController.addCallback(mCallback);
    }

    protected void cleanUp() {}


    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    public void onDismiss(DialogInterface unused) {
        mContext.unregisterReceiver(mReceiver);
        mController.removeCallback(mCallback);
        cleanUp();
    }

    @Override
    public void onDetailItemClick(OutputChooserLayout.Item item) {
        if (item == null || item.tag == null) return;
        final CachedBluetoothDevice device = (CachedBluetoothDevice) item.tag;
        if (device != null && device.getMaxConnectionState()
                == BluetoothProfile.STATE_DISCONNECTED) {
            mController.connect(device);
        }
    }

    @Override
    public void onDetailItemDisconnect(OutputChooserLayout.Item item) {
        if (item == null || item.tag == null) return;
        final CachedBluetoothDevice device = (CachedBluetoothDevice) item.tag;
        if (device != null) {
            mController.disconnect(device);
        }
    }

    private void updateItems() {
        if (mView == null) return;
        if (mController.isBluetoothEnabled()) {
            mView.setEmptyState(R.drawable.ic_qs_bluetooth_detail_empty,
                    R.string.quick_settings_bluetooth_detail_empty_text);
            mView.setItemsVisible(true);
        } else {
            mView.setEmptyState(R.drawable.ic_qs_bluetooth_detail_empty,
                    R.string.bt_is_off);
            mView.setItemsVisible(false);
        }
        ArrayList<OutputChooserLayout.Item> items = new ArrayList<>();
        final Collection<CachedBluetoothDevice> devices = mController.getDevices();
        if (devices != null) {
            int connectedDevices = 0;
            int count = 0;
            for (CachedBluetoothDevice device : devices) {
                if (mController.getBondState(device) == BluetoothDevice.BOND_NONE) continue;
                final int majorClass = device.getBtClass().getMajorDeviceClass();
                if (majorClass != BluetoothClass.Device.Major.AUDIO_VIDEO
                        && majorClass != BluetoothClass.Device.Major.UNCATEGORIZED) {
                    continue;
                }
                final OutputChooserLayout.Item item = new OutputChooserLayout.Item();
                item.iconResId = R.drawable.ic_qs_bluetooth_on;
                item.line1 = device.getName();
                item.tag = device;
                int state = device.getMaxConnectionState();
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    item.iconResId = R.drawable.ic_qs_bluetooth_connected;
                    int batteryLevel = device.getBatteryLevel();
                    if (batteryLevel != BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
                        Pair<Drawable, String> pair =
                                getBtClassDrawableWithDescription(getContext(), device);
                        item.icon = pair.first;
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
        mView.setItems(items.toArray(new OutputChooserLayout.Item[items.size()]));
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                if (D.BUG) Log.d(TAG, "Received ACTION_CLOSE_SYSTEM_DIALOGS");
                cancel();
                cleanUp();
            }
        }
    };

    private final BluetoothController.Callback mCallback = new BluetoothController.Callback() {
        @Override
        public void onBluetoothStateChange(boolean enabled) {
            updateItems();
        }

        @Override
        public void onBluetoothDevicesChanged() {
            updateItems();
        }
    };
}