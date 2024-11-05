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

package com.android.wm.shell.pip2.animation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.kotlin.MatchersKt.eq;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.wm.shell.pip2.PipSurfaceTransactionHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit test against {@link PipResizeAnimator}.
 */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class PipResizeAnimatorTest {

    private static final float FLOAT_COMPARISON_DELTA = 0.001f;

    @Mock private Context mMockContext;

    @Mock private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory mMockFactory;

    @Mock private SurfaceControl.Transaction mMockTransaction;

    @Mock private SurfaceControl.Transaction mMockStartTransaction;

    @Mock private SurfaceControl.Transaction mMockFinishTransaction;

    @Mock private Runnable mMockStartCallback;

    @Mock private Runnable mMockEndCallback;

    private PipResizeAnimator mPipResizeAnimator;
    private Rect mBaseBounds;
    private Rect mStartBounds;
    private Rect mEndBounds;
    private SurfaceControl mTestLeash;
    private ArgumentCaptor<Matrix> mArgumentCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockFactory.getTransaction()).thenReturn(mMockTransaction);
        when(mMockTransaction.setMatrix(any(SurfaceControl.class), any(Matrix.class), any()))
                .thenReturn(mMockTransaction);
        when(mMockStartTransaction.setMatrix(any(SurfaceControl.class), any(Matrix.class), any()))
                .thenReturn(mMockStartTransaction);
        when(mMockFinishTransaction.setMatrix(any(SurfaceControl.class), any(Matrix.class), any()))
                .thenReturn(mMockFinishTransaction);

        mArgumentCaptor = ArgumentCaptor.forClass(Matrix.class);
        mTestLeash = new SurfaceControl.Builder()
                .setContainerLayer()
                .setName("PipResizeAnimatorTest")
                .setCallsite("PipResizeAnimatorTest")
                .build();
    }

    @Test
    public void setAnimationStartCallback_resize_callbackStartCallback() {
        mBaseBounds = new Rect(100, 100, 500, 500);
        mStartBounds = new Rect(200, 200, 1_000, 1_000);
        mEndBounds = new Rect(mBaseBounds);
        final int duration = 10;
        final float delta = 0;
        mPipResizeAnimator = new PipResizeAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mBaseBounds, mStartBounds, mEndBounds,
                duration, delta);

        mPipResizeAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipResizeAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipResizeAnimator.start();
            mPipResizeAnimator.pause();
        });

        verify(mMockStartCallback).run();
        verifyZeroInteractions(mMockEndCallback);
    }

    @Test
    public void setAnimationEndCallback_resize_callbackStartAndEndCallback() {
        mBaseBounds = new Rect(100, 100, 500, 500);
        mStartBounds = new Rect(200, 200, 1_000, 1_000);
        mEndBounds = new Rect(mBaseBounds);
        final int duration = 10;
        final float delta = 0;
        mPipResizeAnimator = new PipResizeAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mBaseBounds, mStartBounds, mEndBounds,
                duration, delta);

        mPipResizeAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipResizeAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipResizeAnimator.start();
            mPipResizeAnimator.end();
        });

        verify(mMockStartCallback).run();
        verify(mMockEndCallback).run();
    }

    @Test
    public void onAnimationEnd_resizeDown_sizeChanged() {
        // Resize from 800x800 to 400x400, eg. resize down
        mBaseBounds = new Rect(100, 100, 500, 500);
        mStartBounds = new Rect(200, 200, 1_000, 1_000);
        mEndBounds = new Rect(mBaseBounds);
        final int duration = 10;
        final float delta = 0;
        final float[] matrix = new float[9];
        mPipResizeAnimator = new PipResizeAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mBaseBounds, mStartBounds, mEndBounds,
                duration, delta);
        mPipResizeAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        mPipResizeAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipResizeAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipResizeAnimator.start();
            clearInvocations(mMockTransaction);
            mPipResizeAnimator.end();
        });

        // Start transaction scales down from its final state
        verify(mMockStartTransaction).setMatrix(eq(mTestLeash), mArgumentCaptor.capture(), any());
        mArgumentCaptor.getValue().getValues(matrix);
        assertEquals(matrix[Matrix.MSCALE_X],
                mStartBounds.width() / (float) mEndBounds.width(), FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MSCALE_Y],
                mStartBounds.height() / (float) mEndBounds.height(), FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MTRANS_X], mStartBounds.left, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MTRANS_Y], mStartBounds.top, FLOAT_COMPARISON_DELTA);

        // Final animation transaction scales to 1 and puts the leash at final position
        verify(mMockTransaction).setMatrix(eq(mTestLeash), mArgumentCaptor.capture(), any());
        mArgumentCaptor.getValue().getValues(matrix);
        assertEquals(matrix[Matrix.MSCALE_X], 1f, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MSCALE_Y], 1f, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MTRANS_X], mEndBounds.left, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MTRANS_Y], mEndBounds.top, FLOAT_COMPARISON_DELTA);

        // Finish transaction resets scale and puts the leash at final position
        verify(mMockFinishTransaction).setMatrix(eq(mTestLeash), mArgumentCaptor.capture(), any());
        mArgumentCaptor.getValue().getValues(matrix);
        assertEquals(matrix[Matrix.MSCALE_X], 1f, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MSCALE_Y], 1f, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MTRANS_X], mEndBounds.left, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MTRANS_Y], mEndBounds.top, FLOAT_COMPARISON_DELTA);
    }

    @Test
    public void onAnimationEnd_resizeUp_sizeChanged() {
        // Resize from 400x400 to 800x800, eg. resize up
        mBaseBounds = new Rect(200, 200, 1_000, 1_000);
        mStartBounds = new Rect(100, 100, 500, 500);
        mEndBounds = new Rect(mBaseBounds);
        final int duration = 10;
        final float delta = 0;
        final float[] matrix = new float[9];
        mPipResizeAnimator = new PipResizeAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mBaseBounds, mStartBounds, mEndBounds,
                duration, delta);
        mPipResizeAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        mPipResizeAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipResizeAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipResizeAnimator.start();
            clearInvocations(mMockTransaction);
            mPipResizeAnimator.end();
        });

        // Start transaction scales up from its final state
        verify(mMockStartTransaction).setMatrix(eq(mTestLeash), mArgumentCaptor.capture(), any());
        mArgumentCaptor.getValue().getValues(matrix);
        assertEquals(matrix[Matrix.MSCALE_X],
                mStartBounds.width() / (float) mEndBounds.width(), FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MSCALE_Y],
                mStartBounds.height() / (float) mEndBounds.height(), FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MTRANS_X], mStartBounds.left, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MTRANS_Y], mStartBounds.top, FLOAT_COMPARISON_DELTA);

        // Final animation transaction scales to 1 and puts the leash at final position
        verify(mMockTransaction).setMatrix(eq(mTestLeash), mArgumentCaptor.capture(), any());
        mArgumentCaptor.getValue().getValues(matrix);
        assertEquals(matrix[Matrix.MSCALE_X], 1f, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MSCALE_Y], 1f, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MTRANS_X], mEndBounds.left, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MTRANS_Y], mEndBounds.top, FLOAT_COMPARISON_DELTA);

        // Finish transaction resets scale and puts the leash at final position
        verify(mMockFinishTransaction).setMatrix(eq(mTestLeash), mArgumentCaptor.capture(), any());
        mArgumentCaptor.getValue().getValues(matrix);
        assertEquals(matrix[Matrix.MSCALE_X], 1f, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MSCALE_Y], 1f, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MTRANS_X], mEndBounds.left, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MTRANS_Y], mEndBounds.top, FLOAT_COMPARISON_DELTA);
    }

    @Test
    public void onAnimationEnd_withInitialDelta_rotateToZeroDegree() {
        mBaseBounds = new Rect(200, 200, 1_000, 1_000);
        mStartBounds = new Rect(100, 100, 500, 500);
        mEndBounds = new Rect(mBaseBounds);
        final int duration = 10;
        final float delta = 45;
        final float[] matrix = new float[9];
        mPipResizeAnimator = new PipResizeAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mBaseBounds, mStartBounds, mEndBounds,
                duration, delta);
        mPipResizeAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        mPipResizeAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipResizeAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipResizeAnimator.start();
            clearInvocations(mMockTransaction);
            mPipResizeAnimator.end();
        });

        // Final animation transaction sets skew to zero
        verify(mMockTransaction).setMatrix(eq(mTestLeash), mArgumentCaptor.capture(), any());
        mArgumentCaptor.getValue().getValues(matrix);
        assertEquals(matrix[Matrix.MSKEW_X], 0f, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MSKEW_Y], 0f, FLOAT_COMPARISON_DELTA);

        // Finish transaction sets skew to zero
        verify(mMockFinishTransaction).setMatrix(eq(mTestLeash), mArgumentCaptor.capture(), any());
        mArgumentCaptor.getValue().getValues(matrix);
        assertEquals(matrix[Matrix.MSKEW_X], 0f, FLOAT_COMPARISON_DELTA);
        assertEquals(matrix[Matrix.MSKEW_Y], 0f, FLOAT_COMPARISON_DELTA);
    }
}
