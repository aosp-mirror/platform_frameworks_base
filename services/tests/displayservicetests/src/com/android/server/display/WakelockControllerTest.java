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

package com.android.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.hardware.display.DisplayManagerInternal;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Callable;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class WakelockControllerTest {
    private static final int DISPLAY_ID = 1;

    @Mock
    private DisplayManagerInternal.DisplayPowerCallbacks mDisplayPowerCallbacks;

    private WakelockController mWakelockController;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        mWakelockController = new WakelockController(DISPLAY_ID, mDisplayPowerCallbacks);
    }

    @Test
    public void validateSuspendBlockerIdsAreExpected() {
        assertEquals(mWakelockController.getSuspendBlockerUnfinishedBusinessId(),
                "[" + DISPLAY_ID + "]unfinished business");
        assertEquals(mWakelockController.getSuspendBlockerOnStateChangedId(),
                "[" + DISPLAY_ID + "]on state changed");
        assertEquals(mWakelockController.getSuspendBlockerProxPositiveId(),
                "[" + DISPLAY_ID + "]prox positive");
        assertEquals(mWakelockController.getSuspendBlockerProxNegativeId(),
                "[" + DISPLAY_ID + "]prox negative");
        assertEquals(mWakelockController.getSuspendBlockerProxDebounceId(),
                "[" + DISPLAY_ID + "]prox debounce");
        assertEquals(mWakelockController.getSuspendBlockerOverrideDozeScreenState(),
                "[" + DISPLAY_ID + "]override doze screen state");
    }

    @Test
    public void acquireStateChangedSuspendBlockerAcquiresIfNotAcquired() throws Exception {
        // Acquire
        verifyWakelockAcquisitionAndReaquisition(WakelockController.WAKE_LOCK_STATE_CHANGED,
                () -> mWakelockController.isOnStateChangedPending());

        // Verify acquire happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .acquireSuspendBlocker(mWakelockController.getSuspendBlockerOnStateChangedId());

        // Release
        verifyWakelockReleaseAndRerelease(WakelockController.WAKE_LOCK_STATE_CHANGED,
                () -> mWakelockController.isOnStateChangedPending());

        // Verify release happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .releaseSuspendBlocker(mWakelockController.getSuspendBlockerOnStateChangedId());
    }

    @Test
    public void acquireUnfinishedBusinessSuspendBlockerAcquiresIfNotAcquired() throws Exception {
        // Acquire
        verifyWakelockAcquisitionAndReaquisition(WakelockController.WAKE_LOCK_UNFINISHED_BUSINESS,
                () -> mWakelockController.hasUnfinishedBusiness());

        // Verify acquire happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .acquireSuspendBlocker(mWakelockController.getSuspendBlockerUnfinishedBusinessId());

        // Release
        verifyWakelockReleaseAndRerelease(WakelockController.WAKE_LOCK_UNFINISHED_BUSINESS,
                () -> mWakelockController.hasUnfinishedBusiness());

        // Verify release happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .releaseSuspendBlocker(mWakelockController.getSuspendBlockerUnfinishedBusinessId());
    }

    @Test
    public void acquireProxPositiveSuspendBlockerAcquiresIfNotAcquired() throws Exception {
        // Acquire
        verifyWakelockAcquisitionAndReaquisition(WakelockController.WAKE_LOCK_PROXIMITY_POSITIVE,
                () -> mWakelockController.isProximityPositiveAcquired());

        // Verify acquire happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .acquireSuspendBlocker(mWakelockController.getSuspendBlockerProxPositiveId());

        // Release
        verifyWakelockReleaseAndRerelease(WakelockController.WAKE_LOCK_PROXIMITY_POSITIVE,
                () -> mWakelockController.isProximityPositiveAcquired());

        // Verify release happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .releaseSuspendBlocker(mWakelockController.getSuspendBlockerProxPositiveId());
    }

    @Test
    public void acquireProxNegativeSuspendBlockerAcquiresIfNotAcquired() throws Exception {
        // Acquire
        verifyWakelockAcquisitionAndReaquisition(WakelockController.WAKE_LOCK_PROXIMITY_NEGATIVE,
                () -> mWakelockController.isProximityNegativeAcquired());

        // Verify acquire happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .acquireSuspendBlocker(mWakelockController.getSuspendBlockerProxNegativeId());

        // Release
        verifyWakelockReleaseAndRerelease(WakelockController.WAKE_LOCK_PROXIMITY_NEGATIVE,
                () -> mWakelockController.isProximityNegativeAcquired());

        // Verify release happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .releaseSuspendBlocker(mWakelockController.getSuspendBlockerProxNegativeId());
    }

    @Test
    public void acquireProxDebounceSuspendBlockerAcquiresIfNotAcquired() throws Exception {
        // Acquire the suspend blocker
        verifyWakelockAcquisitionAndReaquisition(WakelockController.WAKE_LOCK_PROXIMITY_DEBOUNCE,
                () -> mWakelockController.hasProximitySensorDebounced());

        // Verify acquire happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .acquireSuspendBlocker(mWakelockController.getSuspendBlockerProxDebounceId());

        // Release the suspend blocker
        verifyWakelockReleaseAndRerelease(WakelockController.WAKE_LOCK_PROXIMITY_DEBOUNCE,
                () -> mWakelockController.hasProximitySensorDebounced());

        // Verify suspend blocker was released only once
        verify(mDisplayPowerCallbacks, times(1))
                .releaseSuspendBlocker(mWakelockController.getSuspendBlockerProxDebounceId());
    }

    @Test
    public void acquireOverrideDozeScreenStateSuspendBlocker() throws Exception {
        // Acquire the suspend blocker
        verifyWakelockAcquisitionAndReaquisition(WakelockController
                        .WAKE_LOCK_OVERRIDE_DOZE_SCREEN_STATE,
                () -> mWakelockController.isOverrideDozeScreenStateAcquired());

        // Verify acquire happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .acquireSuspendBlocker(mWakelockController
                        .getSuspendBlockerOverrideDozeScreenState());

        // Release the suspend blocker
        verifyWakelockReleaseAndRerelease(WakelockController.WAKE_LOCK_OVERRIDE_DOZE_SCREEN_STATE,
                () -> mWakelockController.isOverrideDozeScreenStateAcquired());

        // Verify suspend blocker was released only once
        verify(mDisplayPowerCallbacks, times(1))
                .releaseSuspendBlocker(mWakelockController
                        .getSuspendBlockerOverrideDozeScreenState());
    }

    @Test
    public void proximityPositiveRunnableWorksAsExpected() {
        // Acquire the suspend blocker twice
        assertTrue(mWakelockController.acquireWakelock(
                WakelockController.WAKE_LOCK_PROXIMITY_POSITIVE));

        // Execute the runnable
        Runnable proximityPositiveRunnable = mWakelockController.getOnProximityPositiveRunnable();
        proximityPositiveRunnable.run();

        // Validate one suspend blocker was released
        assertFalse(mWakelockController.isProximityPositiveAcquired());
        verify(mDisplayPowerCallbacks).onProximityPositive();
        verify(mDisplayPowerCallbacks).releaseSuspendBlocker(
                mWakelockController.getSuspendBlockerProxPositiveId());
    }

    @Test
    public void proximityPositiveRunnableDoesNothingIfNotAcquired() {
        // Execute the runnable
        Runnable proximityPositiveRunnable = mWakelockController.getOnProximityPositiveRunnable();
        proximityPositiveRunnable.run();

        // Validate one suspend blocker was released
        assertFalse(mWakelockController.isProximityPositiveAcquired());
        verifyZeroInteractions(mDisplayPowerCallbacks);
    }

    @Test
    public void proximityNegativeRunnableWorksAsExpected() {
        // Acquire the suspend blocker twice
        assertTrue(mWakelockController.acquireWakelock(
                WakelockController.WAKE_LOCK_PROXIMITY_NEGATIVE));

        // Execute the runnable
        Runnable proximityNegativeRunnable = mWakelockController.getOnProximityNegativeRunnable();
        proximityNegativeRunnable.run();

        // Validate one suspend blocker was released
        assertFalse(mWakelockController.isProximityNegativeAcquired());
        verify(mDisplayPowerCallbacks).onProximityNegative();
        verify(mDisplayPowerCallbacks).releaseSuspendBlocker(
                mWakelockController.getSuspendBlockerProxNegativeId());
    }

    @Test
    public void proximityNegativeRunnableDoesNothingIfNotAcquired() {
        // Execute the runnable
        Runnable proximityNegativeRunnable = mWakelockController.getOnProximityNegativeRunnable();
        proximityNegativeRunnable.run();

        // Validate one suspend blocker was released
        assertFalse(mWakelockController.isProximityNegativeAcquired());
        verifyZeroInteractions(mDisplayPowerCallbacks);
    }

    @Test
    public void onStateChangeRunnableWorksAsExpected() {
        // Acquire the suspend blocker twice
        assertTrue(mWakelockController.acquireWakelock(WakelockController.WAKE_LOCK_STATE_CHANGED));

        // Execute the runnable
        Runnable stateChangeRunnable = mWakelockController.getOnStateChangedRunnable();
        stateChangeRunnable.run();

        // Validate one suspend blocker was released
        assertFalse(mWakelockController.isOnStateChangedPending());
        verify(mDisplayPowerCallbacks).onStateChanged();
        verify(mDisplayPowerCallbacks).releaseSuspendBlocker(
                mWakelockController.getSuspendBlockerOnStateChangedId());
    }

    @Test
    public void onStateChangeRunnableDoesNothingIfNotAcquired() {
        // Execute the runnable
        Runnable stateChangeRunnable = mWakelockController.getOnStateChangedRunnable();
        stateChangeRunnable.run();

        // Validate one suspend blocker was released
        assertFalse(mWakelockController.isOnStateChangedPending());
        verifyZeroInteractions(mDisplayPowerCallbacks);
    }

    @Test
    public void testReleaseAll() throws Exception {
        // Use WAKE_LOCK_MAX to verify it has been correctly set and used in releaseAll().
        verifyWakelockAcquisition(WakelockController.WAKE_LOCK_MAX,
                () -> mWakelockController.hasUnfinishedBusiness());
        mWakelockController.releaseAll();
        assertFalse(mWakelockController.hasUnfinishedBusiness());
    }

    private void verifyWakelockAcquisitionAndReaquisition(int wakelockId,
            Callable<Boolean> isWakelockAcquiredCallable)
            throws Exception {
        verifyWakelockAcquisition(wakelockId, isWakelockAcquiredCallable);
        verifyWakelockReacquisition(wakelockId, isWakelockAcquiredCallable);
    }

    private void verifyWakelockReleaseAndRerelease(int wakelockId,
            Callable<Boolean> isWakelockAcquiredCallable)
            throws Exception {
        verifyWakelockRelease(wakelockId, isWakelockAcquiredCallable);
        verifyWakelockRerelease(wakelockId, isWakelockAcquiredCallable);
    }

    private void verifyWakelockAcquisition(int wakelockId,
            Callable<Boolean> isWakelockAcquiredCallable)
            throws Exception {
        assertTrue(mWakelockController.acquireWakelock(wakelockId));
        assertTrue(isWakelockAcquiredCallable.call());
    }

    private void verifyWakelockReacquisition(int wakelockId,
            Callable<Boolean> isWakelockAcquiredCallable)
            throws Exception {
        assertFalse(mWakelockController.acquireWakelock(wakelockId));
        assertTrue(isWakelockAcquiredCallable.call());
    }

    private void verifyWakelockRelease(int wakelockId, Callable<Boolean> isWakelockAcquiredCallable)
            throws Exception {
        assertTrue(mWakelockController.releaseWakelock(wakelockId));
        assertFalse(isWakelockAcquiredCallable.call());
    }

    private void verifyWakelockRerelease(int wakelockId,
            Callable<Boolean> isWakelockAcquiredCallable)
            throws Exception {
        assertFalse(mWakelockController.releaseWakelock(wakelockId));
        assertFalse(isWakelockAcquiredCallable.call());
    }
}
