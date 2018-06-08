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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.FlakyTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Builder;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceSession;

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
 * atest FrameworksServicesTests:com.android.server.wm.SurfaceAnimatorTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class SurfaceAnimatorTest extends WindowTestsBase {

    @Mock AnimationAdapter mSpec;
    @Mock AnimationAdapter mSpec2;
    @Mock Transaction mTransaction;

    private SurfaceSession mSession = new SurfaceSession();
    private MyAnimatable mAnimatable;
    private MyAnimatable mAnimatable2;
    private DeferFinishAnimatable mDeferFinishAnimatable;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mAnimatable = new MyAnimatable();
        mAnimatable2 = new MyAnimatable();
        mDeferFinishAnimatable = new DeferFinishAnimatable();
    }

    @Test
    public void testRunAnimation() throws Exception {
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        final ArgumentCaptor<OnAnimationFinishedCallback> callbackCaptor = ArgumentCaptor.forClass(
                OnAnimationFinishedCallback.class);
        assertAnimating(mAnimatable);
        verify(mTransaction).reparent(eq(mAnimatable.mSurface), eq(mAnimatable.mLeash.getHandle()));
        verify(mSpec).startAnimation(any(), any(), callbackCaptor.capture());

        callbackCaptor.getValue().onAnimationFinished(mSpec);
        assertNotAnimating(mAnimatable);
        assertTrue(mAnimatable.mFinishedCallbackCalled);
        verify(mTransaction).destroy(eq(mAnimatable.mLeash));
        // TODO: Verify reparenting once we use mPendingTransaction to reparent it back
    }

    @Test
    public void testOverrideAnimation() throws Exception {
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        final SurfaceControl firstLeash = mAnimatable.mLeash;
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec2, true /* hidden */);

        verify(mTransaction).destroy(eq(firstLeash));
        assertFalse(mAnimatable.mFinishedCallbackCalled);

        final ArgumentCaptor<OnAnimationFinishedCallback> callbackCaptor = ArgumentCaptor.forClass(
                OnAnimationFinishedCallback.class);
        assertAnimating(mAnimatable);
        verify(mSpec).startAnimation(any(), any(), callbackCaptor.capture());

        // First animation was finished, but this shouldn't cancel the second animation
        callbackCaptor.getValue().onAnimationFinished(mSpec);
        assertTrue(mAnimatable.mSurfaceAnimator.isAnimating());

        // Second animation was finished
        verify(mSpec2).startAnimation(any(), any(), callbackCaptor.capture());
        callbackCaptor.getValue().onAnimationFinished(mSpec2);
        assertNotAnimating(mAnimatable);
        assertTrue(mAnimatable.mFinishedCallbackCalled);
    }

    @Test
    public void testCancelAnimation() throws Exception {
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        assertAnimating(mAnimatable);
        mAnimatable.mSurfaceAnimator.cancelAnimation();
        assertNotAnimating(mAnimatable);
        verify(mSpec).onAnimationCancelled(any());
        assertTrue(mAnimatable.mFinishedCallbackCalled);
        verify(mTransaction).destroy(eq(mAnimatable.mLeash));
    }

    @Test
    public void testDelayingAnimationStart() throws Exception {
        mAnimatable.mSurfaceAnimator.startDelayingAnimationStart();
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        verifyZeroInteractions(mSpec);
        assertAnimating(mAnimatable);
        assertTrue(mAnimatable.mSurfaceAnimator.isAnimationStartDelayed());
        mAnimatable.mSurfaceAnimator.endDelayingAnimationStart();
        verify(mSpec).startAnimation(any(), any(), any());
    }

    @Test
    public void testDelayingAnimationStartAndCancelled() throws Exception {
        mAnimatable.mSurfaceAnimator.startDelayingAnimationStart();
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        mAnimatable.mSurfaceAnimator.cancelAnimation();
        verifyZeroInteractions(mSpec);
        assertNotAnimating(mAnimatable);
        assertTrue(mAnimatable.mFinishedCallbackCalled);
        verify(mTransaction).destroy(eq(mAnimatable.mLeash));
    }

    @Test
    public void testTransferAnimation() throws Exception {
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);

        final ArgumentCaptor<OnAnimationFinishedCallback> callbackCaptor = ArgumentCaptor.forClass(
                OnAnimationFinishedCallback.class);
        verify(mSpec).startAnimation(any(), any(), callbackCaptor.capture());
        final SurfaceControl leash = mAnimatable.mLeash;

        mAnimatable2.mSurfaceAnimator.transferAnimation(mAnimatable.mSurfaceAnimator);
        assertNotAnimating(mAnimatable);
        assertAnimating(mAnimatable2);
        assertEquals(leash, mAnimatable2.mSurfaceAnimator.mLeash);
        verify(mTransaction, never()).destroy(eq(leash));
        callbackCaptor.getValue().onAnimationFinished(mSpec);
        assertNotAnimating(mAnimatable2);
        assertTrue(mAnimatable2.mFinishedCallbackCalled);
        verify(mTransaction).destroy(eq(leash));
    }

    @Test
    @FlakyTest(detail = "Promote once confirmed non-flaky")
    public void testDeferFinish() throws Exception {

        // Start animation
        mDeferFinishAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec,
                true /* hidden */);
        final ArgumentCaptor<OnAnimationFinishedCallback> callbackCaptor = ArgumentCaptor.forClass(
                OnAnimationFinishedCallback.class);
        assertAnimating(mDeferFinishAnimatable);
        verify(mSpec).startAnimation(any(), any(), callbackCaptor.capture());

        // Finish the animation but then make sure we are deferring.
        callbackCaptor.getValue().onAnimationFinished(mSpec);
        assertAnimating(mDeferFinishAnimatable);

        // Now end defer finishing.
        mDeferFinishAnimatable.endDeferFinishCallback.run();
        assertNotAnimating(mAnimatable2);
        assertTrue(mDeferFinishAnimatable.mFinishedCallbackCalled);
        verify(mTransaction).destroy(eq(mDeferFinishAnimatable.mLeash));
    }

    private void assertAnimating(MyAnimatable animatable) {
        assertTrue(animatable.mSurfaceAnimator.isAnimating());
        assertNotNull(animatable.mSurfaceAnimator.getAnimation());
    }

    private void assertNotAnimating(MyAnimatable animatable) {
        assertFalse(animatable.mSurfaceAnimator.isAnimating());
        assertNull(animatable.mSurfaceAnimator.getAnimation());
    }

    private class MyAnimatable implements Animatable {

        final SurfaceControl mParent;
        final SurfaceControl mSurface;
        final SurfaceAnimator mSurfaceAnimator;
        SurfaceControl mLeash;
        boolean mFinishedCallbackCalled;

        MyAnimatable() {
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
            mSurfaceAnimator = new SurfaceAnimator(this, mFinishedCallback, sWm);
        }

        @Override
        public Transaction getPendingTransaction() {
            return mTransaction;
        }

        @Override
        public void commitPendingTransaction() {
        }

        @Override
        public void onAnimationLeashCreated(Transaction t, SurfaceControl leash) {
        }

        @Override
        public void onAnimationLeashDestroyed(Transaction t) {
        }

        @Override
        public Builder makeAnimationLeash() {
            return new SurfaceControl.Builder(mSession) {

                @Override
                public SurfaceControl build() {
                    mLeash = super.build();
                    return mLeash;
                }
            }.setParent(mParent);
        }

        @Override
        public SurfaceControl getAnimationLeashParent() {
            return mParent;
        }

        @Override
        public SurfaceControl getSurfaceControl() {
            return mSurface;
        }

        @Override
        public SurfaceControl getParentSurfaceControl() {
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

        private final Runnable mFinishedCallback = () -> mFinishedCallbackCalled = true;
    }

    private class DeferFinishAnimatable extends MyAnimatable {

        Runnable endDeferFinishCallback;

        @Override
        public boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
            this.endDeferFinishCallback = endDeferFinishCallback;
            return true;
        }
    }
}
