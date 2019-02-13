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
import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
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
import static com.android.server.DeviceIdleController.STATE_QUICK_DOZE_DELAY;
import static com.android.server.DeviceIdleController.STATE_SENSING;
import static com.android.server.DeviceIdleController.lightStateToString;
import static com.android.server.DeviceIdleController.stateToString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.SystemClock;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.deviceidle.ConstraintController;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.InOrder;
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
    private DeviceIdleController.Constants mConstants;
    private InjectorForTest mInjector;

    private MockitoSession mMockingSession;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private ConnectivityService mConnectivityService;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private DeviceIdleController.MyHandler mHandler;
    @Mock
    private IActivityManager mIActivityManager;
    @Mock
    private LocationManager mLocationManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private PowerManager.WakeLock mWakeLock;
    @Mock
    private PowerManagerInternal mPowerManagerInternal;
    @Mock
    private SensorManager mSensorManager;

    class InjectorForTest extends DeviceIdleController.Injector {
        ConnectivityService connectivityService;
        LocationManager locationManager;
        ConstraintController constraintController;

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
            return connectivityService;
        }

        @Override
        LocationManager getLocationManager() {
            return locationManager;
        }

        @Override
        DeviceIdleController.MyHandler getHandler(DeviceIdleController controller) {
            return mHandler;
        }

        @Override
        PowerManager getPowerManager() {
            return mPowerManager;
        }

        @Override
        SensorManager getSensorManager() {
            return mSensorManager;
        }

        @Override
        ConstraintController getConstraintController(
                Handler handler, DeviceIdleController.LocalService localService) {
            return constraintController;
        }

        @Override
        boolean useMotionSensor() {
            return true;
        }
    }

    private class AnyMotionDetectorForTest extends AnyMotionDetector {
        boolean isMonitoring = false;

        AnyMotionDetectorForTest() {
            super(mPowerManager, mock(Handler.class), mSensorManager,
                    mock(DeviceIdleCallback.class), 0.5f);
        }

        @Override
        public boolean hasSensor() {
            return true;
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
                .spyStatic(LocalServices.class)
                .startMocking();
        spyOn(getContext());
        doReturn(null).when(getContext()).registerReceiver(any(), any());
        doReturn(mock(ActivityManagerInternal.class))
                .when(() -> LocalServices.getService(ActivityManagerInternal.class));
        doReturn(mock(ActivityTaskManagerInternal.class))
                .when(() -> LocalServices.getService(ActivityTaskManagerInternal.class));
        doReturn(mock(AlarmManagerInternal.class))
                .when(() -> LocalServices.getService(AlarmManagerInternal.class));
        doReturn(mPowerManagerInternal)
                .when(() -> LocalServices.getService(PowerManagerInternal.class));
        when(mPowerManagerInternal.getLowPowerState(anyInt()))
                .thenReturn(mock(PowerSaveState.class));
        doReturn(mock(NetworkPolicyManagerInternal.class))
                .when(() -> LocalServices.getService(NetworkPolicyManagerInternal.class));
        when(mPowerManager.newWakeLock(anyInt(), anyString())).thenReturn(mWakeLock);
        doNothing().when(mWakeLock).acquire();
        doNothing().when(mAlarmManager).set(anyInt(), anyLong(), anyString(), any(), any());
        doReturn(mock(Sensor.class)).when(mSensorManager)
                .getDefaultSensor(eq(Sensor.TYPE_SIGNIFICANT_MOTION), eq(true));
        doReturn(true).when(mSensorManager).registerListener(any(), any(), anyInt());
        mAppStateTracker = new AppStateTrackerForTest(getContext(), Looper.getMainLooper());
        mAnyMotionDetector = new AnyMotionDetectorForTest();
        mHandler = mock(DeviceIdleController.MyHandler.class, Answers.RETURNS_DEEP_STUBS);
        doNothing().when(mHandler).handleMessage(any());
        mInjector = new InjectorForTest(getContext());
        doNothing().when(mContentResolver).registerContentObserver(any(), anyBoolean(), any());

        mDeviceIdleController = new DeviceIdleController(getContext(), mInjector);
        spyOn(mDeviceIdleController);
        doNothing().when(mDeviceIdleController).publishBinderService(any(), any());
        mDeviceIdleController.onStart();
        mDeviceIdleController.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        mDeviceIdleController.setDeepEnabledForTest(true);
        mDeviceIdleController.setLightEnabledForTest(true);

        // Get the same Constants object that mDeviceIdleController got.
        mConstants = mInjector.getConstants(mDeviceIdleController,
                mInjector.getHandler(mDeviceIdleController), mContentResolver);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
        // DeviceIdleController adds these to LocalServices in the constructor, so we have to remove
        // them after each test, otherwise, subsequent tests will fail.
        LocalServices.removeServiceForTest(AppStateTracker.class);
        LocalServices.removeServiceForTest(DeviceIdleController.LocalService.class);
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
    public void testUpdateConnectivityState() {
        // No connectivity service
        final boolean isConnected = mDeviceIdleController.isNetworkConnected();
        mInjector.connectivityService = null;
        mDeviceIdleController.updateConnectivityState(null);
        assertEquals(isConnected, mDeviceIdleController.isNetworkConnected());

        // No active network info
        mInjector.connectivityService = mConnectivityService;
        doReturn(null).when(mConnectivityService).getActiveNetworkInfo();
        mDeviceIdleController.updateConnectivityState(null);
        assertFalse(mDeviceIdleController.isNetworkConnected());

        // Active network info says connected.
        final NetworkInfo ani = mock(NetworkInfo.class);
        doReturn(ani).when(mConnectivityService).getActiveNetworkInfo();
        doReturn(true).when(ani).isConnected();
        mDeviceIdleController.updateConnectivityState(null);
        assertTrue(mDeviceIdleController.isNetworkConnected());

        // Active network info says not connected.
        doReturn(false).when(ani).isConnected();
        mDeviceIdleController.updateConnectivityState(null);
        assertFalse(mDeviceIdleController.isNetworkConnected());

        // Wrong intent passed (false).
        Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, 3);
        doReturn(true).when(ani).isConnected();
        doReturn(1).when(ani).getType();
        mDeviceIdleController.updateConnectivityState(intent);
        // Wrong intent means we shouldn't update the connected state.
        assertFalse(mDeviceIdleController.isNetworkConnected());

        // Intent says connected.
        doReturn(1).when(ani).getType();
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, 1);
        intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
        mDeviceIdleController.updateConnectivityState(intent);
        assertTrue(mDeviceIdleController.isNetworkConnected());

        // Wrong intent passed (true).
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, 3);
        // Wrong intent means we shouldn't update the connected state.
        assertTrue(mDeviceIdleController.isNetworkConnected());

        // Intent says not connected.
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, 1);
        intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
        mDeviceIdleController.updateConnectivityState(intent);
        assertFalse(mDeviceIdleController.isNetworkConnected());
    }

    @Test
    public void testUpdateQuickDozeFlagLocked() {
        mDeviceIdleController.updateQuickDozeFlagLocked(false);
        assertFalse(mDeviceIdleController.isQuickDozeEnabled());

        // Make sure setting false when quick doze is already off doesn't change anything.
        mDeviceIdleController.updateQuickDozeFlagLocked(false);
        assertFalse(mDeviceIdleController.isQuickDozeEnabled());

        // Test changing from quick doze off to quick doze on.
        mDeviceIdleController.updateQuickDozeFlagLocked(true);
        assertTrue(mDeviceIdleController.isQuickDozeEnabled());

        // Make sure setting true when quick doze is already on doesn't change anything.
        mDeviceIdleController.updateQuickDozeFlagLocked(true);
        assertTrue(mDeviceIdleController.isQuickDozeEnabled());

        // Test changing from quick doze on to quick doze off.
        mDeviceIdleController.updateQuickDozeFlagLocked(false);
        assertFalse(mDeviceIdleController.isQuickDozeEnabled());
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

        mConstants.WAIT_FOR_UNLOCK = false;
        setScreenLocked(true);
        setScreenOn(true);
        setChargingOn(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_ACTIVE);

        setScreenLocked(false);
        setScreenOn(true);
        setChargingOn(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_ACTIVE);

        mConstants.WAIT_FOR_UNLOCK = true;
        setScreenLocked(false);
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
    public void testTransitionFromAnyStateToStateQuickDozeDelay() {
        enterDeepState(STATE_ACTIVE);
        setQuickDozeEnabled(true);
        setChargingOn(false);
        setScreenOn(false);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);

        enterDeepState(STATE_INACTIVE);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);

        enterDeepState(STATE_IDLE_PENDING);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);

        enterDeepState(STATE_SENSING);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);

        enterDeepState(STATE_LOCATING);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);

        // IDLE should stay as IDLE.
        enterDeepState(STATE_IDLE);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_IDLE);

        // IDLE_MAINTENANCE should stay as IDLE_MAINTENANCE.
        enterDeepState(STATE_IDLE_MAINTENANCE);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_IDLE_MAINTENANCE);

        enterDeepState(STATE_QUICK_DOZE_DELAY);
        setQuickDozeEnabled(true);
        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
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
    public void testStepIdleStateLocked_ValidStates_QuickDoze() {
        setAlarmSoon(false);

        // Quick doze should go directly into IDLE.
        enterDeepState(STATE_QUICK_DOZE_DELAY);
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

    @Test
    public void testStepIdleStateLocked_ValidStates_WithWakeFromIdleAlarmSoon() {
        enterDeepState(STATE_ACTIVE);
        // Return that there's an alarm coming soon.
        setAlarmSoon(true);
        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_ACTIVE);

        // Everything besides ACTIVE should end up as INACTIVE since the screen would be off.

        enterDeepState(STATE_INACTIVE);
        setAlarmSoon(true);
        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_IDLE_PENDING);
        setAlarmSoon(true);
        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_SENSING);
        setAlarmSoon(true);
        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_LOCATING);
        setAlarmSoon(true);
        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_INACTIVE);

        // With quick doze enabled, we should end up in QUICK_DOZE_DELAY instead of INACTIVE.
        enterDeepState(STATE_QUICK_DOZE_DELAY);
        setQuickDozeEnabled(true);
        setAlarmSoon(true);
        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);

        // With quick doze disabled, we should end up in INACTIVE instead of QUICK_DOZE_DELAY.
        enterDeepState(STATE_QUICK_DOZE_DELAY);
        setQuickDozeEnabled(false);
        setAlarmSoon(true);
        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_IDLE);
        setAlarmSoon(true);
        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_IDLE_MAINTENANCE);
        setAlarmSoon(true);
        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_INACTIVE);
    }

    @Test
    public void testStepIdleStateLocked_ValidStates_NoLocationManager() {
        mInjector.locationManager = null;
        // Make sure the controller doesn't think there's a wake-from-idle alarm coming soon.
        setAlarmSoon(false);
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
        setAlarmSoon(false);
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
        mInjector.locationManager = mLocationManager;
        doReturn(mock(LocationProvider.class)).when(mLocationManager).getProvider(anyString());
        // Make sure the controller doesn't think there's a wake-from-idle alarm coming soon.
        setAlarmSoon(false);
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

    @Test
    public void testLightStepIdleStateLocked_InvalidStates() {
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        mDeviceIdleController.stepLightIdleStateLocked("testing");
        // stepLightIdleStateLocked doesn't handle the ACTIVE case, so the state
        // should stay as ACTIVE.
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);
    }

    /**
     * Make sure stepLightIdleStateLocked doesn't change state when the state is
     * LIGHT_STATE_OVERRIDE.
     */
    @Test
    public void testLightStepIdleStateLocked_Overriden() {
        enterLightState(LIGHT_STATE_OVERRIDE);
        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_OVERRIDE);
    }

    @Test
    public void testLightStepIdleStateLocked_ValidStates_NoActiveOps_NetworkConnected() {
        setNetworkConnected(true);
        mDeviceIdleController.setJobsActive(false);
        mDeviceIdleController.setAlarmsActive(false);
        mDeviceIdleController.setActiveIdleOpsForTest(0);

        // Set state to INACTIVE.
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        setChargingOn(false);
        setScreenOn(false);
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);

        // No active ops means INACTIVE should go straight to IDLE.
        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        // Should just alternate between IDLE and IDLE_MAINTENANCE now.

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);
    }

    @Test
    public void testLightStepIdleStateLocked_ValidStates_ActiveOps_NetworkConnected() {
        setNetworkConnected(true);
        // Set state to INACTIVE.
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        setChargingOn(false);
        setScreenOn(false);
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);

        // Active ops means INACTIVE should go to PRE_IDLE to wait.
        mDeviceIdleController.setJobsActive(true);
        mDeviceIdleController.setAlarmsActive(true);
        mDeviceIdleController.setActiveIdleOpsForTest(1);
        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_PRE_IDLE);

        // Even with active ops, PRE_IDLE should go to IDLE.
        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        // Should just alternate between IDLE and IDLE_MAINTENANCE now.

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);
    }

    @Test
    public void testLightStepIdleStateLocked_ValidStates_NoActiveOps_NoNetworkConnected() {
        setNetworkConnected(false);
        mDeviceIdleController.setJobsActive(false);
        mDeviceIdleController.setAlarmsActive(false);
        mDeviceIdleController.setActiveIdleOpsForTest(0);

        // Set state to INACTIVE.
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        setChargingOn(false);
        setScreenOn(false);
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);

        // No active ops means INACTIVE should go straight to IDLE.
        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        // Should cycle between IDLE, WAITING_FOR_NETWORK, and IDLE_MAINTENANCE now.

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_WAITING_FOR_NETWORK);

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_WAITING_FOR_NETWORK);

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);
    }

    @Test
    public void testLightStepIdleStateLocked_ValidStates_ActiveOps_NoNetworkConnected() {
        setNetworkConnected(false);
        // Set state to INACTIVE.
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        setChargingOn(false);
        setScreenOn(false);
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);

        // Active ops means INACTIVE should go to PRE_IDLE to wait.
        mDeviceIdleController.setJobsActive(true);
        mDeviceIdleController.setAlarmsActive(true);
        mDeviceIdleController.setActiveIdleOpsForTest(1);
        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_PRE_IDLE);

        // Even with active ops, PRE_IDLE should go to IDLE.
        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        // Should cycle between IDLE, WAITING_FOR_NETWORK, and IDLE_MAINTENANCE now.

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_WAITING_FOR_NETWORK);

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_WAITING_FOR_NETWORK);

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);
    }

    ///////////////// EXIT conditions ///////////////////

    @Test
    public void testExitMaintenanceEarlyIfNeededLocked_deep_noActiveOps() {
        mDeviceIdleController.setJobsActive(false);
        mDeviceIdleController.setAlarmsActive(false);
        mDeviceIdleController.setActiveIdleOpsForTest(0);

        // This method should only change things if in IDLE_MAINTENANCE or PRE_IDLE states.

        enterDeepState(STATE_ACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_INACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_IDLE_PENDING);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_IDLE_PENDING);

        enterDeepState(STATE_SENSING);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_SENSING);

        enterDeepState(STATE_LOCATING);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_LOCATING);

        enterDeepState(STATE_IDLE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_IDLE);

        enterDeepState(STATE_IDLE_MAINTENANCE);
        // Going into IDLE_MAINTENANCE increments the active idle op count.
        mDeviceIdleController.setActiveIdleOpsForTest(0);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_IDLE);

        enterDeepState(STATE_QUICK_DOZE_DELAY);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
    }

    @Test
    public void testExitMaintenanceEarlyIfNeededLocked_deep_activeJobs() {
        mDeviceIdleController.setJobsActive(true);
        mDeviceIdleController.setAlarmsActive(false);
        mDeviceIdleController.setActiveIdleOpsForTest(0);

        // This method should only change things if in IDLE_MAINTENANCE or PRE_IDLE states.

        enterDeepState(STATE_ACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_INACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_IDLE_PENDING);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_IDLE_PENDING);

        enterDeepState(STATE_SENSING);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_SENSING);

        enterDeepState(STATE_LOCATING);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_LOCATING);

        enterDeepState(STATE_IDLE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_IDLE);

        enterDeepState(STATE_IDLE_MAINTENANCE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_IDLE_MAINTENANCE);

        enterDeepState(STATE_QUICK_DOZE_DELAY);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
    }

    @Test
    public void testExitMaintenanceEarlyIfNeededLocked_deep_activeAlarms() {
        mDeviceIdleController.setJobsActive(false);
        mDeviceIdleController.setAlarmsActive(true);
        mDeviceIdleController.setActiveIdleOpsForTest(0);

        // This method should only change things if in IDLE_MAINTENANCE or PRE_IDLE states.

        enterDeepState(STATE_ACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_INACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_IDLE_PENDING);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_IDLE_PENDING);

        enterDeepState(STATE_SENSING);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_SENSING);

        enterDeepState(STATE_LOCATING);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_LOCATING);

        enterDeepState(STATE_IDLE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_IDLE);

        enterDeepState(STATE_IDLE_MAINTENANCE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_IDLE_MAINTENANCE);

        enterDeepState(STATE_QUICK_DOZE_DELAY);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
    }

    @Test
    public void testExitMaintenanceEarlyIfNeededLocked_deep_activeOps() {
        mDeviceIdleController.setJobsActive(false);
        mDeviceIdleController.setAlarmsActive(false);
        mDeviceIdleController.setActiveIdleOpsForTest(1);

        // This method should only change things if in IDLE_MAINTENANCE or PRE_IDLE states.

        enterDeepState(STATE_ACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_INACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_IDLE_PENDING);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_IDLE_PENDING);

        enterDeepState(STATE_SENSING);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_SENSING);

        enterDeepState(STATE_LOCATING);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_LOCATING);

        enterDeepState(STATE_IDLE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_IDLE);

        enterDeepState(STATE_IDLE_MAINTENANCE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_IDLE_MAINTENANCE);

        enterDeepState(STATE_QUICK_DOZE_DELAY);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
    }

    @Test
    public void testExitMaintenanceEarlyIfNeededLocked_light_noActiveOps() {
        mDeviceIdleController.setJobsActive(false);
        mDeviceIdleController.setAlarmsActive(false);
        mDeviceIdleController.setActiveIdleOpsForTest(0);

        // This method should only change things if in IDLE_MAINTENANCE or PRE_IDLE states.

        enterLightState(LIGHT_STATE_ACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_INACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);

        enterLightState(LIGHT_STATE_PRE_IDLE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        enterLightState(LIGHT_STATE_IDLE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        enterLightState(LIGHT_STATE_WAITING_FOR_NETWORK);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_WAITING_FOR_NETWORK);

        enterLightState(LIGHT_STATE_IDLE_MAINTENANCE);
        // Going into IDLE_MAINTENANCE increments the active idle op count.
        mDeviceIdleController.setActiveIdleOpsForTest(0);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        enterLightState(LIGHT_STATE_OVERRIDE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_OVERRIDE);
    }

    @Test
    public void testExitMaintenanceEarlyIfNeededLocked_light_activeJobs() {
        mDeviceIdleController.setJobsActive(true);
        mDeviceIdleController.setAlarmsActive(false);
        mDeviceIdleController.setActiveIdleOpsForTest(0);

        // This method should only change things if in IDLE_MAINTENANCE or PRE_IDLE states.

        enterLightState(LIGHT_STATE_ACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_INACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);

        enterLightState(LIGHT_STATE_PRE_IDLE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_PRE_IDLE);

        enterLightState(LIGHT_STATE_IDLE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        enterLightState(LIGHT_STATE_WAITING_FOR_NETWORK);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_WAITING_FOR_NETWORK);

        enterLightState(LIGHT_STATE_IDLE_MAINTENANCE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);

        enterLightState(LIGHT_STATE_OVERRIDE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_OVERRIDE);
    }

    @Test
    public void testExitMaintenanceEarlyIfNeededLocked_light_activeAlarms() {
        mDeviceIdleController.setJobsActive(false);
        mDeviceIdleController.setAlarmsActive(true);
        mDeviceIdleController.setActiveIdleOpsForTest(0);

        // This method should only change things if in IDLE_MAINTENANCE or PRE_IDLE states.

        enterLightState(LIGHT_STATE_ACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_INACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);

        enterLightState(LIGHT_STATE_PRE_IDLE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_PRE_IDLE);

        enterLightState(LIGHT_STATE_IDLE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        enterLightState(LIGHT_STATE_WAITING_FOR_NETWORK);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_WAITING_FOR_NETWORK);

        enterLightState(LIGHT_STATE_IDLE_MAINTENANCE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);

        enterLightState(LIGHT_STATE_OVERRIDE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_OVERRIDE);
    }

    @Test
    public void testExitMaintenanceEarlyIfNeededLocked_light_activeOps() {
        mDeviceIdleController.setJobsActive(false);
        mDeviceIdleController.setAlarmsActive(false);
        mDeviceIdleController.setActiveIdleOpsForTest(1);

        // This method should only change things if in IDLE_MAINTENANCE or PRE_IDLE states.

        enterLightState(LIGHT_STATE_ACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_INACTIVE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);

        enterLightState(LIGHT_STATE_PRE_IDLE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_PRE_IDLE);

        enterLightState(LIGHT_STATE_IDLE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        enterLightState(LIGHT_STATE_WAITING_FOR_NETWORK);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_WAITING_FOR_NETWORK);

        enterLightState(LIGHT_STATE_IDLE_MAINTENANCE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);

        enterLightState(LIGHT_STATE_OVERRIDE);
        mDeviceIdleController.exitMaintenanceEarlyIfNeededLocked();
        verifyLightStateConditions(LIGHT_STATE_OVERRIDE);
    }

    @Test
    public void testHandleMotionDetectedLocked_deep_quickDoze_off() {
        enterDeepState(STATE_ACTIVE);
        setQuickDozeEnabled(false);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_ACTIVE);

        // Anything that wasn't ACTIVE before motion detection should end up in the INACTIVE state.

        enterDeepState(STATE_INACTIVE);
        setQuickDozeEnabled(false);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_IDLE_PENDING);
        setQuickDozeEnabled(false);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_SENSING);
        setQuickDozeEnabled(false);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_LOCATING);
        setQuickDozeEnabled(false);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_IDLE);
        setQuickDozeEnabled(false);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_IDLE_MAINTENANCE);
        setQuickDozeEnabled(false);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_INACTIVE);

        enterDeepState(STATE_QUICK_DOZE_DELAY);
        setQuickDozeEnabled(false);
        // Disabling quick doze doesn't immediately change the state as coming out is harder than
        // going in.
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_INACTIVE);
    }

    @Test
    public void testHandleMotionDetectedLocked_deep_quickDoze_on() {
        enterDeepState(STATE_ACTIVE);
        setQuickDozeEnabled(true);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_ACTIVE);

        // Anything that wasn't ACTIVE before motion detection should end up in the
        // QUICK_DOZE_DELAY state since quick doze is enabled.

        enterDeepState(STATE_INACTIVE);
        setQuickDozeEnabled(true);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);

        enterDeepState(STATE_IDLE_PENDING);
        setQuickDozeEnabled(true);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);

        enterDeepState(STATE_SENSING);
        setQuickDozeEnabled(true);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);

        enterDeepState(STATE_LOCATING);
        setQuickDozeEnabled(true);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);

        enterDeepState(STATE_IDLE);
        setQuickDozeEnabled(true);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);

        enterDeepState(STATE_IDLE_MAINTENANCE);
        setQuickDozeEnabled(true);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);

        enterDeepState(STATE_QUICK_DOZE_DELAY);
        setQuickDozeEnabled(true);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
    }

    @Test
    public void testHandleMotionDetectedLocked_light() {
        enterLightState(LIGHT_STATE_ACTIVE);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        // Motion shouldn't affect light idle, so LIGHT states should stay as they were except for
        // OVERRIDE. OVERRIDE means deep was active, so if motion was detected,
        // LIGHT_STATE_OVERRIDE should end up as LIGHT_STATE_INACTIVE.

        enterLightState(LIGHT_STATE_INACTIVE);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);

        enterLightState(LIGHT_STATE_PRE_IDLE);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyLightStateConditions(LIGHT_STATE_PRE_IDLE);

        enterLightState(LIGHT_STATE_IDLE);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyLightStateConditions(LIGHT_STATE_IDLE);

        enterLightState(LIGHT_STATE_WAITING_FOR_NETWORK);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyLightStateConditions(LIGHT_STATE_WAITING_FOR_NETWORK);

        enterLightState(LIGHT_STATE_IDLE_MAINTENANCE);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);

        enterLightState(LIGHT_STATE_OVERRIDE);
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);
    }

    @Test
    public void testBecomeActiveLocked_deep() {
        // becomeActiveLocked should put everything into ACTIVE.

        enterDeepState(STATE_ACTIVE);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_INACTIVE);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_IDLE_PENDING);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_SENSING);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_LOCATING);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_IDLE);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_IDLE_MAINTENANCE);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_QUICK_DOZE_DELAY);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyStateConditions(STATE_ACTIVE);
    }

    @Test
    public void testBecomeActiveLocked_light() {
        // becomeActiveLocked should put everything into ACTIVE.

        enterLightState(LIGHT_STATE_ACTIVE);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_INACTIVE);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_PRE_IDLE);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_IDLE);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_WAITING_FOR_NETWORK);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_IDLE_MAINTENANCE);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_OVERRIDE);
        mDeviceIdleController.becomeActiveLocked("test", 1000);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);
    }

    /** Test based on b/119058625. */
    @Test
    public void testExitNotifiesDependencies_WaitForUnlockOn_KeyguardOn_ScreenThenMotion() {
        mConstants.WAIT_FOR_UNLOCK = true;
        enterDeepState(STATE_IDLE);
        reset(mAlarmManager);
        spyOn(mDeviceIdleController);

        mDeviceIdleController.keyguardShowingLocked(true);
        setScreenOn(true);
        // With WAIT_FOR_UNLOCK = true and the screen locked, turning the screen on by itself
        // shouldn't bring the device out of deep IDLE.
        verifyStateConditions(STATE_IDLE);
        mDeviceIdleController.handleMotionDetectedLocked(1000, "test");
        // Motion should bring the device out of Doze. Since the screen is still locked (albeit
        // on), the states should go back into INACTIVE.
        verifyStateConditions(STATE_INACTIVE);
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);
        verify(mAlarmManager).cancel(eq(mDeviceIdleController.mDeepAlarmListener));
        verify(mDeviceIdleController).scheduleReportActiveLocked(anyString(), anyInt());
    }

    /** Test based on b/119058625. */
    @Test
    public void testExitNotifiesDependencies_WaitForUnlockOn_KeyguardOff_ScreenThenMotion() {
        mConstants.WAIT_FOR_UNLOCK = true;
        enterDeepState(STATE_IDLE);
        reset(mAlarmManager);
        spyOn(mDeviceIdleController);

        mDeviceIdleController.keyguardShowingLocked(false);
        setScreenOn(true);
        // With WAIT_FOR_UNLOCK = true and the screen unlocked, turning the screen on by itself
        // should bring the device out of deep IDLE.
        verifyStateConditions(STATE_ACTIVE);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);
        verify(mAlarmManager).cancel(eq(mDeviceIdleController.mDeepAlarmListener));
        verify(mDeviceIdleController).scheduleReportActiveLocked(anyString(), anyInt());
    }

    /** Test based on b/119058625. */
    @Test
    public void testExitNotifiesDependencies_WaitForUnlockOn_KeyguardOn_MotionThenScreen() {
        mConstants.WAIT_FOR_UNLOCK = true;
        enterDeepState(STATE_IDLE);
        reset(mAlarmManager);
        spyOn(mDeviceIdleController);

        InOrder alarmManagerInOrder = inOrder(mAlarmManager);
        InOrder controllerInOrder = inOrder(mDeviceIdleController);

        mDeviceIdleController.keyguardShowingLocked(true);
        mDeviceIdleController.handleMotionDetectedLocked(1000, "test");
        // The screen is still off, so motion should result in the INACTIVE state.
        verifyStateConditions(STATE_INACTIVE);
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);
        alarmManagerInOrder.verify(mAlarmManager)
                .cancel(eq(mDeviceIdleController.mDeepAlarmListener));
        controllerInOrder.verify(mDeviceIdleController)
                .scheduleReportActiveLocked(anyString(), anyInt());

        setScreenOn(true);
        // With WAIT_FOR_UNLOCK = true and the screen locked, turning the screen on by itself
        // shouldn't bring the device all the way to ACTIVE.
        verifyStateConditions(STATE_INACTIVE);
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);
        alarmManagerInOrder.verify(mAlarmManager, never()).cancel(
                eq(mDeviceIdleController.mDeepAlarmListener));

        // User finally unlocks the device. Device should be fully active.
        mDeviceIdleController.keyguardShowingLocked(false);
        verifyStateConditions(STATE_ACTIVE);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);
        alarmManagerInOrder.verify(mAlarmManager)
                .cancel(eq(mDeviceIdleController.mDeepAlarmListener));
        controllerInOrder.verify(mDeviceIdleController)
                .scheduleReportActiveLocked(anyString(), anyInt());
    }

    /** Test based on b/119058625. */
    @Test
    public void testExitNotifiesDependencies_WaitForUnlockOn_KeyguardOff_MotionThenScreen() {
        mConstants.WAIT_FOR_UNLOCK = true;
        enterDeepState(STATE_IDLE);
        reset(mAlarmManager);
        spyOn(mDeviceIdleController);

        InOrder alarmManagerInOrder = inOrder(mAlarmManager);
        InOrder controllerInOrder = inOrder(mDeviceIdleController);

        mDeviceIdleController.keyguardShowingLocked(false);
        mDeviceIdleController.handleMotionDetectedLocked(1000, "test");
        // The screen is still off, so motion should result in the INACTIVE state.
        verifyStateConditions(STATE_INACTIVE);
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);
        alarmManagerInOrder.verify(mAlarmManager)
                .cancel(eq(mDeviceIdleController.mDeepAlarmListener));
        controllerInOrder.verify(mDeviceIdleController)
                .scheduleReportActiveLocked(anyString(), anyInt());

        setScreenOn(true);
        // With WAIT_FOR_UNLOCK = true and the screen unlocked, turning the screen on by itself
        // should bring the device out of deep IDLE.
        verifyStateConditions(STATE_ACTIVE);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);
        alarmManagerInOrder.verify(mAlarmManager)
                .cancel(eq(mDeviceIdleController.mDeepAlarmListener));
        controllerInOrder.verify(mDeviceIdleController)
                .scheduleReportActiveLocked(anyString(), anyInt());
    }

    @Test
    public void testExitNotifiesDependencies_WaitForUnlockOff_Screen() {
        mConstants.WAIT_FOR_UNLOCK = false;
        enterDeepState(STATE_IDLE);
        reset(mAlarmManager);
        spyOn(mDeviceIdleController);

        setScreenOn(true);
        // With WAIT_FOR_UNLOCK = false and the screen locked, turning the screen on by itself
        // should bring the device out of deep IDLE.
        verifyStateConditions(STATE_ACTIVE);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);
        verify(mAlarmManager).cancel(eq(mDeviceIdleController.mDeepAlarmListener));
        verify(mDeviceIdleController).scheduleReportActiveLocked(anyString(), anyInt());
    }

    @Test
    public void testExitNotifiesDependencies_WaitForUnlockOff_MotionThenScreen() {
        mConstants.WAIT_FOR_UNLOCK = false;
        enterDeepState(STATE_IDLE);
        reset(mAlarmManager);
        spyOn(mDeviceIdleController);

        InOrder alarmManagerInOrder = inOrder(mAlarmManager);
        InOrder controllerInOrder = inOrder(mDeviceIdleController);

        mDeviceIdleController.handleMotionDetectedLocked(1000, "test");
        // The screen is still off, so motion should result in the INACTIVE state.
        verifyStateConditions(STATE_INACTIVE);
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);
        alarmManagerInOrder.verify(mAlarmManager)
                .cancel(eq(mDeviceIdleController.mDeepAlarmListener));
        controllerInOrder.verify(mDeviceIdleController)
                .scheduleReportActiveLocked(anyString(), anyInt());

        setScreenOn(true);
        // With WAIT_FOR_UNLOCK = false and the screen locked, turning the screen on by itself
        // should bring the device out of deep IDLE.
        verifyStateConditions(STATE_ACTIVE);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);
        alarmManagerInOrder.verify(mAlarmManager)
                .cancel(eq(mDeviceIdleController.mDeepAlarmListener));
        controllerInOrder.verify(mDeviceIdleController)
                .scheduleReportActiveLocked(anyString(), anyInt());
    }

    @Test
    public void testStepToIdleMode() {
        float delta = mDeviceIdleController.MIN_PRE_IDLE_FACTOR_CHANGE;
        for (int mode = PowerManager.PRE_IDLE_TIMEOUT_MODE_NORMAL;
                mode <= PowerManager.PRE_IDLE_TIMEOUT_MODE_LONG;
                mode++) {
            int ret = mDeviceIdleController.setPreIdleTimeoutMode(mode);
            if (mode == PowerManager.PRE_IDLE_TIMEOUT_MODE_NORMAL) {
                assertEquals("setPreIdleTimeoutMode: " + mode + " failed.",
                        mDeviceIdleController.SET_IDLE_FACTOR_RESULT_IGNORED, ret);
            } else {
                assertEquals("setPreIdleTimeoutMode: " + mode + " failed.",
                        mDeviceIdleController.SET_IDLE_FACTOR_RESULT_OK, ret);
            }
            //TODO(b/123045185): Mocked Handler of DeviceIdleController to make message loop
            //workable in this test class
            mDeviceIdleController.updatePreIdleFactor();
            float expectedfactor = mDeviceIdleController.getPreIdleTimeoutByMode(mode);
            float curfactor = mDeviceIdleController.getPreIdleTimeoutFactor();
            assertEquals("Pre idle time factor of mode [" + mode + "].",
                    expectedfactor, curfactor, delta);
            mDeviceIdleController.resetPreIdleTimeoutMode();
            mDeviceIdleController.updatePreIdleFactor();

            checkNextAlarmTimeWithNewPreIdleFactor(expectedfactor, STATE_INACTIVE);
            checkNextAlarmTimeWithNewPreIdleFactor(expectedfactor, STATE_IDLE_PENDING);

            checkNextAlarmTimeWithNewPreIdleFactor(expectedfactor, STATE_SENSING);
            checkNextAlarmTimeWithNewPreIdleFactor(expectedfactor, STATE_LOCATING);
            checkNextAlarmTimeWithNewPreIdleFactor(expectedfactor, STATE_QUICK_DOZE_DELAY);
            checkNextAlarmTimeWithNewPreIdleFactor(expectedfactor, STATE_IDLE_MAINTENANCE);
            checkNextAlarmTimeWithNewPreIdleFactor(expectedfactor, STATE_IDLE);
            checkMaybeDoAnImmediateMaintenance(expectedfactor);
        }
        float curfactor = mDeviceIdleController.getPreIdleTimeoutFactor();
        assertEquals("Pre idle time factor of mode default.",
                1.0f, curfactor, delta);
    }

    private void enterDeepState(int state) {
        switch (state) {
            case STATE_ACTIVE:
                setScreenOn(true);
                mDeviceIdleController.becomeActiveLocked("testing", 0);
                break;
            case STATE_QUICK_DOZE_DELAY:
                // Start off from ACTIVE in case we're already past the desired state.
                enterDeepState(STATE_ACTIVE);
                setQuickDozeEnabled(true);
                setScreenOn(false);
                setChargingOn(false);
                mDeviceIdleController.becomeInactiveIfAppropriateLocked();
                break;
            case STATE_LOCATING:
                mInjector.locationManager = mLocationManager;
                doReturn(mock(LocationProvider.class)).when(mLocationManager).getProvider(
                        anyString());
                // Fallthrough to step loop.
            case STATE_IDLE_PENDING:
            case STATE_SENSING:
            case STATE_IDLE:
            case STATE_IDLE_MAINTENANCE:
                // Make sure the controller doesn't think there's a wake-from-idle alarm coming
                // soon.
                setAlarmSoon(false);
            case STATE_INACTIVE:
                // Start off from ACTIVE in case we're already past the desired state.
                enterDeepState(STATE_ACTIVE);
                setQuickDozeEnabled(false);
                setScreenOn(false);
                setChargingOn(false);
                mDeviceIdleController.becomeInactiveIfAppropriateLocked();
                int count = 0;
                while (mDeviceIdleController.getState() != state) {
                    // Stepping through each state ensures that the proper features are turned
                    // on/off.
                    mDeviceIdleController.stepIdleStateLocked("testing");
                    count++;
                    if (count > 10) {
                        fail("Infinite loop. Check test configuration. Currently at " +
                                stateToString(mDeviceIdleController.getState()));
                    }
                }
                break;
            default:
                fail("Unknown deep state " + stateToString(state));
        }
    }

    private void enterLightState(int lightState) {
        switch (lightState) {
            case LIGHT_STATE_ACTIVE:
                setScreenOn(true);
                mDeviceIdleController.becomeActiveLocked("testing", 0);
                break;
            case LIGHT_STATE_INACTIVE:
            case LIGHT_STATE_IDLE:
            case LIGHT_STATE_IDLE_MAINTENANCE:
                // Start off from ACTIVE in case we're already past the desired state.
                enterLightState(LIGHT_STATE_ACTIVE);
                setScreenOn(false);
                setChargingOn(false);
                int count = 0;
                mDeviceIdleController.becomeInactiveIfAppropriateLocked();
                while (mDeviceIdleController.getLightState() != lightState) {
                    // Stepping through each state ensures that the proper features are turned
                    // on/off.
                    mDeviceIdleController.stepLightIdleStateLocked("testing");

                    count++;
                    if (count > 10) {
                        fail("Infinite loop. Check test configuration. Currently at " +
                                lightStateToString(mDeviceIdleController.getLightState()));
                    }
                }
                break;
            case LIGHT_STATE_PRE_IDLE:
            case LIGHT_STATE_WAITING_FOR_NETWORK:
            case LIGHT_STATE_OVERRIDE:
                setScreenOn(false);
                setChargingOn(false);
                mDeviceIdleController.setLightStateForTest(lightState);
                break;
            default:
                fail("Unknown light state " + lightStateToString(lightState));
        }
    }

    private void setChargingOn(boolean on) {
        mDeviceIdleController.updateChargingLocked(on);
    }

    private void setScreenLocked(boolean locked) {
        mDeviceIdleController.keyguardShowingLocked(locked);
    }

    private void setScreenOn(boolean on) {
        doReturn(on).when(mPowerManager).isInteractive();
        mDeviceIdleController.updateInteractivityLocked();
    }

    private void setNetworkConnected(boolean connected) {
        mInjector.connectivityService = mConnectivityService;
        final NetworkInfo ani = mock(NetworkInfo.class);
        doReturn(connected).when(ani).isConnected();
        doReturn(ani).when(mConnectivityService).getActiveNetworkInfo();
        mDeviceIdleController.updateConnectivityState(null);
    }

    private void setQuickDozeEnabled(boolean on) {
        mDeviceIdleController.updateQuickDozeFlagLocked(on);
    }

    private void setAlarmSoon(boolean isSoon) {
        if (isSoon) {
            doReturn(SystemClock.elapsedRealtime() + mConstants.MIN_TIME_TO_ALARM / 2)
                    .when(mAlarmManager).getNextWakeFromIdleTime();
        } else {
            doReturn(Long.MAX_VALUE).when(mAlarmManager).getNextWakeFromIdleTime();
        }
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
                assertFalse(mDeviceIdleController.isScreenOn()
                        && !mDeviceIdleController.isKeyguardShowing());
                break;
            case STATE_IDLE_PENDING:
                assertEquals(
                        mDeviceIdleController.hasMotionSensor(),
                        mDeviceIdleController.mMotionListener.isActive());
                assertFalse(mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn()
                        && !mDeviceIdleController.isKeyguardShowing());
                break;
            case STATE_SENSING:
                assertEquals(
                        mDeviceIdleController.hasMotionSensor(),
                        mDeviceIdleController.mMotionListener.isActive());
                assertEquals(
                        mDeviceIdleController.hasMotionSensor(),
                        mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn()
                        && !mDeviceIdleController.isKeyguardShowing());
                break;
            case STATE_LOCATING:
                assertEquals(
                        mDeviceIdleController.hasMotionSensor(),
                        mDeviceIdleController.mMotionListener.isActive());
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn()
                        && !mDeviceIdleController.isKeyguardShowing());
                break;
            case STATE_IDLE:
                if (mDeviceIdleController.hasMotionSensor()) {
                    assertTrue(mDeviceIdleController.mMotionListener.isActive()
                        // If quick doze is enabled, the motion listener should NOT be active.
                        || mDeviceIdleController.isQuickDozeEnabled());
                }
                assertFalse(mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn()
                        && !mDeviceIdleController.isKeyguardShowing());
                // Light state should be OVERRIDE at this point.
                verifyLightStateConditions(LIGHT_STATE_OVERRIDE);
                break;
            case STATE_IDLE_MAINTENANCE:
                if (mDeviceIdleController.hasMotionSensor()) {
                    assertTrue(mDeviceIdleController.mMotionListener.isActive()
                        // If quick doze is enabled, the motion listener should NOT be active.
                        || mDeviceIdleController.isQuickDozeEnabled());
                }
                assertFalse(mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn()
                        && !mDeviceIdleController.isKeyguardShowing());
                break;
            case STATE_QUICK_DOZE_DELAY:
                // If quick doze is enabled, the motion listener should NOT be active.
                assertFalse(mDeviceIdleController.mMotionListener.isActive());
                assertFalse(mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn()
                        && !mDeviceIdleController.isKeyguardShowing());
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
                        mDeviceIdleController.isCharging() || mDeviceIdleController.isScreenOn()
                                // Or there's an alarm coming up soon.
                                || SystemClock.elapsedRealtime() + mConstants.MIN_TIME_TO_ALARM
                                > mAlarmManager.getNextWakeFromIdleTime());
                break;
            case LIGHT_STATE_INACTIVE:
            case LIGHT_STATE_PRE_IDLE:
            case LIGHT_STATE_IDLE:
            case LIGHT_STATE_WAITING_FOR_NETWORK:
            case LIGHT_STATE_IDLE_MAINTENANCE:
            case LIGHT_STATE_OVERRIDE:
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn()
                        && !mDeviceIdleController.isKeyguardShowing());
                break;
            default:
                fail("Conditions for " + lightStateToString(expectedLightState) + " unknown.");
        }
    }

    private void checkNextAlarmTimeWithNewPreIdleFactor(float factor, int state) {
        final long errorTolerance = 1000;
        enterDeepState(state);
        long now = SystemClock.elapsedRealtime();
        long alarm = mDeviceIdleController.getNextAlarmTime();
        if (state == STATE_INACTIVE || state == STATE_IDLE_PENDING) {
            int ret = mDeviceIdleController.setPreIdleTimeoutFactor(factor);
            if (Float.compare(factor, 1.0f) == 0) {
                assertEquals("setPreIdleTimeoutMode: " + factor + " failed.",
                        mDeviceIdleController.SET_IDLE_FACTOR_RESULT_IGNORED, ret);
            } else {
                assertEquals("setPreIdleTimeoutMode: " + factor + " failed.",
                        mDeviceIdleController.SET_IDLE_FACTOR_RESULT_OK, ret);
            }
            if (ret == mDeviceIdleController.SET_IDLE_FACTOR_RESULT_OK) {
                mDeviceIdleController.updatePreIdleFactor();
                long newAlarm = mDeviceIdleController.getNextAlarmTime();
                long newDelay = (long) ((alarm - now) * factor);
                assertTrue("setPreIdleTimeoutFactor: " + factor,
                        Math.abs(newDelay - (newAlarm - now)) <  errorTolerance);
                mDeviceIdleController.resetPreIdleTimeoutMode();
                mDeviceIdleController.updatePreIdleFactor();
                mDeviceIdleController.maybeDoImmediateMaintenance();
                newAlarm = mDeviceIdleController.getNextAlarmTime();
                assertTrue("resetPreIdleTimeoutMode from: " + factor,
                        Math.abs(newAlarm - alarm) < errorTolerance);
                mDeviceIdleController.setPreIdleTimeoutFactor(factor);
                now = SystemClock.elapsedRealtime();
                enterDeepState(state);
                newAlarm = mDeviceIdleController.getNextAlarmTime();
                assertTrue("setPreIdleTimeoutFactor: " + factor + " before step to idle",
                        Math.abs(newDelay - (newAlarm - now)) <  errorTolerance);
                mDeviceIdleController.resetPreIdleTimeoutMode();
                mDeviceIdleController.updatePreIdleFactor();
                mDeviceIdleController.maybeDoImmediateMaintenance();
            }
        } else {
            mDeviceIdleController.setPreIdleTimeoutFactor(factor);
            mDeviceIdleController.updatePreIdleFactor();
            long newAlarm = mDeviceIdleController.getNextAlarmTime();
            assertTrue("setPreIdleTimeoutFactor: " + factor
                    + " shounld not change next alarm" ,
                    (newAlarm == alarm));
            mDeviceIdleController.resetPreIdleTimeoutMode();
            mDeviceIdleController.updatePreIdleFactor();
            mDeviceIdleController.maybeDoImmediateMaintenance();
        }
    }

    private void checkMaybeDoAnImmediateMaintenance(float factor) {
        int ret = mDeviceIdleController.setPreIdleTimeoutFactor(factor);
        final long minuteInMillis = 60 * 1000;
        if (Float.compare(factor, 1.0f) == 0) {
            assertEquals("setPreIdleTimeoutMode: " + factor + " failed.",
                    mDeviceIdleController.SET_IDLE_FACTOR_RESULT_IGNORED, ret);
        } else {
            assertEquals("setPreIdleTimeoutMode: " + factor + " failed.",
                    mDeviceIdleController.SET_IDLE_FACTOR_RESULT_OK, ret);
        }
        if (ret == mDeviceIdleController.SET_IDLE_FACTOR_RESULT_OK) {
            enterDeepState(STATE_IDLE);
            long now = SystemClock.elapsedRealtime();
            long alarm = mDeviceIdleController.getNextAlarmTime();
            mDeviceIdleController.setIdleStartTimeForTest(
                    now - (long) (mConstants.IDLE_TIMEOUT * 0.6));
            mDeviceIdleController.maybeDoImmediateMaintenance();
            long newAlarm = mDeviceIdleController.getNextAlarmTime();
            assertTrue("maintenance not reschedule IDLE_TIMEOUT * 0.6",
                    newAlarm == alarm);
            mDeviceIdleController.setIdleStartTimeForTest(
                    now - (long) (mConstants.IDLE_TIMEOUT * 1.2));
            mDeviceIdleController.maybeDoImmediateMaintenance();
            newAlarm = mDeviceIdleController.getNextAlarmTime();
            assertTrue("maintenance not reschedule IDLE_TIMEOUT * 1.2",
                    (newAlarm - now) < minuteInMillis);
            mDeviceIdleController.resetPreIdleTimeoutMode();
            mDeviceIdleController.updatePreIdleFactor();
        }
    }
}
