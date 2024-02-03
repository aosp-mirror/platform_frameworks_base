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

package android.platform.test.ravenwood;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.os.RuntimeInit;

import org.junit.Assert;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;

import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class RavenwoodRuleImpl {
    private static final String MAIN_THREAD_NAME = "RavenwoodMain";

    /**
     * When enabled, attempt to dump all thread stacks just before we hit the
     * overall Tradefed timeout, to aid in debugging deadlocks.
     */
    private static final boolean ENABLE_TIMEOUT_STACKS = false;
    private static final int TIMEOUT_MILLIS = 9_000;

    private static final ScheduledExecutorService sTimeoutExecutor =
            Executors.newScheduledThreadPool(1);

    private static ScheduledFuture<?> sPendingTimeout;

    /**
     * When enabled, attempt to detect uncaught exceptions from background threads.
     */
    private static final boolean ENABLE_UNCAUGHT_EXCEPTION_DETECTION = false;

    /**
     * When set, an unhandled exception was discovered (typically on a background thread), and we
     * capture it here to ensure it's reported as a test failure.
     */
    private static final AtomicReference<Throwable> sPendingUncaughtException =
            new AtomicReference<>();

    private static final Thread.UncaughtExceptionHandler sUncaughtExceptionHandler =
            (thread, throwable) -> {
                // Remember the first exception we discover
                sPendingUncaughtException.compareAndSet(null, throwable);
            };

    public static boolean isOnRavenwood() {
        return true;
    }

    public static void init(RavenwoodRule rule) {
        if (ENABLE_UNCAUGHT_EXCEPTION_DETECTION) {
            maybeThrowPendingUncaughtException(false);
            Thread.setDefaultUncaughtExceptionHandler(sUncaughtExceptionHandler);
        }

        RuntimeInit.redirectLogStreams();

        android.os.Process.init$ravenwood(rule.mUid, rule.mPid);
        android.os.Binder.init$ravenwood();
        android.os.SystemProperties.init$ravenwood(
                rule.mSystemProperties.getValues(),
                rule.mSystemProperties.getKeyReadablePredicate(),
                rule.mSystemProperties.getKeyWritablePredicate());

        ActivityManager.init$ravenwood(rule.mCurrentUser);

        com.android.server.LocalServices.removeAllServicesForTest();

        if (rule.mProvideMainThread) {
            final HandlerThread main = new HandlerThread(MAIN_THREAD_NAME);
            main.start();
            Looper.setMainLooperForTest(main.getLooper());
        }

        InstrumentationRegistry.registerInstance(new Instrumentation(), Bundle.EMPTY);

        if (ENABLE_TIMEOUT_STACKS) {
            sPendingTimeout = sTimeoutExecutor.schedule(RavenwoodRuleImpl::dumpStacks,
                    TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        // Touch some references early to ensure they're <clinit>'ed
        Objects.requireNonNull(Build.IS_USERDEBUG);
        Objects.requireNonNull(Build.VERSION.SDK);
    }

    public static void reset(RavenwoodRule rule) {
        if (ENABLE_TIMEOUT_STACKS) {
            sPendingTimeout.cancel(false);
        }

        InstrumentationRegistry.registerInstance(null, Bundle.EMPTY);

        if (rule.mProvideMainThread) {
            Looper.getMainLooper().quit();
            Looper.clearMainLooperForTest();
        }

        com.android.server.LocalServices.removeAllServicesForTest();

        ActivityManager.reset$ravenwood();

        android.os.SystemProperties.reset$ravenwood();
        android.os.Binder.reset$ravenwood();
        android.os.Process.reset$ravenwood();

        if (ENABLE_UNCAUGHT_EXCEPTION_DETECTION) {
            maybeThrowPendingUncaughtException(true);
        }
    }

    public static void logTestRunner(String label, Description description) {
        // This message string carefully matches the exact format emitted by on-device tests, to
        // aid developers in debugging raw text logs
        Log.e("TestRunner", label + ": " + description.getMethodName()
                + "(" + description.getTestClass().getName() + ")");
    }

    private static void dumpStacks() {
        final PrintStream out = System.err;
        out.println("-----BEGIN ALL THREAD STACKS-----");
        final Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> stack : stacks.entrySet()) {
            out.println();
            Thread t = stack.getKey();
            out.println(t.toString() + " ID=" + t.getId());
            for (StackTraceElement e : stack.getValue()) {
                out.println("\tat " + e);
            }
        }
        out.println("-----END ALL THREAD STACKS-----");
    }

    /**
     * If there's a pending uncaught exception, consume and throw it now. Typically used to
     * report an exception on a background thread as a failure for the currently running test.
     */
    private static void maybeThrowPendingUncaughtException(boolean duringReset) {
        final Throwable pending = sPendingUncaughtException.getAndSet(null);
        if (pending != null) {
            if (duringReset) {
                throw new IllegalStateException(
                        "Found an uncaught exception during this test", pending);
            } else {
                throw new IllegalStateException(
                        "Found an uncaught exception before this test started", pending);
            }
        }
    }

    public static void validate(Statement base, Description description,
            boolean enableOptionalValidation) {
        validateTestRunner(base, description, enableOptionalValidation);
    }

    private static void validateTestRunner(Statement base, Description description,
            boolean shouldFail) {
        final var testClass = description.getTestClass();
        final var runWith = testClass.getAnnotation(RunWith.class);
        if (runWith == null) {
            return;
        }

        // Due to build dependencies, we can't directly refer to androidx classes here,
        // so just check the class name instead.
        if (runWith.value().getCanonicalName().equals("androidx.test.runner.AndroidJUnit4")) {
            var message = "Test " + testClass.getCanonicalName() + " uses deprecated"
                    + " test runner androidx.test.runner.AndroidJUnit4."
                    + " Switch to androidx.test.ext.junit.runners.AndroidJUnit4.";
            if (shouldFail) {
                Assert.fail(message);
            } else {
                System.err.println("Warning: " + message);
            }
        }
    }
}
