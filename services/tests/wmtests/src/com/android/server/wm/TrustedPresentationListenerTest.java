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

import static android.server.wm.ActivityManagerTestBase.createFullscreenActivityScenarioRule;
import static android.server.wm.BuildUtils.HW_TIMEOUT_MULTIPLIER;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.server.wm.CtsWindowInfoUtils;
import android.util.AndroidRuntimeException;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.window.TrustedPresentationThresholds;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.server.wm.utils.CommonUtils;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * TODO (b/287076178): Move these tests to
 * {@link android.view.surfacecontrol.cts.TrustedPresentationListenerTest} when API is made public
 */
@Presubmit
public class TrustedPresentationListenerTest {
    private static final String TAG = "TrustedPresentationListenerTest";
    private static final int STABILITY_REQUIREMENT_MS = 500;
    private static final long WAIT_TIME_MS = HW_TIMEOUT_MULTIPLIER * 2000L;

    private static final float FRACTION_VISIBLE = 0.1f;

    private final List<Boolean> mResults = Collections.synchronizedList(new ArrayList<>());
    private CountDownLatch mReceivedResults = new CountDownLatch(1);

    private TrustedPresentationThresholds mThresholds = new TrustedPresentationThresholds(
            1 /* minAlpha */, FRACTION_VISIBLE, STABILITY_REQUIREMENT_MS);

    @Rule
    public TestName mName = new TestName();

    @Rule
    public ActivityScenarioRule<TestActivity> mActivityRule = createFullscreenActivityScenarioRule(
            TestActivity.class);

    private TestActivity mActivity;

    private SurfaceControlViewHost.SurfacePackage mSurfacePackage = null;

    @Before
    public void setup() {
        mActivityRule.getScenario().onActivity(activity -> mActivity = activity);
        mDefaultListener = new Listener(mReceivedResults);
    }

    @After
    public void tearDown() {
        if (mSurfacePackage != null) {
            new SurfaceControl.Transaction().remove(mSurfacePackage.getSurfaceControl()).apply(
                    true);
            mSurfacePackage.release();
        }
        CommonUtils.waitUntilActivityRemoved(mActivity);

    }

    private class Listener implements Consumer<Boolean> {
        final CountDownLatch mLatch;

        Listener(CountDownLatch latch) {
            mLatch = latch;
        }

        @Override
        public void accept(Boolean inTrustedPresentationState) {
            Log.d(TAG, "onTrustedPresentationChanged " + inTrustedPresentationState);
            mResults.add(inTrustedPresentationState);
            mLatch.countDown();
        }
    }

    private Consumer<Boolean> mDefaultListener;

    @Test
    public void testAddTrustedPresentationListenerOnWindow() {
        WindowManager windowManager = mActivity.getSystemService(WindowManager.class);
        windowManager.registerTrustedPresentationListener(
                mActivity.getWindow().getDecorView().getWindowToken(), mThresholds, Runnable::run,
                mDefaultListener);
        assertResults(List.of(true));
    }

