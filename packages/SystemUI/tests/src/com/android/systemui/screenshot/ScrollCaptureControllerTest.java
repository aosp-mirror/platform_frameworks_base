/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.testing.AndroidTestingRunner;
import android.view.ScrollCaptureResponse;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.systemui.SysuiTestCase;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;

/**
 * Tests for ScrollCaptureController which manages sequential image acquisition for long
 * screenshots.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ScrollCaptureControllerTest extends SysuiTestCase {

    private static class FakeSession implements ScrollCaptureClient.Session {
        public int availableTop = Integer.MIN_VALUE;
        public int availableBottom = Integer.MAX_VALUE;
        // If true, return an empty rect any time a partial result would have been returned.
        public boolean emptyInsteadOfPartial = false;
        private int mPreviousTopRequested = 0;

        @Override
        public ListenableFuture<ScrollCaptureClient.CaptureResult> requestTile(int top) {
            // Ensure we don't request a tile more than a tile away.
            assertTrue(Math.abs(top - mPreviousTopRequested) <= getTileHeight());
            mPreviousTopRequested = top;
            Rect requested = new Rect(0, top, getPageWidth(), top + getTileHeight());
            Rect fullContent = new Rect(0, availableTop, getPageWidth(), availableBottom);
            Rect captured = new Rect(requested);
            assertTrue(captured.intersect(fullContent));
            if (emptyInsteadOfPartial && captured.height() != getTileHeight()) {
                captured = new Rect();
            }
            Image image = mock(Image.class);
            when(image.getHardwareBuffer()).thenReturn(mock(HardwareBuffer.class));
            ScrollCaptureClient.CaptureResult result =
                    new ScrollCaptureClient.CaptureResult(image, requested, captured);
            return Futures.immediateFuture(result);
        }

        public int getMaxHeight() {
            return getTileHeight() * getMaxTiles();
        }

        @Override
        public int getMaxTiles() {
            return 10;
        }

        @Override
        public int getTileHeight() {
            return 50;
        }

        @Override
        public int getPageHeight() {
            return 100;
        }

        @Override
        public int getPageWidth() {
            return 100;
        }

        @Override
        public Rect getWindowBounds() {
            return null;
        }

        @Override
        public ListenableFuture<Void> end() {
            return Futures.immediateVoidFuture();
        }

        @Override
        public void release() {
        }
    }

    private ScrollCaptureController mController;
    private FakeSession mSession;
    private ScrollCaptureClient mScrollCaptureClient;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mSession = new FakeSession();
        mScrollCaptureClient = mock(ScrollCaptureClient.class);
        when(mScrollCaptureClient.request(anyInt(), anyInt())).thenReturn(
                Futures.immediateFuture(new ScrollCaptureResponse.Builder().build()));
        when(mScrollCaptureClient.start(any(), anyFloat())).thenReturn(
                Futures.immediateFuture(mSession));
        mController = new ScrollCaptureController(context, context.getMainExecutor(),
                mScrollCaptureClient, new ImageTileSet(context.getMainThreadHandler()));
    }

    @Test
    public void testInfinite() throws ExecutionException, InterruptedException {
        ScrollCaptureController.LongScreenshot screenshot =
                mController.run(new ScrollCaptureResponse.Builder().build()).get();
        assertEquals(mSession.getMaxHeight(), screenshot.getHeight());
        // TODO: the top and bottom ratio in the infinite case should be extracted and tested.
        assertEquals(-150, screenshot.getTop());
        assertEquals(350, screenshot.getBottom());
    }

    @Test
    public void testLimitedBottom() throws ExecutionException, InterruptedException {
        // We hit the bottom of the content, so expect it to scroll back up and go above the -150
        // default top position
        mSession.availableBottom = 275;
        ScrollCaptureController.LongScreenshot screenshot =
                mController.run(new ScrollCaptureResponse.Builder().build()).get();
        // Bottom tile will be 25px tall, 10 tiles total
        assertEquals(mSession.getMaxHeight() - 25, screenshot.getHeight());
        assertEquals(-200, screenshot.getTop());
        assertEquals(mSession.availableBottom, screenshot.getBottom());
    }

    @Test
    public void testLimitedTopAndBottom() throws ExecutionException, InterruptedException {
        mSession.availableBottom = 275;
        mSession.availableTop = -200;
        ScrollCaptureController.LongScreenshot screenshot =
                mController.run(new ScrollCaptureResponse.Builder().build()).get();
        assertEquals(mSession.availableBottom - mSession.availableTop, screenshot.getHeight());
        assertEquals(mSession.availableTop, screenshot.getTop());
        assertEquals(mSession.availableBottom, screenshot.getBottom());
    }

    @Test
    public void testVeryLimitedTopInfiniteBottom() throws ExecutionException, InterruptedException {
        // Hit the boundary before the "headroom" is hit in the up direction, then go down
        // infinitely.
        mSession.availableTop = -55;
        ScrollCaptureController.LongScreenshot screenshot =
                mController.run(new ScrollCaptureResponse.Builder().build()).get();
        // The top tile will be 5px tall, so subtract 45px from the theoretical max.
        assertEquals(mSession.getMaxHeight() - 45, screenshot.getHeight());
        assertEquals(mSession.availableTop, screenshot.getTop());
        assertEquals(mSession.availableTop + mSession.getMaxHeight() - 45, screenshot.getBottom());
    }

    @Test
    public void testVeryLimitedTopLimitedBottom() throws ExecutionException, InterruptedException {
        mSession.availableBottom = 275;
        mSession.availableTop = -55;
        ScrollCaptureController.LongScreenshot screenshot =
                mController.run(new ScrollCaptureResponse.Builder().build()).get();
        assertEquals(mSession.availableBottom - mSession.availableTop, screenshot.getHeight());
        assertEquals(mSession.availableTop, screenshot.getTop());
        assertEquals(mSession.availableBottom, screenshot.getBottom());
    }

    @Test
    public void testLimitedTopAndBottomWithEmpty() throws ExecutionException, InterruptedException {
        mSession.emptyInsteadOfPartial = true;
        mSession.availableBottom = 275;
        mSession.availableTop = -167;
        ScrollCaptureController.LongScreenshot screenshot =
                mController.run(new ScrollCaptureResponse.Builder().build()).get();
        // Expecting output from -150 to 250
        assertEquals(400, screenshot.getHeight());
        assertEquals(-150, screenshot.getTop());
        assertEquals(250, screenshot.getBottom());
    }

    @Test
    public void testVeryLimitedTopWithEmpty() throws ExecutionException, InterruptedException {
        // Hit the boundary before the "headroom" is hit in the up direction, then go down
        // infinitely.
        mSession.availableTop = -55;
        mSession.emptyInsteadOfPartial = true;
        ScrollCaptureController.LongScreenshot screenshot =
                mController.run(new ScrollCaptureResponse.Builder().build()).get();
        assertEquals(mSession.getMaxHeight(), screenshot.getHeight());
        assertEquals(-50, screenshot.getTop());
        assertEquals(-50 + mSession.getMaxHeight(), screenshot.getBottom());
    }
}
