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

import static com.google.common.util.concurrent.Futures.getUnchecked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link FakeSession}, a a test double for a ScrollCaptureClient.Session.
 * <p>
 * These tests verify a single tile request behaves similarly to a live scroll capture
 * client/connection.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class FakeSessionTest extends SysuiTestCase {
    @Test
    public void testNonEmptyResult_hasImage() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 1.0f,
                /* tileHeight */ 10,
                /* visiblePageTop */ 0,
                /* visiblePageBottom */ 100,
                /* availableTop */ 0,
                /* availableBottom */ 100,
                /* max Tiles */ 30);
        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(0));
        assertNotNull("result.image", result.image);
        assertNotNull("result.image.getHardwareBuffer()", result.image.getHardwareBuffer());
    }

    @Test
    public void testEmptyResult_hasNullImage() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 1.0f,
                /* tileHeight */ 10,
                /* visiblePageTop */ 0,
                /* visiblePageBottom */ 100,
                /* availableTop */ 0,
                /* availableBottom */ 100,
                /* max Tiles */ 30);
        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(-100));
        assertNull("result.image", result.image);
    }

    @Test
    public void testCaptureAtZero() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 2.5f,
                /* tileHeight */ 10,
                /* visiblePageTop */ 0,
                /* visiblePageBottom */ 100,
                /* availableTop */ -250,
                /* availableBottom */ 250,
                /* max Tiles */ 30);

        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(0));
        assertEquals("requested top", 0, result.requested.top);
        assertEquals("requested bottom", 10, result.requested.bottom);
        assertEquals("captured top", 0, result.captured.top);
        assertEquals("captured bottom", 10, result.captured.bottom);
        assertEquals("scroll delta", 0, session.getScrollDelta());
    }

    @Test
    public void testCaptureAtPageBottom() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 2.5f,
                /* tileHeight */ 10,
                /* visiblePageTop */ 0,
                /* visiblePageBottom */ 100,
                /* availableTop */ -250,
                /* availableBottom */ 250,
                /* max Tiles */ 30);

        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(90));
        assertEquals("requested top", 90, result.requested.top);
        assertEquals("requested bottom", 100, result.requested.bottom);
        assertEquals("captured top", 90, result.captured.top);
        assertEquals("captured bottom", 100, result.captured.bottom);
        assertEquals("scroll delta", 0, session.getScrollDelta());
    }

    @Test
    public void testCaptureFromPreviousPage() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 2.5f,
                /* tileHeight */ 10,
                /* visiblePageTop */ 0,
                /* visiblePageBottom */ 100,
                /* availableTop */ -250,
                /* availableBottom */ 250,
                /* max Tiles */ 30);

        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(-100));
        assertEquals("requested top", -100, result.requested.top);
        assertEquals("requested bottom", -90, result.requested.bottom);
        assertEquals("captured top", -100, result.captured.top);
        assertEquals("captured bottom", -90, result.captured.bottom);
        assertEquals("scroll delta", -100, session.getScrollDelta());
    }

    @Test
    public void testCaptureFromNextPage() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 2.5f,
                /* tileHeight */ 10,
                /* visiblePageTop */ 0,
                /* visiblePageBottom */ 100,
                /* availableTop */ -250,
                /* availableBottom */ 250,
                /* max Tiles */ 30);

        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(150));
        assertEquals("requested top", 150, result.requested.top);
        assertEquals("requested bottom", 160, result.requested.bottom);
        assertEquals("captured top", 150, result.captured.top);
        assertEquals("captured bottom", 160, result.captured.bottom);
        assertEquals("scroll delta", 60, session.getScrollDelta());
    }

    @Test
    public void testCaptureTopPartiallyUnavailable() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 3f,
                /* tileHeight */ 50,
                /* visiblePageTop */ 0,
                /* visiblePageBottom */ 100,
                /* availableTop */ -100,
                /* availableBottom */ 100,
                /* max Tiles */ 30);

        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(-125));
        assertEquals("requested top", -125, result.requested.top);
        assertEquals("requested bottom", -75, result.requested.bottom);
        assertEquals("captured top", -100, result.captured.top);
        assertEquals("captured bottom", -75, result.captured.bottom);
        assertEquals("scroll delta", -100, session.getScrollDelta());
    }

    @Test
    public void testCaptureBottomPartiallyUnavailable() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 3f,
                /* tileHeight */ 50,
                /* visiblePageTop */ 0,
                /* visiblePageBottom */ 100,
                /* availableTop */ -100,
                /* availableBottom */ 100,
                /* max Tiles */ 30);

        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(75));
        assertEquals("requested top", 75, result.requested.top);
        assertEquals("requested bottom", 125, result.requested.bottom);
        assertEquals("captured top", 75, result.captured.top);
        assertEquals("captured bottom", 100, result.captured.bottom);
        assertEquals("scroll delta", 0, session.getScrollDelta());
    }

    /**
     * Set visiblePageTop > 0  to cause the returned request's top edge to be cropped.
     */
    @Test
    public void testCaptureTopPartiallyInvisible() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 3f,
                /* tileHeight */ 50,
                /* visiblePageTop */ 25,  // <<--
                /* visiblePageBottom */ 100,
                /* availableTop */ -150,
                /* availableBottom */ 150,
                /* max Tiles */ 30);

        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(-150));
        assertEquals("requested top", -150, result.requested.top);
        assertEquals("requested bottom", -100, result.requested.bottom);
        assertEquals("captured top", -125, result.captured.top);
        assertEquals("captured bottom", -100, result.captured.bottom);
        assertEquals("scroll delta", -150, session.getScrollDelta());
    }

    /**
     * Set visiblePageBottom < pageHeight to cause the returned request's bottom edge to be cropped.
     */
    @Test
    public void testCaptureBottomPartiallyInvisible() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 3f,
                /* tileHeight */ 50,
                /* visiblePageTop */ 0,
                /* visiblePageBottom */ 75,
                /* availableTop */ -150,
                /* availableBottom */ 150,
                /* max Tiles */ 30);

        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(50));
        assertEquals("requested top", 50, result.requested.top);
        assertEquals("requested bottom", 100, result.requested.bottom);
        assertEquals("captured top", 50, result.captured.top);
        assertEquals("captured bottom", 75, result.captured.bottom);
        assertEquals("scroll delta", 0, session.getScrollDelta());
    }

    @Test
    public void testEmptyResult_aboveAvailableTop() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 3.0f,
                /* tileHeight */ 50,
                /* visiblePageTop */ 0,
                /* visiblePageBottom */ 100,
                /* availableTop */ -100,
                /* availableBottom */ 200,
                /* max Tiles */ 30);
        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(-150));
        assertTrue("captured rect is empty", result.captured.isEmpty());
    }

    @Test
    public void testEmptyResult_belowAvailableBottom() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 3.0f,
                /* tileHeight */ 50,
                /* visiblePageTop */ 0,
                /* visiblePageBottom */ 100,
                /* availableTop */ -100,
                /* availableBottom */ 200,
                /* max Tiles */ 30);
        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(200));
        assertTrue("captured rect is empty", result.captured.isEmpty());
    }

    @Test
    public void testEmptyResult_notVisible() {
        FakeSession session = new FakeSession(
                /* pageHeight */ 100,
                /* maxPages */ 3f,
                /* tileHeight */ 50,
                /* visiblePageTop */ 60,  // <<---
                /* visiblePageBottom */ 0,
                /* availableTop */ -150,
                /* availableBottom */ 150,
                /* max Tiles */ 30);

        ScrollCaptureClient.CaptureResult result = getUnchecked(session.requestTile(0));
        assertEquals("requested top", 0, result.requested.top);
        assertEquals("requested bottom", 50, result.requested.bottom);
        assertEquals("captured top", 0, result.captured.top);
        assertEquals("captured bottom", 0, result.captured.bottom);
        assertEquals("scroll delta", 0, session.getScrollDelta());
    }

}
