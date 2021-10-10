/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.perftests.utils;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.test.rule.ActivityTestRule;

import org.junit.After;
import org.junit.BeforeClass;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** The base class for window related performance tests. */
public class WindowPerfTestBase {
    public static final long NANOS_PER_S = 1000L * 1000 * 1000;
    public static final long TIME_1_S_IN_NS = 1 * NANOS_PER_S;

    static boolean sIsProfilingMethod;

    @BeforeClass
    public static void setUpOnce() {
        final Context context = getInstrumentation().getContext();

        if (!context.getSystemService(PowerManager.class).isInteractive()
                || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            executeShellCommand("input keyevent KEYCODE_WAKEUP");
            executeShellCommand("wm dismiss-keyguard");
        }
        context.startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @After
    public void tearDown() {
        // Make sure that profiling is stopped if test fails.
        if (sIsProfilingMethod) {
            stopProfiling();
        }
    }

    public static UiAutomation getUiAutomation() {
        return getInstrumentation().getUiAutomation();
    }

    public static void startAsyncAtrace(String tags) {
        getUiAutomation().executeShellCommand("atrace -b 32768 --async_start " + tags);
        // Avoid atrace isn't ready immediately.
        SystemClock.sleep(TimeUnit.NANOSECONDS.toMillis(TIME_1_S_IN_NS));
    }

    public static InputStream stopAsyncAtraceWithStream() {
        return new ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand("atrace --async_stop"));
    }

    /** Starts method tracing on system server. */
    public static void startProfiling(File basePath, String outFileName) {
        if (!basePath.exists()) {
            executeShellCommand("mkdir -p " + basePath);
        }
        final String samplingArg = WindowPerfRunPreconditionBase.sSamplingIntervalUs > 0
                ? ("--sampling " + WindowPerfRunPreconditionBase.sSamplingIntervalUs)
                : "";
        executeShellCommand("am profile start " + samplingArg + " system "
                + new File(basePath, outFileName));
        sIsProfilingMethod = true;
    }

    /** Stops method tracing of system server. */
    public static void stopProfiling() {
        executeShellCommand("am profile stop system");
        sIsProfilingMethod = false;
    }

    public static boolean sIsProfilingMethod() {
        return sIsProfilingMethod;
    }

    /** Returns how many iterations should run with method tracing. */
    public static int getProfilingIterations() {
        return WindowPerfRunPreconditionBase.sProfilingIterations;
    }

    /**
     * Executes shell command with reading the output. It may also used to block until the current
     * command is completed.
     */
    public static ByteArrayOutputStream executeShellCommand(String command) {
        final ParcelFileDescriptor pfd = getUiAutomation().executeShellCommand(command);
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

    public static void runWithShellPermissionIdentity(Runnable runnable) {
        getUiAutomation().adoptShellPermissionIdentity();
        try {
            runnable.run();
        } finally {
            getUiAutomation().dropShellPermissionIdentity();
        }
    }

    public static class SettingsSession<T> implements AutoCloseable {
        private final Consumer<T> mSetter;
        private final T mOriginalValue;
        private boolean mChanged;

        public SettingsSession(T originalValue, Consumer<T> setter) {
            mOriginalValue = originalValue;
            mSetter = setter;
        }

        public void set(T value) {
            if (Objects.equals(value, mOriginalValue)) {
                mChanged = false;
                return;
            }
            mSetter.accept(value);
            mChanged = true;
        }

        @Override
        public void close() {
            if (mChanged) {
                mSetter.accept(mOriginalValue);
            }
        }
    }

    /**
     * Provides the {@link PerfTestActivity} with an associated customizable intent.
     */
    public static class PerfTestActivityRuleBase extends ActivityTestRule<PerfTestActivity> {
        protected final Intent mStartIntent =
                new Intent(getInstrumentation().getTargetContext(), PerfTestActivity.class);

        public PerfTestActivityRuleBase() {
            this(false /* launchActivity */);
        }

        public PerfTestActivityRuleBase(boolean launchActivity) {
            super(PerfTestActivity.class, false /* initialTouchMode */, launchActivity);
        }

        @Override
        public Intent getActivityIntent() {
            return mStartIntent;
        }

        public PerfTestActivity launchActivity() {
            return launchActivity(mStartIntent);
        }
    }
}
