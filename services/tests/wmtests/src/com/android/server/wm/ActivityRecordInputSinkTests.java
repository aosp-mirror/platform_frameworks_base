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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.window.WindowInfosListenerForTest;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.window.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Internal variant of {@link android.server.wm.window.ActivityRecordInputSinkTests}.
 */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class ActivityRecordInputSinkTests {
    private static final String OVERLAY_APP_PKG = "com.android.server.wm.overlay_app";
    private static final String OVERLAY_ACTIVITY = OVERLAY_APP_PKG + "/.OverlayApp";
    private static final String KEY_DISABLE_INPUT_SINK = "disableInputSink";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule
    public final ActivityScenarioRule<TestActivity> mActivityRule =
            new ActivityScenarioRule<>(TestActivity.class);

    private UiAutomation mUiAutomation;

    @Before
    public void setUp() {
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    @After
    public void tearDown() {
        ActivityManager am =
                InstrumentationRegistry.getInstrumentation().getContext().getSystemService(
                        ActivityManager.class);
        mUiAutomation.adoptShellPermissionIdentity();
        try {
            am.forceStopPackage(OVERLAY_APP_PKG);
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    @Test
    public void testSimpleButtonPress() {
        injectTapOnButton();

        mActivityRule.getScenario().onActivity(a -> {
            assertEquals(1, a.mNumClicked);
        });
    }

    @Test
    public void testSimpleButtonPress_withOverlay() throws InterruptedException {
        startOverlayApp(false);
        waitForOverlayApp();

        injectTapOnButton();

        mActivityRule.getScenario().onActivity(a -> {
            assertEquals(0, a.mNumClicked);
        });
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ALLOW_DISABLE_ACTIVITY_RECORD_INPUT_SINK)
    public void testSimpleButtonPress_withOverlayDisableInputSink() throws InterruptedException {
        startOverlayApp(true);
        waitForOverlayApp();

        injectTapOnButton();

        mActivityRule.getScenario().onActivity(a -> {
            assertEquals(1, a.mNumClicked);
        });
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ALLOW_DISABLE_ACTIVITY_RECORD_INPUT_SINK)
    public void testSimpleButtonPress_withOverlayDisableInputSink_flagDisabled()
            throws InterruptedException {
        startOverlayApp(true);
        waitForOverlayApp();

        injectTapOnButton();

        mActivityRule.getScenario().onActivity(a -> {
            assertEquals(0, a.mNumClicked);
        });
    }

    private void startOverlayApp(boolean disableInputSink) {
        String launchCommand = "am start -n " + OVERLAY_ACTIVITY;
        if (disableInputSink) {
            launchCommand += " --ez " + KEY_DISABLE_INPUT_SINK + " true";
        }

        mUiAutomation.adoptShellPermissionIdentity();
        try {
            mUiAutomation.executeShellCommand(launchCommand);
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    private void waitForOverlayApp() throws InterruptedException {
        final var listenerHost = new WindowInfosListenerForTest();
        final var latch = new CountDownLatch(1);
        final Consumer<List<WindowInfosListenerForTest.WindowInfo>> listener = windowInfos -> {
            final boolean inputSinkReady = windowInfos.stream().anyMatch(info ->
                    info.isVisible
                            && info.name.contains("ActivityRecordInputSink " + OVERLAY_ACTIVITY));
            if (inputSinkReady) {
                latch.countDown();
            }
        };

        listenerHost.addWindowInfosListener(listener);
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } finally {
            listenerHost.removeWindowInfosListener(listener);
        }
    }

    private void injectTapOnButton() {
        Rect buttonBounds = new Rect();
        mActivityRule.getScenario().onActivity(a -> {
            a.mButton.getBoundsOnScreen(buttonBounds);
        });
        final int x = buttonBounds.centerX();
        final int y = buttonBounds.centerY();

        MotionEvent down = MotionEvent.obtain(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x, y, 0);
        mUiAutomation.injectInputEvent(down, true);

        SystemClock.sleep(10);

        MotionEvent up = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, x, y, 0);
        mUiAutomation.injectInputEvent(up, true);

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    public static class TestActivity extends Activity {
        int mNumClicked = 0;
        Button mButton;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mButton = new Button(this);
            mButton.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(mButton);
            mButton.setOnClickListener(v -> mNumClicked++);
        }
    }
}
