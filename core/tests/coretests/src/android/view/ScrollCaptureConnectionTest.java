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

package android.view;

import static androidx.test.InstrumentationRegistry.getInstrumentation;
import static androidx.test.InstrumentationRegistry.getTargetContext;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

/**
 * Tests of {@link ScrollCaptureConnection}.
 */
@SuppressWarnings("UnnecessaryLocalVariable")
@RunWith(AndroidJUnit4.class)
public class ScrollCaptureConnectionTest {

    private final Point mPositionInWindow = new Point(1, 2);
    private final Rect mLocalVisibleRect = new Rect(2, 3, 4, 5);
    private final Rect mScrollBounds = new Rect(3, 4, 5, 6);

    private Handler mHandler;
    private ScrollCaptureTarget mTarget1;

    @Mock
    private Surface mSurface;
    @Mock
    private IScrollCaptureCallbacks mConnectionCallbacks;
    @Mock
    private View mMockView1;
    @Mock
    private ScrollCaptureCallback mCallback1;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mHandler = new Handler(getTargetContext().getMainLooper());

        when(mMockView1.getHandler()).thenReturn(mHandler);
        when(mMockView1.getScrollCaptureHint()).thenReturn(View.SCROLL_CAPTURE_HINT_INCLUDE);

        mTarget1 = new ScrollCaptureTarget(
                mMockView1, mLocalVisibleRect, mPositionInWindow, mCallback1);
        mTarget1.setScrollBounds(mScrollBounds);
    }

    /** Test the DelayedAction timeout helper class works as expected. */
    @Test
    public void testDelayedAction() {
        Runnable action = Mockito.mock(Runnable.class);
        ScrollCaptureConnection.DelayedAction delayed =
                new ScrollCaptureConnection.DelayedAction(mHandler, 100, action);
        try {
            Thread.sleep(200);
        } catch (InterruptedException ex) {
            /* ignore */
        }
        getInstrumentation().waitForIdleSync();
        assertFalse(delayed.cancel());
        assertFalse(delayed.timeoutNow());
        verify(action, times(1)).run();
    }

    /** Test the DelayedAction cancel() */
    @Test
    public void testDelayedAction_cancel() {
        Runnable action = Mockito.mock(Runnable.class);
        ScrollCaptureConnection.DelayedAction delayed =
                new ScrollCaptureConnection.DelayedAction(mHandler, 100, action);
        try {
            Thread.sleep(50);
        } catch (InterruptedException ex) {
            /* ignore */
        }
        assertTrue(delayed.cancel());
        assertFalse(delayed.timeoutNow());
        try {
            Thread.sleep(200);
        } catch (InterruptedException ex) {
            /* ignore */
        }
        getInstrumentation().waitForIdleSync();
        verify(action, never()).run();
    }

    /** Test the DelayedAction timeoutNow() - for testing only */
    @Test
    public void testDelayedAction_timeoutNow() {
        Runnable action = Mockito.mock(Runnable.class);
        ScrollCaptureConnection.DelayedAction delayed =
                new ScrollCaptureConnection.DelayedAction(mHandler, 100, action);
        try {
            Thread.sleep(50);
        } catch (InterruptedException ex) {
            /* ignore */
        }
        assertTrue(delayed.timeoutNow());
        assertFalse(delayed.cancel());
        getInstrumentation().waitForIdleSync();
        verify(action, times(1)).run();
    }

    /** Test creating a client with valid info */
    @Test
    public void testConstruction() {
        new ScrollCaptureConnection(mTarget1, mConnectionCallbacks);
    }

    /** Test creating a client fails if arguments are not valid. */
    @Test
    public void testConstruction_requiresScrollBounds() {
        try {
            mTarget1.setScrollBounds(null);
            new ScrollCaptureConnection(mTarget1, mConnectionCallbacks);
            fail("An exception was expected.");
        } catch (RuntimeException ex) {
            // Ignore, expected.
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static Answer<Void> runRunnable(int arg) {
        return invocation -> {
            Runnable r = invocation.getArgument(arg);
            r.run();
            return null;
        };
    }

    @SuppressWarnings("SameParameterValue")
    private static Answer<Void> reportBufferSent(int sessionArg, long frameNum, Rect capturedArea) {
        return invocation -> {
            ScrollCaptureSession session = invocation.getArgument(sessionArg);
            session.notifyBufferSent(frameNum, capturedArea);
            return null;
        };
    }

    /** @see ScrollCaptureConnection#startCapture(Surface) */
    @Test
    public void testStartCapture() throws Exception {
        final ScrollCaptureConnection connection = new ScrollCaptureConnection(mTarget1,
                mConnectionCallbacks);

        // Have the session start accepted immediately
        doAnswer(runRunnable(1)).when(mCallback1)
                .onScrollCaptureStart(any(ScrollCaptureSession.class), any(Runnable.class));
        connection.startCapture(mSurface);
        getInstrumentation().waitForIdleSync();

        verify(mCallback1, times(1))
                .onScrollCaptureStart(any(ScrollCaptureSession.class), any(Runnable.class));
        verify(mConnectionCallbacks, times(1)).onCaptureStarted();
        verifyNoMoreInteractions(mConnectionCallbacks);
    }

    @Test
    public void testStartCaptureTimeout() throws Exception {
        final ScrollCaptureConnection connection = new ScrollCaptureConnection(mTarget1,
                mConnectionCallbacks);
        connection.startCapture(mSurface);

        // Force timeout to fire
        connection.getTimeoutAction().timeoutNow();

        getInstrumentation().waitForIdleSync();
        verify(mCallback1, times(1)).onScrollCaptureEnd(any(Runnable.class));
    }

    private void startCapture(ScrollCaptureConnection connection) throws Exception {
        doAnswer(runRunnable(1)).when(mCallback1)
                .onScrollCaptureStart(any(ScrollCaptureSession.class), any(Runnable.class));
        connection.startCapture(mSurface);
        getInstrumentation().waitForIdleSync();
        reset(mCallback1, mConnectionCallbacks);
    }

    /** @see ScrollCaptureConnection#requestImage(Rect) */
    @Test
    public void testRequestImage() throws Exception {
        final ScrollCaptureConnection connection = new ScrollCaptureConnection(mTarget1,
                mConnectionCallbacks);
        startCapture(connection);

        // Stub the callback to complete the request immediately
        doAnswer(reportBufferSent(/* sessionArg */ 0, /* frameNum */ 1L, new Rect(1, 2, 3, 4)))
                .when(mCallback1)
                .onScrollCaptureImageRequest(any(ScrollCaptureSession.class), any(Rect.class));

        // Make the inbound binder call
        connection.requestImage(new Rect(1, 2, 3, 4));

        // Wait for handler thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mCallback1, times(1)).onScrollCaptureImageRequest(
                any(ScrollCaptureSession.class), eq(new Rect(1, 2, 3, 4)));

        // Wait for binder thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mConnectionCallbacks, times(1))
                .onCaptureBufferSent(eq(1L), eq(new Rect(1, 2, 3, 4)));

        verifyNoMoreInteractions(mCallback1, mConnectionCallbacks);
    }

    @Test
    public void testRequestImageTimeout() throws Exception {
        final ScrollCaptureConnection connection = new ScrollCaptureConnection(mTarget1,
                mConnectionCallbacks);
        startCapture(connection);

        // Make the inbound binder call
        connection.requestImage(new Rect(1, 2, 3, 4));

        // Wait for handler thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mCallback1, times(1)).onScrollCaptureImageRequest(
                any(ScrollCaptureSession.class), eq(new Rect(1, 2, 3, 4)));

        // Force timeout to fire
        connection.getTimeoutAction().timeoutNow();
        getInstrumentation().waitForIdleSync();

        // (callback not stubbed, does nothing)
        // Timeout triggers request to end capture
        verify(mCallback1, times(1)).onScrollCaptureEnd(any(Runnable.class));
        verifyNoMoreInteractions(mCallback1, mConnectionCallbacks);
    }

    /** @see ScrollCaptureConnection#endCapture() */
    @Test
    public void testEndCapture() throws Exception {
        final ScrollCaptureConnection connection = new ScrollCaptureConnection(mTarget1,
                mConnectionCallbacks);
        startCapture(connection);

        // Stub the callback to complete the request immediately
        doAnswer(runRunnable(0))
                .when(mCallback1)
                .onScrollCaptureEnd(any(Runnable.class));

        // Make the inbound binder call
        connection.endCapture();

        // Wait for handler thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mCallback1, times(1)).onScrollCaptureEnd(any(Runnable.class));

        // Wait for binder thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mConnectionCallbacks, times(1)).onConnectionClosed();

        verifyNoMoreInteractions(mCallback1, mConnectionCallbacks);
    }

    @Test
    public void testEndCaptureTimeout() throws Exception {
        final ScrollCaptureConnection connection = new ScrollCaptureConnection(mTarget1,
                mConnectionCallbacks);
        startCapture(connection);

        // Make the inbound binder call
        connection.endCapture();

        // Wait for handler thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mCallback1, times(1)).onScrollCaptureEnd(any(Runnable.class));

        // Force timeout to fire
        connection.getTimeoutAction().timeoutNow();

        // Wait for binder thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mConnectionCallbacks, times(1)).onConnectionClosed();

        verifyNoMoreInteractions(mCallback1, mConnectionCallbacks);
    }
}
