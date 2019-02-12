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
 * limitations under the License.
 */

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyZeroInteractions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;

import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Builder;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceSession;

import androidx.test.filters.SmallTest;

import com.android.server.wm.SurfaceAnimator.Animatable;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link SurfaceAnimatorTest}.
 *
 * Build/Install/Run:
 *  atest WmTests:SurfaceAnimatorTest
 */
@SmallTest
@Presubmit
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
        MockitoAnnotations.initMocks(this);

        mAnimatable = new MyAnimatable(mWm, mSession, mTransaction);
        mAnimatable2 = new MyAnimatable(mWm, mSession, mTransaction);
        mDeferFinishAnimatable = new DeferFinishAnimatable(mWm, mSession, mTransaction);
    }

    @After
    public void tearDown() {
        mAnimatable = null;
        mAnimatable2 = null;
        mDeferFinishAnimatable = null;
        mSession.kill();
        mSession = null;
    }

    @Test
    public void testRunAnimation() {
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        final ArgumentCaptor<OnAnimationFinishedCallback> callbackCaptor = ArgumentCaptor.forClass(
                OnAnimationFinishedCallback.class);
        assertAnimating(mAnimatable);
        verify(mTransaction).reparent(eq(mAnimatable.mSurface), eq(mAnimatable.mLeash));
        verify(mSpec).startAnimation(any(), any(), callbackCaptor.capture());

        callbackCaptor.getValue().onAnimationFinished(mSpec);
        assertNotAnimating(mAnimatable);
        assertTrue(mAnimatable.mFinishedCallbackCalled);
        verify(mTransaction).remove(eq(mAnimatable.mLeash));
        // TODO: Verify reparenting once we use mPendingTransaction to reparent it back
    }

    @Test
    public void testOverrideAnimation() {
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        final SurfaceControl firstLeash = mAnimatable.mLeash;
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec2, true /* hidden */);

        verify(mTransaction).remove(eq(firstLeash));
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
    public void testCancelAnimation() {
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        assertAnimating(mAnimatable);
        mAnimatable.mSurfaceAnimator.cancelAnimation();
        assertNotAnimating(mAnimatable);
        verify(mSpec).onAnimationCancelled(any());
        assertTrue(mAnimatable.mFinishedCallbackCalled);
        verify(mTransaction).remove(eq(mAnimatable.mLeash));
    }

    @Test
    public void testDelayingAnimationStart() {
        mAnimatable.mSurfaceAnimator.startDelayingAnimationStart();
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        verifyZeroInteractions(mSpec);
        assertAnimating(mAnimatable);
        assertTrue(mAnimatable.mSurfaceAnimator.isAnimationStartDelayed());
        mAnimatable.mSurfaceAnimator.endDelayingAnimationStart();
        verify(mSpec).startAnimation(any(), any(), any());
    }

    @Test
    public void testDelayingAnimationStartAndCancelled() {
        mAnimatable.mSurfaceAnimator.startDelayingAnimationStart();
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);
        mAnimatable.mSurfaceAnimator.cancelAnimation();
        verifyZeroInteractions(mSpec);
        assertNotAnimating(mAnimatable);
        assertTrue(mAnimatable.mFinishedCallbackCalled);
        verify(mTransaction).remove(eq(mAnimatable.mLeash));
    }

    @Test
    public void testTransferAnimation() {
        mAnimatable.mSurfaceAnimator.startAnimation(mTransaction, mSpec, true /* hidden */);

        final ArgumentCaptor<OnAnimationFinishedCallback> callbackCaptor = ArgumentCaptor.forClass(
                OnAnimationFinishedCallback.class);
        verify(mSpec).startAnimation(any(), any(), callbackCaptor.capture());
        final SurfaceControl leash = mAnimatable.mLeash;

        mAnimatable2.mSurfaceAnimator.transferAnimation(mAnimatable.mSurfaceAnimator);
        assertNotAnimating(mAnimatable);
        assertAnimating(mAnimatable2);
        assertEquals(leash, mAnimatable2.mSurfaceAnimator.mLeash);
        verify(mTransaction, never()).remove(eq(leash));
        callbackCaptor.getValue().onAnimationFinished(mSpec);
        assertNotAnimating(mAnimatable2);
        assertTrue(mAnimatable2.mFinishedCallbackCalled);
        verify(mTransaction).remove(eq(leash));
    }

    @Test
    public void testDeferFinish() {

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
        mDeferFinishAnimatable.mEndDeferFinishCallback.run();
        assertNotAnimating(mAnimatable2);
        assertTrue(mDeferFinishAnimatable.mFinishedCallbackCalled);
        verify(mTransaction).remove(eq(mDeferFinishAnimatable.mLeash));
    }

    private void assertAnimating(MyAnimatable animatable) {
        assertTrue(animatable.mSurfaceAnimator.isAnimating());
        assertNotNull(animatable.mSurfaceAnimator.getAnimation());
    }

    private void assertNotAnimating(MyAnimatable animatable) {
        assertFalse(animatable.mSurfaceAnimator.isAnimating());
        assertNull(animatable.mSurfaceAnimator.getAnimation());
    }

    private static class MyAnimatable implements Animatable {

        private final SurfaceSession mSession;
        private final Transaction mTransaction;
        final SurfaceControl mParent;
        final SurfaceControl mSurface;
        final SurfaceAnimator mSurfaceAnimator;
        SurfaceControl mLeash;
        boolean mFinishedCallbackCalled;

        MyAnimatable(WindowManagerService wm, SurfaceSession session, Transaction transaction) {
            mSession = session;
            mTransaction = transaction;
            mParent = wm.makeSurfaceBuilder(mSession)
                    .setName("test surface parent")
                    .build();
            mSurface = wm.makeSurfaceBuilder(mSession)
                    .setName("test surface")
                    .build();
            mFinishedCallbackCalled = false;
            mLeash = null;
            mSurfaceAnimator = new SurfaceAnimator(this, mFinishedCallback, wm);
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

    private static class DeferFinishAnimatable extends MyAnimatable {

        Runnable mEndDeferFinishCallback;

        DeferFinishAnimatable(WindowManagerService wm, SurfaceSession session,
                Transaction transaction) {
            super(wm, session, transaction);
        }

        @Override
        public boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
            mEndDeferFinishCallback = endDeferFinishCallback;
            return true;
        }
    }
}
