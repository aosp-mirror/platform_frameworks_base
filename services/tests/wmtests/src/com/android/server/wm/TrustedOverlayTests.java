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

import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.server.wm.BuildUtils;
import android.server.wm.CtsWindowInfoUtils;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.window.flags.Flags;

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
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule
    public TestName mName = new TestName();

    @Rule
    public final ActivityScenarioRule<Activity> mActivityRule = new ActivityScenarioRule<>(
            Activity.class);

    private Instrumentation mInstrumentation;
    private Activity mActivity;

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mActivityRule.getScenario().onActivity(activity -> {
            mActivity = activity;
        });
    }

    @RequiresFlagsDisabled(Flags.FLAG_SURFACE_TRUSTED_OVERLAY)
    @Test
    public void setTrustedOverlayInputWindow() throws InterruptedException {
        testTrustedOverlayChildHelper(false);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SURFACE_TRUSTED_OVERLAY)
    public void setTrustedOverlayChildLayer() throws InterruptedException {
        testTrustedOverlayChildHelper(true);
    }

    /**
     * b/300659960 where setting spy window and trusted overlay were not happening in the same
     * transaction causing the system to crash. This ensures there are no synchronization issues
     * setting both spy window and trusted overlay.
     */
    @Test
    public void setSpyWindowDoesntCrash() throws InterruptedException {
        IBinder[] tokens = new IBinder[1];
        CountDownLatch hostTokenReady = new CountDownLatch(1);
        mInstrumentation.runOnMainSync(() -> {
            WindowManager.LayoutParams params = mActivity.getWindow().getAttributes();
            params.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_SPY;
            params.privateFlags |= PRIVATE_FLAG_TRUSTED_OVERLAY;
            mActivity.getWindow().setAttributes(params);

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

        boolean[] foundTrusted = new boolean[1];
        CtsWindowInfoUtils.waitForWindowInfos(
                windowInfos -> {
                    for (var windowInfo : windowInfos) {
                        if (windowInfo.windowToken == tokens[0] && windowInfo.isTrustedOverlay) {
                            foundTrusted[0] = true;
                            return true;
                        }
                    }
                    return false;
                }, TIMEOUT_S, TimeUnit.SECONDS);

        if (!foundTrusted[0]) {
            CtsWindowInfoUtils.dumpWindowsOnScreen(TAG, mName.getMethodName());
        }

        assertTrue("Failed to find window or was not marked trusted", foundTrusted[0]);
    }

    private void testTrustedOverlayChildHelper(boolean expectedTrustedChild)
            throws InterruptedException {
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

        assertTrue("Failed to find parent window or was not marked trusted", foundTrusted[0]);
        assertEquals("Failed to find child window or was not marked trusted", expectedTrustedChild,
                foundTrusted[1]);
    }
}
