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
 * Tests of {@link ScrollCaptureClient}.
 */
@SuppressWarnings("UnnecessaryLocalVariable")
@RunWith(AndroidJUnit4.class)
public class ScrollCaptureClientTest {

    private final Point mPositionInWindow = new Point(1, 2);
    private final Rect mLocalVisibleRect = new Rect(2, 3, 4, 5);
    private final Rect mScrollBounds = new Rect(3, 4, 5, 6);

    private Handler mHandler;
    private ScrollCaptureTarget mTarget1;

    @Mock
    private Surface mSurface;
    @Mock
    private IScrollCaptureController mClientCallbacks;
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
        ScrollCaptureClient.DelayedAction delayed =
                new ScrollCaptureClient.DelayedAction(mHandler, 100, action);
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
        ScrollCaptureClient.DelayedAction delayed =
                new ScrollCaptureClient.DelayedAction(mHandler, 100, action);
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
        ScrollCaptureClient.DelayedAction delayed =
                new ScrollCaptureClient.DelayedAction(mHandler, 100, action);
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
        new ScrollCaptureClient(mTarget1, mClientCallbacks);
    }

    /** Test creating a client fails if arguments are not valid. */
    @Test
    public void testConstruction_requiresScrollBounds() {
        try {
            mTarget1.setScrollBounds(null);
            new ScrollCaptureClient(mTarget1, mClientCallbacks);
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

    /** @see ScrollCaptureClient#startCapture(Surface) */
    @Test
    public void testStartCapture() throws Exception {
        final ScrollCaptureClient client = new ScrollCaptureClient(mTarget1, mClientCallbacks);

        // Have the session start accepted immediately
        doAnswer(runRunnable(1)).when(mCallback1)
                .onScrollCaptureStart(any(ScrollCaptureSession.class), any(Runnable.class));
        client.startCapture(mSurface);
        getInstrumentation().waitForIdleSync();

        verify(mCallback1, times(1))
                .onScrollCaptureStart(any(ScrollCaptureSession.class), any(Runnable.class));
        verify(mClientCallbacks, times(1)).onCaptureStarted();
        verifyNoMoreInteractions(mClientCallbacks);
    }

    @Test
    public void testStartCaptureTimeout() throws Exception {
        final ScrollCaptureClient client = new ScrollCaptureClient(mTarget1, mClientCallbacks);
        client.startCapture(mSurface);

        // Force timeout to fire
        client.getTimeoutAction().timeoutNow();

        getInstrumentation().waitForIdleSync();
        verify(mCallback1, times(1)).onScrollCaptureEnd(any(Runnable.class));
    }

    private void startClient(ScrollCaptureClient client) throws Exception {
        doAnswer(runRunnable(1)).when(mCallback1)
                .onScrollCaptureStart(any(ScrollCaptureSession.class), any(Runnable.class));
        client.startCapture(mSurface);
        getInstrumentation().waitForIdleSync();
        reset(mCallback1, mClientCallbacks);
    }

    /** @see ScrollCaptureClient#requestImage(Rect) */
    @Test
    public void testRequestImage() throws Exception {
        final ScrollCaptureClient client = new ScrollCaptureClient(mTarget1, mClientCallbacks);
        startClient(client);

        // Stub the callback to complete the request immediately
        doAnswer(reportBufferSent(/* sessionArg */ 0, /* frameNum */ 1L, new Rect(1, 2, 3, 4)))
                .when(mCallback1)
                .onScrollCaptureImageRequest(any(ScrollCaptureSession.class), any(Rect.class));

        // Make the inbound binder call
        client.requestImage(new Rect(1, 2, 3, 4));

        // Wait for handler thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mCallback1, times(1)).onScrollCaptureImageRequest(
                any(ScrollCaptureSession.class), eq(new Rect(1, 2, 3, 4)));

        // Wait for binder thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mClientCallbacks, times(1)).onCaptureBufferSent(eq(1L), eq(new Rect(1, 2, 3, 4)));

        verifyNoMoreInteractions(mCallback1, mClientCallbacks);
    }

    @Test
    public void testRequestImageTimeout() throws Exception {
        final ScrollCaptureClient client = new ScrollCaptureClient(mTarget1, mClientCallbacks);
        startClient(client);

        // Make the inbound binder call
        client.requestImage(new Rect(1, 2, 3, 4));

        // Wait for handler thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mCallback1, times(1)).onScrollCaptureImageRequest(
                any(ScrollCaptureSession.class), eq(new Rect(1, 2, 3, 4)));

        // Force timeout to fire
        client.getTimeoutAction().timeoutNow();
        getInstrumentation().waitForIdleSync();

        // (callback not stubbed, does nothing)
        // Timeout triggers request to end capture
        verify(mCallback1, times(1)).onScrollCaptureEnd(any(Runnable.class));
        verifyNoMoreInteractions(mCallback1, mClientCallbacks);
    }

    /** @see ScrollCaptureClient#endCapture() */
    @Test
    public void testEndCapture() throws Exception {
        final ScrollCaptureClient client = new ScrollCaptureClient(mTarget1, mClientCallbacks);
        startClient(client);

        // Stub the callback to complete the request immediately
        doAnswer(runRunnable(0))
                .when(mCallback1)
                .onScrollCaptureEnd(any(Runnable.class));

        // Make the inbound binder call
        client.endCapture();

        // Wait for handler thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mCallback1, times(1)).onScrollCaptureEnd(any(Runnable.class));

        // Wait for binder thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mClientCallbacks, times(1)).onConnectionClosed();

        verifyNoMoreInteractions(mCallback1, mClientCallbacks);
    }

    @Test
    public void testEndCaptureTimeout() throws Exception {
        final ScrollCaptureClient client = new ScrollCaptureClient(mTarget1, mClientCallbacks);
        startClient(client);

        // Make the inbound binder call
        client.endCapture();

        // Wait for handler thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mCallback1, times(1)).onScrollCaptureEnd(any(Runnable.class));

        // Force timeout to fire
        client.getTimeoutAction().timeoutNow();

        // Wait for binder thread dispatch
        getInstrumentation().waitForIdleSync();
        verify(mClientCallbacks, times(1)).onConnectionClosed();

        verifyNoMoreInteractions(mCallback1, mClientCallbacks);
    }
}
