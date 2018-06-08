/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils.leaks;

import android.testing.LeakCheck;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothController.Callback;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class FakeBluetoothController extends BaseLeakChecker<Callback> implements
        BluetoothController {

    public FakeBluetoothController(LeakCheck test) {
        super(test, "bluetooth");
    }

    @Override
    public boolean isBluetoothSupported() {
        return false;
    }

    @Override
    public boolean isBluetoothEnabled() {
        return false;
    }

    @Override
    public int getBluetoothState() {
        return 0;
    }

    @Override
    public boolean isBluetoothConnected() {
        return false;
    }

    @Override
    public boolean isBluetoothConnecting() {
        return false;
    }

    @Override
    public String getConnectedDeviceName() {
        return null;
    }

    @Override
    public void setBluetoothEnabled(boolean enabled) {

    }

    @Override
    public Collection<CachedBluetoothDevice> getDevices() {
        return null;
    }

    @Override
    public void connect(CachedBluetoothDevice device) {

    }

    @Override
    public void disconnect(CachedBluetoothDevice device) {

    }

    @Override
    public boolean canConfigBluetooth() {
        return false;
    }

    @Override
    public int getMaxConnectionState(CachedBluetoothDevice device) {
        return 0;
    }

    @Override
    public int getBondState(CachedBluetoothDevice device) {
        return 0;
    }

    @Override
    public List<CachedBluetoothDevice> getConnectedDevices() {
        return Collections.emptyList();
    }
}
