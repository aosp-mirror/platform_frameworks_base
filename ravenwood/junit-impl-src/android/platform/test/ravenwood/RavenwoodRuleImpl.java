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

import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_EMPTY_RESOURCES_APK;
import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_RESOURCE_APK;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.ResourcesManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ServiceManager;
import android.util.Log;
import android.view.DisplayAdjustments;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.os.RuntimeInit;
import com.android.server.LocalServices;

import org.junit.runner.Description;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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

    public static void init(RavenwoodRule rule) throws IOException {
        if (ENABLE_UNCAUGHT_EXCEPTION_DETECTION) {
            maybeThrowPendingUncaughtException(false);
            Thread.setDefaultUncaughtExceptionHandler(sUncaughtExceptionHandler);
        }

        RuntimeInit.redirectLogStreams();

        android.os.Process.init$ravenwood(rule.mUid, rule.mPid);
        android.os.Binder.init$ravenwood();
        setSystemProperties(rule.mSystemProperties);

        ServiceManager.init$ravenwood();
        LocalServices.removeAllServicesForTest();

        ActivityManager.init$ravenwood(rule.mCurrentUser);

        final HandlerThread main;
        if (rule.mProvideMainThread) {
            main = new HandlerThread(MAIN_THREAD_NAME);
            main.start();
            Looper.setMainLooperForTest(main.getLooper());
        } else {
            main = null;
        }

        // TODO This should be integrated into LoadedApk
        final Supplier<Resources> resourcesSupplier = () -> {
            var resApkFile = new File(RAVENWOOD_RESOURCE_APK);
            if (!resApkFile.isFile()) {
                resApkFile = new File(RAVENWOOD_EMPTY_RESOURCES_APK);
            }
            assertTrue(resApkFile.isFile());
            final String res = resApkFile.getAbsolutePath();
            final var emptyPaths = new String[0];

            ResourcesManager.getInstance().initializeApplicationPaths(res, emptyPaths);

            final var ret = ResourcesManager.getInstance().getResources(null, res,
                    emptyPaths, emptyPaths, emptyPaths,
                    emptyPaths, null, null,
                    new DisplayAdjustments().getCompatibilityInfo(),
                    RavenwoodRuleImpl.class.getClassLoader(), null);

            assertNotNull(ret);
            return ret;
        };

        rule.mContext = new RavenwoodContext(rule.mPackageName, main, resourcesSupplier);
        rule.mInstrumentation = new Instrumentation();
        rule.mInstrumentation.basicInit(rule.mContext);
        InstrumentationRegistry.registerInstance(rule.mInstrumentation, Bundle.EMPTY);

        RavenwoodSystemServer.init(rule);

        if (ENABLE_TIMEOUT_STACKS) {
            sPendingTimeout = sTimeoutExecutor.schedule(RavenwoodRuleImpl::dumpStacks,
                    TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        // Touch some references early to ensure they're <clinit>'ed
        Objects.requireNonNull(Build.TYPE);
        Objects.requireNonNull(Build.VERSION.SDK);
    }

    public static void reset(RavenwoodRule rule) {
        if (ENABLE_TIMEOUT_STACKS) {
            sPendingTimeout.cancel(false);
        }

        RavenwoodSystemServer.reset(rule);

        InstrumentationRegistry.registerInstance(null, Bundle.EMPTY);
        rule.mInstrumentation = null;
        if (rule.mContext != null) {
            ((RavenwoodContext) rule.mContext).cleanUp();
        }
        rule.mContext = null;

        if (rule.mProvideMainThread) {
            Looper.getMainLooper().quit();
            Looper.clearMainLooperForTest();
        }

        ActivityManager.reset$ravenwood();

        LocalServices.removeAllServicesForTest();
        ServiceManager.reset$ravenwood();

        setSystemProperties(RavenwoodSystemProperties.DEFAULT_VALUES);
        android.os.Binder.reset$ravenwood();
        android.os.Process.reset$ravenwood();

        ResourcesManager.setInstance(null); // Better structure needed.

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

    /**
     * Set the current configuration to the actual SystemProperties.
     */
    public static void setSystemProperties(RavenwoodSystemProperties ravenwoodSystemProperties) {
        var clone = new RavenwoodSystemProperties(ravenwoodSystemProperties, true);

        android.os.SystemProperties.init$ravenwood(
                clone.getValues(),
                clone.getKeyReadablePredicate(),
                clone.getKeyWritablePredicate());
    }
}
