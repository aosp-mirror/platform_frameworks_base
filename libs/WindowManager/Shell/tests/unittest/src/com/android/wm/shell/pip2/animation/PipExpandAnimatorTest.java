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
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Surface;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.wm.shell.pip2.PipSurfaceTransactionHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit test against {@link PipExpandAnimator}.
 */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class PipExpandAnimatorTest {

    @Mock private Context mMockContext;

    @Mock private Resources mMockResources;

    @Mock private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory mMockFactory;

    @Mock private SurfaceControl.Transaction mMockTransaction;

    @Mock private SurfaceControl.Transaction mMockStartTransaction;

    @Mock private SurfaceControl.Transaction mMockFinishTransaction;

    @Mock private Runnable mMockStartCallback;

    @Mock private Runnable mMockEndCallback;

    private PipExpandAnimator mPipExpandAnimator;
    private Rect mBaseBounds;
    private Rect mStartBounds;
    private Rect mEndBounds;
    private Rect mSourceRectHint;
    @Surface.Rotation private int mRotation;
    private SurfaceControl mTestLeash;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getInteger(anyInt())).thenReturn(0);
        when(mMockFactory.getTransaction()).thenReturn(mMockTransaction);
        // No-op on the mMockTransaction
        when(mMockTransaction.setAlpha(any(SurfaceControl.class), anyFloat()))
                .thenReturn(mMockTransaction);
        when(mMockTransaction.setCrop(any(SurfaceControl.class), any(Rect.class)))
                .thenReturn(mMockTransaction);
        when(mMockTransaction.setMatrix(any(SurfaceControl.class), any(Matrix.class), any()))
                .thenReturn(mMockTransaction);
        when(mMockTransaction.setCornerRadius(any(SurfaceControl.class), anyFloat()))
                .thenReturn(mMockTransaction);
        when(mMockTransaction.setShadowRadius(any(SurfaceControl.class), anyFloat()))
                .thenReturn(mMockTransaction);
        // No-op on the mMockStartTransaction
        when(mMockStartTransaction.setAlpha(any(SurfaceControl.class), anyFloat()))
                .thenReturn(mMockFinishTransaction);
        when(mMockStartTransaction.setCrop(any(SurfaceControl.class), any(Rect.class)))
                .thenReturn(mMockFinishTransaction);
        when(mMockStartTransaction.setMatrix(any(SurfaceControl.class), any(Matrix.class), any()))
                .thenReturn(mMockFinishTransaction);
        when(mMockStartTransaction.setCornerRadius(any(SurfaceControl.class), anyFloat()))
                .thenReturn(mMockFinishTransaction);
        when(mMockStartTransaction.setShadowRadius(any(SurfaceControl.class), anyFloat()))
                .thenReturn(mMockFinishTransaction);
        // Do the same for mMockFinishTransaction
        when(mMockFinishTransaction.setAlpha(any(SurfaceControl.class), anyFloat()))
                .thenReturn(mMockFinishTransaction);
        when(mMockFinishTransaction.setCrop(any(SurfaceControl.class), any(Rect.class)))
                .thenReturn(mMockFinishTransaction);
        when(mMockFinishTransaction.setMatrix(any(SurfaceControl.class), any(Matrix.class), any()))
                .thenReturn(mMockFinishTransaction);
        when(mMockFinishTransaction.setCornerRadius(any(SurfaceControl.class), anyFloat()))
                .thenReturn(mMockFinishTransaction);
        when(mMockFinishTransaction.setShadowRadius(any(SurfaceControl.class), anyFloat()))
                .thenReturn(mMockFinishTransaction);

        mTestLeash = new SurfaceControl.Builder()
                .setContainerLayer()
                .setName("PipExpandAnimatorTest")
                .setCallsite("PipExpandAnimatorTest")
                .build();
    }

    @Test
    public void setAnimationStartCallback_expand_callbackStartCallback() {
        mRotation = Surface.ROTATION_0;
        mBaseBounds = new Rect(0, 0, 1_000, 2_000);
        mStartBounds = new Rect(500, 1_000, 1_000, 2_000);
        mEndBounds = new Rect(mBaseBounds);
        mPipExpandAnimator = new PipExpandAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mBaseBounds, mStartBounds, mEndBounds, mSourceRectHint,
                mRotation);
        mPipExpandAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        mPipExpandAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipExpandAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipExpandAnimator.start();
            mPipExpandAnimator.pause();
        });

        verify(mMockStartCallback).run();
        verifyZeroInteractions(mMockEndCallback);
    }

    @Test
    public void setAnimationEndCallback_expand_callbackStartAndEndCallback() {
        mRotation = Surface.ROTATION_0;
        mBaseBounds = new Rect(0, 0, 1_000, 2_000);
        mStartBounds = new Rect(500, 1_000, 1_000, 2_000);
        mEndBounds = new Rect(mBaseBounds);
        mPipExpandAnimator = new PipExpandAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mBaseBounds, mStartBounds, mEndBounds, mSourceRectHint,
                mRotation);
        mPipExpandAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        mPipExpandAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipExpandAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipExpandAnimator.start();
            mPipExpandAnimator.end();
        });

        verify(mMockStartCallback).run();
        verify(mMockEndCallback).run();
    }

    @Test
    public void onAnimationEnd_expand_leashIsFullscreen() {
        mRotation = Surface.ROTATION_0;
        mBaseBounds = new Rect(0, 0, 1_000, 2_000);
        mStartBounds = new Rect(500, 1_000, 1_000, 2_000);
        mEndBounds = new Rect(mBaseBounds);
        mPipExpandAnimator = new PipExpandAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mBaseBounds, mStartBounds, mEndBounds, mSourceRectHint,
                mRotation);
        mPipExpandAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        mPipExpandAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipExpandAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipExpandAnimator.start();
            clearInvocations(mMockTransaction);
            mPipExpandAnimator.end();
        });

        verify(mMockTransaction).setCrop(mTestLeash, mEndBounds);
        verify(mMockTransaction).setCornerRadius(mTestLeash, 0f);
        verify(mMockTransaction).setShadowRadius(mTestLeash, 0f);
    }
}
