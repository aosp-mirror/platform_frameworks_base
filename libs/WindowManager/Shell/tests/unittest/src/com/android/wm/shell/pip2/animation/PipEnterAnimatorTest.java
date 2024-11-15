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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Surface;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.wm.shell.R;
import com.android.wm.shell.pip2.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip2.phone.PipAppIconOverlay;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit test against {@link PipEnterAnimator}.
 */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class PipEnterAnimatorTest {
    private static final float TEST_CORNER_RADIUS = 1f;
    private static final float TEST_SHADOW_RADIUS = 2f;

    @Mock private Context mMockContext;
    @Mock private Resources mMockResources;
    @Mock private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory mMockFactory;
    @Mock private SurfaceControl.Transaction mMockAnimateTransaction;
    @Mock private SurfaceControl.Transaction mMockStartTransaction;
    @Mock private SurfaceControl.Transaction mMockFinishTransaction;
    @Mock private Runnable mMockStartCallback;
    @Mock private Runnable mMockEndCallback;
    @Mock private PipAppIconOverlay mMockPipAppIconOverlay;
    @Mock private SurfaceControl mMockAppIconOverlayLeash;
    @Mock private ActivityInfo mMockActivityInfo;

    @Surface.Rotation private int mRotation;
    private SurfaceControl mTestLeash;
    private Rect mEndBounds;
    private PipEnterAnimator mPipEnterAnimator;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getInteger(anyInt())).thenReturn(0);
        when(mMockFactory.getTransaction()).thenReturn(mMockAnimateTransaction);
        when(mMockPipAppIconOverlay.getLeash()).thenReturn(mMockAppIconOverlayLeash);
        when(mMockResources.getDimensionPixelSize(R.dimen.pip_corner_radius))
                .thenReturn((int) TEST_CORNER_RADIUS);
        when(mMockResources.getDimensionPixelSize(R.dimen.pip_shadow_radius))
                .thenReturn((int) TEST_SHADOW_RADIUS);

        prepareTransaction(mMockAnimateTransaction);
        prepareTransaction(mMockStartTransaction);
        prepareTransaction(mMockFinishTransaction);

        mTestLeash = new SurfaceControl.Builder()
                .setContainerLayer()
                .setName("PipExpandAnimatorTest")
                .setCallsite("PipExpandAnimatorTest")
                .build();
    }

    @Test
    public void setAnimationStartCallback_enter_callbackStartCallback() {
        mRotation = Surface.ROTATION_0;
        mEndBounds = new Rect(100, 100, 500, 500);
        mPipEnterAnimator = new PipEnterAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mEndBounds, mRotation);
        mPipEnterAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        mPipEnterAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipEnterAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipEnterAnimator.start();
            mPipEnterAnimator.pause();
        });

        verify(mMockStartCallback).run();
        verifyZeroInteractions(mMockEndCallback);

        // Check corner and shadow radii were set
        verify(mMockAnimateTransaction, atLeastOnce())
                .setCornerRadius(eq(mTestLeash), eq(TEST_CORNER_RADIUS));
        verify(mMockAnimateTransaction, atLeastOnce())
                .setShadowRadius(eq(mTestLeash), eq(TEST_SHADOW_RADIUS));
    }

    @Test
    public void setAnimationEndCallback_enter_callbackStartAndEndCallback() {
        mRotation = Surface.ROTATION_0;
        mEndBounds = new Rect(100, 100, 500, 500);
        mPipEnterAnimator = new PipEnterAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mEndBounds, mRotation);
        mPipEnterAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        mPipEnterAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipEnterAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipEnterAnimator.start();
            mPipEnterAnimator.end();
        });

        verify(mMockStartCallback).run();
        verify(mMockEndCallback).run();

        // Check corner and shadow radii were set
        verify(mMockAnimateTransaction, atLeastOnce())
                .setCornerRadius(eq(mTestLeash), eq(TEST_CORNER_RADIUS));
        verify(mMockAnimateTransaction, atLeastOnce())
                .setShadowRadius(eq(mTestLeash), eq(TEST_SHADOW_RADIUS));
    }

    @Test
    public void setAppIconContentOverlay_thenGetContentOverlayLeash_returnOverlayLeash() {
        mRotation = Surface.ROTATION_0;
        mEndBounds = new Rect(100, 100, 500, 500);
        mPipEnterAnimator = new PipEnterAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mEndBounds, mRotation);
        mPipEnterAnimator.setSurfaceControlTransactionFactory(mMockFactory);
        mPipEnterAnimator.setPipAppIconOverlaySupplier(
                (context, appBounds, endBounds, icon, iconSize) -> mMockPipAppIconOverlay);

        mPipEnterAnimator.setAppIconContentOverlay(mMockContext, mEndBounds, mEndBounds,
                mMockActivityInfo, 64 /* iconSize */);

        assertEquals(mPipEnterAnimator.getContentOverlayLeash(), mMockAppIconOverlayLeash);
    }

    @Test
    public void setAppIconContentOverlay_thenClearAppIconOverlay_returnNullLeash() {
        mRotation = Surface.ROTATION_0;
        mEndBounds = new Rect(100, 100, 500, 500);
        mPipEnterAnimator = new PipEnterAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mEndBounds, mRotation);
        mPipEnterAnimator.setSurfaceControlTransactionFactory(mMockFactory);
        mPipEnterAnimator.setPipAppIconOverlaySupplier(
                (context, appBounds, endBounds, icon, iconSize) -> mMockPipAppIconOverlay);

        mPipEnterAnimator.setAppIconContentOverlay(mMockContext, mEndBounds, mEndBounds,
                mMockActivityInfo, 64 /* iconSize */);
        mPipEnterAnimator.clearAppIconOverlay();

        assertNull(mPipEnterAnimator.getContentOverlayLeash());
    }

    @Test
    public void onEnterAnimationUpdate_withContentOverlay_animateOverlay() {
        mRotation = Surface.ROTATION_0;
        mEndBounds = new Rect(100, 100, 500, 500);
        mPipEnterAnimator = new PipEnterAnimator(mMockContext, mTestLeash,
                mMockStartTransaction, mMockFinishTransaction,
                mEndBounds, mRotation);
        mPipEnterAnimator.setSurfaceControlTransactionFactory(mMockFactory);
        mPipEnterAnimator.setPipAppIconOverlaySupplier(
                (context, appBounds, endBounds, icon, iconSize) -> mMockPipAppIconOverlay);

        float fraction = 0.5f;
        mPipEnterAnimator.setAppIconContentOverlay(mMockContext, mEndBounds, mEndBounds,
                mMockActivityInfo, 64 /* iconSize */);
        mPipEnterAnimator.onEnterAnimationUpdate(fraction, mMockAnimateTransaction);

        verify(mMockPipAppIconOverlay).onAnimationUpdate(
                eq(mMockAnimateTransaction), anyFloat(), eq(fraction), eq(mEndBounds));

        // Check corner and shadow radii were set
        verify(mMockAnimateTransaction, atLeastOnce())
                .setCornerRadius(eq(mTestLeash), eq(TEST_CORNER_RADIUS));
        verify(mMockAnimateTransaction, atLeastOnce())
                .setShadowRadius(eq(mTestLeash), eq(TEST_SHADOW_RADIUS));
    }

    // set up transaction chaining
    private void prepareTransaction(SurfaceControl.Transaction tx) {
        when(tx.setMatrix(any(SurfaceControl.class), any(Matrix.class), any()))
                .thenReturn(tx);
        when(tx.setCornerRadius(any(SurfaceControl.class), anyFloat()))
                .thenReturn(tx);
        when(tx.setShadowRadius(any(SurfaceControl.class), anyFloat()))
                .thenReturn(tx);
    }
}
