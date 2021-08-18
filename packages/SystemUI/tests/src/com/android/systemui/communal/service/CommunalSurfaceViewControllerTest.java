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

package com.android.systemui.communal.service;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.IBinder;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import com.google.common.util.concurrent.SettableFuture;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class CommunalSurfaceViewControllerTest extends SysuiTestCase {
    private static final int MEASURED_HEIGHT = 200;
    private static final int MEASURED_WIDTH = 500;
    private static final int DISPLAY_ID = 3;

    @Mock
    private Display mDisplay;

    @Mock
    private IBinder mHostToken;

    @Mock
    private SurfaceView mSurfaceView;

    @Mock
    private SurfaceHolder mSurfaceHolder;

    @Mock
    private CommunalSourceImpl mCommunalSource;

    @Mock
    private SurfaceControlViewHost.SurfacePackage mSurfacePackage;

    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    private SurfaceHolder.Callback mCallback;

    private CommunalSurfaceViewController mController;

    private SettableFuture<SurfaceControlViewHost.SurfacePackage> mPackageFuture;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        final ArgumentCaptor<SurfaceHolder.Callback> callbackCapture =
                ArgumentCaptor.forClass(SurfaceHolder.Callback.class);
        when(mSurfaceView.getHolder()).thenReturn(mSurfaceHolder);
        when(mSurfaceView.getDisplay()).thenReturn(mDisplay);
        when(mDisplay.getDisplayId()).thenReturn(DISPLAY_ID);
        when(mSurfaceView.getHostToken()).thenReturn(mHostToken);
        when(mSurfaceView.getMeasuredWidth()).thenReturn(MEASURED_WIDTH);
        when(mSurfaceView.getMeasuredHeight()).thenReturn(MEASURED_HEIGHT);
        when(mSurfaceView.isAttachedToWindow()).thenReturn(false);
        mController = new CommunalSurfaceViewController(mSurfaceView, mFakeExecutor,
                mCommunalSource);
        mController.init();
        verify(mSurfaceHolder).addCallback(callbackCapture.capture());
        mCallback = callbackCapture.getValue();

        mPackageFuture = SettableFuture.create();

        when(mCommunalSource.requestCommunalSurface(any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(mPackageFuture);
    }

    @Test
    public void testSetSurfacePackage() {
        // There should be no requests without the proper state.
        verify(mCommunalSource, times(0))
                .requestCommunalSurface(any(), anyInt(), anyInt(), anyInt());

        // The full state must be present to make a request.
        mController.onViewAttached();
        verify(mCommunalSource, times(0))
                .requestCommunalSurface(any(), anyInt(), anyInt(), anyInt());

        clearInvocations(mSurfaceView);

        // Request surface view once all conditions are met.
        mCallback.surfaceCreated(mSurfaceHolder);
        verify(mCommunalSource)
                .requestCommunalSurface(mHostToken, DISPLAY_ID, MEASURED_WIDTH, MEASURED_HEIGHT);

        when(mSurfaceView.isAttachedToWindow()).thenReturn(true);

        // Respond to request.
        mPackageFuture.set(mSurfacePackage);
        mFakeExecutor.runAllReady();


        // Make sure SurfaceView is set.
        verify(mSurfaceView).setChildSurfacePackage(mSurfacePackage);
        verify(mSurfaceView).setZOrderOnTop(true);
        verify(mSurfaceView).setWillNotDraw(false);
    }

    // Invoked to setup surface view package.
    private void givenSurfacePresent() {
        mController.onViewAttached();
        mCallback.surfaceCreated(mSurfaceHolder);
        when(mSurfaceView.isAttachedToWindow()).thenReturn(true);
        mPackageFuture.set(mSurfacePackage);
        mFakeExecutor.runAllReady();
        clearInvocations(mSurfaceView);
    }

    @Test
    public void testClearOnDetach() {
        givenSurfacePresent();
        when(mSurfaceView.isAttachedToWindow()).thenReturn(false);
        mController.onViewDetached();
        verify(mSurfaceView).setWillNotDraw(true);
    }

    @Test
    public void testClearOnSurfaceDestroyed() {
        givenSurfacePresent();
        mCallback.surfaceDestroyed(mSurfaceHolder);
        verify(mSurfaceView).setWillNotDraw(true);
    }

    @Test
    public void testCancelRequest() {
        mController.onViewAttached();
        mCallback.surfaceCreated(mSurfaceHolder);
        when(mSurfaceView.isAttachedToWindow()).thenReturn(true);
        mFakeExecutor.runAllReady();
        clearInvocations(mSurfaceView);

        verify(mCommunalSource, times(1))
                .requestCommunalSurface(mHostToken, DISPLAY_ID, MEASURED_WIDTH, MEASURED_HEIGHT);

        mController.onViewDetached();
        assertTrue(mPackageFuture.isCancelled());
        verify(mSurfaceView).setWillNotDraw(true);
    }
}
