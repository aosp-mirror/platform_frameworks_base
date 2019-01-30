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
import android.graphics.PointF;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;

import androidx.dynamicanimation.animation.DynamicAnimation;

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
    private ExpandedAnimationController mExpandedController = new ExpandedAnimationController();

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

        testExpanded();
        Mockito.verify(afterExpand).run();

        Runnable afterCollapse = Mockito.mock(Runnable.class);
        mExpandedController.collapseBackToStack(afterCollapse);

        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        testStackedAtPosition(mExpansionPoint.x, mExpansionPoint.y, -1);
        Mockito.verify(afterExpand).run();
    }

    @Test
    public void testOnChildRemoved() throws InterruptedException {
        Runnable afterExpand = Mockito.mock(Runnable.class);
        mExpandedController.expandFromStack(mExpansionPoint, afterExpand);
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);
        testExpanded();

        // Remove some views and see if the remaining child views still pass the expansion test.
        mLayout.removeView(mViews.get(0));
        mLayout.removeView(mViews.get(3));
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);
        testExpanded();
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
    private void testExpanded() {
        // Check all the visible bubbles to see if they're in the right place.
        for (int i = 0; i < Math.min(mLayout.getChildCount(), mMaxRenderedBubbles); i++) {
            assertEquals(mBubblePadding + (i * (mBubbleSize + mBubblePadding)),
                    mLayout.getChildAt(i).getTranslationX(),
                    2f);
            assertEquals(mBubblePadding + mCutoutInsetSize,
                    mLayout.getChildAt(i).getTranslationY(), 2f);

            if (i < mMaxRenderedBubbles) {
                assertEquals(1f, mLayout.getChildAt(i).getAlpha(), .01f);
            }
        }
    }
}
