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

package com.android.wm.shell.pip2.phone;

import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_REMOVE_PIP;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.kotlin.MatchersKt.eq;
import static org.mockito.kotlin.VerificationKt.times;
import static org.mockito.kotlin.VerificationKt.verify;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.SurfaceControl;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.desktopmode.DesktopUserRepositories;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip2.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip2.animation.PipAlphaAnimator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

/**
 * Unit test against {@link PipScheduler}
 */

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class PipSchedulerTest {
    private static final int TEST_RESIZE_DURATION = 1;
    private static final Rect TEST_STARTING_BOUNDS = new Rect(0, 0, 10, 10);
    private static final Rect TEST_BOUNDS = new Rect(0, 0, 20, 20);

    @Mock private Context mMockContext;
    @Mock private Resources mMockResources;
    @Mock private PipBoundsState mMockPipBoundsState;
    @Mock private ShellExecutor mMockMainExecutor;
    @Mock private PipTransitionState mMockPipTransitionState;
    @Mock private PipTransitionController mMockPipTransitionController;
    @Mock private Runnable mMockUpdateMovementBoundsRunnable;
    @Mock private WindowContainerToken mMockPipTaskToken;
    @Mock private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory mMockFactory;
    @Mock private SurfaceControl.Transaction mMockTransaction;
    @Mock private PipAlphaAnimator mMockAlphaAnimator;
    @Mock private Optional<DesktopUserRepositories> mMockOptionalDesktopUserRepositories;
    @Mock private RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;

    @Captor private ArgumentCaptor<Runnable> mRunnableArgumentCaptor;
    @Captor private ArgumentCaptor<WindowContainerTransaction> mWctArgumentCaptor;

    private PipScheduler mPipScheduler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getInteger(anyInt())).thenReturn(0);
        when(mMockPipBoundsState.getBounds()).thenReturn(TEST_STARTING_BOUNDS);
        when(mMockFactory.getTransaction()).thenReturn(mMockTransaction);
        when(mMockTransaction.setMatrix(any(SurfaceControl.class), any(Matrix.class), any()))
                .thenReturn(mMockTransaction);

        mPipScheduler = new PipScheduler(mMockContext, mMockPipBoundsState, mMockMainExecutor,
                mMockPipTransitionState, mMockOptionalDesktopUserRepositories,
                mRootTaskDisplayAreaOrganizer);
        mPipScheduler.setPipTransitionController(mMockPipTransitionController);
        mPipScheduler.setSurfaceControlTransactionFactory(mMockFactory);
        mPipScheduler.setPipAlphaAnimatorSupplier((context, leash, tx, direction) ->
                mMockAlphaAnimator);

        SurfaceControl testLeash = new SurfaceControl.Builder()
                .setContainerLayer()
                .setName("PipSchedulerTest")
                .setCallsite("PipSchedulerTest")
                .build();
        when(mMockPipTransitionState.getPinnedTaskLeash()).thenReturn(testLeash);
    }

    @Test
    public void scheduleExitPipViaExpand_nullTaskToken_noop() {
        setNullPipTaskToken();

        mPipScheduler.scheduleExitPipViaExpand();

        verify(mMockMainExecutor, never()).execute(any());
    }

    @Test
    public void scheduleExitPipViaExpand_exitTransitionCalled() {
        setMockPipTaskToken();

        mPipScheduler.scheduleExitPipViaExpand();

        verify(mMockMainExecutor, times(1)).execute(mRunnableArgumentCaptor.capture());
        assertNotNull(mRunnableArgumentCaptor.getValue());
        mRunnableArgumentCaptor.getValue().run();

        verify(mMockPipTransitionController, times(1))
                .startExitTransition(eq(TRANSIT_EXIT_PIP), any(), isNull());
    }

    @Test
    public void removePipAfterAnimation() {
        //TODO: Update once this is changed to run animation as part of transition
        setMockPipTaskToken();

        mPipScheduler.removePipAfterAnimation();
        verify(mMockAlphaAnimator, times(1))
                .setAnimationEndCallback(mRunnableArgumentCaptor.capture());
        assertNotNull(mRunnableArgumentCaptor.getValue());
        verify(mMockAlphaAnimator, times(1)).start();

        mRunnableArgumentCaptor.getValue().run();

        verify(mMockMainExecutor, times(1)).execute(mRunnableArgumentCaptor.capture());
        assertNotNull(mRunnableArgumentCaptor.getValue());

        mRunnableArgumentCaptor.getValue().run();

        verify(mMockPipTransitionController, times(1))
                .startExitTransition(eq(TRANSIT_REMOVE_PIP), any(), isNull());
    }

    @Test
    public void scheduleAnimateResizePip_bounds_nullTaskToken_noop() {
        setNullPipTaskToken();

        mPipScheduler.scheduleAnimateResizePip(TEST_BOUNDS);

        verify(mMockPipTransitionController, never()).startResizeTransition(any(), anyInt());
    }

    @Test
    public void scheduleAnimateResizePip_boundsConfig_nullTaskToken_noop() {
        setNullPipTaskToken();

        mPipScheduler.scheduleAnimateResizePip(TEST_BOUNDS, true);

        verify(mMockPipTransitionController, never()).startResizeTransition(any(), anyInt());
    }

    @Test
    public void scheduleAnimateResizePip_boundsConfig_setsConfigAtEnd() {
        setMockPipTaskToken();
        when(mMockPipTransitionState.isInPip()).thenReturn(true);

        mPipScheduler.scheduleAnimateResizePip(TEST_BOUNDS, true);

        verify(mMockPipTransitionController, times(1))
                .startResizeTransition(mWctArgumentCaptor.capture(), anyInt());
        assertNotNull(mWctArgumentCaptor.getValue());
        assertNotNull(mWctArgumentCaptor.getValue().getChanges());
        boolean hasConfigAtEndChange = false;
        for (WindowContainerTransaction.Change change :
                mWctArgumentCaptor.getValue().getChanges().values()) {
            if (change.getConfigAtTransitionEnd()) {
                hasConfigAtEndChange = true;
                break;
            }
        }
        assertTrue(hasConfigAtEndChange);
    }

    @Test
    public void scheduleAnimateResizePip_boundsConfigDuration_nullTaskToken_noop() {
        setNullPipTaskToken();

        mPipScheduler.scheduleAnimateResizePip(TEST_BOUNDS, true, TEST_RESIZE_DURATION);

        verify(mMockPipTransitionController, never()).startResizeTransition(any(), anyInt());
    }

    @Test
    public void scheduleAnimateResizePip_notInPip_noop() {
        setMockPipTaskToken();
        when(mMockPipTransitionState.isInPip()).thenReturn(false);

        mPipScheduler.scheduleAnimateResizePip(TEST_BOUNDS, true, TEST_RESIZE_DURATION);

        verify(mMockPipTransitionController, never()).startResizeTransition(any(), anyInt());
    }

    @Test
    public void scheduleAnimateResizePip_resizeTransition() {
        setMockPipTaskToken();
        when(mMockPipTransitionState.isInPip()).thenReturn(true);

        mPipScheduler.scheduleAnimateResizePip(TEST_BOUNDS, true, TEST_RESIZE_DURATION);

        verify(mMockPipTransitionController, times(1))
                .startResizeTransition(any(), eq(TEST_RESIZE_DURATION));
    }

    @Test
    public void scheduleUserResizePip_emptyBounds_noop() {
        setMockPipTaskToken();

        mPipScheduler.scheduleUserResizePip(new Rect());

        verify(mMockTransaction, never()).apply();
    }

    @Test
    public void scheduleUserResizePip_rotation_emptyBounds_noop() {
        setMockPipTaskToken();

        mPipScheduler.scheduleUserResizePip(new Rect(), 90);

        verify(mMockTransaction, never()).apply();
    }

    @Test
    public void scheduleUserResizePip_applyTransaction() {
        setMockPipTaskToken();

        mPipScheduler.scheduleUserResizePip(TEST_BOUNDS, 90);

        verify(mMockTransaction, times(1)).apply();
    }

    @Test
    public void finishResize_movementBoundsRunnableCalled() {
        mPipScheduler.setUpdateMovementBoundsRunnable(mMockUpdateMovementBoundsRunnable);
        mPipScheduler.scheduleFinishResizePip(TEST_BOUNDS);

        verify(mMockUpdateMovementBoundsRunnable, times(1)).run();
    }

    private void setNullPipTaskToken() {
        when(mMockPipTransitionState.getPipTaskToken()).thenReturn(null);
    }

    private void setMockPipTaskToken() {
        when(mMockPipTransitionState.getPipTaskToken()).thenReturn(mMockPipTaskToken);
    }
}
