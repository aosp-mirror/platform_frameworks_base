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

package android.view.choreographertests;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.hardware.display.DisplayManager;
import android.support.test.uiautomator.UiDevice;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class AttachedChoreographerNativeTest {
    private static final String TAG = "AttachedChoreographerNativeTest";

    static {
        System.loadLibrary("choreographertests_jni");
    }

    private final CountDownLatch mSurfaceCreationCountDown = new CountDownLatch(1);
    private CountDownLatch mTestCompleteSignal;
    private long mChoreographerPtr;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private ActivityScenario<GraphicsActivity> mScenario;
    private int mInitialMatchContentFrameRate;
    private DisplayManager mDisplayManager;

    private static native long nativeSurfaceControl_getChoreographer(SurfaceControl surfaceControl);
    private native void nativeTestPostVsyncCallbackAtFrameRate(
            long choreographerPtr, float expectedFrameRate);

    @Before
    public void setup() throws Exception {
        mScenario = ActivityScenario.launch(GraphicsActivity.class);
        mScenario.moveToState(Lifecycle.State.CREATED);
        mScenario.onActivity(activity -> {
            mSurfaceView = activity.findViewById(R.id.surface);
            mSurfaceHolder = mSurfaceView.getHolder();
            mSurfaceHolder.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceChanged(
                        SurfaceHolder holder, int format, int width, int height) {}

                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    mSurfaceCreationCountDown.countDown();
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {}
            });
        });

        mScenario.moveToState(Lifecycle.State.RESUMED);
        UiDevice uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        uiDevice.wakeUp();
        uiDevice.executeShellCommand("wm dismiss-keyguard");

        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                android.Manifest.permission.LOG_COMPAT_CHANGE,
                android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                android.Manifest.permission.MODIFY_REFRESH_RATE_SWITCHING_TYPE,
                android.Manifest.permission.OVERRIDE_DISPLAY_MODE_REQUESTS,
                Manifest.permission.MANAGE_GAME_MODE);
        mScenario.onActivity(activity -> {
            mDisplayManager = activity.getSystemService(DisplayManager.class);
            mInitialMatchContentFrameRate =
                    toSwitchingType(mDisplayManager.getMatchContentFrameRateUserPreference());
            mDisplayManager.setRefreshRateSwitchingType(
                    DisplayManager.SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY);
            mDisplayManager.setShouldAlwaysRespectAppRequestedMode(true);
        });
    }

    @After
    public void tearDown() {
        mDisplayManager.setRefreshRateSwitchingType(mInitialMatchContentFrameRate);
        mDisplayManager.setShouldAlwaysRespectAppRequestedMode(false);
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void test_choreographer_callbacksForVariousFrameRates() {
        for (int divisor : new int[] {2, 3}) {
            mTestCompleteSignal = new CountDownLatch(1);
            mScenario.onActivity(activity -> {
                if (waitForCountDown(mSurfaceCreationCountDown, /* timeoutInSeconds= */ 1L)) {
                    fail("Unable to create surface within 1 Second");
                }

                SurfaceControl surfaceControl = mSurfaceView.getSurfaceControl();
                mChoreographerPtr = nativeSurfaceControl_getChoreographer(surfaceControl);
                Log.i(TAG, "mChoreographerPtr value " + mChoreographerPtr);

                float displayRefreshRate = activity.getDisplay().getMode().getRefreshRate();
                float expectedFrameRate = displayRefreshRate / divisor;

                SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
                transaction
                        .setFrameRate(surfaceControl, expectedFrameRate,
                                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                        .addTransactionCommittedListener(Runnable::run,
                                () -> {
                                    assertTrue(mChoreographerPtr != 0L);
                                    Log.i(TAG, "Testing frame rate of " + expectedFrameRate);
                                    nativeTestPostVsyncCallbackAtFrameRate(
                                            mChoreographerPtr, expectedFrameRate);
                                })
                        .apply();
            });
            // wait for the previous callbacks to finish before moving to the next divisor
            if (waitForCountDown(mTestCompleteSignal, /* timeoutInSeconds= */ 5L)) {
                fail("Test for divisor " + divisor + " not finished in 5 seconds");
            }
        }
    }

    /** Call from native to trigger test completion. */
    private void endTest() {
        Log.i(TAG, "Signal test completion!");
        mTestCompleteSignal.countDown();
    }

    private boolean waitForCountDown(CountDownLatch countDownLatch, long timeoutInSeconds) {
        try {
            return !countDownLatch.await(timeoutInSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            throw new AssertionError("Test interrupted", ex);
        }
    }

    private int toSwitchingType(int matchContentFrameRateUserPreference) {
        switch (matchContentFrameRateUserPreference) {
            case DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER:
                return DisplayManager.SWITCHING_TYPE_NONE;
            case DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY:
                return DisplayManager.SWITCHING_TYPE_WITHIN_GROUPS;
            case DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS:
                return DisplayManager.SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS;
            default:
                return -1;
        }
    }
}
