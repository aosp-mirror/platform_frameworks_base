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
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
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
    private int mExpandedViewPadding = 10;
    private int mOrientation = Configuration.ORIENTATION_PORTRAIT;
    private float mLauncherGridDiff = 30f;

    private Runnable mOnBubbleAnimatedOutAction = Mockito.mock(Runnable.class);

    @Spy
    private ExpandedAnimationController mExpandedController =
            new ExpandedAnimationController(
                    new Point(mDisplayWidth, mDisplayHeight) /* displaySize */,
                    mExpandedViewPadding, mOrientation, mOnBubbleAnimatedOutAction);

    private int mStackOffset;
    private float mBubblePaddingTop;
    private float mBubbleSize;

    private PointF mExpansionPoint;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        addOneMoreThanBubbleLimitBubbles();
        mLayout.setActiveController(mExpandedController);

        Resources res = mLayout.getResources();
        mStackOffset = res.getDimensionPixelSize(R.dimen.bubble_stack_offset);
        mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);
        mBubbleSize = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mExpansionPoint = new PointF(100, 100);
    }

    @Test
    @Ignore
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
    @Ignore
    public void testOnChildAdded() throws InterruptedException {
        expand();

        // Add another new view and wait for its animation.
        final View newView = new FrameLayout(getContext());
        mLayout.addView(newView, 0);
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        testBubblesInCorrectExpandedPositions();
    }

    @Test
    @Ignore
    public void testOnChildRemoved() throws InterruptedException {
        expand();

        // Remove some views and see if the remaining child views still pass the expansion test.
        mLayout.removeView(mViews.get(0));
        mLayout.removeView(mViews.get(3));
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);
        testBubblesInCorrectExpandedPositions();
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
        final float bubbleLeft = index * (mBubbleSize + getSpaceBetweenBubbles());
        return getRowLeft() + bubbleLeft;
    }

    private float getRowLeft() {
        if (mLayout == null) {
            return 0;
        }
        int bubbleCount = mLayout.getChildCount();
        final float totalBubbleWidth = bubbleCount * mBubbleSize;
        final float totalGapWidth = (bubbleCount - 1) * getSpaceBetweenBubbles();
        final float rowWidth = totalGapWidth + totalBubbleWidth;

        final float centerScreen = mDisplayWidth / 2f;
        final float halfRow = rowWidth / 2f;
        final float rowLeft = centerScreen - halfRow;

        return rowLeft;
    }

    /**
     * @return Space between bubbles in row above expanded view.
     */
    private float getSpaceBetweenBubbles() {
        final float rowMargins = (mExpandedViewPadding + mLauncherGridDiff) * 2;
        final float maxRowWidth = mDisplayWidth - rowMargins;

        final float totalBubbleWidth = mMaxBubbles * mBubbleSize;
        final float totalGapWidth = maxRowWidth - totalBubbleWidth;

        final int gapCount = mMaxBubbles - 1;
        final float gapWidth = totalGapWidth / gapCount;
        return gapWidth;
    }
}
