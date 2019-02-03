/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.bubbles.animation;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.dynamicanimation.animation.DynamicAnimation;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test case for tests that involve the {@link PhysicsAnimationLayout}. This test case constructs a
 * testable version of the layout, and provides some helpful methods to add views to the layout and
 * wait for physics animations to finish running.
 *
 * See physics-animation-testing.md.
 */
public class PhysicsAnimationLayoutTestCase extends SysuiTestCase {
    TestablePhysicsAnimationLayout mLayout;
    List<View> mViews = new ArrayList<>();

    Handler mMainThreadHandler;

    int mMaxRenderedBubbles;
    int mSystemWindowInsetSize = 50;
    int mCutoutInsetSize = 100;

    int mWidth = 1000;
    int mHeight = 1000;

    @Mock
    private WindowInsets mWindowInsets;

    @Mock
    private DisplayCutout mCutout;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mLayout = new TestablePhysicsAnimationLayout(mContext);
        mLayout.setLeft(0);
        mLayout.setRight(mWidth);
        mLayout.setTop(0);
        mLayout.setBottom(mHeight);

        mMaxRenderedBubbles =
                getContext().getResources().getInteger(R.integer.bubbles_max_rendered);
        mMainThreadHandler = new Handler(Looper.getMainLooper());

        when(mWindowInsets.getSystemWindowInsetTop()).thenReturn(mSystemWindowInsetSize);
        when(mWindowInsets.getSystemWindowInsetBottom()).thenReturn(mSystemWindowInsetSize);
        when(mWindowInsets.getSystemWindowInsetLeft()).thenReturn(mSystemWindowInsetSize);
        when(mWindowInsets.getSystemWindowInsetRight()).thenReturn(mSystemWindowInsetSize);

        when(mWindowInsets.getDisplayCutout()).thenReturn(mCutout);
        when(mCutout.getSafeInsetTop()).thenReturn(mCutoutInsetSize);
        when(mCutout.getSafeInsetBottom()).thenReturn(mCutoutInsetSize);
        when(mCutout.getSafeInsetLeft()).thenReturn(mCutoutInsetSize);
        when(mCutout.getSafeInsetRight()).thenReturn(mCutoutInsetSize);
    }

    /** Add one extra bubble over the limit, so we can make sure it's gone/chains appropriately. */
    void addOneMoreThanRenderLimitBubbles() throws InterruptedException {
        for (int i = 0; i < mMaxRenderedBubbles + 1; i++) {
            final View newView = new FrameLayout(mContext);
            mLayout.addView(newView, 0);
            mViews.add(0, newView);

            newView.setTranslationX(0);
            newView.setTranslationY(0);
        }
    }

    /**
     * Uses a {@link java.util.concurrent.CountDownLatch} to wait for the given properties'
     * animations to finish before allowing the test to proceed.
     */
    void waitForPropertyAnimations(DynamicAnimation.ViewProperty... properties)
            throws InterruptedException {
        final CountDownLatch animLatch = new CountDownLatch(properties.length);
        for (DynamicAnimation.ViewProperty property : properties) {
            mLayout.setTestEndListenerForProperty(new OneTimeEndListener() {
                @Override
                public void onAnimationEnd(DynamicAnimation animation, boolean canceled,
                        float value,
                        float velocity) {
                    super.onAnimationEnd(animation, canceled, value, velocity);
                    animLatch.countDown();
                }
            }, property);
        }
        animLatch.await(1, TimeUnit.SECONDS);
    }

    /** Uses a latch to wait for the message queue to finish. */
    void waitForLayoutMessageQueue() throws InterruptedException {
        // Wait for layout, then the view should be actually removed.
        CountDownLatch layoutLatch = new CountDownLatch(1);
        mMainThreadHandler.post(layoutLatch::countDown);
        layoutLatch.await(1, TimeUnit.SECONDS);
    }

    /**
     * Testable subclass of the PhysicsAnimationLayout that ensures methods that trigger animations
     * are run on the main thread, which is a requirement of DynamicAnimation.
     */
    protected class TestablePhysicsAnimationLayout extends PhysicsAnimationLayout {
        public TestablePhysicsAnimationLayout(Context context) {
            super(context);
        }

        @Override
        public void setController(PhysicsAnimationController controller) {
            mMainThreadHandler.post(() -> super.setController(controller));
            waitForMessageQueueAndIgnoreIfInterrupted();
        }

        @Override
        public void cancelAllAnimations() {
            mMainThreadHandler.post(super::cancelAllAnimations);
        }

        @Override
        protected void animateValueForChildAtIndex(DynamicAnimation.ViewProperty property,
                int index, float value, float startVel, Runnable after) {
            mMainThreadHandler.post(() ->
                    super.animateValueForChildAtIndex(property, index, value, startVel, after));
        }

        @Override
        public WindowInsets getRootWindowInsets() {
            return mWindowInsets;
        }

        @Override
        public void removeView(View view) {
            mMainThreadHandler.post(() ->
                    super.removeView(view));
            waitForMessageQueueAndIgnoreIfInterrupted();
        }

        @Override
        public void addView(View child, int index, ViewGroup.LayoutParams params) {
            mMainThreadHandler.post(() ->
                    super.addView(child, index, params));
            waitForMessageQueueAndIgnoreIfInterrupted();
        }

        /**
         * Wait for the queue but just catch and print the exception if interrupted, since we can't
         * just add the exception to the overridden methods' signatures.
         */
        private void waitForMessageQueueAndIgnoreIfInterrupted() {
            try {
                waitForLayoutMessageQueue();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        /**
         * Sets an end listener that will be called after the 'real' end listener that was already
         * set.
         */
        private void setTestEndListenerForProperty(DynamicAnimation.OnAnimationEndListener listener,
                DynamicAnimation.ViewProperty property) {
            final DynamicAnimation.OnAnimationEndListener realEndListener =
                    mEndListenerForProperty.get(property);

            setEndListenerForProperty((animation, canceled, value, velocity) -> {
                if (realEndListener != null) {
                    realEndListener.onAnimationEnd(animation, canceled, value, velocity);
                }

                listener.onAnimationEnd(animation, canceled, value, velocity);
            }, property);
        }
    }
}
