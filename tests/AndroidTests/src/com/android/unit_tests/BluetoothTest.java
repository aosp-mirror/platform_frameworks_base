/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.unit_tests;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothIntent;
import android.bluetooth.BluetoothClass;
import android.bluetooth.IBluetoothDeviceCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemProperties;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import junit.framework.Assert;

import java.util.List;
import java.util.HashSet;

public class BluetoothTest extends AndroidTestCase {
    private static final String TAG = "BluetoothTest";

    @MediumTest
    public void testBluetoothSmokeTest() throws Exception {

        BluetoothDevice btDevice =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);

        // TODO: Use a more reliable check to see if this product should
        // support Bluetooth - see bug 988521
        boolean shouldSupportBluetooth = SystemProperties.get("ro.kernel.qemu").equals("0");

        assertFalse(shouldSupportBluetooth && btDevice == null);
        if (!shouldSupportBluetooth) {
            Log.i(TAG, "Skipping test - this device does not have bluetooth.");
            return;
        }

        boolean bluetoothWasEnabled = btDevice.isEnabled();

        if (bluetoothWasEnabled) {
            Log.i(TAG, "Bluetooth already enabled");
        } else {
            Log.i(TAG, "Enabling Bluetooth...");
            btDevice.enable();
            Log.i(TAG, "Bluetooth enabled");
        }
        Assert.assertTrue(btDevice.isEnabled());

        String myAddress = btDevice.getAddress();
        Assert.assertTrue(myAddress != null);
        Log.i(TAG, "My Bluetooth Address is " + myAddress);
        Assert.assertFalse(myAddress.equals("00:00:00:00:00:00"));

