/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Builder;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceSession;

import com.google.android.collect.Lists;

import com.android.server.wm.SurfaceAnimator.Animatable;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

/**
 * Test class for {@link SurfaceAnimatorTest}.
 *
 * runtest frameworks-services -c com.android.server.wm.SurfaceAnimatorTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class SurfaceAnimatorTest extends WindowTestsBase {

    @Mock
    AnimationAdapter mSpec;
    @Mock
    AnimationAdapter mSpec2;
    @Mock Transaction mTransaction;

    private SurfaceAnimator mSurfaceAnimator;
    private SurfaceControl mParent;
    private SurfaceControl mSurface;
    private boolean mFinishedCallbackCalled;
    private SurfaceControl mLeash;
    private SurfaceSession mSession = new SurfaceSession();

    private final Animatable mAnimatable = new Animatable() {
        @Override
        public Transaction getPendingTransaction() {
            return mTransaction;
        }

        @Override
        public void commitPendingTransaction() {
        }

        @Override
        public void onLeashCreated(Transaction t, SurfaceControl leash) {
        }

        @Override
        public void onLeashDestroyed(Transaction t) {
        }

        @Override
        public Builder makeLeash() {
            return new SurfaceControl.Builder(mSession) {

                @Override
                public SurfaceControl build() {
                    mLeash = super.build();
                    return mLeash;
                }
            }.setParent(mParent);
        }

        @Override
        public SurfaceControl getSurface() {
            return mSurface;
        }

        @Override
        public SurfaceControl getParentSurface() {
            return mParent;
        }

        @Override
        public int getSurfaceWidth() {
            return 1;
        }

        @Override
        public int getSurfaceHeight() {
            return 1;
        }
    };

    private final Runnable mFinishedCallback = () -> {
        mFinishedCallbackCalled = true;
    };

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mParent = sWm.makeSurfaceBuilder(mSession)
                .setName("test surface parent")
                .setSize(3000, 3000)
                .build();
        mSurface = sWm.makeSurfaceBuilder(mSession)
                .setName("test surface")
                .setSize(1, 1)
                .build();
        mFinishedCallbackCalled = false;
        mLeash = null;
        mSurfaceAnimator = new SurfaceAnimator(mAnimatable, mFinishedCallback, sWm);
    }

    @Test
    public void testRunAnimation() throws Exception {
        mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        final ArgumentCaptor<OnAnimationFinishedCallback> callbackCaptor = ArgumentCaptor.forClass(
                OnAnimationFinishedCallback.class);

        assertTrue(mSurfaceAnimator.isAnimating());
        assertNotNull(mSurfaceAnimator.getAnimation());
        verify(mTransaction).reparent(eq(mSurface), eq(mLeash.getHandle()));
        verify(mSpec).startAnimation(any(), any(), callbackCaptor.capture());

        callbackCaptor.getValue().onAnimationFinished(mSpec);
        assertFalse(mSurfaceAnimator.isAnimating());
        assertNull(mSurfaceAnimator.getAnimation());
        assertTrue(mFinishedCallbackCalled);

        // TODO: Verify reparenting once we use mPendingTransaction to reparent it back
    }

    @Test
    public void testOverrideAnimation() throws Exception {
        mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        mSurfaceAnimator.startAnimation(mTransaction, mSpec2, true /* hidden */);

        assertFalse(mFinishedCallbackCalled);

        final ArgumentCaptor<OnAnimationFinishedCallback> callbackCaptor = ArgumentCaptor.forClass(
                OnAnimationFinishedCallback.class);
        assertTrue(mSurfaceAnimator.isAnimating());
        assertNotNull(mSurfaceAnimator.getAnimation());
        verify(mSpec).startAnimation(any(), any(), callbackCaptor.capture());

        // First animation was finished, but this shouldn't cancel the second animation
        callbackCaptor.getValue().onAnimationFinished(mSpec);
        assertTrue(mSurfaceAnimator.isAnimating());

        // Second animation was finished
        verify(mSpec2).startAnimation(any(), any(), callbackCaptor.capture());
        callbackCaptor.getValue().onAnimationFinished(mSpec2);
        assertFalse(mSurfaceAnimator.isAnimating());
        assertTrue(mFinishedCallbackCalled);
    }

    @Test
    public void testCancelAnimation() throws Exception {
        mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        assertTrue(mSurfaceAnimator.isAnimating());
        mSurfaceAnimator.cancelAnimation();
        assertFalse(mSurfaceAnimator.isAnimating());
        verify(mSpec).onAnimationCancelled(any());
        assertTrue(mFinishedCallbackCalled);
    }

    @Test
    public void testDelayingAnimationStart() throws Exception {
        mSurfaceAnimator.startDelayingAnimationStart();
        mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        verifyZeroInteractions(mSpec);
        assertTrue(mSurfaceAnimator.isAnimating());
        mSurfaceAnimator.endDelayingAnimationStart();
        verify(mSpec).startAnimation(any(), any(), any());
    }

    @Test
    public void testDelayingAnimationStartAndCancelled() throws Exception {
        mSurfaceAnimator.startDelayingAnimationStart();
        mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        mSurfaceAnimator.cancelAnimation();
        verifyZeroInteractions(mSpec);
        assertFalse(mSurfaceAnimator.isAnimating());
        assertTrue(mFinishedCallbackCalled);
    }
}
