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

package com.android.wm.shell.pip.phone;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipKeepClearAlgorithm;
import com.android.wm.shell.pip.PipSnapAlgorithm;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.sysui.ShellInit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests against {@link PipTouchHandler}, including but not limited to:
 * - Update movement bounds based on new bounds
 * - Update movement bounds based on IME/shelf
 * - Update movement bounds to PipResizeHandler
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class PipTouchHandlerTest extends ShellTestCase {

    private static final int INSET = 10;
    private static final int PIP_LENGTH = 100;

    private PipTouchHandler mPipTouchHandler;

    @Mock
    private PhonePipMenuController mPhonePipMenuController;

    @Mock
    private PipTaskOrganizer mPipTaskOrganizer;

    @Mock
    private PipTransitionController mMockPipTransitionController;

    @Mock
    private FloatingContentCoordinator mFloatingContentCoordinator;

    @Mock
    private PipUiEventLogger mPipUiEventLogger;

    @Mock
    private ShellInit mShellInit;

    @Mock
    private ShellExecutor mMainExecutor;

    private PipBoundsState mPipBoundsState;
    private PipBoundsAlgorithm mPipBoundsAlgorithm;
    private PipSnapAlgorithm mPipSnapAlgorithm;
    private PipMotionHelper mMotionHelper;
    private PipResizeGestureHandler mPipResizeGestureHandler;

    private DisplayLayout mDisplayLayout;
    private Rect mInsetBounds;
    private Rect mPipBounds;
    private Rect mCurBounds;
    private boolean mFromImeAdjustment;
    private boolean mFromShelfAdjustment;
    private int mDisplayRotation;
    private int mImeHeight;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mPipBoundsState = new PipBoundsState(mContext);
        mPipSnapAlgorithm = new PipSnapAlgorithm();
        mPipBoundsAlgorithm = new PipBoundsAlgorithm(mContext, mPipBoundsState, mPipSnapAlgorithm,
                new PipKeepClearAlgorithm() {});
        PipMotionHelper pipMotionHelper = new PipMotionHelper(mContext, mPipBoundsState,
                mPipTaskOrganizer, mPhonePipMenuController, mPipSnapAlgorithm,
                mMockPipTransitionController, mFloatingContentCoordinator);
        mPipTouchHandler = new PipTouchHandler(mContext, mShellInit, mPhonePipMenuController,
                mPipBoundsAlgorithm, mPipBoundsState, mPipTaskOrganizer, pipMotionHelper,
                mFloatingContentCoordinator, mPipUiEventLogger, mMainExecutor);
        // We aren't actually using ShellInit, so just call init directly
        mPipTouchHandler.onInit();
        mMotionHelper = Mockito.spy(mPipTouchHandler.getMotionHelper());
        mPipResizeGestureHandler = Mockito.spy(mPipTouchHandler.getPipResizeGestureHandler());
        mPipTouchHandler.setPipMotionHelper(mMotionHelper);
        mPipTouchHandler.setPipResizeGestureHandler(mPipResizeGestureHandler);

        mDisplayLayout = new DisplayLayout(mContext, mContext.getDisplay());
        mPipBoundsState.setDisplayLayout(mDisplayLayout);
        mInsetBounds = new Rect(mPipBoundsState.getDisplayBounds().left + INSET,
                mPipBoundsState.getDisplayBounds().top + INSET,
                mPipBoundsState.getDisplayBounds().right - INSET,
                mPipBoundsState.getDisplayBounds().bottom - INSET);
        // minBounds of 100x100 bottom right corner
        mPipBounds = new Rect(mPipBoundsState.getDisplayBounds().right - INSET - PIP_LENGTH,
                mPipBoundsState.getDisplayBounds().bottom - INSET - PIP_LENGTH,
                mPipBoundsState.getDisplayBounds().right - INSET,
                mPipBoundsState.getDisplayBounds().bottom - INSET);
        mCurBounds = new Rect(mPipBounds);
        mFromImeAdjustment = false;
        mFromShelfAdjustment = false;
        mDisplayRotation = 0;
        mImeHeight = 100;
    }

    @Test
    public void instantiate_addInitCallback() {
        verify(mShellInit, times(1)).addInitCallback(any(), any());
    }

    @Test
    public void updateMovementBounds_minMaxBounds() {
        final int shorterLength = Math.min(mPipBoundsState.getDisplayBounds().width(),
                mPipBoundsState.getDisplayBounds().height());
        Rect expectedMovementBounds = new Rect();
        mPipBoundsAlgorithm.getMovementBounds(mPipBounds, mInsetBounds, expectedMovementBounds,
                0);

        mPipTouchHandler.onMovementBoundsChanged(mInsetBounds, mPipBounds, mCurBounds,
                mFromImeAdjustment, mFromShelfAdjustment, mDisplayRotation);

        assertEquals(expectedMovementBounds, mPipBoundsState.getNormalMovementBounds());
        verify(mPipResizeGestureHandler, times(1))
                .updateMinSize(mPipBounds.width(), mPipBounds.height());

        verify(mPipResizeGestureHandler, times(1))
                .updateMaxSize(shorterLength - 2 * mInsetBounds.left,
                        shorterLength - 2 * mInsetBounds.left);
    }

    @Test
    public void updateMovementBounds_withImeAdjustment_movesPip() {
        mFromImeAdjustment = true;
        mPipTouchHandler.onImeVisibilityChanged(true /* imeVisible */, mImeHeight);

        mPipTouchHandler.onMovementBoundsChanged(mInsetBounds, mPipBounds, mCurBounds,
                mFromImeAdjustment, mFromShelfAdjustment, mDisplayRotation);

        verify(mMotionHelper, times(1)).animateToOffset(any(), anyInt());
    }
}
