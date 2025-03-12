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
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
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
 * Unit test against {@link PipAlphaAnimator}.
 */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class PipAlphaAnimatorTest {

    @Mock private Context mMockContext;

    @Mock private Resources mMockResources;

    @Mock private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory mMockFactory;

    @Mock private SurfaceControl.Transaction mMockTransaction;

    @Mock private Runnable mMockStartCallback;

    @Mock private Runnable mMockEndCallback;

    private PipAlphaAnimator mPipAlphaAnimator;
    private SurfaceControl mTestLeash;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getInteger(anyInt())).thenReturn(0);
        when(mMockFactory.getTransaction()).thenReturn(mMockTransaction);
        when(mMockTransaction.setAlpha(any(SurfaceControl.class), anyFloat()))
                .thenReturn(mMockTransaction);

        mTestLeash = new SurfaceControl.Builder()
                .setContainerLayer()
                .setName("PipAlphaAnimatorTest")
                .setCallsite("PipAlphaAnimatorTest")
                .build();
    }

    @Test
    public void setAnimationStartCallback_fadeInAnimator_callbackStartCallback() {
        mPipAlphaAnimator = new PipAlphaAnimator(mMockContext, mTestLeash, mMockTransaction,
                PipAlphaAnimator.FADE_IN);

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
        mPipAlphaAnimator = new PipAlphaAnimator(mMockContext, mTestLeash, mMockTransaction,
                PipAlphaAnimator.FADE_IN);

        mPipAlphaAnimator.setAnimationStartCallback(mMockStartCallback);
        mPipAlphaAnimator.setAnimationEndCallback(mMockEndCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipAlphaAnimator.start();
            mPipAlphaAnimator.end();
        });

        verify(mMockStartCallback).run();
        verify(mMockStartCallback).run();
    }

    @Test
    public void onAnimationEnd_fadeInAnimator_leashVisibleAtEnd() {
        mPipAlphaAnimator = new PipAlphaAnimator(mMockContext, mTestLeash, mMockTransaction,
                PipAlphaAnimator.FADE_IN);
        mPipAlphaAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipAlphaAnimator.start();
            clearInvocations(mMockTransaction);
            mPipAlphaAnimator.end();
        });

        verify(mMockTransaction).setAlpha(mTestLeash, 1.0f);
    }

    @Test
    public void onAnimationEnd_fadeOutAnimator_leashInvisibleAtEnd() {
        mPipAlphaAnimator = new PipAlphaAnimator(mMockContext, mTestLeash, mMockTransaction,
                PipAlphaAnimator.FADE_OUT);
        mPipAlphaAnimator.setSurfaceControlTransactionFactory(mMockFactory);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPipAlphaAnimator.start();
            clearInvocations(mMockTransaction);
            mPipAlphaAnimator.end();
        });

        verify(mMockTransaction).setAlpha(mTestLeash, 0f);
    }
}
