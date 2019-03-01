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

import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.widget.FrameLayout;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.test.filters.SmallTest;

import com.android.systemui.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.Spy;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ExpandedAnimationControllerTest extends PhysicsAnimationLayoutTestCase {

    @Spy
    private ExpandedAnimationController mExpandedController =
            new ExpandedAnimationController(new Point(500, 1000) /* displaySize */);

    private int mStackOffset;
    private float mBubblePadding;
    private float mBubbleSize;

    private PointF mExpansionPoint;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        addOneMoreThanRenderLimitBubbles();
        mLayout.setController(mExpandedController);
        Resources res = mLayout.getResources();
        mStackOffset = res.getDimensionPixelSize(R.dimen.bubble_stack_offset);
        mBubblePadding = res.getDimensionPixelSize(R.dimen.bubble_padding);
        mBubbleSize = res.getDimensionPixelSize(R.dimen.individual_bubble_size);

        mExpansionPoint = new PointF(100, 100);
    }

    @Test
    public void testExpansionAndCollapse() throws InterruptedException {
        Runnable afterExpand = Mockito.mock(Runnable.class);
        mExpandedController.expandFromStack(mExpansionPoint, afterExpand);
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        testBubblesInCorrectExpandedPositions();
        Mockito.verify(afterExpand).run();

        Runnable afterCollapse = Mockito.mock(Runnable.class);
        mExpandedController.collapseBackToStack(afterCollapse);
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        testStackedAtPosition(mExpansionPoint.x, mExpansionPoint.y, -1);
        Mockito.verify(afterExpand).run();
    }

    @Test
    public void testOnChildAdded() throws InterruptedException {
        expand();

        // Add another new view and wait for its animation.
        final View newView = new FrameLayout(getContext());
        mLayout.addView(newView, 0);
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        testBubblesInCorrectExpandedPositions();
    }

    @Test
    public void testOnChildRemoved() throws InterruptedException {
        expand();

        // Remove some views and see if the remaining child views still pass the expansion test.
        mLayout.removeView(mViews.get(0));
        mLayout.removeView(mViews.get(3));
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);
        testBubblesInCorrectExpandedPositions();
    }

    @Test
    public void testBubbleDraggedNotDismissedSnapsBack() throws InterruptedException {
        expand();

        final View draggedBubble = mViews.get(0);
        mExpandedController.prepareForBubbleDrag(draggedBubble);
        mExpandedController.dragBubbleOut(draggedBubble, 500f, 500f);

        assertEquals(500f, draggedBubble.getTranslationX(), 1f);
        assertEquals(500f, draggedBubble.getTranslationY(), 1f);

        // Snap it back and make sure it made it back correctly.
        mExpandedController.snapBubbleBack(draggedBubble, 0f, 0f);
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);
        testBubblesInCorrectExpandedPositions();
    }

    @Test
    public void testBubbleDismissed() throws InterruptedException {
        expand();

        final View draggedBubble = mViews.get(0);
        mExpandedController.prepareForBubbleDrag(draggedBubble);
        mExpandedController.dragBubbleOut(draggedBubble, 500f, 500f);

        assertEquals(500f, draggedBubble.getTranslationX(), 1f);
        assertEquals(500f, draggedBubble.getTranslationY(), 1f);

        // Snap it back and make sure it made it back correctly.
        mExpandedController.prepareForDismissalWithVelocity(draggedBubble, 0f, 0f);
        mLayout.removeView(draggedBubble);
        waitForLayoutMessageQueue();
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        assertEquals(-1, mLayout.indexOfChild(draggedBubble));
        testBubblesInCorrectExpandedPositions();
    }

    /** Expand the stack and wait for animations to finish. */
    private void expand() throws InterruptedException {
        mExpandedController.expandFromStack(mExpansionPoint, Mockito.mock(Runnable.class));
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);
    }

    /** Check that children are in the correct positions for being stacked. */
    private void testStackedAtPosition(float x, float y, int offsetMultiplier) {
        // Make sure the rest of the stack moved again, including the first bubble not moving, and
        // is stacked to the right now that we're on the right side of the screen.
        for (int i = 0; i < mLayout.getChildCount(); i++) {
            assertEquals(x + i * offsetMultiplier * mStackOffset,
                    mLayout.getChildAt(i).getTranslationX(), 2f);
            assertEquals(y, mLayout.getChildAt(i).getTranslationY(), 2f);

            if (i < mMaxRenderedBubbles) {
                assertEquals(1f, mLayout.getChildAt(i).getAlpha(), .01f);
            }
        }
    }

    /** Check that children are in the correct positions for being expanded. */
    private void testBubblesInCorrectExpandedPositions() {
        // Check all the visible bubbles to see if they're in the right place.
        for (int i = 0; i < Math.min(mLayout.getChildCount(), mMaxRenderedBubbles); i++) {
            assertEquals(mBubblePadding + (i * (mBubbleSize + mBubblePadding)),
                    mLayout.getChildAt(i).getTranslationX(),
                    2f);
            assertEquals(mExpandedController.getExpandedY(),
                    mLayout.getChildAt(i).getTranslationY(), 2f);

            if (i < mMaxRenderedBubbles) {
                assertEquals(1f, mLayout.getChildAt(i).getAlpha(), .01f);
            }
        }
    }
}
