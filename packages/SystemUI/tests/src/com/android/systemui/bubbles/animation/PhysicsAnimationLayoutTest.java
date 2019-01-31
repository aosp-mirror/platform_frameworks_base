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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;

import android.os.SystemClock;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.widget.FrameLayout;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.google.android.collect.Sets;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.Spy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidTestingRunner.class)
/** Tests the PhysicsAnimationLayout itself, with a basic test animation controller. */
public class PhysicsAnimationLayoutTest extends PhysicsAnimationLayoutTestCase {
    static final float TEST_TRANSLATION_X_OFFSET = 15f;

    @Spy
    private TestableAnimationController mTestableController = new TestableAnimationController();

    @Before
    public void setUp() throws Exception {
        super.setUp();

        // By default, use translation animations, chain the X animations with the default
        // offset, and don't actually remove views immediately (since most implementations will wait
        // to animate child views out before actually removing them).
        mTestableController.setAnimatedProperties(Sets.newHashSet(
                DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y));
        mTestableController.setChainedProperties(Sets.newHashSet(DynamicAnimation.TRANSLATION_X));
        mTestableController.setOffsetForProperty(
                DynamicAnimation.TRANSLATION_X, TEST_TRANSLATION_X_OFFSET);
        mTestableController.setRemoveImmediately(false);
    }

    @Test
    public void testRenderVisibility() throws InterruptedException {
        mLayout.setController(mTestableController);
        addOneMoreThanRenderLimitBubbles();

        // The last child should be GONE, the rest VISIBLE.
        for (int i = 0; i < mMaxRenderedBubbles + 1; i++) {
            assertEquals(i == mMaxRenderedBubbles ? View.GONE : View.VISIBLE,
                    mLayout.getChildAt(i).getVisibility());
        }
    }

    @Test
    public void testHierarchyChanges() throws InterruptedException {
        mLayout.setController(mTestableController);
        addOneMoreThanRenderLimitBubbles();

        // Make sure the controller was notified of all the views we added.
        for (View mView : mViews) {
            Mockito.verify(mTestableController).onChildAdded(mView, 0);
        }

        // Remove some views and ensure the controller was notified, with the proper indices.
        mTestableController.setRemoveImmediately(true);
        mLayout.removeView(mViews.get(1));
        mLayout.removeView(mViews.get(2));
        Mockito.verify(mTestableController).onChildToBeRemoved(
                eq(mViews.get(1)), eq(1), any());
        Mockito.verify(mTestableController).onChildToBeRemoved(
                eq(mViews.get(2)), eq(1), any());

        // Make sure we still get view added notifications after doing some removals.
        final View newBubble = new FrameLayout(mContext);
        mLayout.addView(newBubble, 0);
        Mockito.verify(mTestableController).onChildAdded(newBubble, 0);
    }

    @Test
    public void testUpdateValueNotChained() throws InterruptedException {
        mLayout.setController(mTestableController);
        addOneMoreThanRenderLimitBubbles();

        // Don't chain any values.
        mTestableController.setChainedProperties(Sets.newHashSet());

        // Child views should not be translated.
        assertEquals(0, mLayout.getChildAt(0).getTranslationX(), .1f);
        assertEquals(0, mLayout.getChildAt(1).getTranslationX(), .1f);

        // Animate the first child's translation X.
        final CountDownLatch animLatch = new CountDownLatch(1);
        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_X,
                0,
                100,
                animLatch::countDown);
        animLatch.await(1, TimeUnit.SECONDS);

