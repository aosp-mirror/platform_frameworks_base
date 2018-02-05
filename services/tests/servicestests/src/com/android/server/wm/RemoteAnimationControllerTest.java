/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.graphics.Point;
import android.graphics.Rect;
import android.support.test.filters.FlakyTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * atest FrameworksServicesTests:com.android.server.wm.RemoteAnimationControllerTest
 */
@SmallTest
@FlakyTest(detail = "Promote to presubmit if non-flakyness is established")
@RunWith(AndroidJUnit4.class)
public class RemoteAnimationControllerTest extends WindowTestsBase {

    @Mock SurfaceControl mMockLeash;
    @Mock Transaction mMockTransaction;
    @Mock OnAnimationFinishedCallback mFinishedCallback;
    @Mock IRemoteAnimationRunner mMockRunner;
    private RemoteAnimationAdapter mAdapter;
    private RemoteAnimationController mController;
    private final OffsettableClock mClock = new OffsettableClock.Stopped();
    private TestHandler mHandler;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mAdapter = new RemoteAnimationAdapter(mMockRunner, 100, 50);
        sWm.mH.runWithScissors(() -> {
            mHandler = new TestHandler(null, mClock);
        }, 0);
        mController = new RemoteAnimationController(sWm, mAdapter, mHandler);
    }

    @Test
    public void testRun() throws Exception {
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        sWm.mOpeningApps.add(win.mAppToken);
        try {
            final AnimationAdapter adapter = mController.createAnimationAdapter(win.mAppToken,
                    new Point(50, 100), new Rect(50, 100, 150, 150));
            adapter.startAnimation(mMockLeash, mMockTransaction, mFinishedCallback);
            mController.goodToGo();

            final ArgumentCaptor<RemoteAnimationTarget[]> appsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<IRemoteAnimationFinishedCallback> finishedCaptor =
                    ArgumentCaptor.forClass(IRemoteAnimationFinishedCallback.class);
            verify(mMockRunner).onAnimationStart(appsCaptor.capture(), finishedCaptor.capture());
            assertEquals(1, appsCaptor.getValue().length);
            final RemoteAnimationTarget app = appsCaptor.getValue()[0];
            assertEquals(new Point(50, 100), app.position);
            assertEquals(new Rect(50, 100, 150, 150), app.sourceContainerBounds);
            assertEquals(win.mAppToken.getPrefixOrderIndex(), app.prefixOrderIndex);
            assertEquals(win.mAppToken.getTask().mTaskId, app.taskId);
            assertEquals(mMockLeash, app.leash);
            assertEquals(win.mWinAnimator.mLastClipRect, app.clipRect);
            assertEquals(false, app.isTranslucent);
            verify(mMockTransaction).setLayer(mMockLeash, app.prefixOrderIndex);
            verify(mMockTransaction).setPosition(mMockLeash, app.position.x, app.position.y);
            verify(mMockTransaction).setWindowCrop(mMockLeash, new Rect(0, 0, 100, 50));

            finishedCaptor.getValue().onAnimationFinished();
            verify(mFinishedCallback).onAnimationFinished(eq(adapter));
        } finally {
            sWm.mOpeningApps.clear();
        }
    }

    @Test
    public void testCancel() throws Exception {
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        final AnimationAdapter adapter = mController.createAnimationAdapter(win.mAppToken,
                new Point(50, 100), new Rect(50, 100, 150, 150));
        adapter.startAnimation(mMockLeash, mMockTransaction, mFinishedCallback);
        mController.goodToGo();

        adapter.onAnimationCancelled(mMockLeash);
        verify(mMockRunner).onAnimationCancelled();
    }

    @Test
    public void testTimeout() throws Exception {
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        final AnimationAdapter adapter = mController.createAnimationAdapter(win.mAppToken,
                new Point(50, 100), new Rect(50, 100, 150, 150));
        adapter.startAnimation(mMockLeash, mMockTransaction, mFinishedCallback);
        mController.goodToGo();

        mClock.fastForward(2500);
        mHandler.timeAdvance();

        verify(mMockRunner).onAnimationCancelled();
        verify(mFinishedCallback).onAnimationFinished(eq(adapter));
    }

    @Test
    public void testTimeout_scaled() throws Exception {
        sWm.setAnimationScale(2, 5.0f);
        try{
            final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
            final AnimationAdapter adapter = mController.createAnimationAdapter(win.mAppToken,
                    new Point(50, 100), new Rect(50, 100, 150, 150));
            adapter.startAnimation(mMockLeash, mMockTransaction, mFinishedCallback);
            mController.goodToGo();

            mClock.fastForward(2500);
            mHandler.timeAdvance();

            verify(mMockRunner, never()).onAnimationCancelled();

            mClock.fastForward(10000);
            mHandler.timeAdvance();

            verify(mMockRunner).onAnimationCancelled();
            verify(mFinishedCallback).onAnimationFinished(eq(adapter));
        } finally {
            sWm.setAnimationScale(2, 1.0f);
        }

    }

    @Test
    public void testZeroAnimations() throws Exception {
        mController.goodToGo();
        verifyZeroInteractions(mMockRunner);
    }
}
