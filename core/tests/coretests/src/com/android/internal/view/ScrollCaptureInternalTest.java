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

package com.android.internal.view;

import static android.view.flags.Flags.FLAG_SCROLL_CAPTURE_RELAX_SCROLL_VIEW_CRITERIA;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.internal.view.ScrollCaptureInternal.TYPE_FIXED;
import static com.android.internal.view.ScrollCaptureInternal.TYPE_OPAQUE;
import static com.android.internal.view.ScrollCaptureInternal.TYPE_RECYCLING;
import static com.android.internal.view.ScrollCaptureInternal.TYPE_SCROLLING;
import static com.android.internal.view.ScrollCaptureInternal.detectScrollingType;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Rect;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.AndroidTestingRunner;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests scrolling detection.
 */
@Presubmit
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ScrollCaptureInternalTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    /**
     * Tests the effect of padding on scroll capture search dispatch.
     * <p>
     * Verifies computation of child visible bounds with padding.
     */
    @Test
    public void testDetectScrollingType_scrolling_notScrollable() {
        MockScrollable scrollable = new MockScrollable.Builder()
                .bounds(0, 0, 200, 200)
                .childCount(1)
                .canScrollUp(false)
                .canScrollDown(false)
                .scrollToEnabled(false)
                .build(getInstrumentation().getContext());

        assertEquals(TYPE_FIXED, detectScrollingType(scrollable));
    }

    @Test
    public void testDetectScrollingType_scrolling_noChildren() {
        MockScrollable scrollable = new MockScrollable.Builder()
                .bounds(0, 0, 200, 200)
                .childCount(0)
                .canScrollUp(false)
                .canScrollDown(true)
                .scrollToEnabled(true)
                .build(getInstrumentation().getContext());

        assertEquals(TYPE_OPAQUE, detectScrollingType(scrollable));
    }

    @Test
    public void testDetectScrollingType_scrolling() {
        MockScrollable scrollable = new MockScrollable.Builder()
                .bounds(0, 0, 200, 200)
                .childCount(1)
                .canScrollUp(false)
                .canScrollDown(true)
                .scrollToEnabled(true)
                .build(getInstrumentation().getContext());

        assertEquals(TYPE_SCROLLING, detectScrollingType(scrollable));
    }

    @Test
    public void testDetectScrollingType_scrolling_partiallyScrolled() {
        MockScrollable scrollable = new MockScrollable.Builder()
                .bounds(0, 0, 200, 200)
                .childCount(1)
                .canScrollUp(true)
                .canScrollDown(true)
                .scrollToEnabled(true)
                .build(getInstrumentation().getContext());
        scrollable.scrollTo(0, 100);

        assertEquals(TYPE_SCROLLING, detectScrollingType(scrollable));
    }

    @Test
    @EnableFlags(FLAG_SCROLL_CAPTURE_RELAX_SCROLL_VIEW_CRITERIA)
    public void testDetectScrollingType_scrolling_multipleChildren() {
        MockScrollable scrollable = new MockScrollable.Builder()
                .bounds(0, 0, 200, 200)
                .childCount(10)
                .canScrollUp(false)
                .canScrollDown(true)
                .scrollToEnabled(true)
                .build(getInstrumentation().getContext());

        assertEquals(TYPE_SCROLLING, detectScrollingType(scrollable));
    }

    @Test
    public void testDetectScrollingType_recycling() {
        MockScrollable scrollable = new MockScrollable.Builder()
                .bounds(0, 0, 200, 200)
                .childCount(10)
                .canScrollUp(false)
                .canScrollDown(true)
                .scrollToEnabled(false)
                .build(getInstrumentation().getContext());

        assertEquals(TYPE_RECYCLING, detectScrollingType(scrollable));
    }

    @Test
    public void testDetectScrollingType_noChildren() {
        MockScrollable scrollable = new MockScrollable.Builder()
                .bounds(0, 0, 200, 200)
                .childCount(0)
                .canScrollUp(true)
                .canScrollDown(true)
                .scrollToEnabled(false)
                .build(getInstrumentation().getContext());

        assertEquals(TYPE_OPAQUE, detectScrollingType(scrollable));
    }


    /**
     * A mock which can exhibit some attributes and behaviors used to detect different types
     * of scrolling content.
     */
    private static class MockScrollable extends ViewGroup {
        private final int mChildCount;
        private final boolean mCanScrollUp;
        private final boolean mCanScrollDown;
        private final boolean mScrollToEnabled;

        MockScrollable(Context context, Rect bounds, int childCount, boolean canScrollUp,
                boolean canScrollDown, boolean scrollToEnabled) {
            super(context);
            setFrame(bounds.left, bounds.top, bounds.right, bounds.bottom);
            mCanScrollUp = canScrollUp;
            mCanScrollDown = canScrollDown;
            mScrollToEnabled = scrollToEnabled;
            mChildCount = childCount;
        }

        private static class Builder {
            private int mChildCount;
            private boolean mCanScrollUp;
            private boolean mCanScrollDown;
            private boolean mScrollToEnabled = true;

            private final Rect mBounds = new Rect();

            public MockScrollable build(Context context) {
                return new MockScrollable(context,
                        mBounds, mChildCount, mCanScrollUp, mCanScrollDown,
                        mScrollToEnabled);
            }

            public Builder canScrollUp(boolean canScrollUp) {
                mCanScrollUp = canScrollUp;
                return this;
            }

            public Builder canScrollDown(boolean canScrollDown) {
                mCanScrollDown = canScrollDown;
                return this;
            }

            public Builder scrollToEnabled(boolean enabled) {
                mScrollToEnabled = enabled;
                return this;
            }

            public Builder childCount(int childCount) {
                mChildCount = childCount;
                return this;
            }

            public Builder bounds(int left, int top, int right, int bottom) {
                mBounds.set(left, top, right, bottom);
                return this;
            }
        }

        @Override
        public boolean canScrollVertically(int direction) {
            if (direction > 0) {
                return mCanScrollDown;
            } else if (direction < 0) {
                return mCanScrollUp;
            } else {
                return false;
            }
        }

        @Override
        public int getChildCount() {
            return mChildCount;
        }

        @Override
        public void scrollTo(int x, int y) {
            if (mScrollToEnabled) {
                super.scrollTo(x, y);
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // We don't layout this view.
        }
    }
}
