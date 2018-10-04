/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.DeviceIdleController.LIGHT_STATE_ACTIVE;
import static com.android.server.DeviceIdleController.LIGHT_STATE_IDLE;
import static com.android.server.DeviceIdleController.LIGHT_STATE_IDLE_MAINTENANCE;
import static com.android.server.DeviceIdleController.LIGHT_STATE_INACTIVE;
import static com.android.server.DeviceIdleController.LIGHT_STATE_OVERRIDE;
import static com.android.server.DeviceIdleController.LIGHT_STATE_PRE_IDLE;
import static com.android.server.DeviceIdleController.LIGHT_STATE_WAITING_FOR_NETWORK;
import static com.android.server.DeviceIdleController.STATE_ACTIVE;
import static com.android.server.DeviceIdleController.STATE_IDLE;
import static com.android.server.DeviceIdleController.STATE_IDLE_MAINTENANCE;
import static com.android.server.DeviceIdleController.STATE_IDLE_PENDING;
import static com.android.server.DeviceIdleController.STATE_INACTIVE;
import static com.android.server.DeviceIdleController.STATE_LOCATING;
import static com.android.server.DeviceIdleController.STATE_SENSING;
import static com.android.server.DeviceIdleController.lightStateToString;
import static com.android.server.DeviceIdleController.stateToString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.content.Context;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManagerInternal;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Tests for {@link com.android.server.DeviceIdleController}.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceIdleControllerTest {
    private DeviceIdleController mDeviceIdleController;
    private AnyMotionDetectorForTest mAnyMotionDetector;
    private AppStateTrackerForTest mAppStateTracker;

    private MockitoSession mMockingSession;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private PowerManager.WakeLock mWakeLock;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private LocationManager mLocationManager;
    @Mock
    private IActivityManager mIActivityManager;

    class InjectorForTest extends DeviceIdleController.Injector {

        InjectorForTest(Context ctx) {
            super(ctx);
        }

        @Override
        AlarmManager getAlarmManager() {
            return mAlarmManager;
        }

        @Override
        AnyMotionDetector getAnyMotionDetector(Handler handler, SensorManager sm,
                AnyMotionDetector.DeviceIdleCallback callback, float angleThreshold) {
            return mAnyMotionDetector;
        }

        @Override
        AppStateTracker getAppStateTracker(Context ctx, Looper loop) {
            return mAppStateTracker;
        }

        @Override
        ConnectivityService getConnectivityService() {
            return null;
        }

        @Override
        LocationManager getLocationManager() {
            return mLocationManager;
        }

        @Override
        DeviceIdleController.MyHandler getHandler(DeviceIdleController ctlr) {
            return mock(DeviceIdleController.MyHandler.class);
        }

        @Override
        PowerManager getPowerManager() {
            return mPowerManager;
        }
    }

    private class AnyMotionDetectorForTest extends AnyMotionDetector {
        boolean isMonitoring = false;

        AnyMotionDetectorForTest() {
            super(mPowerManager, mock(Handler.class), mock(SensorManager.class),
                    mock(DeviceIdleCallback.class), 0.5f);
        }

        @Override
        public void checkForAnyMotion() {
            isMonitoring = true;
        }

        @Override
        public void stop() {
            isMonitoring = false;
        }
    }

    private class AppStateTrackerForTest extends AppStateTracker {
        AppStateTrackerForTest(Context ctx, Looper looper) {
            super(ctx, looper);
        }

        @Override
        public void onSystemServicesReady() {
            // Do nothing.
        }

        @Override
        IActivityManager injectIActivityManager() {
            return mIActivityManager;
        }
    }

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .startMocking();
        doReturn(mock(ActivityManagerInternal.class))
                .when(() -> LocalServices.getService(ActivityManagerInternal.class));
        doReturn(mock(ActivityTaskManagerInternal.class))
                .when(() -> LocalServices.getService(ActivityTaskManagerInternal.class));
        doReturn(mock(PowerManagerInternal.class))
                .when(() -> LocalServices.getService(PowerManagerInternal.class));
        doReturn(mock(NetworkPolicyManagerInternal.class))
                .when(() -> LocalServices.getService(NetworkPolicyManagerInternal.class));
        when(mPowerManager.newWakeLock(anyInt(), anyString())).thenReturn(mWakeLock);
        doNothing().when(mWakeLock).acquire();
        mAppStateTracker = new AppStateTrackerForTest(getContext(), Looper.getMainLooper());
        mAnyMotionDetector = new AnyMotionDetectorForTest();
        mDeviceIdleController = new DeviceIdleController(getContext(),
                new InjectorForTest(getContext()));
        spyOn(mDeviceIdleController);
        doNothing().when(mDeviceIdleController).publishBinderService(any(), any());
        mDeviceIdleController.onStart();
        mDeviceIdleController.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        mDeviceIdleController.setDeepEnabledForTest(true);
        mDeviceIdleController.setLightEnabledForTest(true);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
        // DeviceIdleController adds this to LocalServices in the constructor, so we have to remove
        // it after each test, otherwise, subsequent tests will fail.
        LocalServices.removeServiceForTest(AppStateTracker.class);
    }

    @Test
    public void testUpdateInteractivityLocked() {
        doReturn(false).when(mPowerManager).isInteractive();
        mDeviceIdleController.updateInteractivityLocked();
        assertFalse(mDeviceIdleController.isScreenOn());

        // Make sure setting false when screen is already off doesn't change anything.
        doReturn(false).when(mPowerManager).isInteractive();
        mDeviceIdleController.updateInteractivityLocked();
        assertFalse(mDeviceIdleController.isScreenOn());

        // Test changing from screen off to screen on.
        doReturn(true).when(mPowerManager).isInteractive();
        mDeviceIdleController.updateInteractivityLocked();
        assertTrue(mDeviceIdleController.isScreenOn());

        // Make sure setting true when screen is already on doesn't change anything.
        doReturn(true).when(mPowerManager).isInteractive();
        mDeviceIdleController.updateInteractivityLocked();
        assertTrue(mDeviceIdleController.isScreenOn());

        // Test changing from screen on to screen off.
        doReturn(false).when(mPowerManager).isInteractive();
        mDeviceIdleController.updateInteractivityLocked();
        assertFalse(mDeviceIdleController.isScreenOn());
    }

    @Test
    public void testUpdateChargingLocked() {
        mDeviceIdleController.updateChargingLocked(false);
        assertFalse(mDeviceIdleController.isCharging());

        // Make sure setting false when charging is already off doesn't change anything.
        mDeviceIdleController.updateChargingLocked(false);
        assertFalse(mDeviceIdleController.isCharging());

        // Test changing from charging off to charging on.
        mDeviceIdleController.updateChargingLocked(true);
        assertTrue(mDeviceIdleController.isCharging());

        // Make sure setting true when charging is already on doesn't change anything.
        mDeviceIdleController.updateChargingLocked(true);
        assertTrue(mDeviceIdleController.isCharging());

        // Test changing from charging on to charging off.
        mDeviceIdleController.updateChargingLocked(false);
        assertFalse(mDeviceIdleController.isCharging());
    }

    @Test
    public void testStateActiveToStateInactive_ConditionsNotMet() {
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        verifyStateConditions(STATE_ACTIVE);

        // State should stay ACTIVE with screen on and charging.
        setChargingOn(true);
        setScreenOn(true);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_ACTIVE);

        // State should stay ACTIVE with charging on.
        setChargingOn(true);
        setScreenOn(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_ACTIVE);

        // State should stay ACTIVE with screen on.
        // Note the different operation order here makes sure the state doesn't change before test.
        setScreenOn(true);
        setChargingOn(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_ACTIVE);
    }

    @Test
    public void testLightStateActiveToLightStateInactive_ConditionsNotMet() {
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        // State should stay ACTIVE with screen on and charging.
        setChargingOn(true);
        setScreenOn(true);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        // State should stay ACTIVE with charging on.
        setChargingOn(true);
        setScreenOn(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        // State should stay ACTIVE with screen on.
        // Note the different operation order here makes sure the state doesn't change before test.
        setScreenOn(true);
        setChargingOn(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);
    }

    @Test
    public void testStateActiveToStateInactive_ConditionsMet() {
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        verifyStateConditions(STATE_ACTIVE);

        setChargingOn(false);
        setScreenOn(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_INACTIVE);
    }

    @Test
    public void testLightStateActiveToLightStateInactive_ConditionsMet() {
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        setChargingOn(false);
        setScreenOn(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);
    }

    @Test
    public void testStepIdleStateLocked_InvalidStates() {
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        mDeviceIdleController.stepIdleStateLocked("testing");
        // mDeviceIdleController.stepIdleStateLocked doesn't handle the ACTIVE case, so the state
        // should stay as ACTIVE.
        verifyStateConditions(STATE_ACTIVE);
    }

    @Test
    public void testStepIdleStateLocked_ValidStates_NoLocationManager() {
        mDeviceIdleController.setLocationManagerForTest(null);
        // Make sure the controller doesn't think there's a wake-from-idle alarm coming soon.
        doReturn(Long.MAX_VALUE).when(mAlarmManager).getNextWakeFromIdleTime();
        // Set state to INACTIVE.
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        setChargingOn(false);
        setScreenOn(false);
        verifyStateConditions(STATE_INACTIVE);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE_PENDING);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_SENSING);

        mDeviceIdleController.stepIdleStateLocked("testing");
        // No location manager, so SENSING should go straight to IDLE.
        verifyStateConditions(STATE_IDLE);

        // Should just alternate between IDLE and IDLE_MAINTENANCE now.

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE_MAINTENANCE);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE_MAINTENANCE);
    }

    @Test
    public void testStepIdleStateLocked_ValidStates_WithLocationManager_NoProviders() {
        // Make sure the controller doesn't think there's a wake-from-idle alarm coming soon.
        doReturn(Long.MAX_VALUE).when(mAlarmManager).getNextWakeFromIdleTime();
        // Set state to INACTIVE.
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        setChargingOn(false);
        setScreenOn(false);
        verifyStateConditions(STATE_INACTIVE);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE_PENDING);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_SENSING);

        mDeviceIdleController.stepIdleStateLocked("testing");
        // Location manager exists but there isn't a network or GPS provider,
        // so SENSING should go straight to IDLE.
        verifyStateConditions(STATE_IDLE);

        // Should just alternate between IDLE and IDLE_MAINTENANCE now.

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE_MAINTENANCE);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE_MAINTENANCE);
    }

    @Test
    public void testStepIdleStateLocked_ValidStates_WithLocationManager_WithProviders() {
        doReturn(mock(LocationProvider.class)).when(mLocationManager).getProvider(anyString());
        // Make sure the controller doesn't think there's a wake-from-idle alarm coming soon.
        // TODO: add tests for when there's a wake-from-idle alarm coming soon.
        doReturn(Long.MAX_VALUE).when(mAlarmManager).getNextWakeFromIdleTime();
        // Set state to INACTIVE.
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        setChargingOn(false);
        setScreenOn(false);
        verifyStateConditions(STATE_INACTIVE);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE_PENDING);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_SENSING);

        mDeviceIdleController.stepIdleStateLocked("testing");
        // Location manager exists with a provider, so SENSING should go to LOCATING.
        verifyStateConditions(STATE_LOCATING);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE);

        // Should just alternate between IDLE and IDLE_MAINTENANCE now.

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE_MAINTENANCE);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE);

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE_MAINTENANCE);
    }

    private void setChargingOn(boolean on) {
        mDeviceIdleController.updateChargingLocked(on);
    }

    private void setScreenOn(boolean on) {
        doReturn(on).when(mPowerManager).isInteractive();
        mDeviceIdleController.updateInteractivityLocked();
    }

    private void verifyStateConditions(int expectedState) {
        int curState = mDeviceIdleController.getState();
        assertEquals(
                "Expected " + stateToString(expectedState) + " but was " + stateToString(curState),
                expectedState, curState);

        switch (expectedState) {
            case STATE_ACTIVE:
                assertFalse(mDeviceIdleController.mMotionListener.isActive());
                assertFalse(mAnyMotionDetector.isMonitoring);
                break;
            case STATE_INACTIVE:
                assertFalse(mDeviceIdleController.mMotionListener.isActive());
                assertFalse(mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn());
                break;
            case STATE_IDLE_PENDING:
                assertTrue(mDeviceIdleController.mMotionListener.isActive());
                assertFalse(mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn());
                break;
            case STATE_SENSING:
                assertTrue(mDeviceIdleController.mMotionListener.isActive());
                assertTrue(mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn());
                break;
            case STATE_LOCATING:
                assertTrue(mDeviceIdleController.mMotionListener.isActive());
                assertTrue(mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn());
                break;
            case STATE_IDLE:
                assertTrue(mDeviceIdleController.mMotionListener.isActive());
                assertFalse(mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn());
                // Light state should be OVERRIDE at this point.
                verifyLightStateConditions(LIGHT_STATE_OVERRIDE);
                break;
            case STATE_IDLE_MAINTENANCE:
                assertTrue(mDeviceIdleController.mMotionListener.isActive());
                assertFalse(mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn());
                break;
            default:
                fail("Conditions for " + stateToString(expectedState) + " unknown.");
        }
    }

    private void verifyLightStateConditions(int expectedLightState) {
        int curLightState = mDeviceIdleController.getLightState();
        assertEquals(
                "Expected " + lightStateToString(expectedLightState)
                        + " but was " + lightStateToString(curLightState),
                expectedLightState, curLightState);

        switch (expectedLightState) {
            case LIGHT_STATE_ACTIVE:
                assertTrue(
                        mDeviceIdleController.isCharging() || mDeviceIdleController.isScreenOn());
                break;
            case LIGHT_STATE_INACTIVE:
            case LIGHT_STATE_PRE_IDLE:
            case LIGHT_STATE_IDLE:
            case LIGHT_STATE_WAITING_FOR_NETWORK:
            case LIGHT_STATE_IDLE_MAINTENANCE:
            case LIGHT_STATE_OVERRIDE:
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn());
                break;
            default:
                fail("Conditions for " + lightStateToString(expectedLightState) + " unknown.");
        }
    }
}