        if (!bluetoothWasEnabled) {
            Log.i(TAG, "Disabling Bluetooth...");
            btDevice.disable();
            Log.i(TAG, "Bluetooth disabled");
        }
    }

    private boolean listenA2dp = false;
    private void listenA2dp() {
        if (!listenA2dp) {
            listenA2dp = true;
            getContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int state = intent.getIntExtra(BluetoothA2dp.SINK_STATE, -1);
                    int oldState = intent.getIntExtra(BluetoothA2dp.SINK_PREVIOUS_STATE, -1);
                    Log.e(TAG, "A2DP INTENT: state = " + state + " oldState = " + oldState);
                }
            }, new IntentFilter(BluetoothIntent.REMOTE_DEVICE_FOUND_ACTION));
        }
    }

    @MediumTest
    public void testA2dpSmokeTest() throws Exception {
        listenA2dp();

        BluetoothA2dp a2dp = new BluetoothA2dp(getContext());

        List<String> sinks = a2dp.listConnectedSinks();
        Log.e(TAG, "listConnectedSinks()...");
        for (String sink : sinks) {
            Log.e(TAG, sink + " state = " + a2dp.getSinkState(sink));
        }
    }

    @MediumTest
    public void testA2dpConnect() throws Exception {
        listenA2dp();
        String address = SystemProperties.get("debug.a2dp.address", "<none>");
        BluetoothA2dp a2dp = new BluetoothA2dp(getContext());
        int result = a2dp.connectSink(address);
        Log.e(TAG, "connectSink(" + address + ") = " + result);
    }

    @MediumTest
    public void testA2dpDisconnect() throws Exception {
        listenA2dp();
        String address = SystemProperties.get("debug.a2dp.address", "<none>");
        BluetoothA2dp a2dp = new BluetoothA2dp(getContext());
        int result = a2dp.disconnectSink(address);
        Log.e(TAG, "disconnectSink(" + address + ") = " + result);
    }

    @MediumTest
    public void testBluetoothEnabled() throws Exception {
        BluetoothDevice device =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            Log.i(TAG, "Device not Bluetooth capable, skipping test");
            return;
        }

        if (device.isEnabled()) {
            Log.i(TAG, "isEnabled() = yes");
        } else {
            Log.i(TAG, "isEnabled() = no");
        }
    }

    @MediumTest
    public void testEnableBluetooth() throws Exception {
        BluetoothDevice device =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            Log.i(TAG, "Device not Bluetooth capable, skipping test");
            return;
        }
        device.enable();
    }

    @MediumTest
    public void testEnableBluetoothWithCallback() throws Exception {
        BluetoothDevice device =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            Log.i(TAG, "Device not Bluetooth capable, skipping test");
            return;
        }
        if (!device.enable(mCallback)) {
            Log.e(TAG, "enable() failed");
        }
    }

    @MediumTest
    public void testDisableBluetooth() throws Exception {
        BluetoothDevice device =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            Log.i(TAG, "Device not Bluetooth capable, skipping test");
            return;
        }
        device.disable();
    }

    @LargeTest
    public void testDiscovery() throws Exception {
        BluetoothDevice device =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            Log.i(TAG, "Device not Bluetooth capable, skipping test");
            return;
        }
        if (device.isEnabled()) {
            getContext().registerReceiver((BroadcastReceiver)new TestDiscoveryReceiver(),
                    new IntentFilter(BluetoothIntent.REMOTE_DEVICE_FOUND_ACTION));
            Log.i(TAG, "Starting discovery...");
            String result = device.startDiscovery() ? "true" : "false";
            Log.i(TAG, "startDiscovery() = " + result);
        }
    }

    @LargeTest
    public void testMultipleDiscovery() throws Exception {
        BluetoothDevice device =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            Log.i(TAG, "Device not Bluetooth capable, skipping test");
            return;
        }
        if (device.isEnabled()) {
            getContext().registerReceiver((BroadcastReceiver)new TestDiscoveryReceiver(),
                    new IntentFilter(BluetoothIntent.REMOTE_DEVICE_FOUND_ACTION));
            String result;
            Log.i(TAG, "Starting multiple discovery...");
            for (int i = 0; i < 5; i++) {
                result = device.startDiscovery() ? "true" : "false";
                Log.i(TAG, "startDiscovery() = " + result);
            }
        }
    }
    private class TestDiscoveryReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String address = intent.getStringExtra(BluetoothIntent.ADDRESS);
            int deviceClass = intent.getIntExtra(BluetoothIntent.CLASS, -1);
            short rssi = intent.getShortExtra(BluetoothIntent.RSSI, (short)-1);
            Log.i(TAG, "Discovered Device: " + address + " " + deviceClass + " " + rssi);
        }
    }

    private IBluetoothDeviceCallback mCallback = new IBluetoothDeviceCallback.Stub() {
        public void onEnableResult(int res) {
            String result = "unknown";
            switch (res) {
            case BluetoothDevice.RESULT_SUCCESS:
                result = "success";
                break;
            case BluetoothDevice.RESULT_FAILURE:
                result = "FAILURE";
                break;
            }
            Log.i(TAG, "onEnableResult(" + result + ")");
        }
        public void onGetRemoteServiceChannelResult(String device, int channel) {}
    };

    @SmallTest
    public void testCreateBond() throws Exception {
        BluetoothDevice device =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            Log.i(TAG, "Device not Bluetooth capable, skipping test");
            return;
        }
        if (!device.createBond("01:23:45:67:89:AB")) {
            Log.e(TAG, "createBonding() failed");
        }
    }

    @SmallTest
    public void testIsPeriodicDiscovery() throws Exception {
        BluetoothDevice device =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            Log.i(TAG, "Device not Bluetooth capable, skipping test");
            return;
        }
        boolean ret = device.isPeriodicDiscovery();
        if (ret) {
            Log.i(TAG, "isPeriodicDiscovery() = TRUE");
        } else {
            Log.i(TAG, "isPeriodicDiscovery() = FALSE");
        }
    }

    @LargeTest
    public void testListBondings() throws Exception {
        BluetoothDevice device =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            Log.i(TAG, "Device not Bluetooth capable, skipping test");
            return;
        }
        String[] addresses = device.listBonds();
        if (addresses == null) {
            Log.i(TAG, "Bluetooth disabled");
            return;
        }
        for (String address : addresses) {
            String name = device.getRemoteName(address);
            Log.i(TAG, "BONDING: " + address + " (" + name + ")");
        }
    }

    @LargeTest
    public void testListAclConnections() throws Exception {
        BluetoothDevice device =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            Log.i(TAG, "Device not Bluetooth capable, skipping test");
            return;
        }
        String[] addresses = device.listAclConnections();
        if (addresses == null) {
            Log.i(TAG, "Bluetooth disabled");
            return;
        }
        for (String address : addresses) {
            String name = device.getRemoteName(address);
            Log.i(TAG, "CONNECTION: " + address + " (" + name + ")");
        }
    }

    @LargeTest
    public void testListRemoteDevices() throws Exception {
        BluetoothDevice device =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            Log.i(TAG, "Device not Bluetooth capable, skipping test");
            return;
        }
        String[] addresses = device.listRemoteDevices();
        if (addresses == null) {
            Log.i(TAG, "Bluetooth disabled");
            return;
        }
        for (String address : addresses) {
            String name = device.getRemoteName(address);
            Log.i(TAG, "KNOWN DEVICE: " + address + " (" + name + ")");
        }
    }

    @MediumTest
    public void testSetupBTIntentRecv() throws Exception {
        BluetoothDevice device =
                (BluetoothDevice)getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (device == null) {
            Log.i(TAG, "Device not Bluetooth capable, skipping test");
            return;
        }
        if (device.isEnabled()) {
            IntentFilter filter = new IntentFilter(BluetoothIntent.ENABLED_ACTION);
            filter.addAction(BluetoothIntent.ENABLED_ACTION);
            filter.addAction(BluetoothIntent.DISABLED_ACTION);
            filter.addAction(BluetoothIntent.NAME_CHANGED_ACTION);
            filter.addAction(BluetoothIntent.MODE_CHANGED_ACTION);
            filter.addAction(BluetoothIntent.DISCOVERY_STARTED_ACTION);
            filter.addAction(BluetoothIntent.DISCOVERY_COMPLETED_ACTION);
            filter.addAction(BluetoothIntent.PAIRING_REQUEST_ACTION);
            filter.addAction(BluetoothIntent.PAIRING_CANCEL_ACTION);
            filter.addAction(BluetoothIntent.REMOTE_DEVICE_FOUND_ACTION);
            filter.addAction(BluetoothIntent.REMOTE_DEVICE_DISAPPEARED_ACTION);
            filter.addAction(BluetoothIntent.REMOTE_DEVICE_CLASS_UPDATED_ACTION);
            filter.addAction(BluetoothIntent.REMOTE_DEVICE_CONNECTED_ACTION);
            filter.addAction(BluetoothIntent.REMOTE_DEVICE_DISCONNECT_REQUESTED_ACTION);
            filter.addAction(BluetoothIntent.REMOTE_DEVICE_DISCONNECTED_ACTION);
            filter.addAction(BluetoothIntent.REMOTE_NAME_UPDATED_ACTION);
            filter.addAction(BluetoothIntent.REMOTE_NAME_FAILED_ACTION);
            filter.addAction(BluetoothIntent.REMOTE_ALIAS_CHANGED_ACTION);
            filter.addAction(BluetoothIntent.REMOTE_ALIAS_CLEARED_ACTION);
            filter.addAction(BluetoothIntent.BOND_STATE_CHANGED_ACTION);
            filter.addAction(BluetoothIntent.HEADSET_STATE_CHANGED_ACTION);
            getContext().registerReceiver(
                    (BroadcastReceiver)new BluetoothIntentReceiver(), filter);
            Log.i(TAG, "Listening for BLUETOOTH INTENTS....");
        } else {
            Log.e(TAG, "BT not enabled");
        }
    }


    private class BluetoothIntentReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String msg = "";

            String address = intent.getStringExtra(BluetoothIntent.ADDRESS);
            if (address != null) {
                msg += " address=" + address;
            }

            int deviceClass = intent.getIntExtra(BluetoothIntent.CLASS, BluetoothClass.ERROR);
            if (deviceClass != BluetoothClass.ERROR) {
                msg += " class=" + deviceClass;
            }

            int rssi = intent.getIntExtra(BluetoothIntent.RSSI, -1);
            if (rssi != -1) {
                msg += " rssi=" + rssi;
            }

            String name = intent.getStringExtra(BluetoothIntent.NAME);
            if (name != null) {
                msg += " name=" + name;
            }

            String alias = intent.getStringExtra(BluetoothIntent.ALIAS);
            if (alias != null) {
                msg += " alias=" + alias;
            }

            int mode = intent.getIntExtra(BluetoothIntent.MODE, -10);
            if (mode != -10) {
                msg += " mode=" + mode;
            }

            int state = intent.getIntExtra(BluetoothIntent.HEADSET_STATE, -10);
            if (state != -10) {
                msg += " headset state=" + state;
            }
            Log.i(TAG, "BLUETOOTH INTENT: " + intent.getAction() + msg);
        }
    }


    private static final int[] ALL_SERVICE_CLASSES = new int[] {
        BluetoothClass.Service.LIMITED_DISCOVERABILITY,
        BluetoothClass.Service.POSITIONING,
        BluetoothClass.Service.NETWORKING,
        BluetoothClass.Service.RENDER,
        BluetoothClass.Service.CAPTURE,
        BluetoothClass.Service.OBJECT_TRANSFER,
        BluetoothClass.Service.AUDIO,
        BluetoothClass.Service.TELEPHONY,
        BluetoothClass.Service.INFORMATION
    };
    private void assertOnlyTheseServiceClassesAreSupported(int deviceClass, HashSet<Integer> serviceClasses) {
        for (int serviceClassType : ALL_SERVICE_CLASSES) {
            Assert.assertEquals(serviceClasses.contains(new Integer(serviceClassType)),
                                BluetoothClass.Service.hasService(deviceClass, serviceClassType));
        }
    }

    @SmallTest
    public void testDeviceClass() throws Exception {
        // This test does not require bluetooth hardware
        int deviceClass;
        HashSet<Integer> serviceClasses;

        deviceClass = BluetoothClass.ERROR;  // bogus class
        serviceClasses = new HashSet<Integer>();
        assertOnlyTheseServiceClassesAreSupported(deviceClass, serviceClasses);
        Assert.assertEquals(BluetoothClass.ERROR, BluetoothClass.Device.Major.getDeviceMajor(deviceClass));
        Assert.assertEquals(BluetoothClass.ERROR, BluetoothClass.Device.getDevice(deviceClass));

        deviceClass = 0x10210C;  // mac book pro
        serviceClasses = new HashSet<Integer>();
        serviceClasses.add(BluetoothClass.Service.OBJECT_TRANSFER);
        serviceClasses.add(BluetoothClass.Service.LIMITED_DISCOVERABILITY);
        assertOnlyTheseServiceClassesAreSupported(deviceClass, serviceClasses);
        Assert.assertEquals(BluetoothClass.Device.Major.COMPUTER,
                           BluetoothClass.Device.Major.getDeviceMajor(deviceClass));
        Assert.assertEquals(0x10C, BluetoothClass.Device.getDevice(deviceClass));

        // mac book pro with some unused bits set. Expecting the same results
        deviceClass = 0xFF10210F;
        serviceClasses = new HashSet<Integer>();
        serviceClasses.add(BluetoothClass.Service.OBJECT_TRANSFER);
        serviceClasses.add(BluetoothClass.Service.LIMITED_DISCOVERABILITY);
        assertOnlyTheseServiceClassesAreSupported(deviceClass, serviceClasses);
        Assert.assertEquals(BluetoothClass.Device.Major.COMPUTER,
                           BluetoothClass.Device.Major.getDeviceMajor(deviceClass));
        Assert.assertEquals(0x10C, BluetoothClass.Device.getDevice(deviceClass));

        deviceClass = 0x3E0100;  // droid.corp.google.com
        serviceClasses = new HashSet<Integer>();
        serviceClasses.add(BluetoothClass.Service.AUDIO);
        serviceClasses.add(BluetoothClass.Service.OBJECT_TRANSFER);
        serviceClasses.add(BluetoothClass.Service.CAPTURE);
        serviceClasses.add(BluetoothClass.Service.RENDER);
        serviceClasses.add(BluetoothClass.Service.NETWORKING);
        assertOnlyTheseServiceClassesAreSupported(deviceClass, serviceClasses);
        Assert.assertEquals(BluetoothClass.Device.Major.COMPUTER,
                           BluetoothClass.Device.Major.getDeviceMajor(deviceClass));
        Assert.assertEquals(0x100, BluetoothClass.Device.getDevice(deviceClass));

        deviceClass = 0x40020C;  // Android
        serviceClasses = new HashSet<Integer>();
        serviceClasses.add(BluetoothClass.Service.TELEPHONY);
        assertOnlyTheseServiceClassesAreSupported(deviceClass, serviceClasses);
        Assert.assertEquals(BluetoothClass.Device.Major.PHONE, BluetoothClass.Device.Major.getDeviceMajor(deviceClass));
        Assert.assertEquals(0x20C, BluetoothClass.Device.getDevice(deviceClass));

        // Motorola T305 & Jabra BT125 & Jabra BT250V
        // This seems to be a very common headset & handsfree device code
        deviceClass = 0x200404;
        serviceClasses = new HashSet<Integer>();
        serviceClasses.add(BluetoothClass.Service.AUDIO);
        assertOnlyTheseServiceClassesAreSupported(deviceClass, serviceClasses);
        Assert.assertEquals(BluetoothClass.Device.Major.AUDIO_VIDEO,
                           BluetoothClass.Device.Major.getDeviceMajor(deviceClass));
        Assert.assertEquals(BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                           BluetoothClass.Device.getDevice(deviceClass));

        // Audi UHV 0128
        deviceClass = 0x200408;
        serviceClasses = new HashSet<Integer>();
        serviceClasses.add(BluetoothClass.Service.AUDIO);
        assertOnlyTheseServiceClassesAreSupported(deviceClass, serviceClasses);
        Assert.assertEquals(BluetoothClass.Device.Major.AUDIO_VIDEO,
                           BluetoothClass.Device.Major.getDeviceMajor(deviceClass));
        Assert.assertEquals(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
                           BluetoothClass.Device.getDevice(deviceClass));
    }
}
