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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.util.PollingCheck;

import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

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
    @Rule
    public ActivityTestRule<ScrollViewActivity> mActivityRule = new ActivityTestRule<>(
            ScrollViewActivity.class);

    @Before
    public void setUp() throws Exception {
        mActivity = mActivityRule.getActivity();
        mScrollView = mActivity.findViewById(R.id.scroll_view);
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
}

