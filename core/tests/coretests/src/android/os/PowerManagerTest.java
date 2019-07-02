/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.os;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.support.test.uiautomator.UiDevice;
import android.test.AndroidTestCase;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class PowerManagerTest extends AndroidTestCase {

    private PowerManager mPm;
    private UiDevice mUiDevice;
    private Executor mExec = Executors.newSingleThreadExecutor();
    @Mock
    private PowerManager.OnThermalStatusChangedListener mListener1;
    @Mock
    private PowerManager.OnThermalStatusChangedListener mListener2;
    private static final long CALLBACK_TIMEOUT_MILLI_SEC = 5000;

    /**
     * Setup any common data for the upcoming tests.
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mPm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mUiDevice.executeShellCommand("cmd thermalservice override-status 0");
    }

    /**
     * Reset data for the upcoming tests.
     */
    @After
    public void tearDown() throws Exception {
        mUiDevice.executeShellCommand("cmd thermalservice reset");
    }

    /**
     * Confirm that the setup is good.
     *
     * @throws Exception
     */
    @SmallTest
    public void testPreconditions() throws Exception {
        assertNotNull(mPm);
    }

    /**
     * Confirm that we can create functional wakelocks.
     *
     * @throws Exception
     */
    @SmallTest
    public void testNewWakeLock() throws Exception {
        PowerManager.WakeLock wl = mPm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "FULL_WAKE_LOCK");
        doTestWakeLock(wl);

        wl = mPm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "SCREEN_BRIGHT_WAKE_LOCK");
        doTestWakeLock(wl);

        wl = mPm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "SCREEN_DIM_WAKE_LOCK");
        doTestWakeLock(wl);

        wl = mPm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PARTIAL_WAKE_LOCK");
        doTestWakeLock(wl);

        // TODO: Some sort of functional test (maybe not in the unit test here?)
        // that confirms that things are really happening e.g. screen power, keyboard power.
    }

    /**
     * Confirm that we can't create dysfunctional wakelocks.
     *
     * @throws Exception
     */
    @SmallTest
    public void testBadNewWakeLock() throws Exception {
        final int badFlags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.SCREEN_DIM_WAKE_LOCK;
        // wrap in try because we want the error here
        try {
            PowerManager.WakeLock wl = mPm.newWakeLock(badFlags, "foo");
        } catch (IllegalArgumentException e) {
            return;
        }
        fail("Bad WakeLock flag was not caught.");
    }

    /**
     * Ensure that we can have work sources with work chains when uid is not set directly on work
     * source, and that this doesn't crash system server.
     *
     * @throws Exception
     */
    @SmallTest
    public void testWakeLockWithWorkChains() throws Exception {
        PowerManager.WakeLock wakeLock = mPm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "TEST_LOCK");
        WorkSource workSource = new WorkSource();
        WorkSource.WorkChain workChain = workSource.createWorkChain();
        workChain.addNode(1000, "test");
        wakeLock.setWorkSource(workSource);

        doTestWakeLock(wakeLock);
    }

    /**
     * Apply a few tests to a wakelock to make sure it's healthy.
     *
     * @param wl The wakelock to be tested.
     */
    private void doTestWakeLock(PowerManager.WakeLock wl) {
        // First try simple acquire/release
        wl.acquire();
        assertTrue(wl.isHeld());
        wl.release();
        assertFalse(wl.isHeld());

        // Try ref-counted acquire/release
        wl.setReferenceCounted(true);
        wl.acquire();
        assertTrue(wl.isHeld());
        wl.acquire();
        assertTrue(wl.isHeld());
        wl.release();
        assertTrue(wl.isHeld());
        wl.release();
        assertFalse(wl.isHeld());

        // Try non-ref-counted
        wl.setReferenceCounted(false);
        wl.acquire();
        assertTrue(wl.isHeld());
        wl.acquire();
        assertTrue(wl.isHeld());
        wl.release();
        assertFalse(wl.isHeld());

        // TODO: Threaded test (needs handler) to make sure timed wakelocks work too
    }

    /**
     * Confirm that we can get thermal status.
     *
     * @throws Exception
     */
    @Test
    public void testGetThermalStatus() throws Exception {
        int status = 0;
        assertEquals(status, mPm.getCurrentThermalStatus());
        status = 3;
        mUiDevice.executeShellCommand("cmd thermalservice override-status "
                + Integer.toString(status));
        assertEquals(status, mPm.getCurrentThermalStatus());
    }

    /**
     * Confirm that we can add/remove thermal status listener.
     *
     * @throws Exception
     */
    @Test
    public void testThermalStatusCallback() throws Exception {
        // Initial override status is THERMAL_STATUS_NONE
        int status = PowerManager.THERMAL_STATUS_NONE;
        // Add listener1
        mPm.addThermalStatusListener(mExec, mListener1);
        verify(mListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onThermalStatusChanged(status);
        reset(mListener1);
        status = PowerManager.THERMAL_STATUS_SEVERE;
        mUiDevice.executeShellCommand("cmd thermalservice override-status "
                + Integer.toString(status));
        verify(mListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onThermalStatusChanged(status);
        reset(mListener1);
        // Add listener1 again
        try {
            mPm.addThermalStatusListener(mListener1);
            fail("Expected exception not thrown");
        } catch (IllegalArgumentException expectedException) {
        }
        // Add listener2 on main thread.
        mPm.addThermalStatusListener(mListener2);
        status = PowerManager.THERMAL_STATUS_MODERATE;
        mUiDevice.executeShellCommand("cmd thermalservice override-status "
                + Integer.toString(status));
        verify(mListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onThermalStatusChanged(status);
        verify(mListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onThermalStatusChanged(status);
        reset(mListener1);
        reset(mListener2);
        // Remove listener1
        mPm.removeThermalStatusListener(mListener1);
        // Remove listener1 again
        try {
            mPm.removeThermalStatusListener(mListener1);
            fail("Expected exception not thrown");
        } catch (IllegalArgumentException expectedException) {
        }
        status = PowerManager.THERMAL_STATUS_LIGHT;
        mUiDevice.executeShellCommand("cmd thermalservice override-status "
                + Integer.toString(status));
        verify(mListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).onThermalStatusChanged(status);
        verify(mListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onThermalStatusChanged(status);
    }
}
