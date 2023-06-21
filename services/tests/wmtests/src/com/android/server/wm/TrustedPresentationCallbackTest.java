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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.platform.test.annotations.Presubmit;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceControl.TrustedPresentationThresholds;

import androidx.test.ext.junit.rules.ActivityScenarioRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * TODO (b/287076178): Move these tests to
 * {@link android.view.surfacecontrol.cts.TrustedPresentationCallbackTest} when API is made public
 */
@Presubmit
public class TrustedPresentationCallbackTest {
    private static final String TAG = "TrustedPresentationCallbackTest";
    private static final int STABILITY_REQUIREMENT_MS = 500;
    private static final long WAIT_TIME_MS = HW_TIMEOUT_MULTIPLIER * 2000L;

    private static final float FRACTION_VISIBLE = 0.1f;

    @Rule
    public ActivityScenarioRule<TestActivity> mActivityRule = createFullscreenActivityScenarioRule(
            TestActivity.class);

    private TestActivity mActivity;

    @Before
    public void setup() {
        mActivityRule.getScenario().onActivity(activity -> mActivity = activity);
    }

    @Test
    public void testAddTrustedPresentationListenerOnWindow() throws InterruptedException {
        boolean[] results = new boolean[1];
        CountDownLatch receivedResults = new CountDownLatch(1);
        TrustedPresentationThresholds thresholds = new TrustedPresentationThresholds(
                1 /* minAlpha */, FRACTION_VISIBLE, STABILITY_REQUIREMENT_MS);
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        mActivity.getWindow().getRootSurfaceControl().addTrustedPresentationCallback(t, thresholds,
                Runnable::run, inTrustedPresentationState -> {
                    Log.d(TAG, "onTrustedPresentationChanged " + inTrustedPresentationState);
                    results[0] = inTrustedPresentationState;
                    receivedResults.countDown();
                });
        t.apply();

        assertTrue("Timed out waiting for results",
                receivedResults.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS));
        assertTrue(results[0]);
    }

    @Test
    public void testRemoveTrustedPresentationListenerOnWindow() throws InterruptedException {
        final Object resultsLock = new Object();
        boolean[] results = new boolean[1];
        boolean[] receivedResults = new boolean[1];
        TrustedPresentationThresholds thresholds = new TrustedPresentationThresholds(
                1 /* minAlpha */, FRACTION_VISIBLE, STABILITY_REQUIREMENT_MS);
        Consumer<Boolean> trustedPresentationCallback = inTrustedPresentationState -> {
            synchronized (resultsLock) {
                results[0] = inTrustedPresentationState;
                receivedResults[0] = true;
                resultsLock.notify();
            }
        };
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        mActivity.getWindow().getRootSurfaceControl().addTrustedPresentationCallback(t, thresholds,
                Runnable::run, trustedPresentationCallback);
        t.apply();

        synchronized (resultsLock) {
            if (!receivedResults[0]) {
                resultsLock.wait(WAIT_TIME_MS);
            }
            // Make sure we received the results and not just timed out
            assertTrue("Timed out waiting for results", receivedResults[0]);
            assertTrue(results[0]);

            // reset the state
            receivedResults[0] = false;
        }

        mActivity.getWindow().getRootSurfaceControl().removeTrustedPresentationCallback(t,
                trustedPresentationCallback);
        t.apply();

        synchronized (resultsLock) {
            if (!receivedResults[0]) {
                resultsLock.wait(WAIT_TIME_MS);
            }
            // Ensure we waited the full time and never received a notify on the result from the
            // callback.
            assertFalse("Should never have received a callback", receivedResults[0]);
            // results shouldn't have changed.
            assertTrue(results[0]);
        }
    }

    public static class TestActivity extends Activity {
    }
}
