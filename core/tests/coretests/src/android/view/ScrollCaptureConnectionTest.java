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

import static androidx.test.InstrumentationRegistry.getTargetContext;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests of {@link ScrollCaptureConnection}.
 */
@SuppressWarnings("UnnecessaryLocalVariable")
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ScrollCaptureConnectionTest {

    private final Point mPositionInWindow = new Point(1, 2);
    private final Rect mLocalVisibleRect = new Rect(2, 3, 4, 5);
    private final Rect mScrollBounds = new Rect(3, 4, 5, 6);
    private final TestScrollCaptureCallback mCallback = new TestScrollCaptureCallback();

    private ScrollCaptureTarget mTarget;
    private ScrollCaptureConnection mConnection;

    private Handler mHandler;

    @Mock
    private Surface mSurface;
    @Mock
    private IScrollCaptureCallbacks mRemote;
    @Mock
    private View mView;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mHandler = new Handler(getTargetContext().getMainLooper());
        when(mSurface.isValid()).thenReturn(true);
        when(mView.getScrollCaptureHint()).thenReturn(View.SCROLL_CAPTURE_HINT_INCLUDE);

        mTarget = new ScrollCaptureTarget(mView, mLocalVisibleRect, mPositionInWindow, mCallback);
        mTarget.setScrollBounds(mScrollBounds);
        mConnection = new ScrollCaptureConnection(Runnable::run, mTarget);
    }

    /** Test creating a client with valid info */
    @Test
    public void testConstruction() {
        ScrollCaptureTarget target = new ScrollCaptureTarget(
                mView, mLocalVisibleRect, mPositionInWindow, mCallback);
        target.setScrollBounds(new Rect(1, 2, 3, 4));
        new ScrollCaptureConnection(Runnable::run, target);
    }

    /** Test creating a client fails if arguments are not valid. */
    @Test
    public void testConstruction_requiresScrollBounds() {
        try {
            mTarget.setScrollBounds(null);
            new ScrollCaptureConnection(Runnable::run, mTarget);
            fail("An exception was expected.");
        } catch (RuntimeException ex) {
            // Ignore, expected.
        }
    }

    /** @see ScrollCaptureConnection#startCapture(Surface, IScrollCaptureCallbacks) */
    @Test
    public void testStartCapture() throws Exception {
        mConnection.startCapture(mSurface, mRemote);

        mCallback.completeStartRequest();
        assertTrue(mConnection.isActive());

        verify(mRemote, times(1)).onCaptureStarted();
        verifyNoMoreInteractions(mRemote);
    }

    @Test
    public void testStartCapture_cancellation() throws Exception {
        ICancellationSignal signal = mConnection.startCapture(mSurface, mRemote);
        signal.cancel();

        mCallback.completeStartRequest();
        assertFalse(mConnection.isActive());

        verifyNoMoreInteractions(mRemote);
    }

    /** @see ScrollCaptureConnection#requestImage(Rect) */
    @Test
    public void testRequestImage() throws Exception {
        mConnection.startCapture(mSurface, mRemote);
        mCallback.completeStartRequest();
        reset(mRemote);

        mConnection.requestImage(new Rect(1, 2, 3, 4));
        mCallback.completeImageRequest(new Rect(1, 2, 3, 4));

        verify(mRemote, times(1))
                .onImageRequestCompleted(eq(0), eq(new Rect(1, 2, 3, 4)));
        verifyNoMoreInteractions(mRemote);
    }

    @Test
    public void testRequestImage_cancellation() throws Exception {
        mConnection.startCapture(mSurface, mRemote);
        mCallback.completeStartRequest();
        reset(mRemote);

        ICancellationSignal signal = mConnection.requestImage(new Rect(1, 2, 3, 4));
        signal.cancel();
        mCallback.completeImageRequest(new Rect(1, 2, 3, 4));

        verifyNoMoreInteractions(mRemote);
    }

    /** @see ScrollCaptureConnection#endCapture() */
    @Test
    public void testEndCapture() throws Exception {
        mConnection.startCapture(mSurface, mRemote);
        mCallback.completeStartRequest();
        reset(mRemote);

        mConnection.endCapture();
        mCallback.completeEndRequest();

        // And the reply is sent
        verify(mRemote, times(1)).onCaptureEnded();
        verifyNoMoreInteractions(mRemote);
    }

    /** @see ScrollCaptureConnection#endCapture() */
    @Test
    public void testEndCapture_cancellation() throws Exception {
        mConnection.startCapture(mSurface, mRemote);
        mCallback.completeStartRequest();
        reset(mRemote);

        ICancellationSignal signal = mConnection.endCapture();
        signal.cancel();
        mCallback.completeEndRequest();

        verifyNoMoreInteractions(mRemote);
    }

    @Test
    public void testClose() {
        mConnection.close();
        assertFalse(mConnection.isActive());
        verifyNoMoreInteractions(mRemote);
    }

    @Test
    public void testClose_whileActive() throws RemoteException {
        mConnection.startCapture(mSurface, mRemote);

        mCallback.completeStartRequest();
        assertTrue(mConnection.isActive());

        mConnection.close();
        mCallback.completeEndRequest();
        assertFalse(mConnection.isActive());
    }
}
