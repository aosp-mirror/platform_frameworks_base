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

import android.hardware.display.DisplayManagerInternal;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
    }

    @Test
    public void acquireStateChangedSuspendBlockerAcquiresIfNotAcquired() {
        // Acquire the suspend blocker
        assertTrue(mWakelockController.acquireStateChangedSuspendBlocker());
        assertTrue(mWakelockController.isOnStateChangedPending());

        // Try to reacquire
        assertFalse(mWakelockController.acquireStateChangedSuspendBlocker());
        assertTrue(mWakelockController.isOnStateChangedPending());

        // Verify acquire happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .acquireSuspendBlocker(mWakelockController.getSuspendBlockerOnStateChangedId());

        // Release
        mWakelockController.releaseStateChangedSuspendBlocker();
        assertFalse(mWakelockController.isOnStateChangedPending());

        // Try to release again
        mWakelockController.releaseStateChangedSuspendBlocker();

        // Verify release happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .releaseSuspendBlocker(mWakelockController.getSuspendBlockerOnStateChangedId());
    }

    @Test
    public void acquireUnfinishedBusinessSuspendBlockerAcquiresIfNotAcquired() {
        // Acquire the suspend blocker
        mWakelockController.acquireUnfinishedBusinessSuspendBlocker();
        assertTrue(mWakelockController.hasUnfinishedBusiness());

        // Try to reacquire
        mWakelockController.acquireUnfinishedBusinessSuspendBlocker();
        assertTrue(mWakelockController.hasUnfinishedBusiness());

        // Verify acquire happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .acquireSuspendBlocker(mWakelockController.getSuspendBlockerUnfinishedBusinessId());

        // Release the suspend blocker
        mWakelockController.releaseUnfinishedBusinessSuspendBlocker();
        assertFalse(mWakelockController.hasUnfinishedBusiness());

        // Try to release again
        mWakelockController.releaseUnfinishedBusinessSuspendBlocker();

        // Verify release happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .releaseSuspendBlocker(mWakelockController.getSuspendBlockerUnfinishedBusinessId());
    }

    @Test
    public void acquireProxPositiveSuspendBlockerAcquiresIfNotAcquired() {
        // Acquire the suspend blocker
        mWakelockController.acquireProxPositiveSuspendBlocker();
        assertEquals(mWakelockController.getOnProximityPositiveMessages(), 1);

        // Try to reacquire
        mWakelockController.acquireProxPositiveSuspendBlocker();
        assertEquals(mWakelockController.getOnProximityPositiveMessages(), 2);

        // Verify acquire happened only once
        verify(mDisplayPowerCallbacks, times(2))
                .acquireSuspendBlocker(mWakelockController.getSuspendBlockerProxPositiveId());

        // Release the suspend blocker
        mWakelockController.releaseProxPositiveSuspendBlocker();
        assertEquals(mWakelockController.getOnProximityPositiveMessages(), 0);

        // Verify all suspend blockers were released
        verify(mDisplayPowerCallbacks, times(2))
                .releaseSuspendBlocker(mWakelockController.getSuspendBlockerProxPositiveId());
    }

    @Test
    public void acquireProxNegativeSuspendBlockerAcquiresIfNotAcquired() {
        // Acquire the suspend blocker
        mWakelockController.acquireProxNegativeSuspendBlocker();
        assertEquals(mWakelockController.getOnProximityNegativeMessages(), 1);

        // Try to reacquire
        mWakelockController.acquireProxNegativeSuspendBlocker();
        assertEquals(mWakelockController.getOnProximityNegativeMessages(), 2);

        // Verify acquire happened only once
        verify(mDisplayPowerCallbacks, times(2))
                .acquireSuspendBlocker(mWakelockController.getSuspendBlockerProxNegativeId());

        // Release the suspend blocker
        mWakelockController.releaseProxNegativeSuspendBlocker();
        assertEquals(mWakelockController.getOnProximityNegativeMessages(), 0);

        // Verify all suspend blockers were released
        verify(mDisplayPowerCallbacks, times(2))
                .releaseSuspendBlocker(mWakelockController.getSuspendBlockerProxNegativeId());
    }

    @Test
    public void acquireProxDebounceSuspendBlockerAcquiresIfNotAcquired() {
        // Acquire the suspend blocker
        mWakelockController.acquireProxDebounceSuspendBlocker();

        // Try to reacquire
        mWakelockController.acquireProxDebounceSuspendBlocker();
        assertTrue(mWakelockController.hasProximitySensorDebounced());

        // Verify acquire happened only once
        verify(mDisplayPowerCallbacks, times(1))
                .acquireSuspendBlocker(mWakelockController.getSuspendBlockerProxDebounceId());

        // Release the suspend blocker
        assertTrue(mWakelockController.releaseProxDebounceSuspendBlocker());

        // Release again
        assertFalse(mWakelockController.releaseProxDebounceSuspendBlocker());
        assertFalse(mWakelockController.hasProximitySensorDebounced());

        // Verify suspend blocker was released only once
        verify(mDisplayPowerCallbacks, times(1))
                .releaseSuspendBlocker(mWakelockController.getSuspendBlockerProxDebounceId());
    }

    @Test
    public void proximityPositiveRunnableWorksAsExpected() {
        // Acquire the suspend blocker twice
        mWakelockController.acquireProxPositiveSuspendBlocker();
        mWakelockController.acquireProxPositiveSuspendBlocker();

        // Execute the runnable
        Runnable proximityPositiveRunnable = mWakelockController.getOnProximityPositiveRunnable();
        proximityPositiveRunnable.run();

        // Validate one suspend blocker was released
        assertEquals(mWakelockController.getOnProximityPositiveMessages(), 1);
        verify(mDisplayPowerCallbacks).onProximityPositive();
        verify(mDisplayPowerCallbacks).releaseSuspendBlocker(
                mWakelockController.getSuspendBlockerProxPositiveId());
    }

    @Test
    public void proximityNegativeRunnableWorksAsExpected() {
        // Acquire the suspend blocker twice
        mWakelockController.acquireProxNegativeSuspendBlocker();
        mWakelockController.acquireProxNegativeSuspendBlocker();

        // Execute the runnable
        Runnable proximityNegativeRunnable = mWakelockController.getOnProximityNegativeRunnable();
        proximityNegativeRunnable.run();

        // Validate one suspend blocker was released
        assertEquals(mWakelockController.getOnProximityNegativeMessages(), 1);
        verify(mDisplayPowerCallbacks).onProximityNegative();
        verify(mDisplayPowerCallbacks).releaseSuspendBlocker(
                mWakelockController.getSuspendBlockerProxNegativeId());
    }

    @Test
    public void onStateChangeRunnableWorksAsExpected() {
        // Acquire the suspend blocker twice
        mWakelockController.acquireStateChangedSuspendBlocker();

        // Execute the runnable
        Runnable stateChangeRunnable = mWakelockController.getOnStateChangedRunnable();
        stateChangeRunnable.run();

        // Validate one suspend blocker was released
        assertEquals(mWakelockController.isOnStateChangedPending(), false);
        verify(mDisplayPowerCallbacks).onStateChanged();
        verify(mDisplayPowerCallbacks).releaseSuspendBlocker(
                mWakelockController.getSuspendBlockerOnStateChangedId());
    }


}
