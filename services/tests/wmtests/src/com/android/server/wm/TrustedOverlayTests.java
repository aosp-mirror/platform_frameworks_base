/*
 * Copyright 2023 The Android Open Source Project
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

import static android.view.InputWindowHandle.USE_SURFACE_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.server.wm.BuildUtils;
import android.server.wm.CtsWindowInfoUtils;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Presubmit
public class TrustedOverlayTests {
    private static final String TAG = "TrustedOverlayTests";
    private static final long TIMEOUT_S = 5L * BuildUtils.HW_TIMEOUT_MULTIPLIER;

    @Rule
    public TestName mName = new TestName();

    private final ActivityTestRule<Activity> mActivityRule = new ActivityTestRule<>(
            Activity.class);

    private Instrumentation mInstrumentation;
    private Activity mActivity;

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mActivity = mActivityRule.launchActivity(null);
    }

    @Test
    public void setTrustedOverlayInputWindow() throws InterruptedException {
        assumeFalse(USE_SURFACE_TRUSTED_OVERLAY);
        testTrustedOverlayChildHelper(false);
    }

    @Test
    public void setTrustedOverlayChildLayer() throws InterruptedException {
        assumeTrue(USE_SURFACE_TRUSTED_OVERLAY);
        testTrustedOverlayChildHelper(true);
    }

    private void testTrustedOverlayChildHelper(boolean expectTrusted) throws InterruptedException {
        IBinder[] tokens = new IBinder[2];
        CountDownLatch hostTokenReady = new CountDownLatch(1);
        mInstrumentation.runOnMainSync(() -> {
            mActivity.getWindow().addPrivateFlags(PRIVATE_FLAG_TRUSTED_OVERLAY);
            View rootView = mActivity.getWindow().getDecorView();
            if (rootView.isAttachedToWindow()) {
                tokens[0] = rootView.getWindowToken();
                hostTokenReady.countDown();
            } else {
                rootView.getViewTreeObserver().addOnWindowAttachListener(
                        new ViewTreeObserver.OnWindowAttachListener() {
                            @Override
                            public void onWindowAttached() {
                                tokens[0] = rootView.getWindowToken();
                                hostTokenReady.countDown();
                            }

                            @Override
                            public void onWindowDetached() {
                            }
                        });
            }
        });

        assertTrue("Failed to wait for host to get added",
                hostTokenReady.await(TIMEOUT_S, TimeUnit.SECONDS));

        mInstrumentation.runOnMainSync(() -> {
            WindowManager wm = mActivity.getSystemService(WindowManager.class);

            View childView = new View(mActivity) {
                @Override
                protected void onAttachedToWindow() {
                    super.onAttachedToWindow();
                    tokens[1] = getWindowToken();
                }
            };
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.token = tokens[0];
            params.type = TYPE_APPLICATION_PANEL;
            wm.addView(childView, params);
        });

        boolean[] foundTrusted = new boolean[2];

        CtsWindowInfoUtils.waitForWindowInfos(
                windowInfos -> {
                    for (var windowInfo : windowInfos) {
                        if (windowInfo.windowToken == tokens[0]
                                && windowInfo.isTrustedOverlay) {
                            foundTrusted[0] = true;
                        } else if (windowInfo.windowToken == tokens[1]
                                && windowInfo.isTrustedOverlay) {
                            foundTrusted[1] = true;
                        }
                    }
                    return foundTrusted[0] && foundTrusted[1];
                }, TIMEOUT_S, TimeUnit.SECONDS);

        if (!foundTrusted[0] || !foundTrusted[1]) {
            CtsWindowInfoUtils.dumpWindowsOnScreen(TAG, mName.getMethodName());
        }

        assertEquals("Failed to find parent window or was not marked trusted", expectTrusted,
                foundTrusted[0]);
        assertEquals("Failed to find child window or was not marked trusted", expectTrusted,
                foundTrusted[1]);
    }
}
