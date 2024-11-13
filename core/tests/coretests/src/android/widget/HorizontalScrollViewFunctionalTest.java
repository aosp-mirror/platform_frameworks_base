/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.widget;

import static android.view.flags.Flags.FLAG_VIEW_VELOCITY_API;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.AttributeSet;
import android.util.PollingCheck;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@MediumTest
@Presubmit
public class HorizontalScrollViewFunctionalTest {
    private HorizontalScrollViewActivity mActivity;
    private HorizontalScrollView mHorizontalScrollView;
    private MyHorizontalScrollView mMyHorizontalScrollView;
    @Rule
    public ActivityTestRule<HorizontalScrollViewActivity> mActivityRule = new ActivityTestRule<>(
            HorizontalScrollViewActivity.class);

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() throws Exception {
        mActivity = mActivityRule.getActivity();
        mHorizontalScrollView = mActivity.findViewById(R.id.horizontal_scroll_view);
        mMyHorizontalScrollView =
                (MyHorizontalScrollView) mActivity.findViewById(R.id.my_horizontal_scroll_view);
    }

    @Test
    public void testScrollAfterFlingLeft() throws Throwable {
        WatchedEdgeEffect edgeEffect = new WatchedEdgeEffect(mActivity);
        mHorizontalScrollView.mEdgeGlowLeft = edgeEffect;
        mActivityRule.runOnUiThread(() -> mHorizontalScrollView.scrollTo(100, 0));
        mActivityRule.runOnUiThread(() -> mHorizontalScrollView.fling(-10000));
        assertTrue(edgeEffect.onAbsorbLatch.await(1, TimeUnit.SECONDS));
        mActivityRule.runOnUiThread(() -> {}); // let the absorb takes effect -- least one frame
        PollingCheck.waitFor(() -> edgeEffect.getDistance() == 0f);
        assertEquals(0, mHorizontalScrollView.getScrollX());
    }

    @Test
    public void testScrollAfterFlingRight() throws Throwable {
        WatchedEdgeEffect edgeEffect = new WatchedEdgeEffect(mActivity);
        mHorizontalScrollView.mEdgeGlowRight = edgeEffect;
        int childWidth = mHorizontalScrollView.getChildAt(0).getWidth();
        int maxScroll = childWidth - mHorizontalScrollView.getWidth();
        mActivityRule.runOnUiThread(() -> mHorizontalScrollView.scrollTo(maxScroll - 100, 0));
        mActivityRule.runOnUiThread(() -> mHorizontalScrollView.fling(10000));
        assertTrue(edgeEffect.onAbsorbLatch.await(1, TimeUnit.SECONDS));
        mActivityRule.runOnUiThread(() -> {}); // let the absorb takes effect -- at least one frame
        PollingCheck.waitFor(() -> mHorizontalScrollView.mEdgeGlowRight.getDistance() == 0f);
        assertEquals(maxScroll, mHorizontalScrollView.getScrollX());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_VIEW_VELOCITY_API)
    public void testSetVelocity() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            mMyHorizontalScrollView.setFrameContentVelocity(0);
        });
        // set setFrameContentVelocity shouldn't do anything.
        assertTrue(mMyHorizontalScrollView.isSetVelocityCalled);
        assertEquals(0f, mMyHorizontalScrollView.velocity, 0f);
        mMyHorizontalScrollView.isSetVelocityCalled = false;

        mActivityRule.runOnUiThread(() -> {
            mMyHorizontalScrollView.fling(100);
        });
        // set setFrameContentVelocity should be called when fling.
        assertTrue(mMyHorizontalScrollView.isSetVelocityCalled);
        assertTrue(mMyHorizontalScrollView.velocity > 0f);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_VIEW_VELOCITY_API)
    public void hasVelocityInSmoothScrollBy() throws Throwable {
        int maxScroll = mMyHorizontalScrollView.getChildAt(0).getWidth()
                - mMyHorizontalScrollView.getWidth();
        mActivityRule.runOnUiThread(() -> {
            mMyHorizontalScrollView.smoothScrollTo(maxScroll, 0);
        });
        PollingCheck.waitFor(() -> mMyHorizontalScrollView.getScrollX() != 0);
        assertTrue(mMyHorizontalScrollView.isSetVelocityCalled);
        assertTrue(mMyHorizontalScrollView.velocity > 0f);
    }

    static class WatchedEdgeEffect extends EdgeEffect {
        public CountDownLatch onAbsorbLatch = new CountDownLatch(1);

        WatchedEdgeEffect(Context context) {
            super(context);
        }

        @Override
        public void onAbsorb(int velocity) {
            super.onAbsorb(velocity);
            onAbsorbLatch.countDown();
        }
    }

    public static class MyHorizontalScrollView extends HorizontalScrollView {

        public boolean isSetVelocityCalled;
        public float velocity;

        public MyHorizontalScrollView(Context context) {
            super(context);
        }

        public MyHorizontalScrollView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public MyHorizontalScrollView(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
        }

        @Override
        public void setFrameContentVelocity(float pixelsPerSecond) {
            isSetVelocityCalled = true;
            velocity = pixelsPerSecond;
        }
    }
}

