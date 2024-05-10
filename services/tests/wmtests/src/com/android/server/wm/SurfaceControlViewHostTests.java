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

package com.android.server.wm;

import static android.server.wm.CtsWindowInfoUtils.dumpWindowsOnScreen;
import static android.server.wm.CtsWindowInfoUtils.waitForWindowFocus;
import static android.server.wm.CtsWindowInfoUtils.waitForWindowVisible;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.server.wm.BuildUtils;
import android.view.Gravity;
import android.view.IWindow;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowlessWindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.android.server.wm.utils.CommonUtils;
import com.android.server.wm.utils.TestActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Presubmit
@SmallTest
@RunWith(WindowTestRunner.class)
public class SurfaceControlViewHostTests {
    private static final long WAIT_TIME_S = 5L * BuildUtils.HW_TIMEOUT_MULTIPLIER;

    private static final String TAG = "SurfaceControlViewHostTests";

    private final ActivityTestRule<TestActivity> mActivityRule = new ActivityTestRule<>(
            TestActivity.class);
    private Instrumentation mInstrumentation;
    private TestActivity mActivity;

    private View mView1;
    private View mView2;
    private SurfaceControlViewHost mScvh1;
    private SurfaceControlViewHost mScvh2;

    private SurfaceView mSurfaceView;

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mActivity = mActivityRule.launchActivity(null);
    }

    @After
    public void tearDown() {
        CommonUtils.waitUntilActivityRemoved(mActivity);
    }

    @Test
    public void requestFocusWithMultipleWindows() throws InterruptedException, RemoteException {
        SurfaceControl sc = new SurfaceControl.Builder()
                .setName("SurfaceControlViewHostTests")
                .setCallsite("requestFocusWithMultipleWindows")
                .build();
        mView1 = new Button(mActivity);
        mView2 = new Button(mActivity);

        CountDownLatch svReadyLatch = new CountDownLatch(1);
        mActivity.runOnUiThread(() -> addSurfaceView(svReadyLatch));
        assertTrue("Failed to wait for SV to get created",
                svReadyLatch.await(WAIT_TIME_S, TimeUnit.SECONDS));
        new SurfaceControl.Transaction().reparent(sc, mSurfaceView.getSurfaceControl())
                .show(sc).apply();

        mInstrumentation.runOnMainSync(() -> {
            TestWindowlessWindowManager wwm = new TestWindowlessWindowManager(
                    mActivity.getResources().getConfiguration(), sc,
                    mSurfaceView.getHostToken());

            mScvh1 = new SurfaceControlViewHost(mActivity, mActivity.getDisplay(),
                    wwm, "requestFocusWithMultipleWindows");
            mScvh2 = new SurfaceControlViewHost(mActivity, mActivity.getDisplay(),
                    wwm, "requestFocusWithMultipleWindows");


            mView1.setBackgroundColor(Color.RED);
            mView2.setBackgroundColor(Color.BLUE);

            WindowManager.LayoutParams lp1 = new WindowManager.LayoutParams(200, 200,
                    TYPE_APPLICATION, 0, PixelFormat.OPAQUE);
            WindowManager.LayoutParams lp2 = new WindowManager.LayoutParams(100, 100,
                    TYPE_APPLICATION, 0, PixelFormat.OPAQUE);
            mScvh1.setView(mView1, lp1);
            mScvh2.setView(mView2, lp2);
        });

        boolean wasVisible = waitForWindowVisible(mView1);
        if (!wasVisible) {
            dumpWindowsOnScreen(TAG, "requestFocusWithMultipleWindows");
        }
        assertTrue("Failed to wait for view1", wasVisible);

        wasVisible = waitForWindowVisible(mView2);
        if (!wasVisible) {
            dumpWindowsOnScreen(TAG, "requestFocusWithMultipleWindows-not visible");
        }
        assertTrue("Failed to wait for view2", wasVisible);

        IWindow window = IWindow.Stub.asInterface(mSurfaceView.getWindowToken());

        WindowManagerGlobal.getWindowSession().grantEmbeddedWindowFocus(window,
                mScvh1.getInputTransferToken(), true);

        boolean gainedFocus = waitForWindowFocus(mView1, true);
        if (!gainedFocus) {
            dumpWindowsOnScreen(TAG, "requestFocusWithMultipleWindows-view1 not focus");
        }
        assertTrue("Failed to gain focus for view1", gainedFocus);

        WindowManagerGlobal.getWindowSession().grantEmbeddedWindowFocus(window,
                mScvh2.getInputTransferToken(), true);

        gainedFocus = waitForWindowFocus(mView2, true);
        if (!gainedFocus) {
            dumpWindowsOnScreen(TAG, "requestFocusWithMultipleWindows-view2 not focus");
        }
        assertTrue("Failed to gain focus for view2", gainedFocus);
    }

    private static class TestWindowlessWindowManager extends WindowlessWindowManager {
        private final SurfaceControl mRoot;

        TestWindowlessWindowManager(Configuration c, SurfaceControl rootSurface,
                IBinder hostInputToken) {
            super(c, rootSurface, hostInputToken);
            mRoot = rootSurface;
        }

        @Override
        protected SurfaceControl getParentSurface(IWindow window,
                WindowManager.LayoutParams attrs) {
            return mRoot;
        }
    }

    private void addSurfaceView(CountDownLatch svReadyLatch) {
        final FrameLayout content = mActivity.getParentLayout();
        mSurfaceView = new SurfaceView(mActivity);
        mSurfaceView.setZOrderOnTop(true);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(500, 500,
                Gravity.LEFT | Gravity.TOP);
        mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                svReadyLatch.countDown();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width,
                    int height) {

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {

            }
        });
        content.addView(mSurfaceView, lp);
    }
}

