/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot.scroll;

import static com.google.common.util.concurrent.Futures.getUnchecked;
import static com.google.common.util.concurrent.Futures.immediateFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static java.lang.Math.abs;

import android.content.Context;
import android.view.ScrollCaptureResponse;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.screenshot.scroll.ScrollCaptureClient.Session;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for ScrollCaptureController which manages sequential image acquisition for long
 * screenshots.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ScrollCaptureControllerTest extends SysuiTestCase {

    private static final ScrollCaptureResponse EMPTY_RESPONSE =
            new ScrollCaptureResponse.Builder().build();

    @Test
    public void testInfinite() {
        ScrollCaptureController controller = new TestScenario()
                .withPageHeight(100)
                .withMaxPages(2.5f)
                .withTileHeight(10)
                .withAvailableRange(Integer.MIN_VALUE, Integer.MAX_VALUE)
                .createController(mContext);

        ScrollCaptureController.LongScreenshot screenshot =
                getUnchecked(controller.run(EMPTY_RESPONSE));

        assertEquals("top", -90, screenshot.getTop());
        assertEquals("bottom", 160, screenshot.getBottom());

        // Test that top portion is >= getTargetTopSizeRatio()
        // (Due to tileHeight, top will almost always be larger than the target)
        float topPortion = abs(screenshot.getTop()) / abs((float) screenshot.getBottom());
        if (topPortion < controller.getTargetTopSizeRatio()) {
            fail("expected top portion > "
                    + (controller.getTargetTopSizeRatio() * 100) + "%"
                    + " but was " + (topPortion * 100));
        }
    }

    @Test
    public void testInfiniteWithPartialResultsTop() {
        ScrollCaptureController controller = new TestScenario()
                .withPageHeight(100)
                .withPageVisibleRange(5, 100) // <-- simulate 5px of invisible top
                .withMaxPages(2.5f)
                .withTileHeight(50)
                .withAvailableRange(Integer.MIN_VALUE, Integer.MAX_VALUE)
                .createController(mContext);

        ScrollCaptureController.LongScreenshot screenshot =
                getUnchecked(controller.run(EMPTY_RESPONSE));

        // Each tile is cropped to the visible page size, which is inset 5px from the TOP
        // requested    result
        //   0,   50       5,   50
        // -45,    5     -40,    5
        // -90,  -40     -85,  -40   <-- clear previous /  top
        // -40,   10     -40,   10   (not cropped, target is positioned fully within visible range)
        //  10,   60      10,   60
        //  60,  110      60,  110
        // 110,  160     110,  160
        // 160,  210     160,  210   <-- bottom

        assertEquals("top", -85, screenshot.getTop());
        assertEquals("bottom", 210, screenshot.getBottom());
    }

    @Test
    public void testInfiniteWithPartialResultsBottom() {
        ScrollCaptureController controller = new TestScenario()
                .withPageHeight(100)
                .withPageVisibleRange(0, 95) // <-- simulate 5px of invisible bottom
                .withMaxPages(2.5f)
                .withTileHeight(50)
                .withAvailableRange(Integer.MIN_VALUE, Integer.MAX_VALUE)
                .createController(mContext);

        ScrollCaptureController.LongScreenshot screenshot =
                getUnchecked(controller.run(EMPTY_RESPONSE));

        // Each tile is cropped to the visible page size, which is inset 5px from the BOTTOM
        // requested      result
        //    0,   50        0,   50   // not cropped, positioned within visible range
        //  -50,    0      -50,    0   <-- clear previous/reverse
        //    0,   50        0,   45   // target now positioned at page bottom, bottom cropped
        //   45,   95,      45,   90
        //   90,  140,     140,  135
        //  135,  185      185,  180
        //  180,  230      180,  225   <-- bottom

        assertEquals("top", -50, screenshot.getTop());
        assertEquals("bottom", 225, screenshot.getBottom());
    }

    @Test
    public void testLimitedBottom() {
        ScrollCaptureController controller = new TestScenario()
                .withPageHeight(100)
                .withMaxPages(2.5f)
                .withTileHeight(10)
                .withAvailableRange(Integer.MIN_VALUE, 150)
                .createController(mContext);

        ScrollCaptureController.LongScreenshot screenshot =
                getUnchecked(controller.run(EMPTY_RESPONSE));

        assertEquals("top", -100, screenshot.getTop());
        assertEquals("bottom", 150, screenshot.getBottom());
    }

    @Test
    public void testLimitedTopAndBottom() {
        ScrollCaptureController controller = new TestScenario()
                .withPageHeight(100)
                .withMaxPages(2.5f)
                .withTileHeight(10)
                .withAvailableRange(-50, 150)
                .createController(mContext);

        ScrollCaptureController.LongScreenshot screenshot =
                getUnchecked(controller.run(EMPTY_RESPONSE));

        assertEquals("top", -50, screenshot.getTop());
        assertEquals("bottom", 150, screenshot.getBottom());
    }

    @Test
    public void testVeryLimitedTopInfiniteBottom() {
        ScrollCaptureController controller = new TestScenario()
                .withPageHeight(100)
                .withMaxPages(2.5f)
                .withTileHeight(10)
                .withAvailableRange(-10, Integer.MAX_VALUE)
                .createController(mContext);

        ScrollCaptureController.LongScreenshot screenshot =
                getUnchecked(controller.run(EMPTY_RESPONSE));

        assertEquals("top", -10, screenshot.getTop());
        assertEquals("bottom", 240, screenshot.getBottom());
    }

    @Test
    public void testVeryLimitedTopLimitedBottom() {
        ScrollCaptureController controller = new TestScenario()
                .withPageHeight(100)
                .withMaxPages(2.5f)
                .withTileHeight(10)
                .withAvailableRange(-10, 200)
                .createController(mContext);

        ScrollCaptureController.LongScreenshot screenshot =
                getUnchecked(controller.run(EMPTY_RESPONSE));

        assertEquals("top", -10, screenshot.getTop());
        assertEquals("bottom", 200, screenshot.getBottom());
    }

    /**
     * Build and configure a stubbed controller for each test case.
     */
    private static class TestScenario {
        private int mPageHeight = -1;
        private int mTileHeight = -1;
        private boolean mAvailableRangeSet;
        private int mAvailableTop;
        private int mAvailableBottom;
        private int mLocalVisibleTop;
        private int mLocalVisibleBottom = -1;
        private float mMaxPages = -1;

        TestScenario withPageHeight(int pageHeight) {
            if (pageHeight < 0) {
                throw new IllegalArgumentException("pageHeight must be positive");
            }
            mPageHeight = pageHeight;
            return this;
        }

        TestScenario withTileHeight(int tileHeight) {
            if (tileHeight < 0) {
                throw new IllegalArgumentException("tileHeight must be positive");
            }
            mTileHeight = tileHeight;
            return this;
        }

        TestScenario withAvailableRange(int top, int bottom) {
            mAvailableRangeSet = true;
            mAvailableTop = top;
            mAvailableBottom = bottom;
            return this;
        }

        TestScenario withMaxPages(float maxPages) {
            if (maxPages < 0) {
                throw new IllegalArgumentException("maxPages must be positive");
            }
            mMaxPages = maxPages;
            return this;
        }

        TestScenario withPageVisibleRange(int top, int bottom) {
            if (top < 0 || bottom < 0) {
                throw new IllegalArgumentException("top and bottom must be positive");
            }
            mLocalVisibleTop = top;
            mLocalVisibleBottom = bottom;
            return this;
        }


        ScrollCaptureController createController(Context context) {
            if (mTileHeight < 0) {
                throw new IllegalArgumentException("tileHeight not set");
            }
            if (!mAvailableRangeSet) {
                throw new IllegalArgumentException("availableRange not set");
            }
            if (mPageHeight < 0) {
                throw new IllegalArgumentException("pageHeight not set");
            }

            if (mMaxPages < 0) {
                throw new IllegalArgumentException("maxPages not set");
            }
            // Default: page fully visible
            if (mLocalVisibleBottom < 0) {
                mLocalVisibleBottom = mPageHeight;
            }
            Session session = new FakeSession(mPageHeight, mMaxPages, mTileHeight,
                    mLocalVisibleTop, mLocalVisibleBottom, mAvailableTop, mAvailableBottom,
                    /* maxTiles */ 30);
            ScrollCaptureClient client = mock(ScrollCaptureClient.class);
            when(client.start(/* response */ any(), /* maxPages */ anyFloat()))
                    .thenReturn(immediateFuture(session));
            return new ScrollCaptureController(context, context.getMainExecutor(),
                    client, new ImageTileSet(context.getMainThreadHandler()),
                    new UiEventLoggerFake());
        }
    }
}
