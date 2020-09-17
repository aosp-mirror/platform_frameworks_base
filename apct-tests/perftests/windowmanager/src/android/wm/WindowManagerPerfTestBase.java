/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.wm;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.perftests.utils.PerfTestActivity;
import android.provider.Settings;

import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class WindowManagerPerfTestBase {
    static final UiAutomation sUiAutomation = getInstrumentation().getUiAutomation();
    static final long NANOS_PER_S = 1000L * 1000 * 1000;
    static final long TIME_1_S_IN_NS = 1 * NANOS_PER_S;
    static final long TIME_5_S_IN_NS = 5 * NANOS_PER_S;

    /**
     * The out directory matching the directory-keys of collector in AndroidTest.xml. The directory
     * is in /data because while enabling method profling of system server, it cannot write the
     * trace to external storage.
     */
    static final File BASE_OUT_PATH = new File("/data/local/CorePerfTests");

    private static int sOriginalStayOnWhilePluggedIn;

    @BeforeClass
    public static void setUpOnce() {
        final Context context = getInstrumentation().getContext();
        final int stayOnWhilePluggedIn = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);
        sOriginalStayOnWhilePluggedIn = -1;
        if (stayOnWhilePluggedIn != BatteryManager.BATTERY_PLUGGED_ANY) {
            sOriginalStayOnWhilePluggedIn = stayOnWhilePluggedIn;
            // Keep the device awake during testing.
            setStayOnWhilePluggedIn(BatteryManager.BATTERY_PLUGGED_ANY);
        }

        if (!BASE_OUT_PATH.exists()) {
            executeShellCommand("mkdir -p " + BASE_OUT_PATH);
        }
        if (!context.getSystemService(PowerManager.class).isInteractive()
                || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            executeShellCommand("input keyevent KEYCODE_WAKEUP");
            executeShellCommand("wm dismiss-keyguard");
        }
        context.startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @AfterClass
    public static void tearDownOnce() {
        if (sOriginalStayOnWhilePluggedIn != -1) {
            setStayOnWhilePluggedIn(sOriginalStayOnWhilePluggedIn);
        }
    }

    private static void setStayOnWhilePluggedIn(int value) {
        executeShellCommand(String.format("settings put global %s %d",
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, value));
    }

    /**
     * Executes shell command with reading the output. It may also used to block until the current
     * command is completed.
     */
    static ByteArrayOutputStream executeShellCommand(String command) {
        final ParcelFileDescriptor pfd = sUiAutomation.executeShellCommand(command);
        final byte[] buf = new byte[512];
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int bytesRead;
        try (FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            while ((bytesRead = fis.read(buf)) != -1) {
                bytes.write(buf, 0, bytesRead);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bytes;
    }

    /** Starts method tracing on system server. */
    void startProfiling(String subPath) {
        executeShellCommand("am profile start system " + new File(BASE_OUT_PATH, subPath));
    }

    void stopProfiling() {
        executeShellCommand("am profile stop system");
    }

    /**
     * Provides an activity that keeps screen on and is able to wait for a stable lifecycle stage.
     */
    static class PerfTestActivityRule extends ActivityTestRule<PerfTestActivity> {
        private final Intent mStartIntent =
                new Intent(getInstrumentation().getTargetContext(), PerfTestActivity.class);
        private final LifecycleListener mLifecycleListener = new LifecycleListener();

        PerfTestActivityRule() {
            this(false /* launchActivity */);
        }

        PerfTestActivityRule(boolean launchActivity) {
            super(PerfTestActivity.class, false /* initialTouchMode */, launchActivity);
        }

        @Override
        public Statement apply(Statement base, Description description) {
            final Statement wrappedStatement = new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    ActivityLifecycleMonitorRegistry.getInstance()
                            .addLifecycleCallback(mLifecycleListener);
                    base.evaluate();
                    ActivityLifecycleMonitorRegistry.getInstance()
                            .removeLifecycleCallback(mLifecycleListener);
                }
            };
            return super.apply(wrappedStatement, description);
        }

        @Override
        protected Intent getActivityIntent() {
            return mStartIntent;
        }

        @Override
        public PerfTestActivity launchActivity(Intent intent) {
            final PerfTestActivity activity = super.launchActivity(intent);
            mLifecycleListener.setTargetActivity(activity);
            return activity;
        }

        PerfTestActivity launchActivity() {
            return launchActivity(mStartIntent);
        }

        void waitForIdleSync(Stage state) {
            mLifecycleListener.waitForIdleSync(state);
        }
    }

    static class LifecycleListener implements ActivityLifecycleCallback {
        private Activity mTargetActivity;
        private Stage mWaitingStage;
        private Stage mReceivedStage;

        void setTargetActivity(Activity activity) {
            mTargetActivity = activity;
            mReceivedStage = mWaitingStage = null;
        }

        void waitForIdleSync(Stage stage) {
            synchronized (this) {
                if (stage != mReceivedStage) {
                    mWaitingStage = stage;
                    try {
                        wait(TimeUnit.NANOSECONDS.toMillis(TIME_5_S_IN_NS));
                    } catch (InterruptedException impossible) { }
                }
                mWaitingStage = mReceivedStage = null;
            }
            getInstrumentation().waitForIdleSync();
        }

        @Override
        public void onActivityLifecycleChanged(Activity activity, Stage stage) {
            if (mTargetActivity != activity) {
                return;
            }

            synchronized (this) {
                mReceivedStage = stage;
                if (mWaitingStage == mReceivedStage) {
                    notifyAll();
                }
            }
        }
    }
}
