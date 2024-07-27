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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.RavenwoodFlagsValueProvider;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.uiautomator.UiDevice;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = PowerManager.class)
public class PowerManagerTest {

    private static final String TAG = "PowerManagerTest";
    private Context mContext;
    private PowerManager mPm;
    private UiDevice mUiDevice;
    private Executor mExec = Executors.newSingleThreadExecutor();
    @Mock
    private PowerManager.OnThermalStatusChangedListener mListener1;
    @Mock
    private PowerManager.OnThermalStatusChangedListener mListener2;
    private static final long CALLBACK_TIMEOUT_MILLI_SEC = 5000;
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
        if (!RavenwoodRule.isUnderRavenwood()) {
            System.loadLibrary("powermanagertest_jni");
        }
    }

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    // Required for RequiresFlagsEnabled and RequiresFlagsDisabled annotations to take effect.
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = RavenwoodRule.isOnRavenwood()
            ? RavenwoodFlagsValueProvider.createAllOnCheckFlagsRule()
            : DeviceFlagsValueProvider.createCheckFlagsRule();

    /**
     * Setup any common data for the upcoming tests.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
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
    @Test
    @SmallTest
    public void testPreconditions() throws Exception {
        assertNotNull(mPm);
    }

    /**
     * Confirm that we can create functional wakelocks.
     *
     * @throws Exception
     */
    @Test
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
    @Test
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
    @Test
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

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_BATTERY_SAVER_SUPPORTED_CHECK_API)
    public void testBatterySaverSupported_isSupported() throws RemoteException {
        IPowerManager powerManager = mock(IPowerManager.class);
        PowerManager pm = new PowerManager(mContext, powerManager,
                mock(IThermalService.class),
                Handler.createAsync(Looper.getMainLooper()));
        when(powerManager.isBatterySaverSupported()).thenReturn(true);

        assertTrue(pm.isBatterySaverSupported());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_BATTERY_SAVER_SUPPORTED_CHECK_API)
    public void testBatterySaverSupported_isNotSupported() throws RemoteException {
        IPowerManager powerManager = mock(IPowerManager.class);
        PowerManager pm = new PowerManager(mContext, powerManager,
                mock(IThermalService.class),
                Handler.createAsync(Looper.getMainLooper()));
        when(powerManager.isBatterySaverSupported()).thenReturn(false);

        assertFalse(pm.isBatterySaverSupported());
    }
}
