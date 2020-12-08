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

package com.android.systemui.pip.phone;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.IActivityManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Size;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.model.SysUiState;
import com.android.systemui.pip.PipBoundsHandler;
import com.android.systemui.pip.PipSnapAlgorithm;
import com.android.systemui.pip.PipTaskOrganizer;
import com.android.systemui.pip.PipUiEventLogger;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.FloatingContentCoordinator;

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
public class PipTouchHandlerTest extends SysuiTestCase {

    private PipTouchHandler mPipTouchHandler;

    @Mock
    private IActivityManager mActivityManager;

    @Mock
    private PipMenuActivityController mPipMenuActivityController;

    @Mock
    private InputConsumerController mInputConsumerController;

    @Mock
    private PipBoundsHandler mPipBoundsHandler;

    @Mock
    private PipTaskOrganizer mPipTaskOrganizer;

    @Mock
    private FloatingContentCoordinator mFloatingContentCoordinator;

    @Mock
    private DeviceConfigProxy mDeviceConfigProxy;

    @Mock
    private SysUiState mSysUiState;

    @Mock
    private PipUiEventLogger mPipUiEventLogger;

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
        mPipSnapAlgorithm = new PipSnapAlgorithm(mContext);
        mPipTouchHandler = new PipTouchHandler(mContext, mActivityManager,
                mPipMenuActivityController, mInputConsumerController, mPipBoundsHandler,
                mPipTaskOrganizer, mFloatingContentCoordinator, mDeviceConfigProxy,
                mPipSnapAlgorithm, mSysUiState, mPipUiEventLogger);
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
        mPipSnapAlgorithm.getMovementBounds(mMinBounds, mInsetBounds, expectedMinMovementBounds, 0);

        mPipTouchHandler.onMovementBoundsChanged(mInsetBounds, mMinBounds, mCurBounds,
                mFromImeAdjustment, mFromShelfAdjustment, mDisplayRotation);

        assertEquals(expectedMinMovementBounds, mPipTouchHandler.mNormalMovementBounds);
        verify(mPipResizeGestureHandler, times(1))
                .updateMinSize(mMinBounds.width(), mMinBounds.height());
    }

    @Test
    public void updateMovementBounds_maxBounds() {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        Size maxSize = mPipSnapAlgorithm.getSizeForAspectRatio(1,
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.pip_expanded_shortest_edge_size), displaySize.x, displaySize.y);
        Rect maxBounds = new Rect(0, 0, maxSize.getWidth(), maxSize.getHeight());
        Rect expectedMaxMovementBounds = new Rect();
        mPipSnapAlgorithm.getMovementBounds(maxBounds, mInsetBounds, expectedMaxMovementBounds, 0);

        mPipTouchHandler.onMovementBoundsChanged(mInsetBounds, mMinBounds, mCurBounds,
                mFromImeAdjustment, mFromShelfAdjustment, mDisplayRotation);

        assertEquals(expectedMaxMovementBounds, mPipTouchHandler.mExpandedMovementBounds);
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
