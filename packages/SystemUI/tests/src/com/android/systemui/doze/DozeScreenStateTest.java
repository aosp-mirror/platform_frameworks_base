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

import static com.android.systemui.doze.DozeMachine.State.DOZE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSING;
import static com.android.systemui.doze.DozeMachine.State.DOZE_REQUEST_PULSE;
import static com.android.systemui.doze.DozeMachine.State.FINISH;
import static com.android.systemui.doze.DozeMachine.State.INITIALIZED;
import static com.android.systemui.doze.DozeMachine.State.UNINITIALIZED;
import static com.android.systemui.utils.os.FakeHandler.Mode.QUEUEING;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.os.Looper;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.wakelock.WakeLockFake;
import com.android.systemui.utils.os.FakeHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DozeScreenStateTest extends SysuiTestCase {

    DozeServiceFake mServiceFake;
    DozeScreenState mScreen;
    FakeHandler mHandlerFake;
    @Mock
    DozeParameters mDozeParameters;
    WakeLockFake mWakeLock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mDozeParameters.getDisplayNeedsBlanking()).thenReturn(true);
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);
        mServiceFake = new DozeServiceFake();
        mHandlerFake = new FakeHandler(Looper.getMainLooper());
        mWakeLock = new WakeLockFake();
        mScreen = new DozeScreenState(mServiceFake, mHandlerFake, mDozeParameters, mWakeLock);
    }

    @Test
    public void testScreen_offInDoze() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        assertEquals(Display.STATE_OFF, mServiceFake.screenState);
    }

    @Test
    public void testScreen_onInAod() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        assertEquals(Display.STATE_DOZE_SUSPEND, mServiceFake.screenState);
    }

    @Test
    public void testScreen_onInPulse() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);
        mScreen.transitionTo(DOZE_REQUEST_PULSE, DOZE_PULSING);

        assertEquals(Display.STATE_ON, mServiceFake.screenState);
    }

    @Test
    public void testScreen_offInRequestPulseWithoutAoD() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);

        assertEquals(Display.STATE_OFF, mServiceFake.screenState);
    }

    @Test
    public void testScreen_offInRequestPulseWithAoD() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);

        assertEquals(Display.STATE_OFF, mServiceFake.screenState);
    }

    @Test
    public void test_initialScreenStatePostedToHandler() {
        mHandlerFake.setMode(QUEUEING);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mServiceFake.screenStateSet = false;
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        assertFalse(mServiceFake.screenStateSet);

        mHandlerFake.dispatchQueuedMessages();

        assertTrue(mServiceFake.screenStateSet);
        assertEquals(Display.STATE_DOZE_SUSPEND, mServiceFake.screenState);
    }

    @Test
    public void test_noScreenStateSetAfterFinish() {
        mHandlerFake.setMode(QUEUEING);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, FINISH);

        mServiceFake.screenStateSet = false;

        mHandlerFake.dispatchQueuedMessages();

        assertFalse(mServiceFake.screenStateSet);
    }

    @Test
    public void test_holdsWakeLockWhenGoingToLowPowerDelayed() {
        // Transition to low power mode will be delayed to let
        // animations play at 60 fps.
        when(mDozeParameters.shouldControlScreenOff()).thenReturn(true);
        mHandlerFake.setMode(QUEUEING);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mHandlerFake.dispatchQueuedMessages();

        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        assertThat(mWakeLock.isHeld(), is(true));

        mHandlerFake.dispatchQueuedMessages();
        assertThat(mWakeLock.isHeld(), is(false));
    }

    @Test
    public void test_releasesWakeLock_abortingLowPowerDelayed() {
        // Transition to low power mode will be delayed to let
        // animations play at 60 fps.
        when(mDozeParameters.shouldControlScreenOff()).thenReturn(true);
        mHandlerFake.setMode(QUEUEING);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mHandlerFake.dispatchQueuedMessages();

        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        assertThat(mWakeLock.isHeld(), is(true));
        mScreen.transitionTo(DOZE_AOD, FINISH);

        assertThat(mWakeLock.isHeld(), is(false));
    }

}