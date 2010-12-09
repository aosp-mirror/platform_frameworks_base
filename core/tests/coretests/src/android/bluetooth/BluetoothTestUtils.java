/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.bluetooth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Environment;
import android.util.Log;

import junit.framework.Assert;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BluetoothTestUtils extends Assert {

    /**
     * Timeout for {@link BluetoothAdapter#disable()} in ms.
     */
    private static final int DISABLE_TIMEOUT = 20000;

    /**
     * Timeout for {@link BluetoothAdapter#enable()} in ms.
     */
    private static final int ENABLE_TIMEOUT = 20000;

    /**
     * Timeout for {@link BluetoothAdapter#setScanMode(int)} in ms.
     */
    private static final int SET_SCAN_MODE_TIMEOUT = 5000;

    /**
     * Timeout for {@link BluetoothAdapter#startDiscovery()} in ms.
     */
    private static final int START_DISCOVERY_TIMEOUT = 5000;

    /**
     * Timeout for {@link BluetoothAdapter#cancelDiscovery()} in ms.
     */
    private static final int CANCEL_DISCOVERY_TIMEOUT = 5000;

    /**
     * Timeout for {@link BluetoothDevice#createBond()} in ms.
     */
    private static final int PAIR_TIMEOUT = 20000;

    /**
     * Timeout for {@link BluetoothDevice#removeBond()} in ms.
     */
    private static final int UNPAIR_TIMEOUT = 20000;

    /**
     * Timeout for {@link BluetoothProfile#connect(BluetoothDevice)} in ms.
     */
    private static final int CONNECT_PROFILE_TIMEOUT = 20000;

    /**
     * Timeout for {@link BluetoothProfile#disconnect(BluetoothDevice)} in ms.
     */
    private static final int DISCONNECT_PROFILE_TIMEOUT = 20000;

    /**
     * Timeout to connect a profile proxy in ms.
     */
    private static final int CONNECT_PROXY_TIMEOUT = 5000;

    /**
     * Time between polls in ms.
     */
    private static final int POLL_TIME = 100;

    private abstract class FlagReceiver extends BroadcastReceiver {
        private int mExpectedFlags = 0;
        private int mFiredFlags = 0;
        private long mCompletedTime = -1;

        public FlagReceiver(int expectedFlags) {
            mExpectedFlags = expectedFlags;
        }

        public int getFiredFlags() {
            synchronized (this) {
                return mFiredFlags;
            }
        }

        public long getCompletedTime() {
            synchronized (this) {
                return mCompletedTime;
            }
        }

        protected void setFiredFlag(int flag) {
            synchronized (this) {
                mFiredFlags |= flag;
                if ((mFiredFlags & mExpectedFlags) == mExpectedFlags) {
                    mCompletedTime = System.currentTimeMillis();
                }
            }
        }
    }

    private class BluetoothReceiver extends FlagReceiver {
        private static final int DISCOVERY_STARTED_FLAG = 1;
        private static final int DISCOVERY_FINISHED_FLAG = 1 << 1;
        private static final int SCAN_MODE_NONE_FLAG = 1 << 2;
        private static final int SCAN_MODE_CONNECTABLE_FLAG = 1 << 3;
        private static final int SCAN_MODE_CONNECTABLE_DISCOVERABLE_FLAG = 1 << 4;
        private static final int STATE_OFF_FLAG = 1 << 5;
        private static final int STATE_TURNING_ON_FLAG = 1 << 6;
        private static final int STATE_ON_FLAG = 1 << 7;
        private static final int STATE_TURNING_OFF_FLAG = 1 << 8;

        public BluetoothReceiver(int expectedFlags) {
            super(expectedFlags);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(intent.getAction())) {
                setFiredFlag(DISCOVERY_STARTED_FLAG);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                setFiredFlag(DISCOVERY_FINISHED_FLAG);
            } else if (BluetoothAdapter.ACTION_SCAN_MODE_CHANGED.equals(intent.getAction())) {
                int mode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, -1);
                assertNotSame(-1, mode);
                switch (mode) {
                    case BluetoothAdapter.SCAN_MODE_NONE:
                        setFiredFlag(SCAN_MODE_NONE_FLAG);
                        break;
                    case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
                        setFiredFlag(SCAN_MODE_CONNECTABLE_FLAG);
                        break;
                    case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                        setFiredFlag(SCAN_MODE_CONNECTABLE_DISCOVERABLE_FLAG);
                        break;
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                assertNotSame(-1, state);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        setFiredFlag(STATE_OFF_FLAG);
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        setFiredFlag(STATE_TURNING_ON_FLAG);
                        break;
                    case BluetoothAdapter.STATE_ON:
                        setFiredFlag(STATE_ON_FLAG);
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        setFiredFlag(STATE_TURNING_OFF_FLAG);
                        break;
                }
            }
        }
    }

    private class PairReceiver extends FlagReceiver {
        private static final int STATE_BONDED_FLAG = 1;
        private static final int STATE_BONDING_FLAG = 1 << 1;
        private static final int STATE_NONE_FLAG = 1 << 2;

        private BluetoothDevice mDevice;
        private int mPasskey;
        private byte[] mPin;

        public PairReceiver(BluetoothDevice device, int passkey, byte[] pin, int expectedFlags) {
            super(expectedFlags);

            mDevice = device;
            mPasskey = passkey;
            mPin = pin;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mDevice.equals(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE))) {
                return;
            }

            if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
                int varient = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                assertNotSame(-1, varient);
                switch (varient) {
                    case BluetoothDevice.PAIRING_VARIANT_PIN:
                        mDevice.setPin(mPin);
                        break;
                    case BluetoothDevice.PAIRING_VARIANT_PASSKEY:
                        mDevice.setPasskey(mPasskey);
                        break;
                    case BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION:
                    case BluetoothDevice.PAIRING_VARIANT_CONSENT:
                        mDevice.setPairingConfirmation(true);
                        break;
                    case BluetoothDevice.PAIRING_VARIANT_OOB_CONSENT:
                        mDevice.setRemoteOutOfBandData();
                        break;
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                assertNotSame(-1, state);
                switch (state) {
                    case BluetoothDevice.BOND_NONE:
                        setFiredFlag(STATE_NONE_FLAG);
                        break;
                    case BluetoothDevice.BOND_BONDING:
                        setFiredFlag(STATE_BONDING_FLAG);
                        break;
                    case BluetoothDevice.BOND_BONDED:
                        setFiredFlag(STATE_BONDED_FLAG);
                        break;
                }
            }
        }
    }

    private class ConnectProfileReceiver extends FlagReceiver {
        private static final int STATE_DISCONNECTED_FLAG = 1;
        private static final int STATE_CONNECTING_FLAG = 1 << 1;
        private static final int STATE_CONNECTED_FLAG = 1 << 2;
        private static final int STATE_DISCONNECTING_FLAG = 1 << 3;

        private BluetoothDevice mDevice;
        private int mProfile;
        private String mConnectionAction;

        public ConnectProfileReceiver(BluetoothDevice device, int profile, int expectedFlags) {
            super(expectedFlags);

            mDevice = device;
            mProfile = profile;

            switch (mProfile) {
                case BluetoothProfile.A2DP:
                    mConnectionAction = BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED;
                    break;
                case BluetoothProfile.HEADSET:
                    mConnectionAction = BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED;
                    break;
                default:
                    mConnectionAction = null;
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {

            if (mConnectionAction != null && mConnectionAction.equals(intent.getAction())) {
                if (!mDevice.equals(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE))) {
                    return;
                }

                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                assertNotSame(-1, state);
                switch (state) {
                    case BluetoothProfile.STATE_DISCONNECTED:
                        setFiredFlag(STATE_DISCONNECTED_FLAG);
                        break;
                    case BluetoothProfile.STATE_CONNECTING:
                        setFiredFlag(STATE_CONNECTING_FLAG);
                        break;
                    case BluetoothProfile.STATE_CONNECTED:
                        setFiredFlag(STATE_CONNECTED_FLAG);
                        break;
                    case BluetoothProfile.STATE_DISCONNECTING:
                        setFiredFlag(STATE_DISCONNECTING_FLAG);
                        break;
                }
            }
        }
    }

    private BluetoothProfile.ServiceListener mServiceListener =
            new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            synchronized (this) {
                switch (profile) {
                    case BluetoothProfile.A2DP:
                        mA2dp = (BluetoothA2dp) proxy;
                        break;
                    case BluetoothProfile.HEADSET:
                        mHeadset = (BluetoothHeadset) proxy;
                        break;
                }
            }
        }

        public void onServiceDisconnected(int profile) {
            synchronized (this) {
                switch (profile) {
                    case BluetoothProfile.A2DP:
                        mA2dp = null;
                        break;
                    case BluetoothProfile.HEADSET:
                        mHeadset = null;
                        break;
                }
            }
        }
    };

    private List<BroadcastReceiver> mReceivers = new ArrayList<BroadcastReceiver>();

    private BufferedWriter mOutputWriter;
    private String mTag;
    private String mOutputFile;

    private Context mContext;
    private BluetoothA2dp mA2dp;
    private BluetoothHeadset mHeadset;

    public BluetoothTestUtils(Context context, String tag) {
        this(context, tag, null);
    }

    public BluetoothTestUtils(Context context, String tag, String outputFile) {
        mContext = context;
        mTag = tag;
        mOutputFile = outputFile;

        if (mOutputFile == null) {
            mOutputWriter = null;
        } else {
            try {
                mOutputWriter = new BufferedWriter(new FileWriter(new File(
                        Environment.getExternalStorageDirectory(), mOutputFile), true));
            } catch (IOException e) {
                Log.w(mTag, "Test output file could not be opened", e);
                mOutputWriter = null;
            }
        }
    }

    public void close() {
        while (!mReceivers.isEmpty()) {
            mContext.unregisterReceiver(mReceivers.remove(0));
        }

        if (mOutputWriter != null) {
            try {
                mOutputWriter.close();
            } catch (IOException e) {
                Log.w(mTag, "Test output file could not be closed", e);
            }
        }
    }

    public void enable(BluetoothAdapter adapter) {
        int mask = (BluetoothReceiver.STATE_TURNING_ON_FLAG | BluetoothReceiver.STATE_ON_FLAG
                | BluetoothReceiver.SCAN_MODE_CONNECTABLE_FLAG);
        long start = -1;
        BluetoothReceiver receiver = getBluetoothReceiver(mask);

        int state = adapter.getState();
        switch (state) {
            case BluetoothAdapter.STATE_ON:
                assertTrue(adapter.isEnabled());
                removeReceiver(receiver);
                return;
            case BluetoothAdapter.STATE_TURNING_ON:
                assertFalse(adapter.isEnabled());
                mask = 0; // Don't check for received intents since we might have missed them.
                break;
            case BluetoothAdapter.STATE_OFF:
                assertFalse(adapter.isEnabled());
                start = System.currentTimeMillis();
                assertTrue(adapter.enable());
                break;
            case BluetoothAdapter.STATE_TURNING_OFF:
                start = System.currentTimeMillis();
                assertTrue(adapter.enable());
                break;
            default:
                removeReceiver(receiver);
                fail(String.format("enable() invalid state: state=%d", state));
        }

        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < ENABLE_TIMEOUT) {
            state = adapter.getState();
            if (state == BluetoothAdapter.STATE_ON) {
                assertTrue(adapter.isEnabled());
                if ((receiver.getFiredFlags() & mask) == mask) {
                    long finish = receiver.getCompletedTime();
                    if (start != -1 && finish != -1) {
                        writeOutput(String.format("enable() completed in %d ms", (finish - start)));
                    } else {
                        writeOutput("enable() completed");
                    }
                    removeReceiver(receiver);
                    return;
                }
            } else {
                assertEquals(BluetoothAdapter.STATE_TURNING_ON, state);
            }
            sleep(POLL_TIME);
        }

        int firedFlags = receiver.getFiredFlags();
        removeReceiver(receiver);
        fail(String.format("enable() timeout: state=%d (expected %d), flags=0x%x (expected 0x%x)",
                state, BluetoothAdapter.STATE_ON, firedFlags, mask));
    }

    public void disable(BluetoothAdapter adapter) {
        int mask = (BluetoothReceiver.STATE_TURNING_OFF_FLAG | BluetoothReceiver.STATE_OFF_FLAG
                | BluetoothReceiver.SCAN_MODE_NONE_FLAG);
        long start = -1;
        BluetoothReceiver receiver = getBluetoothReceiver(mask);

        int state = adapter.getState();
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
                assertFalse(adapter.isEnabled());
                removeReceiver(receiver);
                return;
            case BluetoothAdapter.STATE_TURNING_ON:
                assertFalse(adapter.isEnabled());
                start = System.currentTimeMillis();
                break;
            case BluetoothAdapter.STATE_ON:
                assertTrue(adapter.isEnabled());
                start = System.currentTimeMillis();
                assertTrue(adapter.disable());
                break;
            case BluetoothAdapter.STATE_TURNING_OFF:
                assertFalse(adapter.isEnabled());
                mask = 0; // Don't check for received intents since we might have missed them.
                break;
            default:
                removeReceiver(receiver);
                fail(String.format("disable() invalid state: state=%d", state));
        }

        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < DISABLE_TIMEOUT) {
            state = adapter.getState();
            if (state == BluetoothAdapter.STATE_OFF) {
                assertFalse(adapter.isEnabled());
                if ((receiver.getFiredFlags() & mask) == mask) {
                    long finish = receiver.getCompletedTime();
                    if (start != -1 && finish != -1) {
                        writeOutput(String.format("disable() completed in %d ms",
                                (finish - start)));
                    } else {
                        writeOutput("disable() completed");
                    }
                    removeReceiver(receiver);
                    return;
                }
            } else {
                assertEquals(BluetoothAdapter.STATE_TURNING_OFF, state);
            }
            sleep(POLL_TIME);
        }

        int firedFlags = receiver.getFiredFlags();
        removeReceiver(receiver);
        fail(String.format("disable() timeout: state=%d (expected %d), flags=0x%x (expected 0x%x)",
                state, BluetoothAdapter.STATE_OFF, firedFlags, mask));
    }

    public void discoverable(BluetoothAdapter adapter) {
        int mask = BluetoothReceiver.SCAN_MODE_CONNECTABLE_DISCOVERABLE_FLAG;

        if (!adapter.isEnabled()) {
            fail("discoverable() bluetooth not enabled");
        }

        int scanMode = adapter.getScanMode();
        if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            return;
        }

        BluetoothReceiver receiver = getBluetoothReceiver(mask);

        assertEquals(BluetoothAdapter.SCAN_MODE_CONNECTABLE, scanMode);
        long start = System.currentTimeMillis();
        assertTrue(adapter.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE));

        while (System.currentTimeMillis() - start < SET_SCAN_MODE_TIMEOUT) {
            scanMode = adapter.getScanMode();
            if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                if ((receiver.getFiredFlags() & mask) == mask) {
                    writeOutput(String.format("discoverable() completed in %d ms",
                            (receiver.getCompletedTime() - start)));
                    removeReceiver(receiver);
                    return;
                }
            } else {
                assertEquals(BluetoothAdapter.SCAN_MODE_CONNECTABLE, scanMode);
            }
            sleep(POLL_TIME);
        }

        int firedFlags = receiver.getFiredFlags();
        removeReceiver(receiver);
        fail(String.format("discoverable() timeout: scanMode=%d (expected %d), flags=0x%x "
                + "(expected 0x%x)", scanMode, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE,
                firedFlags, mask));
    }

    public void undiscoverable(BluetoothAdapter adapter) {
        int mask = BluetoothReceiver.SCAN_MODE_CONNECTABLE_FLAG;

        if (!adapter.isEnabled()) {
            fail("undiscoverable() bluetooth not enabled");
        }

        int scanMode = adapter.getScanMode();
        if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE) {
            return;
        }

        BluetoothReceiver receiver = getBluetoothReceiver(mask);

        assertEquals(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, scanMode);
        long start = System.currentTimeMillis();
        assertTrue(adapter.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE));

        while (System.currentTimeMillis() - start < SET_SCAN_MODE_TIMEOUT) {
            scanMode = adapter.getScanMode();
            if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE) {
                if ((receiver.getFiredFlags() & mask) == mask) {
                    writeOutput(String.format("undiscoverable() completed in %d ms",
                            (receiver.getCompletedTime() - start)));
                    removeReceiver(receiver);
                    return;
                }
            } else {
                assertEquals(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, scanMode);
            }
            sleep(POLL_TIME);
        }

        int firedFlags = receiver.getFiredFlags();
        removeReceiver(receiver);
        fail(String.format("undiscoverable() timeout: scanMode=%d (expected %d), flags=0x%x "
                + "(expected 0x%x)", scanMode, BluetoothAdapter.SCAN_MODE_CONNECTABLE, firedFlags,
                mask));
    }

    public void startScan(BluetoothAdapter adapter) {
        int mask = BluetoothReceiver.DISCOVERY_STARTED_FLAG;

        if (!adapter.isEnabled()) {
            fail("startScan() bluetooth not enabled");
        }

        if (adapter.isDiscovering()) {
            return;
        }

        BluetoothReceiver receiver = getBluetoothReceiver(mask);

        long start = System.currentTimeMillis();
        assertTrue(adapter.startDiscovery());

        while (System.currentTimeMillis() - start < START_DISCOVERY_TIMEOUT) {
            if (adapter.isDiscovering() && ((receiver.getFiredFlags() & mask) == mask)) {
                writeOutput(String.format("startScan() completed in %d ms",
                        (receiver.getCompletedTime() - start)));
                removeReceiver(receiver);
                return;
            }
            sleep(POLL_TIME);
        }

        int firedFlags = receiver.getFiredFlags();
        removeReceiver(receiver);
        fail(String.format("startScan() timeout: isDiscovering=%b, flags=0x%x (expected 0x%x)",
                adapter.isDiscovering(), firedFlags, mask));
    }

    public void stopScan(BluetoothAdapter adapter) {
        int mask = BluetoothReceiver.DISCOVERY_FINISHED_FLAG;

        if (!adapter.isEnabled()) {
            fail("stopScan() bluetooth not enabled");
        }

        if (!adapter.isDiscovering()) {
            return;
        }

        BluetoothReceiver receiver = getBluetoothReceiver(mask);

        long start = System.currentTimeMillis();
        // TODO: put assertTrue() around cancelDiscovery() once it starts returning true.
        adapter.cancelDiscovery();

        while (System.currentTimeMillis() - start < CANCEL_DISCOVERY_TIMEOUT) {
            if (!adapter.isDiscovering() && ((receiver.getFiredFlags() & mask) == mask)) {
                writeOutput(String.format("stopScan() completed in %d ms",
                        (receiver.getCompletedTime() - start)));
                removeReceiver(receiver);
                return;
            }
            sleep(POLL_TIME);
        }

        int firedFlags = receiver.getFiredFlags();
        removeReceiver(receiver);
        fail(String.format("stopScan() timeout: isDiscovering=%b, flags=0x%x (expected 0x%x)",
                adapter.isDiscovering(), firedFlags, mask));

    }

    public void pair(BluetoothAdapter adapter, BluetoothDevice device, int passkey, byte[] pin) {
        pairOrAcceptPair(adapter, device, passkey, pin, true);
    }

    public void acceptPair(BluetoothAdapter adapter, BluetoothDevice device, int passkey,
            byte[] pin) {
        pairOrAcceptPair(adapter, device, passkey, pin, false);
    }

    private void pairOrAcceptPair(BluetoothAdapter adapter, BluetoothDevice device, int passkey,
            byte[] pin, boolean pair) {
        int mask = PairReceiver.STATE_BONDING_FLAG | PairReceiver.STATE_BONDED_FLAG;
        long start = -1;
        String methodName = pair ? "pair()" : "acceptPair()";

        if (!adapter.isEnabled()) {
            fail(methodName + " bluetooth not enabled");
        }

        PairReceiver receiver = getPairReceiver(device, passkey, pin, mask);

        int state = device.getBondState();
        switch (state) {
            case BluetoothDevice.BOND_NONE:
                assertFalse(adapter.getBondedDevices().contains(device));
                start = System.currentTimeMillis();
                if (pair) {
                    assertTrue(device.createBond());
                }
                break;
            case BluetoothDevice.BOND_BONDING:
                mask = 0; // Don't check for received intents since we might have missed them.
                break;
            case BluetoothDevice.BOND_BONDED:
                assertTrue(adapter.getBondedDevices().contains(device));
                return;
            default:
                removeReceiver(receiver);
                fail(String.format("%s invalid state: device=%s, state=%d", methodName, device,
                        state));
        }

        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < PAIR_TIMEOUT) {
            state = device.getBondState();
            if (state == BluetoothDevice.BOND_BONDED) {
                assertTrue(adapter.getBondedDevices().contains(device));
                if ((receiver.getFiredFlags() & mask) == mask) {
                    long finish = receiver.getCompletedTime();
                    if (start != -1 && finish != -1) {
                        writeOutput(String.format("%s completed in %d ms: device=%s", methodName,
                                (finish - start), device));
                    } else {
                        writeOutput(String.format("%s completed: device=%s", methodName, device));
                    }
                    removeReceiver(receiver);
                    return;
                }
            }
            sleep(POLL_TIME);
        }

        int firedFlags = receiver.getFiredFlags();
        removeReceiver(receiver);
        fail(String.format("%s timeout: device=%s, state=%d (expected %d), "
                + "flags=0x%x (expected 0x%x)", methodName, device, state,
                BluetoothDevice.BOND_BONDED, firedFlags, mask));
    }

    public void unpair(BluetoothAdapter adapter, BluetoothDevice device) {
        int mask = PairReceiver.STATE_NONE_FLAG;
        long start = -1;

        if (!adapter.isEnabled()) {
            fail("unpair() bluetooth not enabled");
        }

        PairReceiver receiver = getPairReceiver(device, 0, null, mask);

        int state = device.getBondState();
        switch (state) {
            case BluetoothDevice.BOND_NONE:
                assertFalse(adapter.getBondedDevices().contains(device));
                removeReceiver(receiver);
                return;
            case BluetoothDevice.BOND_BONDING:
                start = System.currentTimeMillis();
                assertTrue(device.removeBond());
                break;
            case BluetoothDevice.BOND_BONDED:
                assertTrue(adapter.getBondedDevices().contains(device));
                start = System.currentTimeMillis();
                assertTrue(device.removeBond());
                break;
            default:
                removeReceiver(receiver);
                fail(String.format("unpair() invalid state: device=%s, state=%d", device, state));
        }

        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < UNPAIR_TIMEOUT) {
            if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                assertFalse(adapter.getBondedDevices().contains(device));
                if ((receiver.getFiredFlags() & mask) == mask) {
                    long finish = receiver.getCompletedTime();
                    if (start != -1 && finish != -1) {
                        writeOutput(String.format("unpair() completed in %d ms: device=%s",
                                (finish - start), device));
                    } else {
                        writeOutput(String.format("unpair() completed: device=%s", device));
                    }
                    removeReceiver(receiver);
                    return;
                }
            }
        }

        int firedFlags = receiver.getFiredFlags();
        removeReceiver(receiver);
        fail(String.format("unpair() timeout: device=%s, state=%d (expected %d), "
                + "flags=0x%x (expected 0x%x)", device, state, BluetoothDevice.BOND_BONDED,
                firedFlags, mask));
    }

    public void connectProfile(BluetoothAdapter adapter, BluetoothDevice device, int profile) {
        int mask = (ConnectProfileReceiver.STATE_CONNECTING_FLAG
                | ConnectProfileReceiver.STATE_CONNECTED_FLAG);
        long start = -1;

        if (!adapter.isEnabled()) {
            fail(String.format("connectProfile() bluetooth not enabled: device=%s, profile=%d",
                    device, profile));
        }

        if (!adapter.getBondedDevices().contains(device)) {
            fail(String.format("connectProfile() device not paired: device=%s, profile=%d",
                    device, profile));
        }

        BluetoothProfile proxy = connectProxy(adapter, profile);
        if (proxy == null) {
            fail(String.format("connectProfile() unknown profile: device=%s, profile=%d",
                    device, profile));
        }

        ConnectProfileReceiver receiver = getConnectProfileReceiver(device, profile, mask);

        int state = proxy.getConnectionState(device);
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                removeReceiver(receiver);
                return;
            case BluetoothProfile.STATE_CONNECTING:
                mask = 0; // Don't check for received intents since we might have missed them.
                break;
            case BluetoothProfile.STATE_DISCONNECTED:
            case BluetoothProfile.STATE_DISCONNECTING:
                start = System.currentTimeMillis();
                assertTrue(proxy.connect(device));
                break;
            default:
                removeReceiver(receiver);
                fail(String.format("connectProfile() invalid state: device=%s, profile=%d, "
                        + "state=%d", device, profile, state));
        }

        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < CONNECT_PROFILE_TIMEOUT) {
            state = proxy.getConnectionState(device);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                if ((receiver.getFiredFlags() & mask) == mask) {
                    long finish = receiver.getCompletedTime();
                    if (start != -1 && finish != -1) {
                        writeOutput(String.format("connectProfile() completed in %d ms: "
                                + "device=%s, profile=%d", (finish - start), device, profile));
                    } else {
                        writeOutput(String.format("connectProfile() completed: device=%s, "
                                + "profile=%d", device, profile));
                    }
                    removeReceiver(receiver);
                    return;
                }
            }
            sleep(POLL_TIME);
        }

        int firedFlags = receiver.getFiredFlags();
        removeReceiver(receiver);
        fail(String.format("connectProfile() timeout: device=%s, profile=%s, "
                + "state=%d (expected %d), flags=0x%x (expected 0x%x)", device, profile, state,
                BluetoothProfile.STATE_CONNECTED, firedFlags, mask));
    }

    public void disconnectProfile(BluetoothAdapter adapter, BluetoothDevice device, int profile) {
        int mask = (ConnectProfileReceiver.STATE_DISCONNECTING_FLAG
                | ConnectProfileReceiver.STATE_DISCONNECTED_FLAG);
        long start = -1;

        if (!adapter.isEnabled()) {
            fail(String.format("disconnectProfile() bluetooth not enabled: device=%s, profile=%d",
                    device, profile));
        }

        if (!adapter.getBondedDevices().contains(device)) {
            fail(String.format("disconnectProfile() device not paired: device=%s, profile=%d",
                    device, profile));
        }

        BluetoothProfile proxy = connectProxy(adapter, profile);
        if (proxy == null) {
            fail(String.format("disconnectProfile() unknown profile: device=%s, profile=%d",
                    device, profile));
        }

        ConnectProfileReceiver receiver = getConnectProfileReceiver(device, profile, mask);

        int state = proxy.getConnectionState(device);
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
            case BluetoothProfile.STATE_CONNECTING:
                start = System.currentTimeMillis();
                assertTrue(proxy.disconnect(device));
                break;
            case BluetoothProfile.STATE_DISCONNECTED:
                removeReceiver(receiver);
                return;
            case BluetoothProfile.STATE_DISCONNECTING:
                mask = 0; // Don't check for received intents since we might have missed them.
                break;
            default:
                removeReceiver(receiver);
                fail(String.format("disconnectProfile() invalid state: device=%s, profile=%d, "
                        + "state=%d", device, profile, state));
        }

        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < DISCONNECT_PROFILE_TIMEOUT) {
            state = proxy.getConnectionState(device);
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if ((receiver.getFiredFlags() & mask) == mask) {
                    long finish = receiver.getCompletedTime();
                    if (start != -1 && finish != -1) {
                        writeOutput(String.format("disconnectProfile() completed in %d ms: "
                                + "device=%s, profile=%d", (finish - start), device, profile));
                    } else {
                        writeOutput(String.format("disconnectProfile() completed: device=%s, "
                                + "profile=%d", device, profile));
                    }
                    removeReceiver(receiver);
                    return;
                }
            }
            sleep(POLL_TIME);
        }

        int firedFlags = receiver.getFiredFlags();
        removeReceiver(receiver);
        fail(String.format("disconnectProfile() timeout: device=%s, profile=%s, "
                + "state=%d (expected %d), flags=0x%x (expected 0x%x)", device, profile, state,
                BluetoothProfile.STATE_DISCONNECTED, firedFlags, mask));
    }

    public void writeOutput(String s) {
        Log.i(mTag, s);
        if (mOutputWriter == null) {
            return;
        }
        try {
            mOutputWriter.write(s + "\n");
            mOutputWriter.flush();
        } catch (IOException e) {
            Log.w(mTag, "Could not write to output file", e);
        }
    }

    private BluetoothReceiver getBluetoothReceiver(int expectedFlags) {
        BluetoothReceiver receiver = new BluetoothReceiver(expectedFlags);
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiver(receiver, filter);
        mReceivers.add(receiver);
        return receiver;
    }

    private PairReceiver getPairReceiver(BluetoothDevice device, int passkey, byte[] pin,
            int expectedFlags) {
        PairReceiver receiver = new PairReceiver(device, passkey, pin, expectedFlags);
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mContext.registerReceiver(receiver, filter);
        mReceivers.add(receiver);
        return receiver;
    }

    private ConnectProfileReceiver getConnectProfileReceiver(BluetoothDevice device, int profile,
            int expectedFlags) {
        ConnectProfileReceiver receiver = new ConnectProfileReceiver(device, profile,
                expectedFlags);
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        mContext.registerReceiver(receiver, filter);
        mReceivers.add(receiver);
        return receiver;
    }

    private void removeReceiver(BroadcastReceiver receiver) {
        mContext.unregisterReceiver(receiver);
        mReceivers.remove(receiver);
    }

    private BluetoothProfile connectProxy(BluetoothAdapter adapter, int profile) {
        adapter.getProfileProxy(mContext, mServiceListener, profile);
        long s = System.currentTimeMillis();
        switch (profile) {
            case BluetoothProfile.A2DP:
                while (mA2dp != null && System.currentTimeMillis() - s < CONNECT_PROXY_TIMEOUT) {
                    sleep(POLL_TIME);
                }
                return mA2dp;
            case BluetoothProfile.HEADSET:
                while (mHeadset != null && System.currentTimeMillis() - s < CONNECT_PROXY_TIMEOUT) {
                    sleep(POLL_TIME);
                }
                return mHeadset;
            default:
                return null;
        }
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
        }
    }
}
