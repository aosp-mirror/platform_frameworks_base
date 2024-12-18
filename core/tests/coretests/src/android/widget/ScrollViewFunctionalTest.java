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
import static org.junit.Assert.assertNotEquals;
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
public class ScrollViewFunctionalTest {
    private ScrollViewActivity mActivity;
    private ScrollView mScrollView;
    private MyScrollView mMyScrollView;
    @Rule
    public ActivityTestRule<ScrollViewActivity> mActivityRule = new ActivityTestRule<>(
            ScrollViewActivity.class);

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() throws Exception {
        mActivity = mActivityRule.getActivity();
        mScrollView = mActivity.findViewById(R.id.scroll_view);
        mMyScrollView = (MyScrollView) mActivity.findViewById(R.id.my_scroll_view);
    }

    @Test
    public void testScrollAfterFlingTop() throws Throwable {
        WatchedEdgeEffect edgeEffect = new WatchedEdgeEffect(mActivity);
        mScrollView.mEdgeGlowTop = edgeEffect;
        mActivityRule.runOnUiThread(() -> mScrollView.scrollTo(0, 100));
        mActivityRule.runOnUiThread(() -> mScrollView.fling(-10000));
        assertTrue(edgeEffect.onAbsorbLatch.await(1, TimeUnit.SECONDS));
        mActivityRule.runOnUiThread(() -> {}); // let the absorb takes effect -- least one frame
        PollingCheck.waitFor(() -> edgeEffect.getDistance() == 0f);
        assertEquals(0, mScrollView.getScrollY());
    }

    @Test
    public void testScrollAfterFlingBottom() throws Throwable {
        WatchedEdgeEffect edgeEffect = new WatchedEdgeEffect(mActivity);
        mScrollView.mEdgeGlowBottom = edgeEffect;
        int childHeight = mScrollView.getChildAt(0).getHeight();
        int maxScroll = childHeight - mScrollView.getHeight();
        mActivityRule.runOnUiThread(() -> mScrollView.scrollTo(0, maxScroll - 100));
        mActivityRule.runOnUiThread(() -> mScrollView.fling(10000));
        assertTrue(edgeEffect.onAbsorbLatch.await(1, TimeUnit.SECONDS));
        mActivityRule.runOnUiThread(() -> {}); // let the absorb takes effect -- least one frame
        PollingCheck.waitFor(() -> edgeEffect.getDistance() == 0f);
        assertEquals(maxScroll, mScrollView.getScrollY());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_VIEW_VELOCITY_API)
    public void testSetVelocity() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            mMyScrollView.setFrameContentVelocity(0);
        });
        // set setFrameContentVelocity shouldn't do anything.
        assertTrue(mMyScrollView.isSetVelocityCalled);
        assertEquals(0f, mMyScrollView.velocity, 0f);
        mMyScrollView.isSetVelocityCalled = false;

        mActivityRule.runOnUiThread(() -> {
            mMyScrollView.fling(100);
        });
        // set setFrameContentVelocity should be called when fling.
        assertTrue(mMyScrollView.isSetVelocityCalled);
        assertNotEquals(0f, mMyScrollView.velocity, 0.01f);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_VIEW_VELOCITY_API)
    public void hasVelocityInSmoothScrollBy() throws Throwable {
        int maxScroll = mMyScrollView.getChildAt(0).getHeight() - mMyScrollView.getHeight();
        mActivityRule.runOnUiThread(() -> {
            mMyScrollView.smoothScrollTo(0, maxScroll);
        });
        PollingCheck.waitFor(() -> mMyScrollView.getScrollY() != 0);
        assertTrue(mMyScrollView.isSetVelocityCalled);
        assertTrue(mMyScrollView.velocity > 0f);
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

    public static class MyScrollView extends ScrollView {

        public boolean isSetVelocityCalled;

        public float velocity;

        public MyScrollView(Context context) {
            super(context);
        }

        public MyScrollView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public MyScrollView(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
        }

        @Override
        public void setFrameContentVelocity(float pixelsPerSecond) {
            isSetVelocityCalled = true;
            velocity = pixelsPerSecond;
        }
    }
}

