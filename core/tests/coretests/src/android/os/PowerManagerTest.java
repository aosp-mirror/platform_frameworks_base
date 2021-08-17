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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
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

    private static final String TAG = "PowerManagerTest";
    private PowerManager mPm;
    private UiDevice mUiDevice;
    private Executor mExec = Executors.newSingleThreadExecutor();
    @Mock
    private PowerManager.OnThermalStatusChangedListener mListener1;
    @Mock
    private PowerManager.OnThermalStatusChangedListener mListener2;
    private static final long CALLBACK_TIMEOUT_MILLI_SEC = 5000;
    private native Parcel nativeObtainWorkSourceParcel(int[] uids, String[] names);
    private native void nativeUnparcelAndVerifyWorkSource(Parcel parcel, int[] uids,
            String[] names);
    private native Parcel nativeObtainPowerSaveStateParcel(boolean batterySaverEnabled,
            boolean globalBatterySaverEnabled, int locationMode, int soundTriggerMode,
            float brightnessFactor);
    private native void nativeUnparcelAndVerifyPowerSaveState(Parcel parcel,
            boolean batterySaverEnabled, boolean globalBatterySaverEnabled,
            int locationMode, int soundTriggerMode, float brightnessFactor);
    private native Parcel nativeObtainBSPConfigParcel(BatterySaverPolicyConfig bs,
            String[] keys, String[] values);
    private native void nativeUnparcelAndVerifyBSPConfig(Parcel parcel, BatterySaverPolicyConfig bs,
            String[] keys, String[] values);

    static {
        System.loadLibrary("powermanagertest_jni");
    }

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
        verify(mListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
            .times(1)).onThermalStatusChanged(status);
        reset(mListener2);
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

    @Test
    public void testGetThermalHeadroom() throws Exception {
        float headroom = mPm.getThermalHeadroom(0);
        // If the device doesn't support thermal headroom, return early
        if (Float.isNaN(headroom)) {
            return;
        }
        assertTrue("Expected non-negative headroom", headroom >= 0.0f);
        assertTrue("Expected reasonably small headroom", headroom < 10.0f);

        // Call again immediately to ensure rate limiting works
        headroom = mPm.getThermalHeadroom(0);
        assertTrue("Expected NaN because of rate limiting", Float.isNaN(headroom));

        // Sleep for a second before attempting to call again so as to not get rate limited
        Thread.sleep(1000);
        headroom = mPm.getThermalHeadroom(5);
        assertFalse("Expected data to still be available", Float.isNaN(headroom));
        assertTrue("Expected non-negative headroom", headroom >= 0.0f);
        assertTrue("Expected reasonably small headroom", headroom < 10.0f);
    }

    @Test
    public void testUserspaceRebootNotSupported_throwsUnsupportedOperationException() {
        // Can't use assumption framework with AndroidTestCase :(
        if (mPm.isRebootingUserspaceSupported()) {
            return;
        }
        try {
            mPm.reboot(PowerManager.REBOOT_USERSPACE);
            fail("UnsupportedOperationException not thrown");
        } catch (UnsupportedOperationException expected) {
        }
    }

    /**
     * Helper function to obtain a WorkSource object as parcel from native, with
     * specified uids and names and verify the WorkSource object created from the parcel.
     */
    private void unparcelWorkSourceFromNativeAndVerify(int[] uids, String[] names) {
        // Obtain WorkSource as parcel from native, with uids and names.
        Parcel wsParcel = nativeObtainWorkSourceParcel(uids, names);
        WorkSource ws = WorkSource.CREATOR.createFromParcel(wsParcel);
        if (uids == null)  {
            assertEquals(ws.size(), 0);
        } else {
            assertEquals(uids.length, ws.size());
            for (int i = 0; i < ws.size(); i++) {
                assertEquals(ws.getUid(i), uids[i]);
            }
        }
        if (names != null)  {
            for (int i = 0; i < names.length; i++) {
                assertEquals(ws.getName(i), names[i]);
            }
        }
    }

    /**
     * Helper function to send a WorkSource as parcel from java to native.
     * Native will verify the WorkSource in native is expected.
     */
    private void parcelWorkSourceToNativeAndVerify(int[] uids, String[] names) {
        WorkSource ws = new WorkSource();
        if (uids != null) {
            if (names == null) {
                for (int i = 0; i < uids.length; i++) {
                    ws.add(uids[i]);
                }
            } else {
                assertEquals(uids.length, names.length);
                for (int i = 0; i < uids.length; i++) {
                    ws.add(uids[i], names[i]);
                }
            }
        }
        Parcel wsParcel = Parcel.obtain();
        ws.writeToParcel(wsParcel, 0 /* flags */);
        wsParcel.setDataPosition(0);
        //Set the WorkSource as parcel to native and verify.
        nativeUnparcelAndVerifyWorkSource(wsParcel, uids, names);
    }

    /**
     * Helper function to obtain a PowerSaveState as parcel from native, with
     * specified parameters, and verify the PowerSaveState object created from the parcel.
     */
    private void unparcelPowerSaveStateFromNativeAndVerify(boolean batterySaverEnabled,
            boolean globalBatterySaverEnabled, int locationMode, int soundTriggerMode,
            float brightnessFactor) {
        // Obtain PowerSaveState as parcel from native, with parameters.
        Parcel psParcel = nativeObtainPowerSaveStateParcel(batterySaverEnabled,
                 globalBatterySaverEnabled, locationMode, soundTriggerMode, brightnessFactor);
        // Verify the parcel.
        PowerSaveState ps = PowerSaveState.CREATOR.createFromParcel(psParcel);
        assertEquals(ps.batterySaverEnabled, batterySaverEnabled);
        assertEquals(ps.globalBatterySaverEnabled, globalBatterySaverEnabled);
        assertEquals(ps.locationMode, locationMode);
        assertEquals(ps.soundTriggerMode, soundTriggerMode);
        assertEquals(ps.brightnessFactor, brightnessFactor, 0.01f);
    }

    /**
     * Helper function to send a PowerSaveState as parcel to native, with
     * specified parameters. Native will verify the PowerSaveState in native is expected.
     */
    private void parcelPowerSaveStateToNativeAndVerify(boolean batterySaverEnabled,
            boolean globalBatterySaverEnabled, int locationMode, int soundTriggerMode,
            float brightnessFactor) {
        Parcel psParcel = Parcel.obtain();
        // PowerSaveState API blocks Builder.build(), generate a parcel instead of object.
        PowerSaveState ps = new PowerSaveState.Builder()
                .setBatterySaverEnabled(batterySaverEnabled)
                .setGlobalBatterySaverEnabled(globalBatterySaverEnabled)
                .setLocationMode(locationMode)
                .setBrightnessFactor(brightnessFactor).build();
        ps.writeToParcel(psParcel, 0 /* flags */);
        psParcel.setDataPosition(0);
        //Set the PowerSaveState as parcel to native and verify in native space.
        nativeUnparcelAndVerifyPowerSaveState(psParcel, batterySaverEnabled,
                globalBatterySaverEnabled, locationMode, soundTriggerMode, brightnessFactor);
    }

    /**
     * Helper function to obtain a BatterySaverPolicyConfig as parcel from native, with
     * specified parameters, and verify the BatterySaverPolicyConfig object created from the parcel.
     */
    private void unparcelBatterySaverPolicyFromNativeAndVerify(BatterySaverPolicyConfig bsIn) {
        // Obtain BatterySaverPolicyConfig as parcel from native, with parameters.
        String[] keys = bsIn.getDeviceSpecificSettings().keySet().toArray(
                    new String[bsIn.getDeviceSpecificSettings().keySet().size()]);
        String[] values = bsIn.getDeviceSpecificSettings().values().toArray(
                    new String[bsIn.getDeviceSpecificSettings().values().size()]);
        Parcel bsParcel = nativeObtainBSPConfigParcel(bsIn, keys, values);
        BatterySaverPolicyConfig bsOut =
                BatterySaverPolicyConfig.CREATOR.createFromParcel(bsParcel);
        assertEquals(bsIn.toString(), bsOut.toString());
    }

    /**
     * Helper function to send a BatterySaverPolicyConfig as parcel to native, with
     * specified parameters.
     * Native will verify BatterySaverPolicyConfig from native is expected.
     */
    private void parcelBatterySaverPolicyConfigToNativeAndVerify(BatterySaverPolicyConfig bsIn) {
        Parcel bsParcel = Parcel.obtain();
        bsIn.writeToParcel(bsParcel, 0 /* flags */);
        bsParcel.setDataPosition(0);
        // Set the BatterySaverPolicyConfig as parcel to native.
        String[] keys = bsIn.getDeviceSpecificSettings().keySet().toArray(
                    new String[bsIn.getDeviceSpecificSettings().keySet().size()]);
        String[] values = bsIn.getDeviceSpecificSettings().values().toArray(
                    new String[bsIn.getDeviceSpecificSettings().values().size()]);
        // Set the BatterySaverPolicyConfig as parcel to native and verify in native space.
        nativeUnparcelAndVerifyBSPConfig(bsParcel, bsIn, keys, values);
    }

    /**
     * Confirm that we can pass WorkSource from native to Java.
     *
     * @throws Exception
     */
    @Test
    public void testWorkSourceNativeToJava() {
        final int[] uids1 = {1000};
        final int[] uids2 = {1000, 2000};
        final String[] names1 = {"testWorkSource1"};
        final String[] names2 = {"testWorkSource1", "testWorkSource2"};
        unparcelWorkSourceFromNativeAndVerify(null /* uids */, null /* names */);
        unparcelWorkSourceFromNativeAndVerify(uids1, null /* names */);
        unparcelWorkSourceFromNativeAndVerify(uids2, null /* names */);
        unparcelWorkSourceFromNativeAndVerify(null /* uids */, names1);
        unparcelWorkSourceFromNativeAndVerify(uids1, names1);
        unparcelWorkSourceFromNativeAndVerify(uids2, names2);
    }

    /**
     * Confirm that we can pass WorkSource from Java to native.
     *
     * @throws Exception
     */
    @Test
    public void testWorkSourceJavaToNative() {
        final int[] uids1 = {1000};
        final int[] uids2 = {1000, 2000};
        final String[] names1 = {"testGetWorkSource1"};
        final String[] names2 = {"testGetWorkSource1", "testGetWorkSource2"};
        parcelWorkSourceToNativeAndVerify(null /* uids */, null /* names */);
        parcelWorkSourceToNativeAndVerify(uids1, null /* names */);
        parcelWorkSourceToNativeAndVerify(uids2, null /* names */);
        parcelWorkSourceToNativeAndVerify(uids1, names1);
        parcelWorkSourceToNativeAndVerify(uids2, names2);
    }

    /**
     * Confirm that we can pass PowerSaveState from native to Java.
     *
     * @throws Exception
     */
    @Test
    public void testPowerSaveStateNativeToJava() {
        unparcelPowerSaveStateFromNativeAndVerify(false /* batterySaverEnabled */,
                false /* globalBatterySaverEnabled */,
                PowerManager.LOCATION_MODE_FOREGROUND_ONLY,
                PowerManager.SOUND_TRIGGER_MODE_CRITICAL_ONLY,
                0.3f /* brightnessFactor */);
        unparcelPowerSaveStateFromNativeAndVerify(true /* batterySaverEnabled */,
                true  /* globalBatterySaverEnabled */,
                PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
                PowerManager.SOUND_TRIGGER_MODE_ALL_DISABLED,
                0.5f /* brightnessFactor */);
    }

    /**
     * Confirm that we can pass PowerSaveState from Java to native.
     *
     * @throws Exception
     */
    @Test
    public void testSetPowerSaveStateJavaToNative() {
        parcelPowerSaveStateToNativeAndVerify(false /* batterySaverEnabled */,
                false /* globalBatterySaverEnabled */,
                PowerManager.LOCATION_MODE_FOREGROUND_ONLY,
                PowerManager.SOUND_TRIGGER_MODE_CRITICAL_ONLY,
                0.3f /* brightnessFactor */);
        parcelPowerSaveStateToNativeAndVerify(true /* batterySaverEnabled */,
                true  /* globalBatterySaverEnabled */,
                PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
                PowerManager.SOUND_TRIGGER_MODE_ALL_DISABLED,
                0.5f /* brightnessFactor */);
    }

    /**
     * Confirm that we can pass BatterySaverPolicyConfig from native to Java.
     *
     * @throws Exception
     */
    @Test
    public void testBatterySaverPolicyConfigNativeToJava() {
        BatterySaverPolicyConfig bs1 = new BatterySaverPolicyConfig.Builder()
                .setAdjustBrightnessFactor(0.55f)
                .setAdvertiseIsEnabled(true)
                .setDeferFullBackup(true)
                .setForceBackgroundCheck(true)
                .setLocationMode(PowerManager.LOCATION_MODE_FOREGROUND_ONLY).build();
        BatterySaverPolicyConfig bs2 = new BatterySaverPolicyConfig.Builder()
                .setAdjustBrightnessFactor(0.55f)
                .setAdvertiseIsEnabled(true)
                .setLocationMode(PowerManager.LOCATION_MODE_FOREGROUND_ONLY)
                .addDeviceSpecificSetting("Key1" /* key */, "Value1" /* value */)
                .addDeviceSpecificSetting("Key2" /* key */, "Value2" /* value */)
                .setDeferFullBackup(true).build();

        unparcelBatterySaverPolicyFromNativeAndVerify(bs1);
        unparcelBatterySaverPolicyFromNativeAndVerify(bs2);
    }

    /**
     * Confirm that we can pass BatterySaverPolicyConfig from Java to native.
     *
     * @throws Exception
     */
    @Test
    public void testBatterySaverPolicyConfigFromJavaToNative() {
        BatterySaverPolicyConfig bs1 = new BatterySaverPolicyConfig.Builder()
                .setAdjustBrightnessFactor(0.55f)
                .setAdvertiseIsEnabled(true)
                .setDeferFullBackup(true).build();
        BatterySaverPolicyConfig bs2 = new BatterySaverPolicyConfig.Builder()
                .setAdjustBrightnessFactor(0.55f)
                .setAdvertiseIsEnabled(true)
                .addDeviceSpecificSetting("Key1" /* key */, "Value1" /* value */)
                .addDeviceSpecificSetting("Key2" /* key */, "Value2" /* value */)
                .setDeferFullBackup(true).build();
        parcelBatterySaverPolicyConfigToNativeAndVerify(bs1);
        parcelBatterySaverPolicyConfigToNativeAndVerify(bs2);
    }

}
