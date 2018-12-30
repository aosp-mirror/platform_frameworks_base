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

package com.android.systemui.doze;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManagerFake;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class DozeDockHandlerTest extends SysuiTestCase {
    private DozeDockHandler mDockHandler;
    private DozeMachine mMachine;
    private DozeHostFake mHost;
    private AmbientDisplayConfiguration mConfig;
    private Instrumentation mInstrumentation;
    private DockManagerFake mDockManagerFake;

    @BeforeClass
    public static void setupSuite() {
        // We can't use KeyguardUpdateMonitor from tests.
        DozeLog.setRegisterKeyguardCallback(false);
    }

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mMachine = mock(DozeMachine.class);
        mHost = spy(new DozeHostFake());
        mConfig = DozeConfigurationUtil.createMockConfig();

        mDockManagerFake = spy(new DockManagerFake());
        mContext.putComponent(DockManager.class, mDockManagerFake);

        mDockHandler = new DozeDockHandler(mContext, mMachine, mHost, mConfig,
                Handler.createAsync(Looper.myLooper()));
    }

    @Test
    public void testDockEventListener_registerAndUnregister() throws Exception {
        mDockHandler.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);

        verify(mDockManagerFake).addListener(any());

        mDockHandler.transitionTo(DozeMachine.State.DOZE, DozeMachine.State.FINISH);

        verify(mDockManagerFake).removeListener(any());
    }

    @Test
    public void testOnEvent_dockingWhenDoze_requestPulse() throws Exception {
        mDockHandler.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);

        mDockManagerFake.setDockEvent(DockManager.STATE_DOCKING);

        verify(mMachine).requestPulse(eq(DozeLog.PULSE_REASON_DOCKING));
    }

    @Test
    public void testOnEvent_dockingWhenPausing_neverRequestPulse() throws Exception {
        mDockHandler.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_AOD_PAUSING);

        mDockManagerFake.setDockEvent(DockManager.STATE_DOCKING);

        verify(mMachine, never()).requestPulse(eq(DozeLog.PULSE_REASON_DOCKING));
    }

    @Test
    public void testOnEvent_undockedWhenPulsing_requestPulseOut() throws Exception {
        mDockHandler.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE_PULSING);
        when(mMachine.getPulseReason()).thenReturn(DozeLog.PULSE_REASON_DOCKING);

        mDockManagerFake.setDockEvent(DockManager.STATE_UNDOCKING);

        verify(mHost).stopPulsing();
    }

    @Test
    public void testOnEvent_undockedWhenDoze_neverRequestPulseOut() throws Exception {
        mDockHandler.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);

        mDockManagerFake.setDockEvent(DockManager.STATE_UNDOCKING);

        verify(mHost, never()).stopPulsing();
    }

    @Test
    public void testTransitionToDozeWhenDocking_RequestPulse() throws Exception {
        mDockHandler.transitionTo(DozeMachine.State.UNINITIALIZED, DozeMachine.State.INITIALIZED);
        mDockManagerFake.setDockEvent(DockManager.STATE_DOCKING);
        mDockHandler.transitionTo(DozeMachine.State.DOZE_AOD_PAUSING, DozeMachine.State.DOZE);
        when(mMachine.getState()).thenReturn(DozeMachine.State.DOZE);

        TestableLooper.get(this).processAllMessages();

        verify(mMachine).requestPulse(eq(DozeLog.PULSE_REASON_DOCKING));
    }
}
