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

import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.test.InstrumentationTestCase;
import android.util.Log;

public class BluetoothStressTest extends InstrumentationTestCase {
    private static final String TAG = "BluetoothEnablerStressTest";

    /**
     * Timeout for {@link BluetoothAdapter#disable()} in ms.
     */
    private static final int DISABLE_TIMEOUT = 5000;

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

    private static final int DISCOVERY_STARTED_FLAG = 1;
    private static final int DISCOVERY_FINISHED_FLAG = 1 << 1;
    private static final int SCAN_MODE_NONE_FLAG = 1 << 2;
    private static final int SCAN_MODE_CONNECTABLE_FLAG = 1 << 3;
    private static final int SCAN_MODE_CONNECTABLE_DISCOVERABLE_FLAG = 1 << 4;
    private static final int STATE_OFF_FLAG = 1 << 5;
    private static final int STATE_TURNING_ON_FLAG = 1 << 6;
    private static final int STATE_ON_FLAG = 1 << 7;
    private static final int STATE_TURNING_OFF_FLAG = 1 << 8;

    /**
     * Time between polls in ms.
     */
    private static final int POLL_TIME = 100;

    private static final int ENABLE_ITERATIONS = 100;
    private static final int DISCOVERABLE_ITERATIONS = 1000;
    private static final int SCAN_ITERATIONS = 1000;

    private Context mContext;

    private Instrumentation mInstrumentation;

