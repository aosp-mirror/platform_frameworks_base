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

import android.graphics.Point;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Size;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipSnapAlgorithm;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip.PipUiEventLogger;

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
    private ShellExecutor mMainExecutor;

    private PipBoundsState mPipBoundsState;
    private PipBoundsAlgorithm mPipBoundsAlgorithm;
    private PipSnapAlgorithm mPipSnapAlgorithm;
    private PipMotionHelper mMotionHelper;
    private PipResizeGestureHandler mPipResizeGestureHandler;

    private Rect mInsetBounds;
    private Rect mMinBounds;
    private Rect mCurBounds;
    private boolean mFromImeAdjustment;
    private boolean mFromShelfAdjustment;
    private int mDisplayRotation;
    private int mImeHeight;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mPipBoundsState = new PipBoundsState(mContext);
        mPipBoundsAlgorithm = new PipBoundsAlgorithm(mContext, mPipBoundsState);
        mPipSnapAlgorithm = mPipBoundsAlgorithm.getSnapAlgorithm();
        mPipSnapAlgorithm = new PipSnapAlgorithm();
        mPipTouchHandler = new PipTouchHandler(mContext, mPhonePipMenuController,
                mPipBoundsAlgorithm, mPipBoundsState, mPipTaskOrganizer,
                mMockPipTransitionController, mFloatingContentCoordinator, mPipUiEventLogger,
                mMainExecutor);
        mMotionHelper = Mockito.spy(mPipTouchHandler.getMotionHelper());
        mPipResizeGestureHandler = Mockito.spy(mPipTouchHandler.getPipResizeGestureHandler());
        mPipTouchHandler.setPipMotionHelper(mMotionHelper);
        mPipTouchHandler.setPipResizeGestureHandler(mPipResizeGestureHandler);

        // Assume a display of 1000 x 1000
        // inset of 10
        mInsetBounds = new Rect(10, 10, 990, 990);
        // minBounds of 100x100 bottom right corner
        mMinBounds = new Rect(890, 890, 990, 990);
        mCurBounds = new Rect(mMinBounds);
        mFromImeAdjustment = false;
        mFromShelfAdjustment = false;
        mDisplayRotation = 0;
        mImeHeight = 100;
    }

    @Test
    public void updateMovementBounds_minBounds() {
        Rect expectedMinMovementBounds = new Rect();
        mPipBoundsAlgorithm.getMovementBounds(mMinBounds, mInsetBounds, expectedMinMovementBounds,
                0);

        mPipTouchHandler.onMovementBoundsChanged(mInsetBounds, mMinBounds, mCurBounds,
                mFromImeAdjustment, mFromShelfAdjustment, mDisplayRotation);

        assertEquals(expectedMinMovementBounds, mPipBoundsState.getNormalMovementBounds());
        verify(mPipResizeGestureHandler, times(1))
                .updateMinSize(mMinBounds.width(), mMinBounds.height());
    }

    @Test
    public void updateMovementBounds_maxBounds() {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        Size maxSize = mPipBoundsAlgorithm.getSizeForAspectRatio(1,
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.pip_expanded_shortest_edge_size), displaySize.x, displaySize.y);
        Rect maxBounds = new Rect(0, 0, maxSize.getWidth(), maxSize.getHeight());
        Rect expectedMaxMovementBounds = new Rect();
        mPipBoundsAlgorithm.getMovementBounds(maxBounds, mInsetBounds, expectedMaxMovementBounds,
                0);

        mPipTouchHandler.onMovementBoundsChanged(mInsetBounds, mMinBounds, mCurBounds,
                mFromImeAdjustment, mFromShelfAdjustment, mDisplayRotation);

        assertEquals(expectedMaxMovementBounds, mPipBoundsState.getExpandedMovementBounds());
        verify(mPipResizeGestureHandler, times(1))
                .updateMaxSize(maxBounds.width(), maxBounds.height());
    }

    @Test
    public void updateMovementBounds_withImeAdjustment_movesPip() {
        mFromImeAdjustment = true;
        mPipTouchHandler.onImeVisibilityChanged(true /* imeVisible */, mImeHeight);

        mPipTouchHandler.onMovementBoundsChanged(mInsetBounds, mMinBounds, mCurBounds,
                mFromImeAdjustment, mFromShelfAdjustment, mDisplayRotation);

        verify(mMotionHelper, times(1)).animateToOffset(any(), anyInt());
    }
}
