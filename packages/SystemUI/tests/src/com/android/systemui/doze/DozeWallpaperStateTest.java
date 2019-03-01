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
 * limitations under the License
 */

package com.android.systemui.doze;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IWallpaperManager;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.DozeParameters;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
@SmallTest
public class DozeWallpaperStateTest extends SysuiTestCase {

    private DozeWallpaperState mDozeWallpaperState;
    @Mock IWallpaperManager mIWallpaperManager;
    @Mock DozeParameters mDozeParameters;
    @Mock DozeMachine mMachine;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDozeWallpaperState = new DozeWallpaperState(mMachine, mIWallpaperManager, mDozeParameters);
    }

    @Test
    public void testDreamNotification() throws RemoteException {
        // Pre-condition
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);

        mDozeWallpaperState.transitionTo(DozeMachine.State.UNINITIALIZED,
                DozeMachine.State.DOZE_AOD);
        verify(mIWallpaperManager).setInAmbientMode(eq(true), anyLong());
        mDozeWallpaperState.transitionTo(DozeMachine.State.DOZE_AOD, DozeMachine.State.FINISH);
        verify(mIWallpaperManager).setInAmbientMode(eq(false), anyLong());

        // Make sure we're sending false when AoD is off
        reset(mDozeParameters);
        mDozeWallpaperState.transitionTo(DozeMachine.State.FINISH, DozeMachine.State.DOZE_AOD);
        verify(mIWallpaperManager).setInAmbientMode(eq(false), anyLong());
    }

    @Test
    public void testAnimates_whenSupported() throws RemoteException {
        // Pre-conditions
        when(mDozeParameters.getDisplayNeedsBlanking()).thenReturn(false);
        when(mDozeParameters.shouldControlScreenOff()).thenReturn(true);
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);

        mDozeWallpaperState.transitionTo(DozeMachine.State.UNINITIALIZED,
                DozeMachine.State.DOZE_AOD);
        verify(mIWallpaperManager).setInAmbientMode(eq(true),
                eq((long) StackStateAnimator.ANIMATION_DURATION_WAKEUP));

        mDozeWallpaperState.transitionTo(DozeMachine.State.DOZE_AOD, DozeMachine.State.FINISH);
        verify(mIWallpaperManager).setInAmbientMode(eq(false),
                eq((long) StackStateAnimator.ANIMATION_DURATION_WAKEUP));
    }

    @Test
    public void testDoesNotAnimate_whenNotSupported() throws RemoteException {
        // Pre-conditions
        when(mDozeParameters.getDisplayNeedsBlanking()).thenReturn(true);
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);
        when(mDozeParameters.shouldControlScreenOff()).thenReturn(false);

        mDozeWallpaperState.transitionTo(DozeMachine.State.UNINITIALIZED,
                DozeMachine.State.DOZE_AOD);
        verify(mIWallpaperManager).setInAmbientMode(eq(true), eq(0L));

        mDozeWallpaperState.transitionTo(DozeMachine.State.DOZE_AOD, DozeMachine.State.FINISH);
        verify(mIWallpaperManager).setInAmbientMode(eq(false), eq(0L));
    }

    @Test
    public void testTransitionTo_requestPulseIsAmbientMode() throws RemoteException {
        mDozeWallpaperState.transitionTo(DozeMachine.State.DOZE,
                DozeMachine.State.DOZE_REQUEST_PULSE);
        verify(mIWallpaperManager).setInAmbientMode(eq(true), eq(0L));
    }

    @Test
    public void testTransitionTo_notificationPulseIsAmbientMode() throws RemoteException {
        when(mMachine.getPulseReason()).thenReturn(DozeLog.PULSE_REASON_NOTIFICATION);
        mDozeWallpaperState.transitionTo(DozeMachine.State.DOZE_REQUEST_PULSE,
                DozeMachine.State.DOZE_PULSING);
        verify(mIWallpaperManager).setInAmbientMode(eq(true), eq(0L));
    }

    @Test
    public void testTransitionTo_wakeFromPulseIsNotAmbientMode() throws RemoteException {
        when(mMachine.getPulseReason()).thenReturn(DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN);
        mDozeWallpaperState.transitionTo(DozeMachine.State.DOZE_AOD,
                DozeMachine.State.DOZE_REQUEST_PULSE);
        reset(mIWallpaperManager);

        mDozeWallpaperState.transitionTo(DozeMachine.State.DOZE_REQUEST_PULSE,
                DozeMachine.State.DOZE_PULSING);
        verify(mIWallpaperManager).setInAmbientMode(eq(false), anyLong());
    }

    @Test
    public void testTransitionTo_animatesWhenWakingUpFromPulse() throws RemoteException {
        when(mMachine.getPulseReason()).thenReturn(DozeLog.PULSE_REASON_NOTIFICATION);
        mDozeWallpaperState.transitionTo(DozeMachine.State.DOZE_REQUEST_PULSE,
                DozeMachine.State.DOZE_PULSING);
        reset(mIWallpaperManager);
        mDozeWallpaperState.transitionTo(DozeMachine.State.DOZE_PULSING,
                DozeMachine.State.FINISH);
        verify(mIWallpaperManager).setInAmbientMode(eq(false),
                eq((long) StackStateAnimator.ANIMATION_DURATION_WAKEUP));
    }
}