    @Test
    public void testRemoveTrustedPresentationListenerOnWindow() throws InterruptedException {
        WindowManager windowManager = mActivity.getSystemService(WindowManager.class);
        windowManager.registerTrustedPresentationListener(
                mActivity.getWindow().getDecorView().getWindowToken(), mThresholds, Runnable::run,
                mDefaultListener);
        assertResults(List.of(true));
        // reset the latch
        mReceivedResults = new CountDownLatch(1);

        windowManager.unregisterTrustedPresentationListener(mDefaultListener);
        mReceivedResults.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS);
        // Ensure we waited the full time and never received a notify on the result from the
        // callback.
        assertEquals("Should never have received a callback", mReceivedResults.getCount(), 1);
        // results shouldn't have changed.
        assertEquals(mResults, List.of(true));
    }

    @Test
    public void testRemovingUnknownListenerIsANoop() {
        WindowManager windowManager = mActivity.getSystemService(WindowManager.class);
        assertNotNull(windowManager);
        windowManager.unregisterTrustedPresentationListener(mDefaultListener);
    }

    @Test
    public void testAddDuplicateListenerThrowsException() {
        WindowManager windowManager = mActivity.getSystemService(WindowManager.class);
        assertNotNull(windowManager);
        windowManager.registerTrustedPresentationListener(
                mActivity.getWindow().getDecorView().getWindowToken(), mThresholds,
                Runnable::run, mDefaultListener);
        assertThrows(AndroidRuntimeException.class,
                () -> windowManager.registerTrustedPresentationListener(
                        mActivity.getWindow().getDecorView().getWindowToken(), mThresholds,
                        Runnable::run, mDefaultListener));
    }

    @Test
    public void testAddDuplicateThresholds() {
        mReceivedResults = new CountDownLatch(2);
        mDefaultListener = new Listener(mReceivedResults);
        WindowManager windowManager = mActivity.getSystemService(WindowManager.class);
        windowManager.registerTrustedPresentationListener(
                mActivity.getWindow().getDecorView().getWindowToken(), mThresholds,
                Runnable::run, mDefaultListener);

        Consumer<Boolean> mNewListener = new Listener(mReceivedResults);

        windowManager.registerTrustedPresentationListener(
                mActivity.getWindow().getDecorView().getWindowToken(), mThresholds,
                Runnable::run, mNewListener);
        assertResults(List.of(true, true));
    }

    private void waitForViewAttach(View view) {
        final CountDownLatch viewAttached = new CountDownLatch(1);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                viewAttached.countDown();
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {

            }
        });
        try {
            viewAttached.await(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (!wait(viewAttached, 2000 /* waitTimeMs */)) {
            fail("Couldn't attach view=" + view);
        }
    }

    @Test
    public void testAddListenerToScvh() {
        WindowManager windowManager = mActivity.getSystemService(WindowManager.class);

        var embeddedView = new View(mActivity);
        mActivityRule.getScenario().onActivity(activity -> {
            var attachedSurfaceControl =
                    mActivity.getWindow().getDecorView().getRootSurfaceControl();
            var scvh = new SurfaceControlViewHost(mActivity, mActivity.getDisplay(),
                    attachedSurfaceControl.getHostToken());
            mSurfacePackage = scvh.getSurfacePackage();
            scvh.setView(embeddedView, mActivity.getWindow().getDecorView().getWidth(),
                    mActivity.getWindow().getDecorView().getHeight());
            attachedSurfaceControl.buildReparentTransaction(
                    mSurfacePackage.getSurfaceControl());
        });

        waitForViewAttach(embeddedView);
        windowManager.registerTrustedPresentationListener(embeddedView.getWindowToken(),
                mThresholds,
                Runnable::run, mDefaultListener);

        assertResults(List.of(true));
    }

    private boolean wait(CountDownLatch latch, long waitTimeMs) {
        while (true) {
            long now = SystemClock.uptimeMillis();
            try {
                return latch.await(waitTimeMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                long elapsedTime = SystemClock.uptimeMillis() - now;
                waitTimeMs = Math.max(0, waitTimeMs - elapsedTime);
            }
        }

    }

    @GuardedBy("mResultsLock")
    private void assertResults(List<Boolean> results) {
        if (!wait(mReceivedResults, WAIT_TIME_MS)) {
            try {
                CtsWindowInfoUtils.dumpWindowsOnScreen(TAG, "test " + mName.getMethodName());
            } catch (InterruptedException e) {
                Log.d(TAG, "Couldn't dump windows", e);
            }
            Assert.fail("Timed out waiting for results mReceivedResults.count="
                    + mReceivedResults.getCount() + "mReceivedResults=" + mReceivedResults);
        }

        // Make sure we received the results
        assertEquals(results.toArray(), mResults.toArray());
    }

    public static class TestActivity extends Activity {
    }
}
