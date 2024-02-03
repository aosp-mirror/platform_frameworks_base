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
    public void testScrollAfterFlingTop() {
        mScrollView.scrollTo(0, 100);
        mScrollView.fling(-10000);
        PollingCheck.waitFor(() -> mScrollView.mEdgeGlowTop.getDistance() > 0);
        PollingCheck.waitFor(() -> mScrollView.mEdgeGlowTop.getDistance() == 0f);
        assertEquals(0, mScrollView.getScrollY());
    }

    @Test
    public void testScrollAfterFlingBottom() {
        int childHeight = mScrollView.getChildAt(0).getHeight();
        int maxScroll = childHeight - mScrollView.getHeight();
        mScrollView.scrollTo(0, maxScroll - 100);
        mScrollView.fling(10000);
        PollingCheck.waitFor(() -> mScrollView.mEdgeGlowBottom.getDistance() > 0);
        PollingCheck.waitFor(() -> mScrollView.mEdgeGlowBottom.getDistance() == 0f);
        assertEquals(maxScroll, mScrollView.getScrollY());
    }
}