    private class BluetoothReceiver extends BroadcastReceiver {
        private int mFiredFlags = 0;

        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (this) {
                if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(intent.getAction())) {
                    mFiredFlags |= DISCOVERY_STARTED_FLAG;
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                    mFiredFlags |= DISCOVERY_FINISHED_FLAG;
                } else if (BluetoothAdapter.ACTION_SCAN_MODE_CHANGED.equals(intent.getAction())) {
                    int mode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE,
                            BluetoothAdapter.ERROR);
                    assertNotSame(mode, BluetoothAdapter.ERROR);
                    switch (mode) {
                        case BluetoothAdapter.SCAN_MODE_NONE:
                            mFiredFlags |= SCAN_MODE_NONE_FLAG;
                            break;
                        case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
                            mFiredFlags |= SCAN_MODE_CONNECTABLE_FLAG;
                            break;
                        case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                            mFiredFlags |= SCAN_MODE_CONNECTABLE_DISCOVERABLE_FLAG;
                            break;
                    }
                } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR);
                    assertNotSame(state, BluetoothAdapter.ERROR);
                    switch (state) {
                        case BluetoothAdapter.STATE_OFF:
                            mFiredFlags |= STATE_OFF_FLAG;
                            break;
                        case BluetoothAdapter.STATE_TURNING_ON:
                            mFiredFlags |= STATE_TURNING_ON_FLAG;
                            break;
                        case BluetoothAdapter.STATE_ON:
                            mFiredFlags |= STATE_ON_FLAG;
                            break;
                        case BluetoothAdapter.STATE_TURNING_OFF:
                            mFiredFlags |= STATE_TURNING_OFF_FLAG;
                            break;
                    }
                }
            }
        }

        public int getFiredFlags() {
            synchronized (this) {
                return mFiredFlags;
            }
        }

        public void resetFiredFlags() {
            synchronized (this) {
                mFiredFlags = 0;
            }
        }
    }

    private BluetoothReceiver mReceiver = new BluetoothReceiver();

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mInstrumentation = getInstrumentation();
        mContext = mInstrumentation.getTargetContext();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        mContext.unregisterReceiver(mReceiver);
    }

    public void testEnableDisable() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        for (int i = 0; i < ENABLE_ITERATIONS; i++) {
            Log.i(TAG, "Enable iteration " + (i + 1) + " of " + ENABLE_ITERATIONS);
            enable(adapter);
            disable(adapter);
        }
    }

    public void testDiscoverable() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        enable(adapter);

        for (int i = 0; i < DISCOVERABLE_ITERATIONS; i++) {
            Log.i(TAG, "Discoverable iteration " + (i + 1) + " of " + DISCOVERABLE_ITERATIONS);
            discoverable(adapter);
            undiscoverable(adapter);
        }

        disable(adapter);
    }

    public void testScan() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        enable(adapter);

        for (int i = 0; i < SCAN_ITERATIONS; i++) {
            Log.i(TAG, "Scan iteration " + (i + 1) + " of " + SCAN_ITERATIONS);
            startScan(adapter);
            stopScan(adapter);
        }

        disable(adapter);
    }

    private void disable(BluetoothAdapter adapter) {
        int mask = STATE_TURNING_OFF_FLAG | STATE_OFF_FLAG | SCAN_MODE_NONE_FLAG;
        mReceiver.resetFiredFlags();

        int state = adapter.getState();
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
                assertFalse(adapter.isEnabled());
                return;
            case BluetoothAdapter.STATE_ON:
                assertTrue(adapter.isEnabled());
                assertTrue(adapter.disable());
                break;
            case BluetoothAdapter.STATE_TURNING_ON:
                assertFalse(adapter.isEnabled());
                assertTrue(adapter.disable());
                break;
            case BluetoothAdapter.STATE_TURNING_OFF:
                assertFalse(adapter.isEnabled());
                mask = 0; // Don't check for received intents since we might have missed them.
                break;
            default:
                fail("disable() invalid state: " + state);
        }

        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < DISABLE_TIMEOUT) {
            state = adapter.getState();
            if (state == BluetoothAdapter.STATE_OFF) {
                assertFalse(adapter.isEnabled());
                if ((mReceiver.getFiredFlags() & mask) == mask) {
                    mReceiver.resetFiredFlags();
                    return;
                }
            } else {
                assertFalse(adapter.isEnabled());
                assertEquals(BluetoothAdapter.STATE_TURNING_OFF, state);
            }
            sleep(POLL_TIME);
        }

        int firedFlags = mReceiver.getFiredFlags();
        mReceiver.resetFiredFlags();
        fail("disable() timeout: " +
                "state=" + state + " (expected " + BluetoothAdapter.STATE_OFF + ") " +
                "flags=" + firedFlags + " (expected " + mask + ")");
    }

    private void enable(BluetoothAdapter adapter) {
        int mask = STATE_TURNING_ON_FLAG | STATE_ON_FLAG | SCAN_MODE_CONNECTABLE_FLAG;
        mReceiver.resetFiredFlags();

        int state = adapter.getState();
        switch (state) {
            case BluetoothAdapter.STATE_ON:
                assertTrue(adapter.isEnabled());
                return;
            case BluetoothAdapter.STATE_OFF:
            case BluetoothAdapter.STATE_TURNING_OFF:
                assertFalse(adapter.isEnabled());
                assertTrue(adapter.enable());
                break;
            case BluetoothAdapter.STATE_TURNING_ON:
                assertFalse(adapter.isEnabled());
                mask = 0; // Don't check for received intents since we might have missed them.
                break;
            default:
                fail("enable() invalid state: state=" + state);
        }

        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < ENABLE_TIMEOUT) {
            state = adapter.getState();
            if (state == BluetoothAdapter.STATE_ON) {
                assertTrue(adapter.isEnabled());
                if ((mReceiver.getFiredFlags() & mask) == mask) {
                    mReceiver.resetFiredFlags();
                    return;
                }
            } else {
                assertFalse(adapter.isEnabled());
                assertEquals(BluetoothAdapter.STATE_TURNING_ON, state);
            }
            sleep(POLL_TIME);
        }

        int firedFlags = mReceiver.getFiredFlags();
        mReceiver.resetFiredFlags();
        fail("enable() timeout: " +
                "state=" + state + " (expected " + BluetoothAdapter.STATE_OFF + ") " +
                "flags=" + firedFlags + " (expected " + mask + ")");
    }

    private void discoverable(BluetoothAdapter adapter) {
        int mask = SCAN_MODE_CONNECTABLE_DISCOVERABLE_FLAG;
        mReceiver.resetFiredFlags();

        if (!adapter.isEnabled()) {
            fail("discoverable() bluetooth not enabled");
        }

        int scanMode = adapter.getScanMode();
        if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            return;
        }

        assertEquals(scanMode, BluetoothAdapter.SCAN_MODE_CONNECTABLE);
        assertTrue(adapter.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE));

        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < SET_SCAN_MODE_TIMEOUT) {
            scanMode = adapter.getScanMode();
            if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                if ((mReceiver.getFiredFlags() & mask) == mask) {
                    mReceiver.resetFiredFlags();
                    return;
                }
            } else {
                assertEquals(scanMode, BluetoothAdapter.SCAN_MODE_CONNECTABLE);
            }
            sleep(POLL_TIME);
        }

        int firedFlags = mReceiver.getFiredFlags();
        mReceiver.resetFiredFlags();
        fail("discoverable() timeout: " +
                "scanMode=" + scanMode + " (expected " +
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE + ") " +
                "flags=" + firedFlags + " (expected " + mask + ")");
    }

    private void undiscoverable(BluetoothAdapter adapter) {
        int mask = SCAN_MODE_CONNECTABLE_FLAG;
        mReceiver.resetFiredFlags();

        if (!adapter.isEnabled()) {
            fail("undiscoverable() bluetooth not enabled");
        }

        int scanMode = adapter.getScanMode();
        if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE) {
            return;
        }

        assertEquals(scanMode, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
        assertTrue(adapter.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE));

        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < SET_SCAN_MODE_TIMEOUT) {
            scanMode = adapter.getScanMode();
            if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE) {
                if ((mReceiver.getFiredFlags() & mask) == mask) {
                    mReceiver.resetFiredFlags();
                    return;
                }
            } else {
                assertEquals(scanMode, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
            }
            sleep(POLL_TIME);
        }

        int firedFlags = mReceiver.getFiredFlags();
        mReceiver.resetFiredFlags();
        fail("undiscoverable() timeout: " +
                "scanMode=" + scanMode + " (expected " +
                BluetoothAdapter.SCAN_MODE_CONNECTABLE + ") " +
                "flags=" + firedFlags + " (expected " + mask + ")");
    }

    private void startScan(BluetoothAdapter adapter) {
        int mask = DISCOVERY_STARTED_FLAG;
        mReceiver.resetFiredFlags();

        if (!adapter.isEnabled()) {
            fail("startScan() bluetooth not enabled");
        }

        if (adapter.isDiscovering()) {
            return;
        }

        assertTrue(adapter.startDiscovery());

        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < START_DISCOVERY_TIMEOUT) {
            if (adapter.isDiscovering() && ((mReceiver.getFiredFlags() & mask) == mask)) {
                mReceiver.resetFiredFlags();
                return;
            }
            sleep(POLL_TIME);
        }

        int firedFlags = mReceiver.getFiredFlags();
        mReceiver.resetFiredFlags();
        fail("startScan() timeout: " +
                "isDiscovering=" + adapter.isDiscovering() + " " +
                "flags=" + firedFlags + " (expected " + mask + ")");
    }

    private void stopScan(BluetoothAdapter adapter) {
        int mask = DISCOVERY_FINISHED_FLAG;
        mReceiver.resetFiredFlags();

        if (!adapter.isEnabled()) {
            fail("stopScan() bluetooth not enabled");
        }

        if (!adapter.isDiscovering()) {
            return;
        }

        // TODO: put assertTrue() around cancelDiscovery() once it starts
        // returning true.
        adapter.cancelDiscovery();

        long s = System.currentTimeMillis();
        while (System.currentTimeMillis() - s < CANCEL_DISCOVERY_TIMEOUT) {
            if (!adapter.isDiscovering() && ((mReceiver.getFiredFlags() & mask) == mask)) {
                mReceiver.resetFiredFlags();
                return;
            }
            sleep(POLL_TIME);
        }

        int firedFlags = mReceiver.getFiredFlags();
        mReceiver.resetFiredFlags();
        fail("stopScan() timeout: " +
                "isDiscovering=" + adapter.isDiscovering() + " " +
                "flags=" + firedFlags + " (expected " + mask + ")");
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
        }
    }
}
