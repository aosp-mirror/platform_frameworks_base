/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.widget;

import com.android.frameworks.coretests.R;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.LayoutInflater;
import android.view.View;

import com.android.internal.widget.SizeAdaptiveLayout;


public class SizeAdaptiveLayoutTest extends AndroidTestCase {

    private LayoutInflater mInflater;
    private int mOneU;
    private int mFourU;
    private SizeAdaptiveLayout mSizeAdaptiveLayout;
    private View mSmallView;
    private View mMediumView;
    private View mLargeView;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // inflate the layout
        final Context context = getContext();
        mInflater = LayoutInflater.from(context);
        mOneU = 64;
        mFourU = 4 * mOneU;
    }

    private void inflate(int resource){
        mSizeAdaptiveLayout = (SizeAdaptiveLayout) mInflater.inflate(resource, null);
        mSizeAdaptiveLayout.onAttachedToWindow();

        mSmallView = mSizeAdaptiveLayout.findViewById(R.id.one_u);
        mMediumView = mSizeAdaptiveLayout.findViewById(R.id.two_u);
        mLargeView = mSizeAdaptiveLayout.findViewById(R.id.four_u);
    }

    /**
     * The name 'test preconditions' is a convention to signal that if this
     * test doesn't pass, the test case was not set up properly and it might
     * explain any and all failures in other tests.  This is not guaranteed
     * to run before other tests, as junit uses reflection to find the tests.
     */
    @SmallTest
    public void testPreconditions() {
        assertNotNull(mInflater);

        inflate(R.layout.size_adaptive);
        assertNotNull(mSizeAdaptiveLayout);
        assertNotNull(mSmallView);
        assertNotNull(mLargeView);
    }

    @SmallTest
    public void testOpenLarge() {
        inflate(R.layout.size_adaptive);
        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mLargeView.getLayoutParams();
        int height = (int) lp.minHeight + 10;

        measureAndLayout(height);

        assertEquals("4U should be visible",
                View.VISIBLE,
                mLargeView.getVisibility());
        assertEquals("1U should be gone",
                View.GONE,
                mSmallView.getVisibility());
    }

    @SmallTest
    public void testOpenSmall() {
        inflate(R.layout.size_adaptive);
        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mSmallView.getLayoutParams();
        int height = (int) lp.minHeight;

        measureAndLayout(height);

        assertEquals("1U should be visible",
                View.VISIBLE,
                mSmallView.getVisibility());
        assertEquals("4U should be gone",
                View.GONE,
                mLargeView.getVisibility());
    }

    @SmallTest
    public void testOpenTooSmall() {
        inflate(R.layout.size_adaptive);
        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mSmallView.getLayoutParams();
        int height = (int) lp.minHeight - 10;

        measureAndLayout(height);

        assertEquals("1U should be visible",
                View.VISIBLE,
                mSmallView.getVisibility());
        assertEquals("4U should be gone",
                View.GONE,
                mLargeView.getVisibility());
    }

    @SmallTest
    public void testOpenTooBig() {
        inflate(R.layout.size_adaptive);
        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mLargeView.getLayoutParams();
        lp.maxHeight = 500;
        mLargeView.setLayoutParams(lp);
        int height = (int) (lp.minHeight + 10);

        measureAndLayout(height);

        assertEquals("4U should be visible",
                View.VISIBLE,
                mLargeView.getVisibility());
        assertEquals("1U should be gone",
                View.GONE,
                mSmallView.getVisibility());
    }

    @SmallTest
    public void testOpenWrapContent() {
        inflate(R.layout.size_adaptive_text);
        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mLargeView.getLayoutParams();
        int height = (int) lp.minHeight + 10;

        // manually measure it, and lay it out
        int measureSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST);
        mSizeAdaptiveLayout.measure(500, measureSpec);
        assertTrue("should not be forced to 4U",
                mSizeAdaptiveLayout.getMeasuredHeight() < mFourU);
    }

    @SmallTest
    public void testOpenOneUOnlySmall() {
        inflate(R.layout.size_adaptive_singleton);
        assertNull("largeView should be NULL in the singleton layout", mLargeView);

        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mSmallView.getLayoutParams();
        int height = (int) lp.minHeight - 10;

        measureAndLayout(height);

        assertEquals("1U should be visible",
                View.VISIBLE,
                mSmallView.getVisibility());
    }

    @SmallTest
    public void testOpenOneUOnlyLarge() {
        inflate(R.layout.size_adaptive_singleton);
        assertNull("largeView should be NULL in the singleton layout", mLargeView);

        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mSmallView.getLayoutParams();
        int height = (int) lp.maxHeight + 10;

        measureAndLayout(height);

        assertEquals("1U should be visible",
                View.VISIBLE,
                mSmallView.getVisibility());
    }

    @SmallTest
    public void testOpenOneUOnlyJustRight() {
        inflate(R.layout.size_adaptive_singleton);
        assertNull("largeView should be NULL in the singleton layout", mLargeView);

        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mSmallView.getLayoutParams();
        int height = (int) lp.minHeight;

        measureAndLayout(height);

        assertEquals("1U should be visible",
                View.VISIBLE,
                mSmallView.getVisibility());
    }

    @SmallTest
    public void testOpenFourUOnlySmall() {
        inflate(R.layout.size_adaptive_large_only);
        assertNull("smallView should be NULL in the singleton layout", mSmallView);

        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mLargeView.getLayoutParams();
        int height = (int) lp.minHeight - 10;

        measureAndLayout(height);

        assertEquals("4U should be visible",
                View.VISIBLE,
                mLargeView.getVisibility());
    }

    @SmallTest
    public void testOpenFourUOnlyLarge() {
        inflate(R.layout.size_adaptive_large_only);
        assertNull("smallView should be NULL in the singleton layout", mSmallView);

        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mLargeView.getLayoutParams();
        int height = (int) lp.maxHeight + 10;

        measureAndLayout(height);

        assertEquals("4U should be visible",
                View.VISIBLE,
                mLargeView.getVisibility());
    }

    @SmallTest
    public void testOpenFourUOnlyJustRight() {
        inflate(R.layout.size_adaptive_large_only);
        assertNull("smallView should be NULL in the singleton layout", mSmallView);

        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mLargeView.getLayoutParams();
        int height = (int) lp.minHeight;

        measureAndLayout(height);

        assertEquals("4U should be visible",
                View.VISIBLE,
                mLargeView.getVisibility());
    }

    @SmallTest
    public void testOpenIntoAGap() {
        inflate(R.layout.size_adaptive_gappy);

        SizeAdaptiveLayout.LayoutParams smallParams =
          (SizeAdaptiveLayout.LayoutParams) mSmallView.getLayoutParams();
        SizeAdaptiveLayout.LayoutParams largeParams =
          (SizeAdaptiveLayout.LayoutParams) mLargeView.getLayoutParams();
        assertTrue("gappy layout should have a gap",
                smallParams.maxHeight + 10 < largeParams.minHeight);
        int height = (int) smallParams.maxHeight + 10;

        measureAndLayout(height);

        assertTrue("one and only one view should be visible",
                mLargeView.getVisibility() != mSmallView.getVisibility());
        // behavior is undefined in this case.
    }

    @SmallTest
    public void testOpenIntoAnOverlap() {
        inflate(R.layout.size_adaptive_overlapping);

        SizeAdaptiveLayout.LayoutParams smallParams =
          (SizeAdaptiveLayout.LayoutParams) mSmallView.getLayoutParams();
        SizeAdaptiveLayout.LayoutParams largeParams =
          (SizeAdaptiveLayout.LayoutParams) mLargeView.getLayoutParams();
        assertEquals("overlapping layout should overlap",
                smallParams.minHeight,
                largeParams.minHeight);
        int height = (int) smallParams.maxHeight;

        measureAndLayout(height);

        assertTrue("one and only one view should be visible",
                mLargeView.getVisibility() != mSmallView.getVisibility());
        assertEquals("1U should get priority in an overlap because it is first",
                View.VISIBLE,
                mSmallView.getVisibility());
    }

    @SmallTest
    public void testOpenThreeWayViewSmall() {
        inflate(R.layout.size_adaptive_three_way);
        assertNotNull("mMediumView should not be NULL in the three view layout", mMediumView);

        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mSmallView.getLayoutParams();
        int height = (int) lp.minHeight;

        measureAndLayout(height);

        assertEquals("1U should be visible",
                View.VISIBLE,
                mSmallView.getVisibility());
        assertEquals("2U should be gone",
                View.GONE,
                mMediumView.getVisibility());
        assertEquals("4U should be gone",
                View.GONE,
                mLargeView.getVisibility());
    }

    @SmallTest
    public void testOpenThreeWayViewMedium() {
        inflate(R.layout.size_adaptive_three_way);
        assertNotNull("mMediumView should not be NULL in the three view layout", mMediumView);

        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mMediumView.getLayoutParams();
        int height = (int) lp.minHeight;

        measureAndLayout(height);

        assertEquals("1U should be gone",
                View.GONE,
                mSmallView.getVisibility());
        assertEquals("2U should be visible",
                View.VISIBLE,
                mMediumView.getVisibility());
        assertEquals("4U should be gone",
                View.GONE,
                mLargeView.getVisibility());
    }

    @SmallTest
    public void testOpenThreeWayViewLarge() {
        inflate(R.layout.size_adaptive_three_way);
        assertNotNull("mMediumView should not be NULL in the three view layout", mMediumView);

        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mLargeView.getLayoutParams();
        int height = (int) lp.minHeight;

        measureAndLayout(height);

        assertEquals("1U should be gone",
                View.GONE,
                mSmallView.getVisibility());
        assertEquals("2U should be gone",
                View.GONE,
                mMediumView.getVisibility());
        assertEquals("4U should be visible",
                View.VISIBLE,
                mLargeView.getVisibility());
    }

    @SmallTest
    public void testResizeWithoutAnimation() {
        inflate(R.layout.size_adaptive);

        SizeAdaptiveLayout.LayoutParams largeParams =
          (SizeAdaptiveLayout.LayoutParams) mLargeView.getLayoutParams();
        int startHeight = (int) largeParams.minHeight + 10;
        int endHeight = (int) largeParams.minHeight + 10;

        measureAndLayout(startHeight);

        assertEquals("4U should be visible",
                View.VISIBLE,
                mLargeView.getVisibility());
        assertFalse("There should be no animation on initial rendering.",
                    mSizeAdaptiveLayout.getTransitionAnimation().isRunning());

        measureAndLayout(endHeight);

        assertEquals("4U should still be visible",
                View.VISIBLE,
                mLargeView.getVisibility());
        assertFalse("There should be no animation on scale within a view.",
                    mSizeAdaptiveLayout.getTransitionAnimation().isRunning());
    }

    @SmallTest
    public void testResizeWithAnimation() {
        inflate(R.layout.size_adaptive);

        SizeAdaptiveLayout.LayoutParams smallParams =
          (SizeAdaptiveLayout.LayoutParams) mSmallView.getLayoutParams();
        SizeAdaptiveLayout.LayoutParams largeParams =
          (SizeAdaptiveLayout.LayoutParams) mLargeView.getLayoutParams();
        int startHeight = (int) largeParams.minHeight + 10;
        int endHeight = (int) smallParams.maxHeight;

        measureAndLayout(startHeight);

        assertEquals("4U should be visible",
                View.VISIBLE,
                mLargeView.getVisibility());
        assertFalse("There should be no animation on initial rendering.",
                    mSizeAdaptiveLayout.getTransitionAnimation().isRunning());

        measureAndLayout(endHeight);

        assertEquals("1U should now be visible",
                View.VISIBLE,
                mSmallView.getVisibility());
        assertTrue("There should be an animation on scale between views.",
                   mSizeAdaptiveLayout.getTransitionAnimation().isRunning());
    }

    @SmallTest
    public void testModestyPanelChangesColorWhite() {
        inflate(R.layout.size_adaptive_color);
        View panel = mSizeAdaptiveLayout.getModestyPanel();
        assertTrue("ModestyPanel should have a ColorDrawable background",
                   panel.getBackground() instanceof ColorDrawable);
        ColorDrawable panelColor = (ColorDrawable) panel.getBackground();
        ColorDrawable salColor = (ColorDrawable) mSizeAdaptiveLayout.getBackground();
        assertEquals("ModestyPanel color should match the SizeAdaptiveLayout",
                     panelColor.getColor(), salColor.getColor());
    }

    @SmallTest
    public void testModestyPanelTracksStateListColor() {
        inflate(R.layout.size_adaptive_color_statelist);
        View panel = mSizeAdaptiveLayout.getModestyPanel();
        assertEquals("ModestyPanel should have a ColorDrawable background" ,
                     panel.getBackground().getClass(), ColorDrawable.class);
        ColorDrawable panelColor = (ColorDrawable) panel.getBackground();
        assertEquals("ModestyPanel color should match the SizeAdaptiveLayout",
                     panelColor.getColor(), Color.RED);
    }
    @SmallTest
    public void testOpenSmallEvenWhenLargeIsActuallySmall() {
        inflate(R.layout.size_adaptive_lies);
        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mSmallView.getLayoutParams();
        int height = (int) lp.minHeight;

        measureAndLayout(height);

        assertEquals("1U should be visible",
                View.VISIBLE,
                mSmallView.getVisibility());
        assertTrue("1U should also have been measured",
                   mSmallView.getMeasuredHeight() > 0);
    }

    @SmallTest
    public void testOpenLargeEvenWhenLargeIsActuallySmall() {
        inflate(R.layout.size_adaptive_lies);
        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) mLargeView.getLayoutParams();
        int height = (int) lp.minHeight;

        measureAndLayout(height);

        assertEquals("4U should be visible",
                View.VISIBLE,
                mLargeView.getVisibility());
        assertTrue("4U should also have been measured",
                   mLargeView.getMeasuredHeight() > 0);
    }

    private void measureAndLayout(int height) {
        // manually measure it, and lay it out
        int measureSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST);
        mSizeAdaptiveLayout.measure(500, measureSpec);
        mSizeAdaptiveLayout.layout(0, 0, 500, mSizeAdaptiveLayout.getMeasuredHeight());
    }
}
