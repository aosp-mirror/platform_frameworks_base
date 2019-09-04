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
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.verify;

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
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.Spy;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ExpandedAnimationControllerTest extends PhysicsAnimationLayoutTestCase {

    private int mDisplayWidth = 500;
    private int mDisplayHeight = 1000;

    @Spy
    private ExpandedAnimationController mExpandedController =
            new ExpandedAnimationController(
                    new Point(mDisplayWidth, mDisplayHeight) /* displaySize */,
                    0 /* expandedViewPadding */);
    private int mStackOffset;
    private float mBubblePadding;
    private float mBubbleSize;

    private PointF mExpansionPoint;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        addOneMoreThanBubbleLimitBubbles();
        mLayout.setActiveController(mExpandedController);

        Resources res = mLayout.getResources();
        mStackOffset = res.getDimensionPixelSize(R.dimen.bubble_stack_offset);
        mBubblePadding = res.getDimensionPixelSize(R.dimen.bubble_padding);
        mBubbleSize = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mExpansionPoint = new PointF(100, 100);
    }

    @Test
    public void testExpansionAndCollapse() throws InterruptedException {
        Runnable afterExpand = Mockito.mock(Runnable.class);
        mExpandedController.expandFromStack(afterExpand);
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        testBubblesInCorrectExpandedPositions();
        verify(afterExpand).run();

        Runnable afterCollapse = Mockito.mock(Runnable.class);
        mExpandedController.collapseBackToStack(mExpansionPoint, afterCollapse);
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        testStackedAtPosition(mExpansionPoint.x, mExpansionPoint.y, -1);
        verify(afterExpand).run();
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
        mLayout.removeView(draggedBubble);
        waitForLayoutMessageQueue();
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        assertEquals(-1, mLayout.indexOfChild(draggedBubble));
        testBubblesInCorrectExpandedPositions();
    }

    @Test
    @Ignore("Flaky")
    public void testMagnetToDismiss_dismiss() throws InterruptedException {
        expand();

        final View draggedOutView = mViews.get(0);
        final Runnable after = Mockito.mock(Runnable.class);

        mExpandedController.prepareForBubbleDrag(draggedOutView);
        mExpandedController.dragBubbleOut(draggedOutView, 25, 25);

        // Magnet to dismiss, verify the bubble is at the dismiss target and the callback was
        // called.
        mExpandedController.magnetBubbleToDismiss(
                mViews.get(0), 100 /* velX */, 100 /* velY */, 1000 /* destY */, after);
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);
        verify(after).run();
        assertEquals(1000, mViews.get(0).getTranslationY(), .1f);

        // Dismiss the now-magneted bubble, verify that the callback was called.
        final Runnable afterDismiss = Mockito.mock(Runnable.class);
        mExpandedController.dismissDraggedOutBubble(draggedOutView, afterDismiss);
        waitForPropertyAnimations(DynamicAnimation.ALPHA);
        verify(after).run();

        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        assertEquals(mBubblePadding, mViews.get(1).getTranslationX(), 1f);
    }

    @Test
    @Ignore("Flaky")
    public void testMagnetToDismiss_demagnetizeThenDrag() throws InterruptedException {
        expand();

        final View draggedOutView = mViews.get(0);
        final Runnable after = Mockito.mock(Runnable.class);

        mExpandedController.prepareForBubbleDrag(draggedOutView);
        mExpandedController.dragBubbleOut(draggedOutView, 25, 25);

        // Magnet to dismiss, verify the bubble is at the dismiss target and the callback was
        // called.
        mExpandedController.magnetBubbleToDismiss(
                draggedOutView, 100 /* velX */, 100 /* velY */, 1000 /* destY */, after);
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);
        verify(after).run();
        assertEquals(1000, mViews.get(0).getTranslationY(), .1f);

        // Demagnetize the bubble towards (25, 25).
        mExpandedController.demagnetizeBubbleTo(25 /* x */, 25 /* y */, 100, 100);

        // Start dragging towards (20, 20).
        mExpandedController.dragBubbleOut(draggedOutView, 20, 20);

        // Since we just demagnetized, the bubble shouldn't be at (20, 20), it should be animating
        // towards it.
        assertNotEquals(20, draggedOutView.getTranslationX());
        assertNotEquals(20, draggedOutView.getTranslationY());
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        // Waiting for the animations should result in the bubble ending at (20, 20) since the
        // animation end value was updated.
        assertEquals(20, draggedOutView.getTranslationX(), 1f);
        assertEquals(20, draggedOutView.getTranslationY(), 1f);

        // Drag to (30, 30).
        mExpandedController.dragBubbleOut(draggedOutView, 30, 30);

        // It should go there instantly since the animations finished.
        assertEquals(30, draggedOutView.getTranslationX(), 1f);
        assertEquals(30, draggedOutView.getTranslationY(), 1f);
    }

    /** Expand the stack and wait for animations to finish. */
    private void expand() throws InterruptedException {
        mExpandedController.expandFromStack(Mockito.mock(Runnable.class));
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
            assertEquals(1f, mLayout.getChildAt(i).getAlpha(), .01f);
        }
    }

    /** Check that children are in the correct positions for being expanded. */
    private void testBubblesInCorrectExpandedPositions() {
        // Check all the visible bubbles to see if they're in the right place.
        for (int i = 0; i < mLayout.getChildCount(); i++) {
            assertEquals(getBubbleLeft(i),
                    mLayout.getChildAt(i).getTranslationX(),
                    2f);
            assertEquals(mExpandedController.getExpandedY(),
                    mLayout.getChildAt(i).getTranslationY(), 2f);
        }
    }

    /**
     * @param index Bubble index in row.
     * @return Bubble left x from left edge of screen.
     */
    public float getBubbleLeft(int index) {
        float bubbleLeftFromRowLeft = index * (mBubbleSize + mBubblePadding);
        return getRowLeft() + bubbleLeftFromRowLeft;
    }

    private float getRowLeft() {
        if (mLayout == null) {
            return 0;
        }
        int bubbleCount = mLayout.getChildCount();

        // Width calculations.
        double bubble = bubbleCount * mBubbleSize;
        float gap = (bubbleCount - 1) * mBubblePadding;
        float row = gap + (float) bubble;

        float halfRow = row / 2f;
        float centerScreen = mDisplayWidth / 2;
        float rowLeftFromScreenLeft = centerScreen - halfRow;

        return rowLeftFromScreenLeft;
    }
}
