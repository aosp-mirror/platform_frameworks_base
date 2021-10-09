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
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_DOCKED;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSED;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSING;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSE_DONE;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Looper;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.UdfpsController;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.wakelock.WakeLockFake;
import com.android.systemui.utils.os.FakeHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.inject.Provider;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DozeScreenStateTest extends SysuiTestCase {

    private DozeServiceFake mServiceFake;
    private FakeHandler mHandlerFake;
    @Mock
    private DozeHost mDozeHost;
    @Mock
    private DozeParameters mDozeParameters;
    private WakeLockFake mWakeLock;
    private DozeScreenState mScreen;
    @Mock
    private Provider<UdfpsController> mUdfpsControllerProvider;
    @Mock
    private AuthController mAuthController;
    @Mock
    private UdfpsController mUdfpsController;
    @Mock
    private DozeLog mDozeLog;
    @Mock
    private DozeScreenBrightness mDozeScreenBrightness;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mDozeParameters.getDisplayNeedsBlanking()).thenReturn(true);
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);
        when(mUdfpsControllerProvider.get()).thenReturn(mUdfpsController);
        when(mAuthController.isUdfpsEnrolled(anyInt())).thenReturn(true);
        when(mUdfpsController.isFingerDown()).thenReturn(false);

        mServiceFake = new DozeServiceFake();
        mHandlerFake = new FakeHandler(Looper.getMainLooper());
        mWakeLock = new WakeLockFake();
        mScreen = new DozeScreenState(mServiceFake, mHandlerFake, mDozeHost, mDozeParameters,
                mWakeLock, mAuthController, mUdfpsControllerProvider, mDozeLog,
                mDozeScreenBrightness);
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
    public void testScreen_onInDockedAod() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD_DOCKED);

        assertEquals(Display.STATE_ON, mServiceFake.screenState);
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

    @Test
    public void test_animatesPausing() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        doAnswer(invocation -> null).when(mDozeHost).prepareForGentleSleep(captor.capture());
        mHandlerFake.setMode(QUEUEING);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        mHandlerFake.dispatchQueuedMessages();
        verify(mDozeHost).prepareForGentleSleep(eq(captor.getValue()));
        captor.getValue().run();
        assertEquals(Display.STATE_OFF, mServiceFake.screenState);
    }

    @Test
    public void test_animatesOff() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        doAnswer(invocation -> null).when(mDozeHost).prepareForGentleSleep(captor.capture());
        mHandlerFake.setMode(QUEUEING);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, DOZE);

        mHandlerFake.dispatchQueuedMessages();
        verify(mDozeHost).prepareForGentleSleep(eq(captor.getValue()));
        captor.getValue().run();
        assertEquals(Display.STATE_OFF, mServiceFake.screenState);
    }

    @Test
    public void testDelayEnterDozeScreenState_whenUdfpsFingerDown() {
        // GIVEN AOD is initialized
        when(mDozeParameters.shouldControlScreenOff()).thenReturn(true);
        mHandlerFake.setMode(QUEUEING);
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mHandlerFake.dispatchQueuedMessages();

        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        // WHEN udfps is activated (fingerDown)
        when(mUdfpsController.isFingerDown()).thenReturn(true);
        mHandlerFake.dispatchQueuedMessages();

        // THEN the display screen state doesn't immediately change
        assertEquals(Display.STATE_ON, mServiceFake.screenState);

        // WHEN udfpsController finger is no longer down and the queued messages are run
        when(mUdfpsController.isFingerDown()).thenReturn(false);
        mHandlerFake.dispatchQueuedMessages();

        // THEN the display screen state will change
        assertEquals(Display.STATE_DOZE_SUSPEND, mServiceFake.screenState);
    }

    @Test
    public void testDelayExitPulsingScreenState_whenUdfpsFingerDown() {
        // GIVEN we're pulsing
        when(mDozeParameters.shouldControlScreenOff()).thenReturn(true);
        mHandlerFake.setMode(QUEUEING);
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, DOZE_REQUEST_PULSE);
        mScreen.transitionTo(DOZE_REQUEST_PULSE, DOZE_PULSING);
        mScreen.transitionTo(DOZE_PULSING, DOZE_PULSE_DONE);
        mHandlerFake.dispatchQueuedMessages();

        // WHEN udfps is activated while are transitioning back to DOZE_AOD
        mScreen.transitionTo(DOZE_PULSE_DONE, DOZE_AOD);
        when(mUdfpsController.isFingerDown()).thenReturn(true);
        mHandlerFake.dispatchQueuedMessages();

        // THEN the display screen state doesn't immediately change
        assertEquals(Display.STATE_ON, mServiceFake.screenState);

        // WHEN udfpsController finger is no longer down and the queued messages are run
        when(mUdfpsController.isFingerDown()).thenReturn(false);
        mHandlerFake.dispatchQueuedMessages();

        // THEN the display screen state will change
        assertEquals(Display.STATE_DOZE_SUSPEND, mServiceFake.screenState);
    }
}