/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.bubbles.animation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.SuppressLint;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.R;
import com.android.wm.shell.bubbles.BubblePositioner;
import com.android.wm.shell.bubbles.BubbleStackView;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ExpandedAnimationControllerTest extends PhysicsAnimationLayoutTestCase {

    private int mDisplayWidth = 500;
    private int mDisplayHeight = 1000;

    private Runnable mOnBubbleAnimatedOutAction = mock(Runnable.class);
    ExpandedAnimationController mExpandedController;

    private int mStackOffset;
    private PointF mExpansionPoint;
    private BubblePositioner mPositioner;
    private BubbleStackView.StackViewState mStackViewState;

    @SuppressLint("VisibleForTests")
    @Before
    public void setUp() throws Exception {
        super.setUp();

        BubbleStackView stackView = mock(BubbleStackView.class);
        when(stackView.getState()).thenReturn(getStackViewState());
        mPositioner = new BubblePositioner(getContext(), mock(WindowManager.class));
        mPositioner.updateInternal(Configuration.ORIENTATION_PORTRAIT,
                Insets.of(0, 0, 0, 0),
                new Rect(0, 0, mDisplayWidth, mDisplayHeight));
        mExpandedController = new ExpandedAnimationController(mPositioner,
                mOnBubbleAnimatedOutAction,
                stackView);
        spyOn(mExpandedController);

        addOneMoreThanBubbleLimitBubbles();
        mLayout.setActiveController(mExpandedController);

        Resources res = mLayout.getResources();
        mStackOffset = res.getDimensionPixelSize(R.dimen.bubble_stack_offset);
        mExpansionPoint = new PointF(100, 100);
    }

    public BubbleStackView.StackViewState getStackViewState() {
        mStackViewState.numberOfBubbles = mLayout.getChildCount();
        mStackViewState.selectedIndex = 0;
        mStackViewState.onLeft = mPositioner.isStackOnLeft(mExpansionPoint);
        return mStackViewState;
    }

    @Test
    @Ignore
    public void testExpansionAndCollapse() throws InterruptedException {
        Runnable afterExpand = mock(Runnable.class);
        mExpandedController.expandFromStack(afterExpand);
        waitForPropertyAnimations(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y);

        testBubblesInCorrectExpandedPositions();
        verify(afterExpand).run();

        Runnable afterCollapse = mock(Runnable.class);
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
        mExpandedController.expandFromStack(mock(Runnable.class));
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
            PointF expectedPosition = mPositioner.getExpandedBubbleXY(i,
                    getStackViewState());
            assertEquals(expectedPosition.x,
                    mLayout.getChildAt(i).getTranslationX(),
                    2f);
            assertEquals(expectedPosition.y,
                    mLayout.getChildAt(i).getTranslationY(), 2f);
        }
    }
}
