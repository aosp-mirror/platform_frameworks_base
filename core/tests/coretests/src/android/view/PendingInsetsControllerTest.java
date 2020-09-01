/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view;

import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.os.CancellationSignal;
import android.platform.test.annotations.Presubmit;
import android.view.WindowInsetsController.OnControllableInsetsChangedListener;
import android.view.animation.LinearInterpolator;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link PendingInsetsControllerTest}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:PendingInsetsControllerTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class PendingInsetsControllerTest {

    private PendingInsetsController mPendingInsetsController = new PendingInsetsController();
    private InsetsController mReplayedController;

    @Before
    public void setUp() {
        mPendingInsetsController = new PendingInsetsController();
        mReplayedController = mock(InsetsController.class);
    }

    @Test
    public void testShow() {
        mPendingInsetsController.show(systemBars());
        mPendingInsetsController.replayAndAttach(mReplayedController);
        verify(mReplayedController).show(eq(systemBars()));
    }

    @Test
    public void testShow_direct() {
        mPendingInsetsController.replayAndAttach(mReplayedController);
        mPendingInsetsController.show(systemBars());
        verify(mReplayedController).show(eq(systemBars()));
    }

    @Test
    public void testHide() {
        mPendingInsetsController.hide(systemBars());
        mPendingInsetsController.replayAndAttach(mReplayedController);
        verify(mReplayedController).hide(eq(systemBars()));
    }

    @Test
    public void testHide_direct() {
        mPendingInsetsController.replayAndAttach(mReplayedController);
        mPendingInsetsController.hide(systemBars());
        verify(mReplayedController).hide(eq(systemBars()));
    }

    @Test
    public void testControl() {
        WindowInsetsAnimationControlListener listener =
                mock(WindowInsetsAnimationControlListener.class);
        CancellationSignal cancellationSignal = new CancellationSignal();
        mPendingInsetsController.controlWindowInsetsAnimation(
                systemBars(), 0, new LinearInterpolator(), cancellationSignal, listener);
        verify(listener).onCancelled(null);
        assertFalse(cancellationSignal.isCanceled());
    }

    @Test
    public void testControl_direct() {
        WindowInsetsAnimationControlListener listener =
                mock(WindowInsetsAnimationControlListener.class);
        mPendingInsetsController.replayAndAttach(mReplayedController);
        CancellationSignal cancellationSignal = new CancellationSignal();
        mPendingInsetsController.controlWindowInsetsAnimation(systemBars(), 0L,
                new LinearInterpolator(), cancellationSignal, listener);
        verify(mReplayedController).controlWindowInsetsAnimation(eq(systemBars()), eq(0L), any(),
                eq(cancellationSignal), eq(listener));
    }

    @Test
    public void testBehavior() {
        mPendingInsetsController.setSystemBarsBehavior(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        mPendingInsetsController.replayAndAttach(mReplayedController);
        verify(mReplayedController).setSystemBarsBehavior(
                eq(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE));
    }

    @Test
    public void testBehavior_direct() {
        mPendingInsetsController.replayAndAttach(mReplayedController);
        mPendingInsetsController.setSystemBarsBehavior(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        verify(mReplayedController).setSystemBarsBehavior(
                eq(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE));
    }

    @Test
    public void testBehavior_direct_get() {
        when(mReplayedController.getSystemBarsBehavior())
                .thenReturn(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        mPendingInsetsController.replayAndAttach(mReplayedController);
        assertEquals(mPendingInsetsController.getSystemBarsBehavior(),
                BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Test
    public void testAppearance() {
        mPendingInsetsController.setSystemBarsAppearance(
                APPEARANCE_LIGHT_STATUS_BARS, APPEARANCE_LIGHT_STATUS_BARS);
        mPendingInsetsController.replayAndAttach(mReplayedController);
        verify(mReplayedController).setSystemBarsAppearance(eq(APPEARANCE_LIGHT_STATUS_BARS),
                eq(APPEARANCE_LIGHT_STATUS_BARS));
    }

    @Test
    public void testAppearance_direct() {
        mPendingInsetsController.replayAndAttach(mReplayedController);
        mPendingInsetsController.setSystemBarsAppearance(
                APPEARANCE_LIGHT_STATUS_BARS, APPEARANCE_LIGHT_STATUS_BARS);
        verify(mReplayedController).setSystemBarsAppearance(eq(APPEARANCE_LIGHT_STATUS_BARS),
                eq(APPEARANCE_LIGHT_STATUS_BARS));
    }

    @Test
    public void testAppearance_direct_get() {
        when(mReplayedController.getSystemBarsAppearance())
                .thenReturn(APPEARANCE_LIGHT_STATUS_BARS);
        mPendingInsetsController.replayAndAttach(mReplayedController);
        assertEquals(mPendingInsetsController.getSystemBarsAppearance(),
                APPEARANCE_LIGHT_STATUS_BARS);
    }

    @Test
    public void testAddOnControllableInsetsChangedListener() {
        OnControllableInsetsChangedListener listener =
                mock(OnControllableInsetsChangedListener.class);
        mPendingInsetsController.addOnControllableInsetsChangedListener(listener);
        mPendingInsetsController.replayAndAttach(mReplayedController);
        verify(mReplayedController).addOnControllableInsetsChangedListener(eq(listener));
        verify(listener).onControllableInsetsChanged(eq(mPendingInsetsController), eq(0));
    }

    @Test
    public void testAddRemoveControllableInsetsChangedListener() {
        OnControllableInsetsChangedListener listener =
                mock(OnControllableInsetsChangedListener.class);
        mPendingInsetsController.addOnControllableInsetsChangedListener(listener);
        mPendingInsetsController.removeOnControllableInsetsChangedListener(listener);
        mPendingInsetsController.replayAndAttach(mReplayedController);
        verify(mReplayedController, never()).addOnControllableInsetsChangedListener(any());
        verify(listener).onControllableInsetsChanged(eq(mPendingInsetsController), eq(0));
    }

    @Test
    public void testAddOnControllableInsetsChangedListener_direct() {
        mPendingInsetsController.replayAndAttach(mReplayedController);
        OnControllableInsetsChangedListener listener =
                mock(OnControllableInsetsChangedListener.class);
        mPendingInsetsController.addOnControllableInsetsChangedListener(listener);
        verify(mReplayedController).addOnControllableInsetsChangedListener(eq(listener));
    }

    @Test
    public void testReplayTwice() {
        mPendingInsetsController.show(systemBars());
        mPendingInsetsController.setSystemBarsBehavior(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        mPendingInsetsController.setSystemBarsAppearance(APPEARANCE_LIGHT_STATUS_BARS,
                APPEARANCE_LIGHT_STATUS_BARS);
        mPendingInsetsController.addOnControllableInsetsChangedListener(
                (controller, typeMask) -> {});
        mPendingInsetsController.replayAndAttach(mReplayedController);
        InsetsController secondController = mock(InsetsController.class);
        mPendingInsetsController.replayAndAttach(secondController);
        verify(mReplayedController).show(eq(systemBars()));
        verifyZeroInteractions(secondController);
    }

    @Test
    public void testDetachReattach() {
        mPendingInsetsController.show(systemBars());
        mPendingInsetsController.setSystemBarsBehavior(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        mPendingInsetsController.setSystemBarsAppearance(APPEARANCE_LIGHT_STATUS_BARS,
                APPEARANCE_LIGHT_STATUS_BARS);
        mPendingInsetsController.replayAndAttach(mReplayedController);
        mPendingInsetsController.detach();
        mPendingInsetsController.show(navigationBars());
        InsetsController secondController = mock(InsetsController.class);
        mPendingInsetsController.replayAndAttach(secondController);

        verify(mReplayedController).show(eq(systemBars()));
        verify(secondController).show(eq(navigationBars()));
    }
}
