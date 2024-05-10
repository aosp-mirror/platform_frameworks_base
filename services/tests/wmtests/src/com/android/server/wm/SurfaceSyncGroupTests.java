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

import static android.os.Build.HW_TIMEOUT_MULTIPLIER;
import static android.window.SurfaceSyncGroup.TRANSACTION_READY_TIMEOUT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.cts.surfacevalidator.BitmapPixelChecker;
import android.widget.FrameLayout;
import android.window.SurfaceSyncGroup;

import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.android.server.wm.utils.CommonUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Presubmit
public class SurfaceSyncGroupTests {
    private static final String TAG = "SurfaceSyncGroupTests";

    private static final long TIMEOUT_S = HW_TIMEOUT_MULTIPLIER * 5L;

    @Rule
    public ActivityTestRule<TestActivity> mActivityRule = new ActivityTestRule<>(
            TestActivity.class);

    private TestActivity mActivity;

    Instrumentation mInstrumentation;

    private final HandlerThread mHandlerThread = new HandlerThread("applyTransaction");
    private Handler mHandler;

    @Before
    public void setup() {
        mActivity = mActivityRule.getActivity();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mHandlerThread.start();
        mHandler = mHandlerThread.getThreadHandler();
    }

    @After
    public void tearDown() {
        mHandlerThread.quitSafely();
        CommonUtils.waitUntilActivityRemoved(mActivity);
    }

    @Test
    public void testOverlappingSyncsEnsureOrder_WhenTimeout() throws InterruptedException {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.format = PixelFormat.TRANSLUCENT;

        CountDownLatch secondDrawCompleteLatch = new CountDownLatch(1);
        CountDownLatch bothSyncGroupsComplete = new CountDownLatch(2);
        final SurfaceSyncGroup firstSsg = new SurfaceSyncGroup(TAG + "-first");
        final SurfaceSyncGroup secondSsg = new SurfaceSyncGroup(TAG + "-second");
        final SurfaceSyncGroup infiniteSsg = new SurfaceSyncGroup(TAG + "-infinite");

        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.addTransactionCommittedListener(Runnable::run, bothSyncGroupsComplete::countDown);
        firstSsg.addTransaction(t);

        View backgroundView = mActivity.getBackgroundView();
        firstSsg.add(backgroundView.getRootSurfaceControl(),
                () -> mActivity.runOnUiThread(() -> backgroundView.setBackgroundColor(Color.RED)));

        addSecondSyncGroup(secondSsg, secondDrawCompleteLatch, bothSyncGroupsComplete);

        assertTrue("Failed to draw two frames",
                secondDrawCompleteLatch.await(TIMEOUT_S, TimeUnit.SECONDS));

        mHandler.postDelayed(() -> {
            // Don't add a markSyncReady for the first sync group until after it's added to another
            // SSG to ensure the timeout is longer than the second frame's timeout. The infinite SSG
            // will never complete to ensure it reaches the timeout, but only after the second SSG
            // had a chance to reach its timeout.
            infiniteSsg.add(firstSsg, null /* runnable */);
            firstSsg.markSyncReady();
        }, 200);

        assertTrue("Failed to wait for both SurfaceSyncGroups to apply",
                bothSyncGroupsComplete.await(TIMEOUT_S, TimeUnit.SECONDS));

        validateScreenshot();
    }

    @Test
    public void testOverlappingSyncsEnsureOrder_WhileHoldingTransaction()
            throws InterruptedException {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.format = PixelFormat.TRANSLUCENT;

        CountDownLatch secondDrawCompleteLatch = new CountDownLatch(1);
        CountDownLatch bothSyncGroupsComplete = new CountDownLatch(2);

        final SurfaceSyncGroup firstSsg = new SurfaceSyncGroup(TAG + "-first",
                transaction -> mHandler.postDelayed(() -> {
                    try {
                        assertTrue("Failed to draw two frames",
                                secondDrawCompleteLatch.await(TIMEOUT_S, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    transaction.apply();
                }, TRANSACTION_READY_TIMEOUT + 200));
        final SurfaceSyncGroup secondSsg = new SurfaceSyncGroup(TAG + "-second");

        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.addTransactionCommittedListener(Runnable::run, bothSyncGroupsComplete::countDown);
        firstSsg.addTransaction(t);

        View backgroundView = mActivity.getBackgroundView();
        firstSsg.add(backgroundView.getRootSurfaceControl(),
                () -> mActivity.runOnUiThread(() -> backgroundView.setBackgroundColor(Color.RED)));
        firstSsg.markSyncReady();

        addSecondSyncGroup(secondSsg, secondDrawCompleteLatch, bothSyncGroupsComplete);

        assertTrue("Failed to wait for both SurfaceSyncGroups to apply",
                bothSyncGroupsComplete.await(TIMEOUT_S, TimeUnit.SECONDS));

        validateScreenshot();
    }

    private void addSecondSyncGroup(SurfaceSyncGroup surfaceSyncGroup,
            CountDownLatch waitForSecondDraw, CountDownLatch bothSyncGroupsComplete) {
        View backgroundView = mActivity.getBackgroundView();
        ViewTreeObserver viewTreeObserver = backgroundView.getViewTreeObserver();
        viewTreeObserver.registerFrameCommitCallback(() -> mHandler.post(() -> {
            surfaceSyncGroup.add(backgroundView.getRootSurfaceControl(),
                    () -> mActivity.runOnUiThread(
                            () -> backgroundView.setBackgroundColor(Color.BLUE)));

            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.addTransactionCommittedListener(Runnable::run, bothSyncGroupsComplete::countDown);
            surfaceSyncGroup.addTransaction(t);
            surfaceSyncGroup.markSyncReady();
            viewTreeObserver.registerFrameCommitCallback(waitForSecondDraw::countDown);
        }));
    }

    private void validateScreenshot() {
        Bitmap screenshot = mInstrumentation.getUiAutomation().takeScreenshot(
                mActivity.getWindow());
        assertNotNull("Failed to generate a screenshot", screenshot);
        Bitmap swBitmap = screenshot.copy(Bitmap.Config.ARGB_8888, false);
        screenshot.recycle();

        BitmapPixelChecker pixelChecker = new BitmapPixelChecker(Color.BLUE);
        int halfWidth = swBitmap.getWidth() / 2;
        int halfHeight = swBitmap.getHeight() / 2;
        // We don't need to check all the pixels since we only care that at least some of them are
        // blue. If the buffers were submitted out of order, all the pixels will be red.
        Rect bounds = new Rect(halfWidth, halfHeight, halfWidth + 10, halfHeight + 10);
        int numMatchingPixels = pixelChecker.getNumMatchingPixels(swBitmap, bounds);
        assertEquals("Expected 100 received " + numMatchingPixels + " matching pixels", 100,
                numMatchingPixels);

        swBitmap.recycle();
    }

    public static class TestActivity extends Activity {
        private ViewGroup mParentView;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);


            mParentView = new FrameLayout(this);
            setContentView(mParentView);

            KeyguardManager km = getSystemService(KeyguardManager.class);
            km.requestDismissKeyguard(this, null);
        }

        public View getBackgroundView() {
            return mParentView;
        }
    }
}
