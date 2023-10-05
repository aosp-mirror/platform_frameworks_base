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

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
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
import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.android.server.wm.utils.CommonUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;

@Presubmit
@SmallTest
@RunWith(WindowTestRunner.class)
public class SurfaceControlViewHostTests {
    private static final String TAG = "SurfaceControlViewHostTests";

    private final ActivityTestRule<TestActivity> mActivityRule = new ActivityTestRule<>(
            TestActivity.class);
    private Instrumentation mInstrumentation;
    private TestActivity mActivity;

    private View mView1;
    private View mView2;
    private SurfaceControlViewHost mScvh1;
    private SurfaceControlViewHost mScvh2;

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

        mInstrumentation.runOnMainSync(() -> {
            try {
                mActivity.attachToSurfaceView(sc);
            } catch (InterruptedException e) {
            }

            TestWindowlessWindowManager wwm = new TestWindowlessWindowManager(
                    mActivity.getResources().getConfiguration(), sc,
                    mActivity.mSurfaceView.getHostToken());

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
            dumpWindowsOnScreen(TAG, "requestFocusWithMultipleWindows");
        }
        assertTrue("Failed to wait for view2", wasVisible);

        IWindow window = IWindow.Stub.asInterface(mActivity.mSurfaceView.getWindowToken());

        WindowManagerGlobal.getWindowSession().grantEmbeddedWindowFocus(window,
                mScvh1.getInputTransferToken(), true);
        assertTrue("Failed to gain focus for view1", waitForWindowFocus(mView1, true));

        WindowManagerGlobal.getWindowSession().grantEmbeddedWindowFocus(window,
                mScvh2.getInputTransferToken(), true);
        assertTrue("Failed to gain focus for view2", waitForWindowFocus(mView2, true));
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

    public static class TestActivity extends Activity implements SurfaceHolder.Callback {
        private SurfaceView mSurfaceView;
        private final CountDownLatch mSvReadyLatch = new CountDownLatch(1);

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            final FrameLayout content = new FrameLayout(this);
            mSurfaceView = new SurfaceView(this);
            mSurfaceView.setBackgroundColor(Color.BLACK);
            mSurfaceView.setZOrderOnTop(true);
            final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(500, 500,
                    Gravity.LEFT | Gravity.TOP);
            content.addView(mSurfaceView, lp);
            setContentView(content);
            mSurfaceView.getHolder().addCallback(this);
        }

        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            mSvReadyLatch.countDown();
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width,
                int height) {
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        }

        public void attachToSurfaceView(SurfaceControl sc) throws InterruptedException {
            mSvReadyLatch.await();
            new SurfaceControl.Transaction().reparent(sc, mSurfaceView.getSurfaceControl())
                    .show(sc).apply();
        }
    }
}

