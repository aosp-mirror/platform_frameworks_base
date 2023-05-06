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
import static com.android.server.DeviceIdleController.LIGHT_STATE_WAITING_FOR_NETWORK;
import static com.android.server.DeviceIdleController.MSG_REPORT_STATIONARY_STATUS;
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
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;

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
@SuppressWarnings("GuardedBy")
@RunWith(AndroidJUnit4.class)
public class DeviceIdleControllerTest {
    private DeviceIdleController mDeviceIdleController;
    private DeviceIdleController.MyHandler mHandler;
    private AnyMotionDetectorForTest mAnyMotionDetector;
    private AppStateTrackerForTest mAppStateTracker;
    private DeviceIdleController.Constants mConstants;
    private TelephonyCallback.OutgoingEmergencyCallListener mEmergencyCallListener;
    private TelephonyCallback.CallStateListener mCallStateListener;
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
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private Sensor mOffBodySensor;

    class InjectorForTest extends DeviceIdleController.Injector {
        ConnectivityManager connectivityManager;
        LocationManager locationManager;
        ConstraintController constraintController;
        // Freeze time for testing.
        long nowElapsed;
        boolean useMotionSensor = true;
        boolean isLocationPrefetchEnabled = true;

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
                        message.what != MSG_REPORT_STATIONARY_STATUS));
                doAnswer(new Answer<Boolean>() {
                    @Override
                    public Boolean answer(InvocationOnMock invocation) throws Throwable {
                        Message msg = invocation.getArgument(0);
                        mHandler.handleMessage(msg);
                        return true;
                    }
                }).when(mHandler).sendMessageDelayed(
                        argThat((message) -> message.what == MSG_REPORT_STATIONARY_STATUS),
                        anyLong());
            }

            return mHandler;
        }

        @Override
        Sensor getMotionSensor() {
            return mMotionSensor;
        }

        @Override
        boolean isLocationPrefetchEnabled() {
            return isLocationPrefetchEnabled;
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
        TelephonyManager getTelephonyManager() {
            return mTelephonyManager;
        }

        @Override
        boolean useMotionSensor() {
            return useMotionSensor;
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
                fail("Got unexpected device stationary status: " + isStationary);
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
                .setWindow(anyInt(), anyLong(), anyLong(), anyString(), any(), any(Handler.class));
        doReturn(mock(Sensor.class)).when(mSensorManager)
                .getDefaultSensor(eq(Sensor.TYPE_SIGNIFICANT_MOTION), eq(true));
        doReturn(true).when(mSensorManager).registerListener(any(), any(), anyInt());
        mAppStateTracker = new AppStateTrackerForTest(getContext(), Looper.getMainLooper());
        mAnyMotionDetector = new AnyMotionDetectorForTest();
        mInjector = new InjectorForTest(getContext());

        setupDeviceIdleController();
    }

    private void setupDeviceIdleController() {
        reset(mTelephonyManager);

        mDeviceIdleController = new DeviceIdleController(getContext(), mInjector);
        spyOn(mDeviceIdleController);
        doNothing().when(mDeviceIdleController).publishBinderService(any(), any());
        mDeviceIdleController.onStart();
        mDeviceIdleController.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        mDeviceIdleController.setDeepEnabledForTest(true);
        mDeviceIdleController.setLightEnabledForTest(true);

        // Get the same Constants object that mDeviceIdleController got.
        mConstants = mInjector.getConstants(mDeviceIdleController);

        final ArgumentCaptor<TelephonyCallback> telephonyCallbackCaptor =
                ArgumentCaptor.forClass(TelephonyCallback.class);
        verify(mTelephonyManager)
                .registerTelephonyCallback(any(), telephonyCallbackCaptor.capture());
        mEmergencyCallListener = (TelephonyCallback.OutgoingEmergencyCallListener)
                telephonyCallbackCaptor.getValue();
        mCallStateListener =
                (TelephonyCallback.CallStateListener) telephonyCallbackCaptor.getValue();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @After
    public void cleanupDeviceIdleController() {
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

        // All other conditions allow for going INACTIVE...
        setAlarmSoon(false);
        setChargingOn(false);
        setScreenOn(false);
        // ...except the emergency call.
        setEmergencyCallActive(true);

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

        // All other conditions allow for going INACTIVE...
        setChargingOn(false);
        setScreenOn(false);
        // ...except the emergency call.
        setEmergencyCallActive(true);

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
        setEmergencyCallActive(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_INACTIVE);
        verify(mDeviceIdleController).scheduleAlarmLocked(eq(mConstants.INACTIVE_TIMEOUT));
    }

    @Test
    public void testStateActiveToStateInactive_DoNotUseMotionSensor() {
        mInjector.useMotionSensor = false;
        cleanupDeviceIdleController();
        setupDeviceIdleController();
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        verifyStateConditions(STATE_ACTIVE);

        setAlarmSoon(false);
        setChargingOn(false);
        setScreenOn(false);
        setEmergencyCallActive(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_INACTIVE);
        verify(mDeviceIdleController).scheduleAlarmLocked(eq(mConstants.INACTIVE_TIMEOUT));
        // The device configuration doesn't require a motion sensor to proceed with idling.
        // This should be the case on TVs or other such devices. We should set an alarm to move
        // forward if the motion sensor is missing in this case.
        verify(mAlarmManager).setWindow(
                anyInt(), anyLong(), anyLong(),
                eq("DeviceIdleController.deep"), any(), any(Handler.class));
    }

    @Test
    public void testStateActiveToStateInactive_MissingMotionSensor() {
        mInjector.useMotionSensor = true;
        mMotionSensor = null;
        cleanupDeviceIdleController();
        setupDeviceIdleController();
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        verifyStateConditions(STATE_ACTIVE);

        setAlarmSoon(false);
        setChargingOn(false);
        setScreenOn(false);
        setEmergencyCallActive(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_INACTIVE);
        verify(mDeviceIdleController).scheduleAlarmLocked(eq(mConstants.INACTIVE_TIMEOUT));
        // The device configuration requires a motion sensor to proceed with idling,
        // so we should never set an alarm to move forward if the motion sensor is
        // missing in this case.
        verify(mAlarmManager, never()).setWindow(
                anyInt(), anyLong(), anyLong(),
                eq("DeviceIdleController.deep"), any(), any(Handler.class));
        verify(mAlarmManager, never()).set(
                anyInt(), anyLong(),
                eq("DeviceIdleController.deep"), any(), any(Handler.class));
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
                .scheduleAlarmLocked(eq(timeUntilAlarm + mConstants.INACTIVE_TIMEOUT));

        enterDeepState(STATE_ACTIVE);
        setQuickDozeEnabled(true);
        setChargingOn(false);
        setScreenOn(false);

        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController).scheduleAlarmLocked(
                eq(timeUntilAlarm + mConstants.QUICK_DOZE_DELAY_TIMEOUT));
    }

    @Test
    public void testLightStateActiveToLightStateInactive_ConditionsMet() {
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        setChargingOn(false);
        setScreenOn(false);
        setEmergencyCallActive(false);

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
                .scheduleAlarmLocked(eq(mConstants.QUICK_DOZE_DELAY_TIMEOUT));

        enterDeepState(STATE_INACTIVE);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController)
                .scheduleAlarmLocked(eq(mConstants.QUICK_DOZE_DELAY_TIMEOUT));

        enterDeepState(STATE_IDLE_PENDING);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController)
                .scheduleAlarmLocked(eq(mConstants.QUICK_DOZE_DELAY_TIMEOUT));

        enterDeepState(STATE_SENSING);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController)
                .scheduleAlarmLocked(eq(mConstants.QUICK_DOZE_DELAY_TIMEOUT));

        enterDeepState(STATE_LOCATING);
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController)
                .scheduleAlarmLocked(eq(mConstants.QUICK_DOZE_DELAY_TIMEOUT));

        // IDLE should stay as IDLE.
        enterDeepState(STATE_IDLE);
        // Clear out any alarm setting from the order before checking for this section.
        inOrder.verify(mDeviceIdleController, atLeastOnce()).scheduleAlarmLocked(anyLong());
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_IDLE);
        inOrder.verify(mDeviceIdleController, never()).scheduleAlarmLocked(anyLong());

        // IDLE_MAINTENANCE should stay as IDLE_MAINTENANCE.
        enterDeepState(STATE_IDLE_MAINTENANCE);
        // Clear out any alarm setting from the order before checking for this section.
        inOrder.verify(mDeviceIdleController, atLeastOnce()).scheduleAlarmLocked(anyLong());
        setQuickDozeEnabled(true);
        verifyStateConditions(STATE_IDLE_MAINTENANCE);
        inOrder.verify(mDeviceIdleController, never()).scheduleAlarmLocked(anyLong());

        // State is already QUICK_DOZE_DELAY. No work should be done.
        enterDeepState(STATE_QUICK_DOZE_DELAY);
        // Clear out any alarm setting from the order before checking for this section.
        inOrder.verify(mDeviceIdleController, atLeastOnce()).scheduleAlarmLocked(anyLong());
        setQuickDozeEnabled(true);
        mDeviceIdleController.becomeInactiveIfAppropriateLocked();
        verifyStateConditions(STATE_QUICK_DOZE_DELAY);
        inOrder.verify(mDeviceIdleController, never()).scheduleAlarmLocked(anyLong());
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
    public void testStepIdleStateLocked_ValidStates_MissingMotionSensor() {
        mInjector.useMotionSensor = true;
        mMotionSensor = null;
        cleanupDeviceIdleController();
        setupDeviceIdleController();
        mInjector.locationManager = mLocationManager;
        doReturn(mock(LocationProvider.class)).when(mLocationManager).getProvider(anyString());
        // Make sure the controller doesn't think there's a wake-from-idle alarm coming soon.
        setAlarmSoon(false);

        InOrder alarmManagerInOrder = inOrder(mAlarmManager);

        // Set state to INACTIVE.
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        setChargingOn(false);
        setScreenOn(false);
        verifyStateConditions(STATE_INACTIVE);

        // The device configuration requires a motion sensor to proceed with idling,
        // so we should never set an alarm to move forward if the motion sensor is
        // missing in this case.
        alarmManagerInOrder.verify(mAlarmManager, never())
                .setWindow(anyInt(), anyLong(), anyLong(),
                        eq("DeviceIdleController.deep"), any(), any(Handler.class));

        // Pretend that someone is forcing state stepping via adb

        mDeviceIdleController.stepIdleStateLocked("testing");
        // verifyStateConditions knows this state typically shouldn't happen during normal
        // operation, so we can't use it directly here. For this test, all we care about
        // is that the state stepped forward.
        assertEquals(STATE_IDLE_PENDING, mDeviceIdleController.getState());
        // Still no alarm
        alarmManagerInOrder.verify(mAlarmManager, never())
                .setWindow(anyInt(), anyLong(), anyLong(),
                        eq("DeviceIdleController.deep"), any(), any(Handler.class));

        mDeviceIdleController.stepIdleStateLocked("testing");
        // verifyStateConditions knows this state typically shouldn't happen during normal
        // operation, so we can't use it directly here. For this test, all we care about
        // is that the state stepped forward.
        assertEquals(STATE_SENSING, mDeviceIdleController.getState());
        // Still no alarm
        alarmManagerInOrder.verify(mAlarmManager, never())
                .setWindow(anyInt(), anyLong(), anyLong(),
                        eq("DeviceIdleController.deep"), any(), any(Handler.class));

        mDeviceIdleController.stepIdleStateLocked("testing");
        // Location manager exists with a provider, so SENSING should go to LOCATING.
        // verifyStateConditions knows this state typically shouldn't happen during normal
        // operation, so we can't use it directly here. For this test, all we care about
        // is that the state stepped forward.
        assertEquals(STATE_LOCATING, mDeviceIdleController.getState());
        // Still no alarm
        alarmManagerInOrder.verify(mAlarmManager, never())
                .setWindow(anyInt(), anyLong(), anyLong(),
                        eq("DeviceIdleController.deep"), any(), any(Handler.class));

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE);
        // The device was forced into IDLE. AlarmManager should be notified.
        alarmManagerInOrder.verify(mAlarmManager)
                .setIdleUntil(anyInt(), anyLong(),
                        eq("DeviceIdleController.deep"), any(), any(Handler.class));

        // Should just alternate between IDLE and IDLE_MAINTENANCE now. Since we've gotten to this
        // point, alarms should be set on each transition.

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE_MAINTENANCE);
        alarmManagerInOrder.verify(mAlarmManager)
                .setWindow(anyInt(), anyLong(), anyLong(),
                        eq("DeviceIdleController.deep"), any(), any(Handler.class));

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE);
        alarmManagerInOrder.verify(mAlarmManager)
                .setIdleUntil(anyInt(), anyLong(),
                        eq("DeviceIdleController.deep"), any(), any(Handler.class));

        mDeviceIdleController.stepIdleStateLocked("testing");
        verifyStateConditions(STATE_IDLE_MAINTENANCE);
        alarmManagerInOrder.verify(mAlarmManager)
                .setWindow(anyInt(), anyLong(), anyLong(),
                        eq("DeviceIdleController.deep"), any(), any(Handler.class));
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
    public void testStepIdleStateLocked_ValidStates_LocationPrefetchDisabled() {
        mInjector.locationManager = mLocationManager;
        mInjector.isLocationPrefetchEnabled = false;
        cleanupDeviceIdleController();
        setupDeviceIdleController();
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
        // Prefetch location is off, so SENSING should go straight through to IDLE.
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
    public void testStepIdleStateLocked_ValidStates_WithLocationManager_MissingProviders() {
        mInjector.locationManager = mLocationManager;
        doReturn(null).when(mLocationManager)
                .getProvider(eq(LocationManager.FUSED_PROVIDER));
        doReturn(null).when(mLocationManager)
                .getProvider(eq(LocationManager.GPS_PROVIDER));
        doReturn(mock(LocationProvider.class)).when(mLocationManager)
                .getProvider(eq(LocationManager.NETWORK_PROVIDER));
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
        // Location manager exists, but the required providers don't exist,
        // so SENSING should go straight through to IDLE.
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

        // After enough time, INACTIVE should go to IDLE regardless of any active ops.
        mDeviceIdleController.setJobsActive(true);
        mDeviceIdleController.setAlarmsActive(true);
        mDeviceIdleController.setActiveIdleOpsForTest(1);
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

        // After enough time, INACTIVE should go to IDLE regardless of any active ops.
        mDeviceIdleController.setJobsActive(true);
        mDeviceIdleController.setAlarmsActive(true);
        mDeviceIdleController.setActiveIdleOpsForTest(1);
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
    public void testLightStepIdleStateIdlingTimeIncreases() {
        final long maintenanceTimeMs = 60_000L;
        mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET = maintenanceTimeMs;
        mConstants.LIGHT_IDLE_MAINTENANCE_MAX_BUDGET = maintenanceTimeMs;
        mConstants.LIGHT_IDLE_TIMEOUT = 5 * 60_000L;
        mConstants.LIGHT_MAX_IDLE_TIMEOUT = 20 * 60_000L;
        mConstants.LIGHT_IDLE_FACTOR = 2f;

        setNetworkConnected(true);
        mDeviceIdleController.setJobsActive(false);
        mDeviceIdleController.setAlarmsActive(false);
        mDeviceIdleController.setActiveIdleOpsForTest(0);

        InOrder alarmManagerInOrder = inOrder(mAlarmManager);

        final ArgumentCaptor<AlarmManager.OnAlarmListener> alarmListenerCaptor = ArgumentCaptor
                .forClass(AlarmManager.OnAlarmListener.class);
        doNothing().when(mAlarmManager).setWindow(anyInt(), anyLong(), anyLong(),
                eq("DeviceIdleController.light"), alarmListenerCaptor.capture(), any());

        // Set state to INACTIVE.
        mDeviceIdleController.becomeActiveLocked("testing", 0);
        setChargingOn(false);
        setScreenOn(false);
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);
        long idlingTimeMs = mConstants.LIGHT_IDLE_TIMEOUT;
        final long idleAfterInactiveExpiryTime =
                mInjector.nowElapsed + mConstants.LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT;
        alarmManagerInOrder.verify(mAlarmManager).setWindow(
                eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                eq(idleAfterInactiveExpiryTime),
                anyLong(), anyString(), any(), any(Handler.class));

        final AlarmManager.OnAlarmListener alarmListener =
                alarmListenerCaptor.getAllValues().get(0);

        // INACTIVE -> IDLE alarm
        mInjector.nowElapsed = mDeviceIdleController.getNextLightAlarmTimeForTesting();
        alarmListener.onAlarm();
        verifyLightStateConditions(LIGHT_STATE_IDLE);
        alarmManagerInOrder.verify(mAlarmManager).setWindow(
                eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                eq(mInjector.nowElapsed + idlingTimeMs),
                anyLong(), anyString(), any(), any(Handler.class));

        for (int i = 0; i < 2; ++i) {
            // IDLE->MAINTENANCE alarm
            mInjector.nowElapsed = mDeviceIdleController.getNextLightAlarmTimeForTesting();
            alarmListener.onAlarm();
            verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);
            long maintenanceExpiryTime = mInjector.nowElapsed + maintenanceTimeMs;
            idlingTimeMs *= mConstants.LIGHT_IDLE_FACTOR;
            // Set MAINTENANCE->IDLE
            alarmManagerInOrder.verify(mAlarmManager).setWindow(
                    eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                    eq(maintenanceExpiryTime),
                    anyLong(), anyString(), any(), any(Handler.class));

            // MAINTENANCE->IDLE alarm
            mInjector.nowElapsed = mDeviceIdleController.getNextLightAlarmTimeForTesting();
            alarmListener.onAlarm();
            verifyLightStateConditions(LIGHT_STATE_IDLE);
            // Set IDLE->MAINTENANCE again
            alarmManagerInOrder.verify(mAlarmManager).setWindow(
                    eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                    eq(mInjector.nowElapsed + idlingTimeMs),
                    anyLong(), anyString(), any(), any(Handler.class));
        }
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
                longThat(l -> l == mConstants.LIGHT_IDLE_TIMEOUT_INITIAL_FLEX),
                eq(true));

        // Should just alternate between IDLE and IDLE_MAINTENANCE now.

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);
        inOrder.verify(mDeviceIdleController).scheduleLightAlarmLocked(
                longThat(l -> l >= mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET),
                longThat(l -> l == mConstants.FLEX_TIME_SHORT),
                eq(true));

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);
        inOrder.verify(mDeviceIdleController).scheduleLightAlarmLocked(
                longThat(l -> l > mConstants.LIGHT_IDLE_TIMEOUT),
                longThat(l -> l > mConstants.LIGHT_IDLE_TIMEOUT_INITIAL_FLEX),
                eq(true));

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE_MAINTENANCE);
        inOrder.verify(mDeviceIdleController).scheduleLightAlarmLocked(
                longThat(l -> l >= mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET),
                longThat(l -> l == mConstants.FLEX_TIME_SHORT),
                eq(true));

        // Test that motion doesn't reset the idle timeout.
        mDeviceIdleController.handleMotionDetectedLocked(50, "test");

        mDeviceIdleController.stepLightIdleStateLocked("testing");
        verifyLightStateConditions(LIGHT_STATE_IDLE);
        inOrder.verify(mDeviceIdleController).scheduleLightAlarmLocked(
                longThat(l -> l > mConstants.LIGHT_IDLE_TIMEOUT),
                longThat(l -> l > mConstants.LIGHT_IDLE_TIMEOUT_INITIAL_FLEX),
                eq(true));
    }

    @Test
    public void testEmergencyCallEndTriggersInactive() {
        setAlarmSoon(false);
        setChargingOn(false);
        setScreenOn(false);
        setEmergencyCallActive(true);

        verifyStateConditions(STATE_ACTIVE);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        setEmergencyCallActive(false);

        verifyStateConditions(STATE_INACTIVE);
        verifyLightStateConditions(LIGHT_STATE_INACTIVE);
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
                anyInt(), anyLong(), anyLong(), eq("DeviceIdleController.motion"), any(),
                any(Handler.class));
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
                anyInt(), anyLong(), anyLong(), eq("DeviceIdleController.motion"), any(),
                any(Handler.class));
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

    @Test
    public void testStationaryDetection_NoDoze_AfterMotion() {
        // Short timeout for testing.
        mConstants.MOTION_INACTIVE_TIMEOUT = 6000L;
        doReturn(Sensor.REPORTING_MODE_CONTINUOUS).when(mMotionSensor).getReportingMode();
        setAlarmSoon(true);

        final ArgumentCaptor<AlarmManager.OnAlarmListener> regAlarmListener = ArgumentCaptor
                .forClass(AlarmManager.OnAlarmListener.class);
        final ArgumentCaptor<AlarmManager.OnAlarmListener> motionAlarmListener = ArgumentCaptor
                .forClass(AlarmManager.OnAlarmListener.class);
        doNothing().when(mAlarmManager).setWindow(
                anyInt(), anyLong(), anyLong(), eq("DeviceIdleController.motion"),
                motionAlarmListener.capture(), any());
        doNothing().when(mAlarmManager).setWindow(anyInt(), anyLong(), anyLong(),
                eq("DeviceIdleController.motion_registration"),
                regAlarmListener.capture(), any());
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);

        StationaryListenerForTest stationaryListener = new StationaryListenerForTest();
        spyOn(stationaryListener);
        InOrder inOrder = inOrder(stationaryListener, mSensorManager, mAlarmManager);

        stationaryListener.motionExpected = true;
        mDeviceIdleController.registerStationaryListener(stationaryListener);
        inOrder.verify(stationaryListener, timeout(1000L).times(1))
                .onDeviceStationaryChanged(eq(false));
        assertFalse(stationaryListener.isStationary);
        inOrder.verify(mSensorManager)
                .registerListener(listenerCaptor.capture(), eq(mMotionSensor),
                        eq(SensorManager.SENSOR_DELAY_NORMAL));
        inOrder.verify(mAlarmManager).setWindow(
                anyInt(), eq(mInjector.nowElapsed + mConstants.MOTION_INACTIVE_TIMEOUT), anyLong(),
                eq("DeviceIdleController.motion"), any(), any(Handler.class));
        final SensorEventListener listener = listenerCaptor.getValue();

        // Trigger motion
        listener.onSensorChanged(mock(SensorEvent.class));
        inOrder.verify(stationaryListener, timeout(1000L).times(1))
                .onDeviceStationaryChanged(eq(false));
        final ArgumentCaptor<Long> registrationTimeCaptor = ArgumentCaptor.forClass(Long.class);
        inOrder.verify(mAlarmManager).setWindow(
                anyInt(), registrationTimeCaptor.capture(), anyLong(),
                eq("DeviceIdleController.motion_registration"), any(), any(Handler.class));

        // Make sure the listener is re-registered.
        mInjector.nowElapsed = registrationTimeCaptor.getValue();
        regAlarmListener.getValue().onAlarm();
        inOrder.verify(mSensorManager)
                .registerListener(eq(listener), eq(mMotionSensor),
                        eq(SensorManager.SENSOR_DELAY_NORMAL));
        final ArgumentCaptor<Long> timeoutCaptor = ArgumentCaptor.forClass(Long.class);
        inOrder.verify(mAlarmManager).setWindow(anyInt(), timeoutCaptor.capture(), anyLong(),
                eq("DeviceIdleController.motion"), any(), any(Handler.class));

        // No motion before timeout
        stationaryListener.motionExpected = false;
        mInjector.nowElapsed = timeoutCaptor.getValue();
        motionAlarmListener.getValue().onAlarm();
        inOrder.verify(stationaryListener, timeout(1000L).times(1))
                .onDeviceStationaryChanged(eq(true));
    }

    @Test
    public void testEmergencyEndsIdle() {
        enterDeepState(STATE_ACTIVE);
        setEmergencyCallActive(true);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_INACTIVE);
        setEmergencyCallActive(true);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_IDLE_PENDING);
        setEmergencyCallActive(true);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_SENSING);
        setEmergencyCallActive(true);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_LOCATING);
        setEmergencyCallActive(true);
        verifyStateConditions(STATE_ACTIVE);

        // Quick doze enabled or not shouldn't affect the end state.
        enterDeepState(STATE_QUICK_DOZE_DELAY);
        setQuickDozeEnabled(true);
        setEmergencyCallActive(true);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_QUICK_DOZE_DELAY);
        setQuickDozeEnabled(false);
        setEmergencyCallActive(true);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_IDLE);
        setEmergencyCallActive(true);
        verifyStateConditions(STATE_ACTIVE);

        enterDeepState(STATE_IDLE_MAINTENANCE);
        setEmergencyCallActive(true);
        verifyStateConditions(STATE_ACTIVE);
    }

    @Test
    public void testEmergencyEndsLightIdle() {
        enterLightState(LIGHT_STATE_ACTIVE);
        setEmergencyCallActive(true);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_INACTIVE);
        setEmergencyCallActive(true);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_WAITING_FOR_NETWORK);
        setEmergencyCallActive(true);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_IDLE);
        setEmergencyCallActive(true);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_IDLE_MAINTENANCE);
        setEmergencyCallActive(true);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);

        enterLightState(LIGHT_STATE_OVERRIDE);
        setEmergencyCallActive(true);
        verifyLightStateConditions(LIGHT_STATE_ACTIVE);
    }

    @Test
    public void testLowLatencyBodyDetection_NoBodySensor() {
        mConstants.USE_BODY_SENSOR = true;
        doReturn(null).when(mSensorManager).getDefaultSensor(
                eq(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT), anyBoolean());
        cleanupDeviceIdleController();
        setupDeviceIdleController();
        verify(mSensorManager, never())
                .registerListener(any(), any(), anyInt());
    }

    @Test
    public void testLowLatencyBodyDetection_NoBatterySaver_QuickDoze() {
        mConstants.USE_BODY_SENSOR = true;
        doReturn(mOffBodySensor)
                .when(mSensorManager)
                .getDefaultSensor(eq(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT), anyBoolean());
        PowerSaveState powerSaveState = new PowerSaveState.Builder().setBatterySaverEnabled(
                false).build();
        when(mPowerManagerInternal.getLowPowerState(anyInt()))
                .thenReturn(powerSaveState);
        cleanupDeviceIdleController();
        setupDeviceIdleController();

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager)
                .registerListener(listenerCaptor.capture(), eq(mOffBodySensor),
                        eq(SensorManager.SENSOR_DELAY_NORMAL));
        final SensorEventListener listener = listenerCaptor.getValue();
        // Set the device as off body
        float[] valsZero = {0.0f};
        SensorEvent offbodyEvent = new SensorEvent(mOffBodySensor, 1, 1L, valsZero);
        listener.onSensorChanged(offbodyEvent);
        assertTrue(mDeviceIdleController.isQuickDozeEnabled());

        // Set the device as on body
        float[] valsNonZero = {1.0f};
        SensorEvent onbodyEvent = new SensorEvent(mOffBodySensor, 1, 1L, valsNonZero);
        listener.onSensorChanged(onbodyEvent);
        assertFalse(mDeviceIdleController.isQuickDozeEnabled());
        verifyStateConditions(STATE_ACTIVE);
    }

    @Test
    public void testLowLatencyBodyDetection_WithBatterySaver_QuickDoze() {
        mConstants.USE_BODY_SENSOR = true;
        doReturn(mOffBodySensor)
                .when(mSensorManager)
                .getDefaultSensor(eq(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT), anyBoolean());
        PowerSaveState powerSaveState = new PowerSaveState.Builder().setBatterySaverEnabled(
                true).build();
        when(mPowerManagerInternal.getLowPowerState(anyInt()))
                .thenReturn(powerSaveState);
        cleanupDeviceIdleController();
        setupDeviceIdleController();

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager)
                .registerListener(listenerCaptor.capture(), eq(mOffBodySensor),
                        eq(SensorManager.SENSOR_DELAY_NORMAL));
        final SensorEventListener listener = listenerCaptor.getValue();
        // Set the device as off body
        float[] valsZero = {0.0f};
        SensorEvent offbodyEvent = new SensorEvent(mOffBodySensor, 1, 1L, valsZero);
        listener.onSensorChanged(offbodyEvent);
        assertTrue(mDeviceIdleController.isQuickDozeEnabled());

        // Set the device as on body. Quick doze should remain enabled because battery saver is on.
        float[] valsNonZero = {1.0f};
        SensorEvent onbodyEvent = new SensorEvent(mOffBodySensor, 1, 1L, valsNonZero);
        listener.onSensorChanged(onbodyEvent);
        assertTrue(mDeviceIdleController.isQuickDozeEnabled());
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
                setEmergencyCallActive(false);
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
                setEmergencyCallActive(false);
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
                setEmergencyCallActive(false);
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
            case LIGHT_STATE_WAITING_FOR_NETWORK:
            case LIGHT_STATE_OVERRIDE:
                setScreenOn(false);
                setChargingOn(false);
                setEmergencyCallActive(false);
                mDeviceIdleController.setLightStateForTest(lightState);
                break;
            default:
                fail("Unknown light state " + lightStateToString(lightState));
        }
    }

    private void setChargingOn(boolean on) {
        mDeviceIdleController.updateChargingLocked(on);
    }

    private void setEmergencyCallActive(boolean active) {
        if (active) {
            mEmergencyCallListener.onOutgoingEmergencyCall(mock(EmergencyNumber.class), 0);
        } else {
            mCallStateListener.onCallStateChanged(TelephonyManager.CALL_STATE_IDLE);
        }
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
                assertFalse(mDeviceIdleController.isEmergencyCallActive());
                break;
            case STATE_IDLE_PENDING:
                assertEquals(
                        mDeviceIdleController.hasMotionSensor(),
                        mDeviceIdleController.mMotionListener.isActive());
                assertFalse(mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn()
                        && !mDeviceIdleController.isKeyguardShowing());
                assertFalse(mDeviceIdleController.isEmergencyCallActive());
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
                assertFalse(mDeviceIdleController.isEmergencyCallActive());
                break;
            case STATE_LOCATING:
                assertEquals(
                        mDeviceIdleController.hasMotionSensor(),
                        mDeviceIdleController.mMotionListener.isActive());
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn()
                        && !mDeviceIdleController.isKeyguardShowing());
                assertFalse(mDeviceIdleController.isEmergencyCallActive());
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
                assertFalse(mDeviceIdleController.isEmergencyCallActive());
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
                assertFalse(mDeviceIdleController.isEmergencyCallActive());
                break;
            case STATE_QUICK_DOZE_DELAY:
                // If quick doze is enabled, the motion listener should NOT be active.
                assertFalse(mDeviceIdleController.mMotionListener.isActive());
                assertFalse(mAnyMotionDetector.isMonitoring);
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn()
                        && !mDeviceIdleController.isKeyguardShowing());
                assertFalse(mDeviceIdleController.isEmergencyCallActive());
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
                                || mDeviceIdleController.isEmergencyCallActive()
                                // Or there's an alarm coming up soon.
                                || SystemClock.elapsedRealtime() + mConstants.MIN_TIME_TO_ALARM
                                > mAlarmManager.getNextWakeFromIdleTime());
                break;
            case LIGHT_STATE_INACTIVE:
            case LIGHT_STATE_IDLE:
            case LIGHT_STATE_WAITING_FOR_NETWORK:
            case LIGHT_STATE_IDLE_MAINTENANCE:
            case LIGHT_STATE_OVERRIDE:
                assertFalse(mDeviceIdleController.isCharging());
                assertFalse(mDeviceIdleController.isScreenOn()
                        && !mDeviceIdleController.isKeyguardShowing());
                assertFalse(mDeviceIdleController.isEmergencyCallActive());
                break;
            default:
                fail("Conditions for " + lightStateToString(expectedLightState) + " unknown.");
        }
    }
}
