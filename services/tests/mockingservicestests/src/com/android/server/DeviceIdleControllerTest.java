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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
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
import static com.android.server.DeviceIdleController.MSG_REPORT_STATIONARY_STATUS;
import static com.android.server.DeviceIdleController.MSG_RESET_PRE_IDLE_TIMEOUT_FACTOR;
import static com.android.server.DeviceIdleController.MSG_UPDATE_PRE_IDLE_TIMEOUT_FACTOR;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.SystemClock;
import android.provider.DeviceConfig;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.deviceidle.ConstraintController;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.util.concurrent.Executor;

/**
 * Tests for {@link com.android.server.DeviceIdleController}.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceIdleControllerTest {
    private DeviceIdleController mDeviceIdleController;
    private DeviceIdleController.MyHandler mHandler;
    private AnyMotionDetectorForTest mAnyMotionDetector;
    private AppStateTrackerForTest mAppStateTracker;
    private DeviceIdleController.Constants mConstants;
    private InjectorForTest mInjector;

    private MockitoSession mMockingSession;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private ConnectivityManager mConnectivityManager;
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
    private Sensor mMotionSensor;
    @Mock
    private SensorManager mSensorManager;

    class InjectorForTest extends DeviceIdleController.Injector {
        ConnectivityManager connectivityManager;
        LocationManager locationManager;
        ConstraintController constraintController;
        // Freeze time for testing.
        long nowElapsed;

        InjectorForTest(Context ctx) {
            super(ctx);
            nowElapsed = SystemClock.elapsedRealtime();
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
        AppStateTrackerImpl getAppStateTracker(Context ctx, Looper loop) {
            return mAppStateTracker;
        }

        @Override
        ConnectivityManager getConnectivityManager() {
            return connectivityManager;
        }

        @Override
        long getElapsedRealtime() {
            return nowElapsed;
        }

        @Override
        LocationManager getLocationManager() {
            return locationManager;
        }

        @Override
        DeviceIdleController.MyHandler getHandler(DeviceIdleController controller) {
            if (mHandler == null) {
                mHandler = controller.new MyHandler(getContext().getMainLooper());
                spyOn(mHandler);
                doNothing().when(mHandler).handleMessage(argThat((message) ->
                        message.what != MSG_REPORT_STATIONARY_STATUS
                        && message.what != MSG_UPDATE_PRE_IDLE_TIMEOUT_FACTOR
                        && message.what != MSG_RESET_PRE_IDLE_TIMEOUT_FACTOR));
                doAnswer(new Answer<Boolean>() {
                    @Override
                    public Boolean answer(InvocationOnMock invocation) throws Throwable {
                        Message msg = invocation.getArgument(0);
                        mHandler.handleMessage(msg);
                        return true;
                    }
                }).when(mHandler).sendMessageDelayed(
                        argThat((message) -> message.what == MSG_REPORT_STATIONARY_STATUS
                                || message.what == MSG_UPDATE_PRE_IDLE_TIMEOUT_FACTOR
                                || message.what == MSG_RESET_PRE_IDLE_TIMEOUT_FACTOR),
                        anyLong());
            }

            return mHandler;
        }

        @Override
        Sensor getMotionSensor() {
            return mMotionSensor;
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
                Handler handler, DeviceIdleInternal localService) {
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

    private class AppStateTrackerForTest extends AppStateTrackerImpl {
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

    private class StationaryListenerForTest implements DeviceIdleInternal.StationaryListener {
        boolean motionExpected = false;
        boolean isStationary = false;

        @Override
        public void onDeviceStationaryChanged(boolean isStationary) {
            if (isStationary == motionExpected) {
                fail("Unexpected device stationary status: " + isStationary);
            }
            this.isStationary = isStationary;
        }
    }

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(DeviceConfig.class)
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
        doAnswer((Answer<Void>) invocationOnMock -> null)
                .when(() -> DeviceConfig.addOnPropertiesChangedListener(
                        anyString(), any(Executor.class),
                        any(DeviceConfig.OnPropertiesChangedListener.class)));
        doAnswer((Answer<DeviceConfig.Properties>) invocationOnMock
                -> mock(DeviceConfig.Properties.class))
                .when(() -> DeviceConfig.getProperties(
                        anyString(), ArgumentMatchers.<String>any()));
        when(mPowerManager.newWakeLock(anyInt(), anyString())).thenReturn(mWakeLock);
        doNothing().when(mWakeLock).acquire();
        doNothing().when(mAlarmManager).set(anyInt(), anyLong(), anyString(), any(), any());
        doNothing().when(mAlarmManager).setExact(anyInt(), anyLong(), anyString(), any(), any());
        doNothing().when(mAlarmManager)
                .setWindow(anyInt(), anyLong(), anyLong(), anyString(), any(), any());
        doReturn(mock(Sensor.class)).when(mSensorManager)
                .getDefaultSensor(eq(Sensor.TYPE_SIGNIFICANT_MOTION), eq(true));
        doReturn(true).when(mSensorManager).registerListener(any(), any(), anyInt());
        mAppStateTracker = new AppStateTrackerForTest(getContext(), Looper.getMainLooper());
        mAnyMotionDetector = new AnyMotionDetectorForTest();
        mInjector = new InjectorForTest(getContext());

        mDeviceIdleController = new DeviceIdleController(getContext(), mInjector);
        spyOn(mDeviceIdleController);
        doNothing().when(mDeviceIdleController).publishBinderService(any(), any());
        mDeviceIdleController.onStart();
        mDeviceIdleController.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        mDeviceIdleController.setDeepEnabledForTest(true);
        mDeviceIdleController.setLightEnabledForTest(true);

        // Get the same Constants object that mDeviceIdleController got.
        mConstants = mInjector.getConstants(mDeviceIdleController);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
        // DeviceIdleController adds these to LocalServices in the constructor, so we have to remove
        // them after each test, otherwise, subsequent tests will fail.
        LocalServices.removeServiceForTest(AppStateTracker.class);
        LocalServices.removeServiceForTest(DeviceIdleInternal.class);
        LocalServices.removeServiceForTest(PowerAllowlistInternal.class);
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
        mInjector.connectivityManager = null;
        mDeviceIdleController.updateConnectivityState(null);
        assertEquals(isConnected, mDeviceIdleController.isNetworkConnected());

        // No active network info
        mInjector.connectivityManager = mConnectivityManager;
        doReturn(null).when(mConnectivityManager).getActiveNetworkInfo();
        mDeviceIdleController.updateConnectivityState(null);
        assertFalse(mDeviceIdleController.isNetworkConnected());

        // Active network info says connected.
        final NetworkInfo ani = mock(NetworkInfo.class);
        doReturn(ani).when(mConnectivityManager).getActiveNetworkInfo();
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

        setAlarmSoon(false);
        setChargingOn(false);
        setScreenOn(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_INACTIVE);
        verify(mDeviceIdleController)
                .scheduleAlarmLocked(eq(mConstants.INACTIVE_TIMEOUT), eq(false));
    }

    @Test
    public void testStateActiveToStateInactive_UpcomingAlarm() {
        final long timeUntilAlarm = mConstants.MIN_TIME_TO_ALARM / 2;
        // Set an upcoming alarm that will prevent full idle.
        doReturn(mInjector.getElapsedRealtime() + timeUntilAlarm)
                .when(mAlarmManager).getNextWakeFromIdleTime();

        InOrder inOrder = inOrder(mDeviceIdleController);

        enterDeepState(STATE_ACTIVE);
        setQuickDozeEnabled(false);
        setChargingOn(false);
        setScreenOn(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_INACTIVE);
        inOrder.verify(mDeviceIdleController)
                .scheduleAlarmLocked(eq(timeUntilAlarm + mConstants.INACTIVE_TIMEOUT), eq(false));

        enterDeepState(STATE_ACTIVE);
        setQuickDozeEnabled(true);
        setChargingOn(false);
        setScreenOn(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController).scheduleAlarmLocked(
                eq(timeUntilAlarm + mConstants.QUICK_DOZE_DELAY_TIMEOUT), eq(false));
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
        setAlarmSoon(false);
        InOrder inOrder = inOrder(mDeviceIdleController);

        enterDeepState(STATE_ACTIVE);
        setQuickDozeEnabled(true);
        setChargingOn(false);
        setScreenOn(false);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController)
                .scheduleAlarmLocked(eq(mConstants.QUICK_DOZE_DELAY_TIMEOUT), eq(false));

        enterDeepState(STATE_INACTIVE);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController)
                .scheduleAlarmLocked(eq(mConstants.QUICK_DOZE_DELAY_TIMEOUT), eq(false));

        enterDeepState(STATE_IDLE_PENDING);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController)
                .scheduleAlarmLocked(eq(mConstants.QUICK_DOZE_DELAY_TIMEOUT), eq(false));

        enterDeepState(STATE_SENSING);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController)
                .scheduleAlarmLocked(eq(mConstants.QUICK_DOZE_DELAY_TIMEOUT), eq(false));

        enterDeepState(STATE_LOCATING);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController)
                .scheduleAlarmLocked(eq(mConstants.QUICK_DOZE_DELAY_TIMEOUT), eq(false));

        // IDLE should stay as IDLE.
        enterDeepState(STATE_IDLE);
        // Clear out any alarm setting from the order before checking for this section.
        inOrder.verify(mDeviceIdleController, atLeastOnce())
                .scheduleAlarmLocked(anyLong(), anyBoolean());
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_IDLE);
        inOrder.verify(mDeviceIdleController, never()).scheduleAlarmLocked(anyLong(), anyBoolean());

        // IDLE_MAINTENANCE should stay as IDLE_MAINTENANCE.
        enterDeepState(STATE_IDLE_MAINTENANCE);
        // Clear out any alarm setting from the order before checking for this section.
        inOrder.verify(mDeviceIdleController, atLeastOnce())
                .scheduleAlarmLocked(anyLong(), anyBoolean());
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_IDLE_MAINTENANCE);
        inOrder.verify(mDeviceIdleController, never()).scheduleAlarmLocked(anyLong(), anyBoolean());

        // State is already QUICK_DOZE_DELAY. No work should be done.
        enterDeepState(STATE_QUICK_DOZE_DELAY);
        // Clear out any alarm setting from the order before checking for this section.
        inOrder.verify(mDeviceIdleController, atLeastOnce())
                .scheduleAlarmLocked(anyLong(), anyBoolean());
        setQuickDozeEnabled(true);
        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController, never()).scheduleAlarmLocked(anyLong(), anyBoolean());
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

    @Test
    public void testLightIdleAlarmUnaffectedByMotion() {
        setNetworkConnected(true);
        mDeviceIdleController.setJobsActive(false);
        mDeviceIdleController.setAlarmsActive(false);
        mDeviceIdleController.setActiveIdleOpsForTest(0);
        spyOn(mDeviceIdleController);

        InOrder inOrder = inOrder(mDeviceIdleController);

        // Set state to INACTIVE.
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        setChargingOn(false);
        setScreenOn(false);
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);

        // No active ops means INACTIVE should go straight to IDLE.
        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);
        inOrder.verify(mDeviceIdleController).scheduleLightAlarmLocked(
                longThat(l -> l == mConstants.LIGHT_IDLE_TIMEOUT),
                longThat(l -> l == mConstants.LIGHT_IDLE_TIMEOUT_INITIAL_FLEX));

        // Should just alternate between IDLE and IDLE_MAINTENANCE now.

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);
        inOrder.verify(mDeviceIdleController).scheduleLightAlarmLocked(
                longThat(l -> l >= mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET),
                longThat(l -> l == mConstants.FLEX_TIME_SHORT));

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);
        inOrder.verify(mDeviceIdleController).scheduleLightAlarmLocked(
                longThat(l -> l > mConstants.LIGHT_IDLE_TIMEOUT),
                longThat(l -> l > mConstants.LIGHT_IDLE_TIMEOUT_INITIAL_FLEX));

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);
        inOrder.verify(mDeviceIdleController).scheduleLightAlarmLocked(
                longThat(l -> l >= mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET),
                longThat(l -> l == mConstants.FLEX_TIME_SHORT));

        // Test that motion doesn't reset the idle timeout.
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);
        inOrder.verify(mDeviceIdleController).scheduleLightAlarmLocked(
                longThat(l -> l > mConstants.LIGHT_IDLE_TIMEOUT),
                longThat(l -> l > mConstants.LIGHT_IDLE_TIMEOUT_INITIAL_FLEX));
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
            float expectedfactor = mDeviceIdleController.getPreIdleTimeoutByMode(mode);
            float curfactor = mDeviceIdleController.getPreIdleTimeoutFactor();
            assertEquals("Pre idle time factor of mode [" + mode + "].",
                    expectedfactor, curfactor, delta);
            mDeviceIdleController.resetPreIdleTimeoutMode();

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

    @Test
    public void testStationaryDetection_QuickDozeOff() {
        setQuickDozeEnabled(false);
        enterDeepState(STATE_IDLE);
        // Regular progression through states, so time should have increased appropriately.
        mInjector.nowElapsed += mConstants.IDLE_AFTER_INACTIVE_TIMEOUT + mConstants.SENSING_TIMEOUT
                + mConstants.LOCATING_TIMEOUT;

        StationaryListenerForTest stationaryListener = new StationaryListenerForTest();

        mDeviceIdleController.registerStationaryListener(stationaryListener);

        // Go to IDLE_MAINTENANCE
        mDeviceIdleController.stepIdleStateLocked("testing");

        // Back to IDLE
        mDeviceIdleController.stepIdleStateLocked("testing");
        assertTrue(stationaryListener.isStationary);

        // Test motion
        stationaryListener.motionExpected = true;
        mDeviceIdleController.mMotionListener.onTrigger(null);
        assertFalse(stationaryListener.isStationary);
    }

    @Test
    public void testStationaryDetection_QuickDozeOn_NoMotion() {
        // Short timeout for testing.
        mConstants.MOTION_INACTIVE_TIMEOUT = 6000L;
        doReturn(Sensor.REPORTING_MODE_ONE_SHOT).when(mMotionSensor).getReportingMode();
        doReturn(true).when(mSensorManager)
                .requestTriggerSensor(eq(mDeviceIdleController.mMotionListener), eq(mMotionSensor));
        setAlarmSoon(false);
        enterDeepState(STATE_QUICK_DOZE_DELAY);
        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE);
        // Quick doze progression through states, so time should have increased appropriately.
        mInjector.nowElapsed += mConstants.QUICK_DOZE_DELAY_TIMEOUT;
        final ArgumentCaptor<AlarmManager.OnAlarmListener> motionAlarmListener = ArgumentCaptor
                .forClass(AlarmManager.OnAlarmListener.class);
        final ArgumentCaptor<AlarmManager.OnAlarmListener> motionRegistrationAlarmListener =
                ArgumentCaptor.forClass(AlarmManager.OnAlarmListener.class);
        doNothing().when(mAlarmManager).setWindow(anyInt(), anyLong(), anyLong(),
                eq("DeviceIdleController.motion"), motionAlarmListener.capture(), any());
        doNothing().when(mAlarmManager).setWindow(anyInt(), anyLong(), anyLong(),
                eq("DeviceIdleController.motion_registration"),
                motionRegistrationAlarmListener.capture(), any());

        StationaryListenerForTest stationaryListener = new StationaryListenerForTest();
        spyOn(stationaryListener);
        InOrder inOrder = inOrder(stationaryListener);

        stationaryListener.motionExpected = true;
        mDeviceIdleController.registerStationaryListener(stationaryListener);
        inOrder.verify(stationaryListener, timeout(1000L).times(1))
                .onDeviceStationaryChanged(eq(false));
        assertFalse(stationaryListener.isStationary);

        // Go to IDLE_MAINTENANCE
        mDeviceIdleController.stepIdleStateLocked("testing");

        mInjector.nowElapsed += mConstants.MOTION_INACTIVE_TIMEOUT / 2;

        // Back to IDLE
        mDeviceIdleController.stepIdleStateLocked("testing");

        // Now enough time has passed.
        mInjector.nowElapsed += mConstants.MOTION_INACTIVE_TIMEOUT;
        stationaryListener.motionExpected = false;
        motionAlarmListener.getValue().onAlarm();
        inOrder.verify(stationaryListener, timeout(1000L).times(1))
                .onDeviceStationaryChanged(eq(true));
        assertTrue(stationaryListener.isStationary);

        stationaryListener.motionExpected = true;
        mDeviceIdleController.mMotionListener.onTrigger(null);
        inOrder.verify(stationaryListener, timeout(1000L).times(1))
                .onDeviceStationaryChanged(eq(false));
        assertFalse(stationaryListener.isStationary);

        // Since we're in quick doze, the device shouldn't stop idling.
        verifyStateConditions(STATE_IDLE);

        // Go to IDLE_MAINTENANCE
        mDeviceIdleController.stepIdleStateLocked("testing");

        motionRegistrationAlarmListener.getValue().onAlarm();
        mInjector.nowElapsed += mConstants.MOTION_INACTIVE_TIMEOUT / 2;

        // Back to IDLE
        stationaryListener.motionExpected = false;
        mDeviceIdleController.stepIdleStateLocked("testing");
        verify(mSensorManager,
                timeout(mConstants.MOTION_INACTIVE_TIMEOUT).times(2))
                .requestTriggerSensor(eq(mDeviceIdleController.mMotionListener), eq(mMotionSensor));

        // Now enough time has passed.
        mInjector.nowElapsed += mConstants.MOTION_INACTIVE_TIMEOUT;
        motionAlarmListener.getValue().onAlarm();
        inOrder.verify(stationaryListener,
                timeout(mConstants.MOTION_INACTIVE_TIMEOUT).times(1))
                .onDeviceStationaryChanged(eq(true));
        assertTrue(stationaryListener.isStationary);
    }

    @Test
    public void testStationaryDetection_QuickDozeOn_OneShot() {
        // Short timeout for testing.
        mConstants.MOTION_INACTIVE_TIMEOUT = 6000L;
        doReturn(Sensor.REPORTING_MODE_ONE_SHOT).when(mMotionSensor).getReportingMode();
        setAlarmSoon(false);
        enterDeepState(STATE_QUICK_DOZE_DELAY);
        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE);
        // Quick doze progression through states, so time should have increased appropriately.
        mInjector.nowElapsed += mConstants.QUICK_DOZE_DELAY_TIMEOUT;
        final ArgumentCaptor<AlarmManager.OnAlarmListener> alarmListener = ArgumentCaptor
                .forClass(AlarmManager.OnAlarmListener.class);
        doNothing().when(mAlarmManager).setWindow(
                anyInt(), anyLong(), anyLong(), eq("DeviceIdleController.motion"), any(), any());
        doNothing().when(mAlarmManager).setWindow(anyInt(), anyLong(), anyLong(),
                eq("DeviceIdleController.motion_registration"),
                alarmListener.capture(), any());
        ArgumentCaptor<TriggerEventListener> listenerCaptor =
                ArgumentCaptor.forClass(TriggerEventListener.class);

        StationaryListenerForTest stationaryListener = new StationaryListenerForTest();
        spyOn(stationaryListener);
        InOrder inOrder = inOrder(stationaryListener, mSensorManager);

        stationaryListener.motionExpected = true;
        mDeviceIdleController.registerStationaryListener(stationaryListener);
        inOrder.verify(stationaryListener, timeout(1000L).times(1))
                .onDeviceStationaryChanged(eq(false));
        assertFalse(stationaryListener.isStationary);
        inOrder.verify(mSensorManager)
                .requestTriggerSensor(listenerCaptor.capture(), eq(mMotionSensor));
        final TriggerEventListener listener = listenerCaptor.getValue();

        // Trigger motion
        listener.onTrigger(mock(TriggerEvent.class));
        inOrder.verify(stationaryListener, timeout(1000L).times(1))
                .onDeviceStationaryChanged(eq(false));

        // Make sure the listener is re-registered.
        alarmListener.getValue().onAlarm();
        inOrder.verify(mSensorManager).requestTriggerSensor(eq(listener), eq(mMotionSensor));
    }

    @Test
    public void testStationaryDetection_QuickDozeOn_MultiShot() {
        // Short timeout for testing.
        mConstants.MOTION_INACTIVE_TIMEOUT = 6000L;
        doReturn(Sensor.REPORTING_MODE_CONTINUOUS).when(mMotionSensor).getReportingMode();
        setAlarmSoon(false);
        enterDeepState(STATE_QUICK_DOZE_DELAY);
        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE);
        // Quick doze progression through states, so time should have increased appropriately.
        mInjector.nowElapsed += mConstants.QUICK_DOZE_DELAY_TIMEOUT;
        final ArgumentCaptor<AlarmManager.OnAlarmListener> alarmListener = ArgumentCaptor
                .forClass(AlarmManager.OnAlarmListener.class);
        doNothing().when(mAlarmManager).setWindow(
                anyInt(), anyLong(), anyLong(), eq("DeviceIdleController.motion"), any(), any());
        doNothing().when(mAlarmManager).setWindow(anyInt(), anyLong(), anyLong(),
                eq("DeviceIdleController.motion_registration"),
                alarmListener.capture(), any());
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);

        StationaryListenerForTest stationaryListener = new StationaryListenerForTest();
        spyOn(stationaryListener);
        InOrder inOrder = inOrder(stationaryListener, mSensorManager);

        stationaryListener.motionExpected = true;
        mDeviceIdleController.registerStationaryListener(stationaryListener);
        inOrder.verify(stationaryListener, timeout(1000L).times(1))
                .onDeviceStationaryChanged(eq(false));
        assertFalse(stationaryListener.isStationary);
        inOrder.verify(mSensorManager)
                .registerListener(listenerCaptor.capture(), eq(mMotionSensor),
                        eq(SensorManager.SENSOR_DELAY_NORMAL));
        final SensorEventListener listener = listenerCaptor.getValue();

        // Trigger motion
        listener.onSensorChanged(mock(SensorEvent.class));
        inOrder.verify(stationaryListener, timeout(1000L).times(1))
                .onDeviceStationaryChanged(eq(false));

        // Make sure the listener is re-registered.
        alarmListener.getValue().onAlarm();
        inOrder.verify(mSensorManager)
                .registerListener(eq(listener), eq(mMotionSensor),
                        eq(SensorManager.SENSOR_DELAY_NORMAL));
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
        mInjector.connectivityManager = mConnectivityManager;
        final NetworkInfo ani = mock(NetworkInfo.class);
        doReturn(connected).when(ani).isConnected();
        doReturn(ani).when(mConnectivityManager).getActiveNetworkInfo();
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
                long newAlarm = mDeviceIdleController.getNextAlarmTime();
                long newDelay = (long) ((alarm - now) * factor);
                assertTrue("setPreIdleTimeoutFactor: " + factor,
                        Math.abs(newDelay - (newAlarm - now)) <  errorTolerance);
                mDeviceIdleController.resetPreIdleTimeoutMode();
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
            }
        } else {
            mDeviceIdleController.setPreIdleTimeoutFactor(factor);
            long newAlarm = mDeviceIdleController.getNextAlarmTime();
            assertTrue("setPreIdleTimeoutFactor: " + factor
                    + " shounld not change next alarm" ,
                    (newAlarm == alarm));
            mDeviceIdleController.resetPreIdleTimeoutMode();
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
            long newAlarm = mDeviceIdleController.getNextAlarmTime();
            assertTrue("maintenance not reschedule IDLE_TIMEOUT * 0.6",
                    newAlarm == alarm);
            mDeviceIdleController.setIdleStartTimeForTest(
                    now - (long) (mConstants.IDLE_TIMEOUT * 1.2));
            newAlarm = mDeviceIdleController.getNextAlarmTime();
            assertTrue("maintenance not reschedule IDLE_TIMEOUT * 1.2",
                    (newAlarm - now) < minuteInMillis);
            mDeviceIdleController.resetPreIdleTimeoutMode();
        }
    }
}
