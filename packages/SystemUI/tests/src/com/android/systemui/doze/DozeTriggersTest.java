/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.doze;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.hardware.Sensor;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.Display;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dock.DockManager;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.FakeProximitySensor;
import com.android.systemui.util.sensors.FakeSensorManager;
import com.android.systemui.util.sensors.FakeThresholdSensor;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.wakelock.WakeLock;
import com.android.systemui.util.wakelock.WakeLockFake;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
public class DozeTriggersTest extends SysuiTestCase {

    @Mock
    private DozeMachine mMachine;
    @Mock
    private DozeHost mHost;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private DockManager mDockManager;
    @Mock
    private ProximitySensor.ProximityCheck mProximityCheck;

    private DozeTriggers mTriggers;
    private FakeSensorManager mSensors;
    private Sensor mTapSensor;
    private FakeProximitySensor mProximitySensor;
    private FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        AmbientDisplayConfiguration config = DozeConfigurationUtil.createMockConfig();
        DozeParameters parameters = DozeConfigurationUtil.createMockParameters();
        mSensors = spy(new FakeSensorManager(mContext));
        mTapSensor = mSensors.getFakeTapSensor().getSensor();
        WakeLock wakeLock = new WakeLockFake();
        AsyncSensorManager asyncSensorManager =
                new AsyncSensorManager(mSensors, null, new Handler());

        FakeThresholdSensor thresholdSensor = new FakeThresholdSensor();
        thresholdSensor.setLoaded(true);
        mProximitySensor = new FakeProximitySensor(thresholdSensor,  null, mExecutor);

        mTriggers = new DozeTriggers(mContext, mMachine, mHost, mAlarmManager, config, parameters,
                asyncSensorManager, wakeLock, true, mDockManager, mProximitySensor,
                mProximityCheck, mock(DozeLog.class), mBroadcastDispatcher);
        waitForSensorManager();
    }

    @Test
    public void testOnNotification_stillWorksAfterOneFailedProxCheck() throws Exception {
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);
        ArgumentCaptor<DozeHost.Callback> captor = ArgumentCaptor.forClass(DozeHost.Callback.class);
        doAnswer(invocation -> null).when(mHost).addCallback(captor.capture());

        mTriggers.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        clearInvocations(mMachine);

        mProximitySensor.setLastEvent(new ProximitySensor.ThresholdSensorEvent(true, 1));
        captor.getValue().onNotificationAlerted(null /* pulseSuppressedListener */);
        mProximitySensor.alertListeners();

        verify(mMachine, never()).requestState(any());
        verify(mMachine, never()).requestPulse(anyInt());

        mProximitySensor.setLastEvent(new ProximitySensor.ThresholdSensorEvent(false, 2));
        mProximitySensor.alertListeners();
        waitForSensorManager();
        captor.getValue().onNotificationAlerted(null /* pulseSuppressedListener */);

        verify(mMachine).requestPulse(anyInt());
    }

    @Test
    public void testTransitionTo_disablesAndEnablesTouchSensors() {
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);

        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        mTriggers.onScreenState(Display.STATE_OFF);
        waitForSensorManager();
        verify(mSensors).requestTriggerSensor(any(), eq(mTapSensor));

        clearInvocations(mSensors);
        mTriggers.transitionTo(DozeMachine.State.DOZE,
                DozeMachine.State.DOZE_REQUEST_PULSE);
        mTriggers.transitionTo(DozeMachine.State.DOZE_REQUEST_PULSE,
                DozeMachine.State.DOZE_PULSING);
        mTriggers.onScreenState(Display.STATE_DOZE);
        waitForSensorManager();
        verify(mSensors).cancelTriggerSensor(any(), eq(mTapSensor));

        clearInvocations(mSensors);
        mTriggers.transitionTo(DozeMachine.State.DOZE_PULSING, DozeMachine.State.DOZE_PULSE_DONE);
        mTriggers.transitionTo(DozeMachine.State.DOZE_PULSE_DONE, DozeMachine.State.DOZE_AOD);
        waitForSensorManager();
        verify(mSensors).requestTriggerSensor(any(), eq(mTapSensor));
    }

    @Test
    public void transitionToDockedAod_disablesTouchSensors() {
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);
        mTriggers.onScreenState(Display.STATE_OFF);
        waitForSensorManager();
        verify(mSensors).requestTriggerSensor(any(), eq(mTapSensor));

        mTriggers.transitionTo(DozeMachine.State.DOZE, DozeMachine.State.DOZE_AOD_DOCKED);
        mTriggers.onScreenState(Display.STATE_DOZE);
        waitForSensorManager();

        verify(mSensors).cancelTriggerSensor(any(), eq(mTapSensor));
    }

    @Test
    public void testDockEventListener_registerAndUnregister() {
        mTriggers.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        verify(mDockManager).addListener(any());

        mTriggers.transitionTo(DozeMachine.State.DOZE, DozeMachine.State.FINISH);
        verify(mDockManager).removeListener(any());
    }

    @Test
    public void testProximitySensorNotAvailablel() {
        mProximitySensor.setSensorAvailable(false);
        mTriggers.onSensor(DozeLog.PULSE_REASON_SENSOR_LONG_PRESS, 100, 100, null);
        mTriggers.onSensor(DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN, 100, 100,
                new float[]{1});
        mTriggers.onSensor(DozeLog.REASON_SENSOR_TAP, 100, 100, null);
    }

    private void waitForSensorManager() {
        TestableLooper.get(this).processAllMessages();
    }
}