        // Ensure that the first view has been translated, but not the second one.
        assertEquals(100, mLayout.getChildAt(0).getTranslationX(), .1f);
        assertEquals(0, mLayout.getChildAt(1).getTranslationX(), .1f);
    }

    @Test
    public void testUpdateValueXChained() throws InterruptedException {
        mLayout.setController(mTestableController);
        addOneMoreThanRenderLimitBubbles();
        testChainedTranslationAnimations();
    }

    @Test
    public void testSetEndListeners() throws InterruptedException {
        mLayout.setController(mTestableController);
        addOneMoreThanRenderLimitBubbles();
        mTestableController.setChainedProperties(Sets.newHashSet());

        final CountDownLatch xLatch = new CountDownLatch(1);
        OneTimeEndListener xEndListener = Mockito.spy(new OneTimeEndListener() {
            @Override
            public void onAnimationEnd(DynamicAnimation animation, boolean canceled, float value,
                    float velocity) {
                super.onAnimationEnd(animation, canceled, value, velocity);
                xLatch.countDown();
            }
        });

        final CountDownLatch yLatch = new CountDownLatch(1);
        final OneTimeEndListener yEndListener = Mockito.spy(new OneTimeEndListener() {
            @Override
            public void onAnimationEnd(DynamicAnimation animation, boolean canceled, float value,
                    float velocity) {
                super.onAnimationEnd(animation, canceled, value, velocity);
                yLatch.countDown();
            }
        });

        // Set end listeners for both x and y.
        mLayout.setEndListenerForProperty(xEndListener, DynamicAnimation.TRANSLATION_X);
        mLayout.setEndListenerForProperty(yEndListener, DynamicAnimation.TRANSLATION_Y);

        // Animate x, and wait for it to finish.
        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_X,
                0,
                100);
        xLatch.await();
        yLatch.await(1, TimeUnit.SECONDS);

        // Make sure the x end listener was called only one time, and the y listener was never
        // called since we didn't animate y. Wait 1 second after the original animation end trigger
        // to make sure it doesn't get called again.
        Mockito.verify(xEndListener, Mockito.after(1000).times(1))
                .onAnimationEnd(
                        any(),
                        eq(false),
                        eq(100f),
                        anyFloat());
        Mockito.verify(yEndListener, Mockito.after(1000).never())
                .onAnimationEnd(any(), anyBoolean(), anyFloat(), anyFloat());
    }

    @Test
    public void testRemoveEndListeners() throws InterruptedException {
        mLayout.setController(mTestableController);
        addOneMoreThanRenderLimitBubbles();
        mTestableController.setChainedProperties(Sets.newHashSet());

        final CountDownLatch xLatch = new CountDownLatch(1);
        OneTimeEndListener xEndListener = Mockito.spy(new OneTimeEndListener() {
            @Override
            public void onAnimationEnd(DynamicAnimation animation, boolean canceled, float value,
                    float velocity) {
                super.onAnimationEnd(animation, canceled, value, velocity);
                xLatch.countDown();
            }
        });

        // Set the end listener.
        mLayout.setEndListenerForProperty(xEndListener, DynamicAnimation.TRANSLATION_X);

        // Animate x, and wait for it to finish.
        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_X,
                0,
                100);
        xLatch.await();

        InOrder endListenerCalls = inOrder(xEndListener);
        endListenerCalls.verify(xEndListener, Mockito.times(1))
                .onAnimationEnd(
                        any(),
                        eq(false),
                        eq(100f),
                        anyFloat());

        // Animate X again, remove the end listener.
        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_X,
                0,
                1000);
        mLayout.removeEndListenerForProperty(DynamicAnimation.TRANSLATION_X);
        xLatch.await(1, TimeUnit.SECONDS);

        // Make sure the end listener was not called.
        endListenerCalls.verifyNoMoreInteractions();
    }

    @Test
    public void testSetController() throws InterruptedException {
        // Add the bubbles, then set the controller, to make sure that a controller added to an
        // already-initialized view works correctly.
        addOneMoreThanRenderLimitBubbles();
        mLayout.setController(mTestableController);
        testChainedTranslationAnimations();

        TestableAnimationController secondController =
                Mockito.spy(new TestableAnimationController());
        secondController.setAnimatedProperties(Sets.newHashSet(
                DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y));
        secondController.setChainedProperties(Sets.newHashSet(
                DynamicAnimation.SCALE_X));
        secondController.setOffsetForProperty(
                DynamicAnimation.SCALE_X, 10f);
        secondController.setRemoveImmediately(true);

        mLayout.setController(secondController);
        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.SCALE_X,
                0,
                1.5f);

        waitForPropertyAnimations(DynamicAnimation.SCALE_X);

        // Make sure we never asked the original controller about any SCALE animations, that would
        // mean the controller wasn't switched over properly.
        Mockito.verify(mTestableController, Mockito.never())
                .getNextAnimationInChain(eq(DynamicAnimation.SCALE_X), anyInt());
        Mockito.verify(mTestableController, Mockito.never())
                .getOffsetForChainedPropertyAnimation(eq(DynamicAnimation.SCALE_X));

        // Make sure we asked the new controller about its animated properties, and configuration
        // options.
        Mockito.verify(secondController, Mockito.atLeastOnce())
                .getAnimatedProperties();
        Mockito.verify(secondController, Mockito.atLeastOnce())
                .getNextAnimationInChain(eq(DynamicAnimation.SCALE_X), anyInt());
        Mockito.verify(secondController, Mockito.atLeastOnce())
                .getOffsetForChainedPropertyAnimation(eq(DynamicAnimation.SCALE_X));

        mLayout.setController(mTestableController);
        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_X,
                0,
                100f);

        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X);

        // Make sure we never asked the second controller about the TRANSLATION_X animation.
        Mockito.verify(secondController, Mockito.never())
                .getNextAnimationInChain(eq(DynamicAnimation.TRANSLATION_X), anyInt());
        Mockito.verify(secondController, Mockito.never())
                .getOffsetForChainedPropertyAnimation(eq(DynamicAnimation.TRANSLATION_X));

    }

    @Test
    public void testArePropertiesAnimating() throws InterruptedException {
        mLayout.setController(mTestableController);
        addOneMoreThanRenderLimitBubbles();

        assertFalse(mLayout.arePropertiesAnimating(
                DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y));

        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_X,
                0,
                100);

        // Wait for the animations to get underway.
        SystemClock.sleep(50);

        assertTrue(mLayout.arePropertiesAnimating(DynamicAnimation.TRANSLATION_X));
        assertFalse(mLayout.arePropertiesAnimating(DynamicAnimation.TRANSLATION_Y));

        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X);

        assertFalse(mLayout.arePropertiesAnimating(
                DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y));
    }

    @Test
    public void testCancelAllAnimations() throws InterruptedException {
        mLayout.setController(mTestableController);
        addOneMoreThanRenderLimitBubbles();

        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_X,
                0,
                1000);
        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_Y,
                0,
                1000);

        mLayout.cancelAllAnimations();

        // Animations should be somewhere before their end point.
        assertTrue(mViews.get(0).getTranslationX() < 1000);
        assertTrue(mViews.get(0).getTranslationY() < 1000);
    }


    /** Standard test of chained translation animations. */
    private void testChainedTranslationAnimations() throws InterruptedException {
        assertEquals(0, mLayout.getChildAt(0).getTranslationX(), .1f);
        assertEquals(0, mLayout.getChildAt(1).getTranslationX(), .1f);

        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_X,
                0,
                100);

        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X);

        // Since we enabled chaining, animating the first view to 100 should animate the second to
        // 115 (since we set the offset to 15) and the third to 130, etc. Despite the sixth bubble
        // not being visible, or animated, make sure that it has the appropriate chained
        // translation.
        for (int i = 0; i < mMaxRenderedBubbles + 1; i++) {
            assertEquals(
                    100 + i * TEST_TRANSLATION_X_OFFSET,
                    mLayout.getChildAt(i).getTranslationX(), .1f);
        }

        // Ensure that the Y translations were unaffected.
        assertEquals(0, mLayout.getChildAt(0).getTranslationY(), .1f);
        assertEquals(0, mLayout.getChildAt(1).getTranslationY(), .1f);

        // Animate the first child's Y translation.
        mLayout.animateValueForChildAtIndex(
                DynamicAnimation.TRANSLATION_Y,
                0,
                100);

        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_Y);

        // Ensure that only the first view's Y translation chained, since we only chained X
        // translations.
        assertEquals(100, mLayout.getChildAt(0).getTranslationY(), .1f);
        assertEquals(0, mLayout.getChildAt(1).getTranslationY(), .1f);
    }

    /**
     * Animation controller with configuration methods whose return values can be set by individual
     * tests.
     */
    private class TestableAnimationController
            extends PhysicsAnimationLayout.PhysicsAnimationController {
        private Set<DynamicAnimation.ViewProperty> mAnimatedProperties = new HashSet<>();
        private Set<DynamicAnimation.ViewProperty> mChainedProperties = new HashSet<>();
        private HashMap<DynamicAnimation.ViewProperty, Float> mOffsetForProperty = new HashMap<>();
        private boolean mRemoveImmediately = false;

        void setAnimatedProperties(
                Set<DynamicAnimation.ViewProperty> animatedProperties) {
            mAnimatedProperties = animatedProperties;
        }

        void setChainedProperties(
                Set<DynamicAnimation.ViewProperty> chainedProperties) {
            mChainedProperties = chainedProperties;
        }

        void setOffsetForProperty(
                DynamicAnimation.ViewProperty property, float offset) {
            mOffsetForProperty.put(property, offset);
        }

        public void setRemoveImmediately(boolean removeImmediately) {
            mRemoveImmediately = removeImmediately;
        }

        @Override
        Set<DynamicAnimation.ViewProperty> getAnimatedProperties() {
            return mAnimatedProperties;
        }

        @Override
        int getNextAnimationInChain(DynamicAnimation.ViewProperty property, int index) {
            return mChainedProperties.contains(property) ? index + 1 : NONE;
        }

        @Override
        float getOffsetForChainedPropertyAnimation(DynamicAnimation.ViewProperty property) {
            return mOffsetForProperty.getOrDefault(property, 0f);
        }

        @Override
        SpringForce getSpringForce(DynamicAnimation.ViewProperty property, View view) {
            return new SpringForce();
        }

        @Override
        void onChildAdded(View child, int index) {}

        @Override
        void onChildToBeRemoved(View child, int index, Runnable actuallyRemove) {
            if (mRemoveImmediately) {
                actuallyRemove.run();
            }
        }
    }
}
