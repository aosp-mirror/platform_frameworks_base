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
public class HorizontalScrollViewFunctionalTest {
    private HorizontalScrollViewActivity mActivity;
    private HorizontalScrollView mHorizontalScrollView;
    @Rule
    public ActivityTestRule<HorizontalScrollViewActivity> mActivityRule = new ActivityTestRule<>(
            HorizontalScrollViewActivity.class);

    @Before
    public void setUp() throws Exception {
        mActivity = mActivityRule.getActivity();
        mHorizontalScrollView = mActivity.findViewById(R.id.horizontal_scroll_view);
    }

    @Test
    public void testScrollAfterFlingTop() {
        mHorizontalScrollView.scrollTo(100, 0);
        mHorizontalScrollView.fling(-10000);
        PollingCheck.waitFor(() -> mHorizontalScrollView.mEdgeGlowLeft.getDistance() > 0);
        PollingCheck.waitFor(() -> mHorizontalScrollView.mEdgeGlowLeft.getDistance() == 0f);
        assertEquals(0, mHorizontalScrollView.getScrollX());
    }

    @Test
    public void testScrollAfterFlingBottom() {
        int childWidth = mHorizontalScrollView.getChildAt(0).getWidth();
        int maxScroll = childWidth - mHorizontalScrollView.getWidth();
        mHorizontalScrollView.scrollTo(maxScroll - 100, 0);
        mHorizontalScrollView.fling(10000);
        PollingCheck.waitFor(() -> mHorizontalScrollView.mEdgeGlowRight.getDistance() > 0);
        PollingCheck.waitFor(() -> mHorizontalScrollView.mEdgeGlowRight.getDistance() == 0f);
        assertEquals(maxScroll, mHorizontalScrollView.getScrollX());
    }
}

