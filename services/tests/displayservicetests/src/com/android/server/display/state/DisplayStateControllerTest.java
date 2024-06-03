/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.display.state;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.display.DisplayManagerInternal;
import android.util.Pair;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.DisplayPowerProximityStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DisplayStateControllerTest {
    private static final boolean DISPLAY_ENABLED = true;
    private static final boolean DISPLAY_IN_TRANSITION = true;

    private DisplayStateController mDisplayStateController;

    @Mock
    private DisplayPowerProximityStateController mDisplayPowerProximityStateController;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        mDisplayStateController = new DisplayStateController(mDisplayPowerProximityStateController);
    }

    @Test
    public void updateProximityStateEvaluatesStateOffPolicyAsExpected() {
        when(mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()).thenReturn(
                false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);

        displayPowerRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF;
        Pair<Integer, Integer> stateAndReason =
                mDisplayStateController.updateDisplayState(
                        displayPowerRequest, DISPLAY_ENABLED, !DISPLAY_IN_TRANSITION);
        assertTrue(Display.STATE_OFF == stateAndReason.first);
        assertTrue(Display.STATE_REASON_DEFAULT_POLICY == stateAndReason.second);
        verify(mDisplayPowerProximityStateController).updateProximityState(displayPowerRequest,
                Display.STATE_OFF);
        assertEquals(true, mDisplayStateController.shouldPerformScreenOffTransition());
    }

    @Test
    public void updateProximityStateEvaluatesDozePolicyAsExpected() {
        when(mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()).thenReturn(
                false);
        validDisplayState(DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE,
                Display.STATE_DOZE, DISPLAY_ENABLED, !DISPLAY_IN_TRANSITION);
    }

    @Test
    public void updateProximityStateEvaluatesDimPolicyAsExpected() {
        when(mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()).thenReturn(
                false);
        validDisplayState(DisplayManagerInternal.DisplayPowerRequest.POLICY_DIM,
                Display.STATE_ON, DISPLAY_ENABLED, !DISPLAY_IN_TRANSITION);
    }

    @Test
    public void updateProximityStateEvaluatesDimBrightAsExpected() {
        when(mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()).thenReturn(
                false);
        validDisplayState(DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT,
                Display.STATE_ON, DISPLAY_ENABLED, !DISPLAY_IN_TRANSITION);
    }

    @Test
    public void updateProximityStateWorksAsExpectedWhenDisplayDisabled() {
        when(mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()).thenReturn(
                false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);

        displayPowerRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
        Pair<Integer, Integer> stateAndReason =
                mDisplayStateController.updateDisplayState(
                        displayPowerRequest, !DISPLAY_ENABLED, !DISPLAY_IN_TRANSITION);
        assertTrue(Display.STATE_OFF == stateAndReason.first);
        assertTrue(Display.STATE_REASON_DEFAULT_POLICY == stateAndReason.second);
        verify(mDisplayPowerProximityStateController).updateProximityState(displayPowerRequest,
                Display.STATE_ON);
        assertEquals(false, mDisplayStateController.shouldPerformScreenOffTransition());
    }

    @Test
    public void updateProximityStateWorksAsExpectedWhenTransitionPhase() {
        when(mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()).thenReturn(
                false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);

        displayPowerRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
        Pair<Integer, Integer> stateAndReason =
                mDisplayStateController.updateDisplayState(
                        displayPowerRequest, DISPLAY_ENABLED, DISPLAY_IN_TRANSITION);
        assertTrue(Display.STATE_OFF == stateAndReason.first);
        assertTrue(Display.STATE_REASON_DEFAULT_POLICY == stateAndReason.second);
        verify(mDisplayPowerProximityStateController).updateProximityState(displayPowerRequest,
                Display.STATE_ON);
        assertEquals(false, mDisplayStateController.shouldPerformScreenOffTransition());
    }

    @Test
    public void updateProximityStateWorksAsExpectedWhenScreenOffBecauseOfProximity() {
        when(mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()).thenReturn(
                true);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);

        displayPowerRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
        Pair<Integer, Integer> stateAndReason =
                mDisplayStateController.updateDisplayState(
                        displayPowerRequest, DISPLAY_ENABLED, !DISPLAY_IN_TRANSITION);
        assertTrue(Display.STATE_OFF == stateAndReason.first);
        assertTrue(Display.STATE_REASON_DEFAULT_POLICY == stateAndReason.second);
        verify(mDisplayPowerProximityStateController).updateProximityState(displayPowerRequest,
                Display.STATE_ON);
        assertEquals(false, mDisplayStateController.shouldPerformScreenOffTransition());
    }

    @Test
    public void dozeScreenStateOverrideToDozeSuspend_DozePolicy_updateDisplayStateToDozeSuspend() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                new DisplayManagerInternal.DisplayPowerRequest();
        displayPowerRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
        mDisplayStateController.overrideDozeScreenState(
                Display.STATE_DOZE_SUSPEND, Display.STATE_REASON_OFFLOAD);

        Pair<Integer, Integer> stateAndReason =
                mDisplayStateController.updateDisplayState(
                        displayPowerRequest, DISPLAY_ENABLED, !DISPLAY_IN_TRANSITION);

        assertTrue(Display.STATE_DOZE_SUSPEND == stateAndReason.first);
        assertTrue(Display.STATE_REASON_OFFLOAD == stateAndReason.second);
    }

    @Test
    public void dozeScreenStateOverrideToDozeSuspend_OffPolicy_displayRemainOff() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                new DisplayManagerInternal.DisplayPowerRequest();
        displayPowerRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF;
        mDisplayStateController.overrideDozeScreenState(
                Display.STATE_DOZE_SUSPEND, Display.STATE_REASON_DEFAULT_POLICY);

        Pair<Integer, Integer> stateAndReason =
                mDisplayStateController.updateDisplayState(
                        displayPowerRequest, DISPLAY_ENABLED, !DISPLAY_IN_TRANSITION);

        assertTrue(Display.STATE_OFF == stateAndReason.first);
        assertTrue(Display.STATE_REASON_DEFAULT_POLICY == stateAndReason.second);
    }

    private void validDisplayState(int policy, int displayState, boolean isEnabled,
            boolean isInTransition) {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.policy = policy;
        Pair<Integer, Integer> stateAndReason =
                mDisplayStateController.updateDisplayState(
                        displayPowerRequest, isEnabled, isInTransition);
        assertTrue(displayState == stateAndReason.first);
        verify(mDisplayPowerProximityStateController).updateProximityState(displayPowerRequest,
                displayState);
        assertEquals(false, mDisplayStateController.shouldPerformScreenOffTransition());
    }
}
