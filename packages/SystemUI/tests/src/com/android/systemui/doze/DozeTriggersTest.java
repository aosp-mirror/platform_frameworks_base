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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.wakelock.WakeLock;
import com.android.systemui.util.wakelock.WakeLockFake;
import com.android.systemui.utils.hardware.FakeSensorManager;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Ignore("failing")
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
public class DozeTriggersTest extends SysuiTestCase {
    private DozeTriggers mTriggers;
    private DozeMachine mMachine;
    private DozeHostFake mHost;
    private AmbientDisplayConfiguration mConfig;
    private DozeParameters mParameters;
    private FakeSensorManager mSensors;
    private WakeLock mWakeLock;
    private Instrumentation mInstrumentation;
    private AlarmManager mAlarmManager;

    @BeforeClass
    public static void setupSuite() {
        // We can't use KeyguardUpdateMonitor from tests.
        DozeLog.setRegisterKeyguardCallback(false);
    }

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mMachine = mock(DozeMachine.class);
        mAlarmManager = mock(AlarmManager.class);
        mHost = new DozeHostFake();
        mConfig = DozeConfigurationUtil.createMockConfig();
        mParameters = DozeConfigurationUtil.createMockParameters();
        mSensors = new FakeSensorManager(mContext);
        mWakeLock = new WakeLockFake();

        mTriggers = new DozeTriggers(mContext, mMachine, mHost, mAlarmManager, mConfig, mParameters,
                mSensors, Handler.createAsync(Looper.myLooper()), mWakeLock, true);
    }

    @Test
    public void testOnNotification_stillWorksAfterOneFailedProxCheck() throws Exception {
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);

        mTriggers.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mTriggers.transitionTo(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE);

        mHost.callback.onNotificationHeadsUp();

        mSensors.getMockProximitySensor().sendProximityResult(false); /* Near */

        verify(mMachine, never()).requestState(any());
        verify(mMachine, never()).requestPulse(anyInt());

        mHost.callback.onNotificationHeadsUp();

        mSensors.getMockProximitySensor().sendProximityResult(true); /* Far */

        verify(mMachine).requestPulse(anyInt());
    }

}
