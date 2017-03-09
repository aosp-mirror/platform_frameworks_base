/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.systemui.doze.DozeMachine.State.DOZE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSE_DONE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSING;
import static com.android.systemui.doze.DozeMachine.State.DOZE_REQUEST_PULSE;
import static com.android.systemui.doze.DozeMachine.State.FINISH;
import static com.android.systemui.doze.DozeMachine.State.INITIALIZED;
import static com.android.systemui.doze.DozeMachine.State.UNINITIALIZED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.Display;

import com.android.systemui.SysUIRunner;
import com.android.systemui.UiThreadTest;
import com.android.systemui.statusbar.phone.DozeParameters;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(SysUIRunner.class)
@UiThreadTest
public class DozeMachineTest {

    DozeMachine mMachine;

    private DozeServiceFake mServiceFake;
    private WakeLockFake mWakeLockFake;
    private DozeParameters mParamsMock;
    private DozeMachine.Part mPartMock;

    @Before
    public void setUp() {
        mServiceFake = new DozeServiceFake();
        mWakeLockFake = new WakeLockFake();
        mParamsMock = mock(DozeParameters.class);
        mPartMock = mock(DozeMachine.Part.class);

        mMachine = new DozeMachine(mServiceFake, mParamsMock, mWakeLockFake);

        mMachine.setParts(new DozeMachine.Part[]{mPartMock});
    }

    @Test
    public void testInitialize_initializesParts() {
        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(UNINITIALIZED, INITIALIZED);
    }

    @Test
    public void testInitialize_goesToDoze() {
        when(mParamsMock.getAlwaysOn()).thenReturn(false);

        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(INITIALIZED, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testInitialize_goesToAod() {
        when(mParamsMock.getAlwaysOn()).thenReturn(true);

        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(INITIALIZED, DOZE_AOD);
        assertEquals(DOZE_AOD, mMachine.getState());
    }

    @Test
    public void testPulseDone_goesToDoze() {
        when(mParamsMock.getAlwaysOn()).thenReturn(false);
        mMachine.requestState(INITIALIZED);
        mMachine.requestState(DOZE_REQUEST_PULSE);
        mMachine.requestState(DOZE_PULSING);

        mMachine.requestState(DOZE_PULSE_DONE);

        verify(mPartMock).transitionTo(DOZE_PULSE_DONE, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testPulseDone_goesToAoD() {
        when(mParamsMock.getAlwaysOn()).thenReturn(true);
        mMachine.requestState(INITIALIZED);
        mMachine.requestState(DOZE_REQUEST_PULSE);
        mMachine.requestState(DOZE_PULSING);

        mMachine.requestState(DOZE_PULSE_DONE);

        verify(mPartMock).transitionTo(DOZE_PULSE_DONE, DOZE_AOD);
        assertEquals(DOZE_AOD, mMachine.getState());
    }

    @Test
    public void testFinished_staysFinished() {
        mMachine.requestState(INITIALIZED);
        mMachine.requestState(FINISH);
        reset(mPartMock);

        mMachine.requestState(DOZE);

        verify(mPartMock, never()).transitionTo(any(), any());
        assertEquals(FINISH, mMachine.getState());
    }

    @Test
    public void testFinish_finishesService() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(FINISH);

        assertTrue(mServiceFake.finished);
    }

    @Test
    public void testWakeLock_heldInTransition() {
        doAnswer((inv) -> {
            assertTrue(mWakeLockFake.isHeld());
            return null;
        }).when(mPartMock).transitionTo(any(), any());

        mMachine.requestState(INITIALIZED);
    }

    @Test
    public void testWakeLock_heldInPulseStates() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE_REQUEST_PULSE);
        assertTrue(mWakeLockFake.isHeld());

        mMachine.requestState(DOZE_PULSING);
        assertTrue(mWakeLockFake.isHeld());
    }

    @Test
    public void testWakeLock_notHeldInDozeStates() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE);
        assertFalse(mWakeLockFake.isHeld());

        mMachine.requestState(DOZE_AOD);
        assertFalse(mWakeLockFake.isHeld());
    }

    @Test
    public void testWakeLock_releasedAfterPulse() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE);
        mMachine.requestState(DOZE_REQUEST_PULSE);
        mMachine.requestState(DOZE_PULSING);
        mMachine.requestState(DOZE_PULSE_DONE);

        assertFalse(mWakeLockFake.isHeld());
    }

    @Test
    public void testPulseDuringPulse_doesntCrash() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE);
        mMachine.requestState(DOZE_REQUEST_PULSE);
        mMachine.requestState(DOZE_PULSING);
        mMachine.requestState(DOZE_REQUEST_PULSE);
        mMachine.requestState(DOZE_PULSE_DONE);
    }

    @Test
    public void testSuppressingPulse_doesntCrash() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE);
        mMachine.requestState(DOZE_REQUEST_PULSE);
        mMachine.requestState(DOZE_PULSE_DONE);
    }

    @Test
    public void testScreen_offInDoze() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE);

        assertEquals(Display.STATE_OFF, mServiceFake.screenState);
    }

    @Test
    public void testScreen_onInAod() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE_AOD);

        assertEquals(Display.STATE_DOZE, mServiceFake.screenState);
    }

    @Test
    public void testScreen_onInPulse() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE_REQUEST_PULSE);
        mMachine.requestState(DOZE_PULSING);

        assertEquals(Display.STATE_DOZE, mServiceFake.screenState);
    }

    @Test
    public void testScreen_offInRequestPulseWithoutAoD() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE);
        mMachine.requestState(DOZE_REQUEST_PULSE);

        assertEquals(Display.STATE_OFF, mServiceFake.screenState);
    }

    @Test
    public void testScreen_onInRequestPulseWithoutAoD() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE_AOD);
        mMachine.requestState(DOZE_REQUEST_PULSE);

        assertEquals(Display.STATE_DOZE, mServiceFake.screenState);
    }

    @Test
    public void testTransitions_canRequestTransitions() {
        mMachine.requestState(INITIALIZED);
        mMachine.requestState(DOZE);
        doAnswer(inv -> {
            mMachine.requestState(DOZE_PULSING);
            return null;
        }).when(mPartMock).transitionTo(any(), eq(DOZE_REQUEST_PULSE));

        mMachine.requestState(DOZE_REQUEST_PULSE);

        assertEquals(DOZE_PULSING, mMachine.getState());
    }

    @Test
    public void testWakeUp_wakesUp() {
        mMachine.wakeUp();

        assertTrue(mServiceFake.requestedWakeup);
    }
}
