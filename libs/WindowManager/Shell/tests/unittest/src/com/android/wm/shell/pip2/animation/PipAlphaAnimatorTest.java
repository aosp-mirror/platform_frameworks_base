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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.wm.shell.R;
import com.android.wm.shell.pip2.PipSurfaceTransactionHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit test against {@link PipAlphaAnimator}.
 */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class PipAlphaAnimatorTest {
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

    private PipAlphaAnimator mPipAlphaAnimator;
    private SurfaceControl mTestLeash;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getInteger(anyInt())).thenReturn(0);
        when(mMockFactory.getTransaction()).thenReturn(mMockAnimateTransaction);
        when(mMockResources.getDimensionPixelSize(R.dimen.pip_corner_radius))
                .thenReturn((int) TEST_CORNER_RADIUS);
        when(mMockResources.getDimensionPixelSize(R.dimen.pip_shadow_radius))
                .thenReturn((int) TEST_SHADOW_RADIUS);

        prepareTransaction(mMockAnimateTransaction);
        prepareTransaction(mMockStartTransaction);
        prepareTransaction(mMockFinishTransaction);

        mTestLeash = new SurfaceControl.Builder()
                .setContainerLayer()
                .setName("PipAlphaAnimatorTest")
                .setCallsite("PipAlphaAnimatorTest")
                .build();
    }

    @Test
    public void setAnimationStartCallback_fadeInAnimator_callbackStartCallback() {
        mPipAlphaAnimator = new PipAlphaAnimator(mMockContext, mTestLeash, mMockStartTransaction,
                mMockFinishTransaction, PipAlphaAnimator.FADE_IN);

        mPipAlphaAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipAlphaAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipAlphaAnimator.start();
            mPipAlphaAnimator.pause();
        });

        verify(mMockStartCallback).run();
        verifyZeroInteractions(mMockEndCallback);
    }

    @Test
    public void setAnimationEndCallback_fadeInAnimator_callbackStartAndEndCallback() {
        mPipAlphaAnimator = new PipAlphaAnimator(mMockContext, mTestLeash, mMockStartTransaction,
                mMockFinishTransaction, PipAlphaAnimator.FADE_IN);

        mPipAlphaAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipAlphaAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipAlphaAnimator.start();
            mPipAlphaAnimator.end();
        });

        verify(mMockStartCallback).run();
        verify(mMockEndCallback).run();
    }

    @Test
    public void onAnimationStart_setCornerAndShadowRadii() {
        mPipAlphaAnimator = new PipAlphaAnimator(mMockContext, mTestLeash, mMockStartTransaction,
                mMockFinishTransaction, PipAlphaAnimator.FADE_IN);
        mPipAlphaAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipAlphaAnimator.start();
            mPipAlphaAnimator.pause();
        });

        verify(mMockStartTransaction, atLeastOnce())
                .setCornerRadius(eq(mTestLeash), eq(TEST_CORNER_RADIUS));
        verify(mMockStartTransaction, atLeastOnce())
                .setShadowRadius(eq(mTestLeash), eq(TEST_SHADOW_RADIUS));
    }

    @Test
    public void onAnimationUpdate_setCornerAndShadowRadii() {
        mPipAlphaAnimator = new PipAlphaAnimator(mMockContext, mTestLeash, mMockStartTransaction,
                mMockFinishTransaction, PipAlphaAnimator.FADE_IN);
        mPipAlphaAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipAlphaAnimator.start();
            mPipAlphaAnimator.pause();
        });

        verify(mMockAnimateTransaction, atLeastOnce())
                .setCornerRadius(eq(mTestLeash), eq(TEST_CORNER_RADIUS));
        verify(mMockAnimateTransaction, atLeastOnce())
                .setShadowRadius(eq(mTestLeash), eq(TEST_SHADOW_RADIUS));
    }

    @Test
    public void onAnimationEnd_setCornerAndShadowRadii() {
        mPipAlphaAnimator = new PipAlphaAnimator(mMockContext, mTestLeash, mMockStartTransaction,
                mMockFinishTransaction, PipAlphaAnimator.FADE_IN);
        mPipAlphaAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipAlphaAnimator.start();
            mPipAlphaAnimator.end();
        });

        verify(mMockFinishTransaction, atLeastOnce())
                .setCornerRadius(eq(mTestLeash), eq(TEST_CORNER_RADIUS));
        verify(mMockFinishTransaction, atLeastOnce())
                .setShadowRadius(eq(mTestLeash), eq(TEST_SHADOW_RADIUS));
    }

    @Test
    public void onAnimationEnd_fadeInAnimator_leashVisibleAtEnd() {
        mPipAlphaAnimator = new PipAlphaAnimator(mMockContext, mTestLeash, mMockStartTransaction,
                mMockFinishTransaction, PipAlphaAnimator.FADE_IN);
        mPipAlphaAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipAlphaAnimator.start();
            clearInvocations(mMockAnimateTransaction);
            mPipAlphaAnimator.end();
        });

        verify(mMockAnimateTransaction).setAlpha(mTestLeash, 1.0f);
    }

    @Test
    public void onAnimationEnd_fadeOutAnimator_leashInvisibleAtEnd() {
        mPipAlphaAnimator = new PipAlphaAnimator(mMockContext, mTestLeash, mMockStartTransaction,
                mMockFinishTransaction, PipAlphaAnimator.FADE_OUT);
        mPipAlphaAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipAlphaAnimator.start();
            clearInvocations(mMockAnimateTransaction);
            mPipAlphaAnimator.end();
        });

        verify(mMockAnimateTransaction).setAlpha(mTestLeash, 0f);
    }


    // set up transaction chaining
    private void prepareTransaction(SurfaceControl.Transaction tx) {
        when(tx.setAlpha(any(SurfaceControl.class), anyFloat()))
                .thenReturn(tx);
        when(tx.setCornerRadius(any(SurfaceControl.class), anyFloat()))
                .thenReturn(tx);
        when(tx.setShadowRadius(any(SurfaceControl.class), anyFloat()))
                .thenReturn(tx);
    }
}
