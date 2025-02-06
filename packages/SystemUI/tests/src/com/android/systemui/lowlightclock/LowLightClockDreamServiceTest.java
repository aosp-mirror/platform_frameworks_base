/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.lowlightclock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.dream.lowlight.LowLightTransitionCoordinator;
import com.android.systemui.SysuiTestCase;

import com.google.android.systemui.lowlightclock.LowLightClockDreamService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class LowLightClockDreamServiceTest extends SysuiTestCase {
    @Mock
    private ChargingStatusProvider mChargingStatusProvider;
    @Mock
    private LowLightDisplayController mDisplayController;
    @Mock
    private LowLightClockAnimationProvider mAnimationProvider;
    @Mock
    private LowLightTransitionCoordinator mLowLightTransitionCoordinator;
    @Mock
    Animator mAnimationInAnimator;
    @Mock
    Animator mAnimationOutAnimator;

    private LowLightClockDreamService mService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mService = new LowLightClockDreamService(
                mChargingStatusProvider,
                mAnimationProvider,
                mLowLightTransitionCoordinator,
                Optional.of(() -> mDisplayController));

        when(mAnimationProvider.provideAnimationIn(any(), any())).thenReturn(mAnimationInAnimator);
        when(mAnimationProvider.provideAnimationOut(any())).thenReturn(
                mAnimationOutAnimator);
    }

    @Test
    public void testSetDbmStateWhenSupported() throws RemoteException {
        when(mDisplayController.isDisplayBrightnessModeSupported()).thenReturn(true);

        mService.onDreamingStarted();

        verify(mDisplayController).setDisplayBrightnessModeEnabled(true);
    }

    @Test
    public void testNotSetDbmStateWhenNotSupported() throws RemoteException {
        when(mDisplayController.isDisplayBrightnessModeSupported()).thenReturn(false);

        mService.onDreamingStarted();

        verify(mDisplayController, never()).setDisplayBrightnessModeEnabled(anyBoolean());
    }

    @Test
    public void testClearDbmState() throws RemoteException {
        when(mDisplayController.isDisplayBrightnessModeSupported()).thenReturn(true);

        mService.onDreamingStarted();
        clearInvocations(mDisplayController);

        mService.onDreamingStopped();

        verify(mDisplayController).setDisplayBrightnessModeEnabled(false);
    }

    @Test
    public void testAnimationsStartedOnDreamingStarted() {
        mService.onDreamingStarted();

        // Entry animation started.
        verify(mAnimationInAnimator).start();
    }

    @Test
    public void testAnimationsStartedOnWakeUp() {
        // Start dreaming then wake up.
        mService.onDreamingStarted();
        mService.onWakeUp();

        // Entry animation started.
        verify(mAnimationInAnimator).cancel();

        // Exit animation started.
        verify(mAnimationOutAnimator).start();
    }

    @Test
    public void testAnimationsStartedBeforeExitingLowLight() {
        mService.onBeforeExitLowLight();

        // Exit animation started.
        verify(mAnimationOutAnimator).start();
    }

    @Test
    public void testWakeUpAnimationCancelledOnDetach() {
        mService.onWakeUp();

        // Exit animation started.
        verify(mAnimationOutAnimator).start();

        mService.onDetachedFromWindow();

        verify(mAnimationOutAnimator).cancel();
    }

    @Test
    public void testExitLowLightAnimationCancelledOnDetach() {
        mService.onBeforeExitLowLight();

        // Exit animation started.
        verify(mAnimationOutAnimator).start();

        mService.onDetachedFromWindow();

        verify(mAnimationOutAnimator).cancel();
    }
}
