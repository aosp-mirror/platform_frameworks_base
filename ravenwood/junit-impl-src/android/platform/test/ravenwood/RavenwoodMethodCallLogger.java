/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.android.ravenwood.RavenwoodRuntimeNative;

import java.io.PrintStream;
import java.util.HashSet;
import java.util.Objects;

/**
 * Provides a method call hook that prints almost all (see below) the framework methods being
 * called with indentation.
 *
 * We don't log methods that are trivial, uninteresting, or would be too noisy.
 * e.g. we don't want to log any logging related methods, or collection APIs.
 *
 */
public class RavenwoodMethodCallLogger {
    private RavenwoodMethodCallLogger() {
    }

    /** We don't want to log anything before ravenwood is initialized. This flag controls it.*/
    private static volatile boolean sEnabled = false;

    private static volatile PrintStream sOut = System.out;

    /** Return the current thread's call nest level. */
    private static int getNestLevel() {
        return Thread.currentThread().getStackTrace().length;
    }

    private static class ThreadInfo {
        /**
         * We save the current thread's nest call level here and use that as the initial level.
         * We do it because otherwise the nest level would be too deep by the time test
         * starts.
         */
        public final int mInitialNestLevel = getNestLevel();

        /**
         * A nest level where shouldLog() returned false.
         * Once it's set, we ignore all calls deeper than this.
         */
        public int mDisabledNestLevel = Integer.MAX_VALUE;
    }

    private static final ThreadLocal<ThreadInfo> sThreadInfo = new ThreadLocal<>() {
        @Override
        protected ThreadInfo initialValue() {
            return new ThreadInfo();
        }
    };

    /** Classes that should be logged. Uses a map for fast lookup. */
    private static final HashSet<Class> sIgnoreClasses = new HashSet<>();
    static {
        // The following classes are not interesting...
        sIgnoreClasses.add(android.util.Log.class);
        sIgnoreClasses.add(android.util.Slog.class);
        sIgnoreClasses.add(android.util.EventLog.class);
        sIgnoreClasses.add(android.util.TimingsTraceLog.class);

        sIgnoreClasses.add(android.util.SparseArray.class);
        sIgnoreClasses.add(android.util.SparseIntArray.class);
        sIgnoreClasses.add(android.util.SparseLongArray.class);
        sIgnoreClasses.add(android.util.SparseBooleanArray.class);
        sIgnoreClasses.add(android.util.SparseDoubleArray.class);
        sIgnoreClasses.add(android.util.SparseSetArray.class);
        sIgnoreClasses.add(android.util.SparseArrayMap.class);
        sIgnoreClasses.add(android.util.LongSparseArray.class);
        sIgnoreClasses.add(android.util.LongSparseLongArray.class);
        sIgnoreClasses.add(android.util.LongArray.class);

        sIgnoreClasses.add(android.text.FontConfig.class);

        sIgnoreClasses.add(android.os.SystemClock.class);
        sIgnoreClasses.add(android.os.Trace.class);
        sIgnoreClasses.add(android.os.LocaleList.class);
        sIgnoreClasses.add(android.os.Build.class);
        sIgnoreClasses.add(android.os.SystemProperties.class);

        sIgnoreClasses.add(com.android.internal.util.Preconditions.class);

        sIgnoreClasses.add(android.graphics.FontListParser.class);
        sIgnoreClasses.add(android.graphics.ColorSpace.class);

        sIgnoreClasses.add(android.graphics.fonts.FontStyle.class);
        sIgnoreClasses.add(android.graphics.fonts.FontVariationAxis.class);

        sIgnoreClasses.add(com.android.internal.compat.CompatibilityChangeInfo.class);
        sIgnoreClasses.add(com.android.internal.os.LoggingPrintStream.class);

        sIgnoreClasses.add(android.os.ThreadLocalWorkSource.class);

        // Following classes *may* be interesting for some purposes, but the initialization is
        // too noisy...
        sIgnoreClasses.add(android.graphics.fonts.SystemFonts.class);

    }

    /**
     * Return if a class should be ignored. Uses {link #sIgnoreCladsses}, but
     * we ignore more classes.
     */
    private static boolean shouldIgnoreClass(Class<?> clazz) {
        if (sIgnoreClasses.contains(clazz)) {
            return true;
        }
        // Let's also ignore collection-ish classes in android.util.
        if (java.util.Collection.class.isAssignableFrom(clazz)
                || java.util.Map.class.isAssignableFrom(clazz)
        ) {
            if ("android.util".equals(clazz.getPackageName())) {
                return true;
            }
            return false;
        }

        switch (clazz.getSimpleName()) {
            case "EventLogTags":
                return false;
        }

        // Following are classes that can't be referred to here directly.
        // e.g. AndroidPrintStream is package-private, so we can't use its "class" here.
        switch (clazz.getName()) {
            case "com.android.internal.os.AndroidPrintStream":
                return false;
        }
        return false;
    }

    private static boolean shouldLog(
            Class<?> methodClass,
            String methodName,
            @SuppressWarnings("UnusedVariable") String methodDescriptor
    ) {
        // Should we ignore this class?
        if (shouldIgnoreClass(methodClass)) {
            return false;
        }
        // Is it a nested class in a class that should be ignored?
        var host = methodClass.getNestHost();
        if (host != methodClass && shouldIgnoreClass(host)) {
            return false;
        }

        var className = methodClass.getName();

        // Ad-hoc ignore list. They'd be too noisy.
        if ("create".equals(methodName)
                // We may apply jarjar, so use endsWith().
                && className.endsWith("com.android.server.compat.CompatConfig")) {
            return false;
        }

        var pkg = methodClass.getPackageName();
        if (pkg.startsWith("android.icu")) {
            return false;
        }

        return true;
    }

    /**
     * Call this to enable logging.
     */
    public static void enable(PrintStream out) {
        sEnabled = true;
        sOut = Objects.requireNonNull(out);

        // It's called from the test thread (Java's main thread). Because we're already
        // in deep nest calls, we initialize the initial nest level here.
        sThreadInfo.get();
    }

    /** Actual method hook entry point.*/
    public static void logMethodCall(
            Class<?> methodClass,
            String methodName,
            String methodDescriptor
    ) {
        if (!sEnabled) {
            return;
        }
        final var ti = sThreadInfo.get();
        final int nestLevel = getNestLevel() - ti.mInitialNestLevel;

        // Once shouldLog() returns false, we just ignore all deeper calls.
        if (ti.mDisabledNestLevel < nestLevel) {
            return; // Still ignore.
        }
        final boolean shouldLog = shouldLog(methodClass, methodName, methodDescriptor);

        if (!shouldLog) {
            ti.mDisabledNestLevel = nestLevel;
            return;
        }
        ti.mDisabledNestLevel = Integer.MAX_VALUE;

        var out = sOut;
        out.print("# [");
        out.print(RavenwoodRuntimeNative.gettid());
        out.print(": ");
        out.print(Thread.currentThread().getName());
        out.print("]: ");
        out.print("[@");
        out.printf("%2d", nestLevel);
        out.print("] ");
        for (int i = 0; i < nestLevel; i++) {
            out.print("  ");
        }
        out.println(methodClass.getName() + "." + methodName + methodDescriptor);
    }
}
