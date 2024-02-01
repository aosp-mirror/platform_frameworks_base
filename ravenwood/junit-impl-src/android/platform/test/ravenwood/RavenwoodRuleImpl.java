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

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.os.RuntimeInit;

import org.junit.runner.Description;

import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

    public static boolean isOnRavenwood() {
        return true;
    }

    public static void init(RavenwoodRule rule) {
        RuntimeInit.redirectLogStreams();

        android.os.Process.init$ravenwood(rule.mUid, rule.mPid);
        android.os.Binder.init$ravenwood();
        android.os.SystemProperties.init$ravenwood(
                rule.mSystemProperties.getValues(),
                rule.mSystemProperties.getKeyReadablePredicate(),
                rule.mSystemProperties.getKeyWritablePredicate());

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

        android.os.SystemProperties.reset$ravenwood();
        android.os.Binder.reset$ravenwood();
        android.os.Process.reset$ravenwood();
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
}
