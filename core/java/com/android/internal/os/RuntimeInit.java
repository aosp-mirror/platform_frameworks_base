/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.os;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.ApplicationErrorReport;
import android.app.IActivityManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.type.DefaultMimeMapFactory;
import android.os.Build;
import android.os.DeadObjectException;
import android.os.Debug;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.Log;
import android.util.Slog;

import com.android.internal.logging.AndroidConfig;
import com.android.server.NetworkManagementSocketTagger;

import dalvik.system.RuntimeHooks;
import dalvik.system.ThreadPrioritySetter;
import dalvik.system.VMRuntime;

import libcore.content.type.MimeMap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.logging.LogManager;

/**
 * Main entry point for runtime initialization.  Not for
 * public consumption.
 * @hide
 */
public class RuntimeInit {
    final static String TAG = "AndroidRuntime";
    final static boolean DEBUG = false;

    /** true if commonInit() has been called */
    @UnsupportedAppUsage
    private static boolean initialized;

    @UnsupportedAppUsage
    private static IBinder mApplicationObject;

    private static volatile boolean mCrashing = false;

    private static volatile ApplicationWtfHandler sDefaultApplicationWtfHandler;

    private static final native void nativeFinishInit();
    private static final native void nativeSetExitWithoutCleanup(boolean exitWithoutCleanup);

    private static int Clog_e(String tag, String msg, Throwable tr) {
        return Log.printlns(Log.LOG_ID_CRASH, Log.ERROR, tag, msg, tr);
    }

    public static void logUncaught(String threadName, String processName, int pid, Throwable e) {
        StringBuilder message = new StringBuilder();
        // The "FATAL EXCEPTION" string is still used on Android even though
        // apps can set a custom UncaughtExceptionHandler that renders uncaught
        // exceptions non-fatal.
        message.append("FATAL EXCEPTION: ").append(threadName).append("\n");
        if (processName != null) {
            message.append("Process: ").append(processName).append(", ");
        }
        message.append("PID: ").append(pid);
        Clog_e(TAG, message.toString(), e);
    }

    /**
     * Logs a message when a thread encounters an uncaught exception. By
     * default, {@link KillApplicationHandler} will terminate this process later,
     * but apps can override that behavior.
     */
    private static class LoggingHandler implements Thread.UncaughtExceptionHandler {
        public volatile boolean mTriggered = false;

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            mTriggered = true;

            // Don't re-enter if KillApplicationHandler has already run
            if (mCrashing) return;

            // mApplicationObject is null for non-zygote java programs (e.g. "am")
            // There are also apps running with the system UID. We don't want the
            // first clause in either of these two cases, only for system_server.
            if (mApplicationObject == null && (Process.SYSTEM_UID == Process.myUid())) {
                Clog_e(TAG, "*** FATAL EXCEPTION IN SYSTEM PROCESS: " + t.getName(), e);
            } else {
                logUncaught(t.getName(), ActivityThread.currentProcessName(), Process.myPid(), e);
            }
        }
    }

    /**
     * Handle application death from an uncaught exception.  The framework
     * catches these for the main threads, so this should only matter for
     * threads created by applications. Before this method runs, the given
     * instance of {@link LoggingHandler} should already have logged details
     * (and if not it is run first).
     */
    private static class KillApplicationHandler implements Thread.UncaughtExceptionHandler {
        private final LoggingHandler mLoggingHandler;

        /**
         * Create a new KillApplicationHandler that follows the given LoggingHandler.
         * If {@link #uncaughtException(Thread, Throwable) uncaughtException} is called
         * on the created instance without {@code loggingHandler} having been triggered,
         * {@link LoggingHandler#uncaughtException(Thread, Throwable)
         * loggingHandler.uncaughtException} will be called first.
         *
         * @param loggingHandler the {@link LoggingHandler} expected to have run before
         *     this instance's {@link #uncaughtException(Thread, Throwable) uncaughtException}
         *     is being called.
         */
        public KillApplicationHandler(LoggingHandler loggingHandler) {
            this.mLoggingHandler = Objects.requireNonNull(loggingHandler);
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try {
                ensureLogging(t, e);

                // Don't re-enter -- avoid infinite loops if crash-reporting crashes.
                if (mCrashing) return;
                mCrashing = true;

                // Try to end profiling. If a profiler is running at this point, and we kill the
                // process (below), the in-memory buffer will be lost. So try to stop, which will
                // flush the buffer. (This makes method trace profiling useful to debug crashes.)
                if (ActivityThread.currentActivityThread() != null) {
                    ActivityThread.currentActivityThread().stopProfiling();
                }

                // Bring up crash dialog, wait for it to be dismissed
                ActivityManager.getService().handleApplicationCrash(
                        mApplicationObject, new ApplicationErrorReport.ParcelableCrashInfo(e));
            } catch (Throwable t2) {
                if (t2 instanceof DeadObjectException) {
                    // System process is dead; ignore
                } else {
                    try {
                        Clog_e(TAG, "Error reporting crash", t2);
                    } catch (Throwable t3) {
                        // Even Clog_e() fails!  Oh well.
                    }
                }
            } finally {
                // Try everything to make sure this process goes away.
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        }

        /**
         * Ensures that the logging handler has been triggered.
         *
         * See b/73380984. This reinstates the pre-O behavior of
         *
         *   {@code thread.getUncaughtExceptionHandler().uncaughtException(thread, e);}
         *
         * logging the exception (in addition to killing the app). This behavior
         * was never documented / guaranteed but helps in diagnostics of apps
         * using the pattern.
         *
         * If this KillApplicationHandler is invoked the "regular" way (by
         * {@link Thread#dispatchUncaughtException(Throwable)
         * Thread.dispatchUncaughtException} in case of an uncaught exception)
         * then the pre-handler (expected to be {@link #mLoggingHandler}) will already
         * have run. Otherwise, we manually invoke it here.
         */
        private void ensureLogging(Thread t, Throwable e) {
            if (!mLoggingHandler.mTriggered) {
                try {
                    mLoggingHandler.uncaughtException(t, e);
                } catch (Throwable loggingThrowable) {
                    // Ignored.
                }
            }
        }
    }

    /**
     * Common initialization that (unlike {@link #commonInit()} should happen prior to
     * the Zygote fork.
     */
    public static void preForkInit() {
        if (DEBUG) Slog.d(TAG, "Entered preForkInit.");
        RuntimeHooks.setThreadPrioritySetter(new RuntimeThreadPrioritySetter());
        RuntimeInit.enableDdms();
        // TODO(b/142019040#comment13): Decide whether to load the default instance eagerly, i.e.
        // MimeMap.setDefault(DefaultMimeMapFactory.create());
        /*
         * Replace libcore's minimal default mapping between MIME types and file
         * extensions with a mapping that's suitable for Android. Android's mapping
         * contains many more entries that are derived from IANA registrations but
         * with several customizations (extensions, overrides).
         */
        MimeMap.setDefaultSupplier(DefaultMimeMapFactory::create);
    }

    private static class RuntimeThreadPrioritySetter implements ThreadPrioritySetter {
        // Should remain consistent with kNiceValues[] in system/libartpalette/palette_android.cc
        private static final int[] NICE_VALUES = {
            Process.THREAD_PRIORITY_LOWEST,  // 1 (MIN_PRIORITY)
            Process.THREAD_PRIORITY_BACKGROUND + 6,
            Process.THREAD_PRIORITY_BACKGROUND + 3,
            Process.THREAD_PRIORITY_BACKGROUND,
            Process.THREAD_PRIORITY_DEFAULT,  // 5 (NORM_PRIORITY)
            Process.THREAD_PRIORITY_DEFAULT - 2,
            Process.THREAD_PRIORITY_DEFAULT - 4,
            Process.THREAD_PRIORITY_URGENT_DISPLAY + 3,
            Process.THREAD_PRIORITY_URGENT_DISPLAY + 2,
            Process.THREAD_PRIORITY_URGENT_DISPLAY  // 10 (MAX_PRIORITY)
        };

        @Override
        public void setPriority(int nativeTid, int priority) {
            // Check NICE_VALUES[] length first.
            if (NICE_VALUES.length != (1 + Thread.MAX_PRIORITY - Thread.MIN_PRIORITY)) {
                throw new AssertionError("Unexpected NICE_VALUES.length=" + NICE_VALUES.length);
            }
            // Priority should be in the range of MIN_PRIORITY (1) to MAX_PRIORITY (10).
            if (priority < Thread.MIN_PRIORITY || priority > Thread.MAX_PRIORITY) {
                throw new IllegalArgumentException("Priority out of range: " + priority);
            }
            Process.setThreadPriority(nativeTid, NICE_VALUES[priority - Thread.MIN_PRIORITY]);
        }
    }

    @UnsupportedAppUsage
    protected static final void commonInit() {
        if (DEBUG) Slog.d(TAG, "Entered RuntimeInit!");

        /*
         * set handlers; these apply to all threads in the VM. Apps can replace
         * the default handler, but not the pre handler.
         */
        LoggingHandler loggingHandler = new LoggingHandler();
        RuntimeHooks.setUncaughtExceptionPreHandler(loggingHandler);
        Thread.setDefaultUncaughtExceptionHandler(new KillApplicationHandler(loggingHandler));

        /*
         * Install a time zone supplier that uses the Android persistent time zone system property.
         */
        RuntimeHooks.setTimeZoneIdSupplier(() -> SystemProperties.get("persist.sys.timezone"));

        /*
         * Sets handler for java.util.logging to use Android log facilities.
         * The odd "new instance-and-then-throw-away" is a mirror of how
         * the "java.util.logging.config.class" system property works. We
         * can't use the system property here since the logger has almost
         * certainly already been initialized.
         */
        LogManager.getLogManager().reset();
        new AndroidConfig();

        /*
         * Sets the default HTTP User-Agent used by HttpURLConnection.
         */
        String userAgent = getDefaultUserAgent();
        System.setProperty("http.agent", userAgent);

        /*
         * Wire socket tagging to traffic stats.
         */
        NetworkManagementSocketTagger.install();

        /*
         * If we're running in an emulator launched with "-trace", put the
         * VM into emulator trace profiling mode so that the user can hit
         * F9/F10 at any time to capture traces.  This has performance
         * consequences, so it's not something you want to do always.
         */
        String trace = SystemProperties.get("ro.kernel.android.tracing");
        if (trace.equals("1")) {
            Slog.i(TAG, "NOTE: emulator trace profiling enabled");
            Debug.enableEmulatorTraceOutput();
        }

        initialized = true;
    }

    /**
     * Returns an HTTP user agent of the form
     * "Dalvik/1.1.0 (Linux; U; Android Eclair Build/MASTER)".
     */
    private static String getDefaultUserAgent() {
        StringBuilder result = new StringBuilder(64);
        result.append("Dalvik/");
        result.append(System.getProperty("java.vm.version")); // such as 1.1.0
        result.append(" (Linux; U; Android ");

        String version = Build.VERSION.RELEASE_OR_CODENAME; // "1.0" or "3.4b5"
        result.append(version.length() > 0 ? version : "1.0");

        // add the model for the release build
        if ("REL".equals(Build.VERSION.CODENAME)) {
            String model = Build.MODEL;
            if (model.length() > 0) {
                result.append("; ");
                result.append(model);
            }
        }
        String id = Build.ID; // "MASTER" or "M4-rc20"
        if (id.length() > 0) {
            result.append(" Build/");
            result.append(id);
        }
        result.append(")");
        return result.toString();
    }

    /**
     * Invokes a static "main(argv[]) method on class "className".
     * Converts various failing exceptions into RuntimeExceptions, with
     * the assumption that they will then cause the VM instance to exit.
     *
     * @param className Fully-qualified class name
     * @param argv Argument vector for main()
     * @param classLoader the classLoader to load {@className} with
     */
    protected static Runnable findStaticMain(String className, String[] argv,
            ClassLoader classLoader) {
        Class<?> cl;

        try {
            cl = Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException ex) {
            throw new RuntimeException(
                    "Missing class when invoking static main " + className,
                    ex);
        }

        Method m;
        try {
            m = cl.getMethod("main", new Class[] { String[].class });
        } catch (NoSuchMethodException ex) {
            throw new RuntimeException(
                    "Missing static main on " + className, ex);
        } catch (SecurityException ex) {
            throw new RuntimeException(
                    "Problem getting static main on " + className, ex);
        }

        int modifiers = m.getModifiers();
        if (! (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers))) {
            throw new RuntimeException(
                    "Main method is not public and static on " + className);
        }

        /*
         * This throw gets caught in ZygoteInit.main(), which responds
         * by invoking the exception's run() method. This arrangement
         * clears up all the stack frames that were required in setting
         * up the process.
         */
        return new MethodAndArgsCaller(m, argv);
    }

    @UnsupportedAppUsage
    public static final void main(String[] argv) {
        preForkInit();
        if (argv.length == 2 && argv[1].equals("application")) {
            if (DEBUG) Slog.d(TAG, "RuntimeInit: Starting application");
            redirectLogStreams();
        } else {
            if (DEBUG) Slog.d(TAG, "RuntimeInit: Starting tool");
        }

        commonInit();

        /*
         * Now that we're running in interpreted code, call back into native code
         * to run the system.
         */
        nativeFinishInit();

        if (DEBUG) Slog.d(TAG, "Leaving RuntimeInit!");
    }

    protected static Runnable applicationInit(int targetSdkVersion, long[] disabledCompatChanges,
            String[] argv, ClassLoader classLoader) {
        // If the application calls System.exit(), terminate the process
        // immediately without running any shutdown hooks.  It is not possible to
        // shutdown an Android application gracefully.  Among other things, the
        // Android runtime shutdown hooks close the Binder driver, which can cause
        // leftover running threads to crash before the process actually exits.
        nativeSetExitWithoutCleanup(true);

        VMRuntime.getRuntime().setTargetSdkVersion(targetSdkVersion);
        VMRuntime.getRuntime().setDisabledCompatChanges(disabledCompatChanges);

        final Arguments args = new Arguments(argv);

        // The end of of the RuntimeInit event (see #zygoteInit).
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

        // Remaining arguments are passed to the start class's static main
        return findStaticMain(args.startClass, args.startArgs, classLoader);
    }

    /**
     * Redirect System.out and System.err to the Android log.
     */
    public static void redirectLogStreams() {
        System.out.close();
        System.setOut(new AndroidPrintStream(Log.INFO, "System.out"));
        System.err.close();
        System.setErr(new AndroidPrintStream(Log.WARN, "System.err"));
    }

    /**
     * Report a serious error in the current process.  May or may not cause
     * the process to terminate (depends on system settings).
     *
     * @param tag to record with the error
     * @param t exception describing the error site and conditions
     */
    public static void wtf(String tag, Throwable t, boolean system) {
        try {
            boolean exit = false;
            final IActivityManager am = ActivityManager.getService();
            if (am != null) {
                exit = am.handleApplicationWtf(
                        mApplicationObject, tag, system,
                        new ApplicationErrorReport.ParcelableCrashInfo(t),
                        Process.myPid());
            } else {
                // Unlikely but possible in early system boot
                final ApplicationWtfHandler handler = sDefaultApplicationWtfHandler;
                if (handler != null) {
                    exit = handler.handleApplicationWtf(
                            mApplicationObject, tag, system,
                            new ApplicationErrorReport.ParcelableCrashInfo(t),
                            Process.myPid());
                } else {
                    // Simply log the error
                    Slog.e(TAG, "Original WTF:", t);
                }
            }
            if (exit) {
                // The Activity Manager has already written us off -- now exit.
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        } catch (Throwable t2) {
            if (t2 instanceof DeadObjectException) {
                // System process is dead; ignore
            } else {
                Slog.e(TAG, "Error reporting WTF", t2);
                Slog.e(TAG, "Original WTF:", t);
            }
        }
    }

    /**
     * Set the default {@link ApplicationWtfHandler}, in case the ActivityManager is not ready yet.
     */
    public static void setDefaultApplicationWtfHandler(final ApplicationWtfHandler handler) {
        sDefaultApplicationWtfHandler = handler;
    }

    /**
     * The handler to deal with the serious application errors.
     */
    public interface ApplicationWtfHandler {
        /**
         * @param app object of the crashing app, null for the system server
         * @param tag reported by the caller
         * @param system whether this wtf is coming from the system
         * @param crashInfo describing the context of the error
         * @param immediateCallerPid the caller Pid
         * @return true if the process should exit immediately (WTF is fatal)
         */
        boolean handleApplicationWtf(IBinder app, String tag, boolean system,
                ApplicationErrorReport.ParcelableCrashInfo crashInfo, int immediateCallerPid);
    }

    /**
     * Set the object identifying this application/process, for reporting VM
     * errors.
     */
    public static final void setApplicationObject(IBinder app) {
        mApplicationObject = app;
    }

    @UnsupportedAppUsage
    public static final IBinder getApplicationObject() {
        return mApplicationObject;
    }

    /**
     * Enable DDMS.
     */
    private static void enableDdms() {
        // Register handlers for DDM messages.
        android.ddm.DdmRegister.registerHandlers();
    }

    /**
     * Handles argument parsing for args related to the runtime.
     *
     * Current recognized args:
     * <ul>
     *   <li> <code> [--] &lt;start class name&gt;  &lt;args&gt;
     * </ul>
     */
    static class Arguments {
        /** first non-option argument */
        String startClass;

        /** all following arguments */
        String[] startArgs;

        /**
         * Constructs instance and parses args
         * @param args runtime command-line args
         * @throws IllegalArgumentException
         */
        Arguments(String args[]) throws IllegalArgumentException {
            parseArgs(args);
        }

        /**
         * Parses the commandline arguments intended for the Runtime.
         */
        private void parseArgs(String args[])
                throws IllegalArgumentException {
            int curArg = 0;
            for (; curArg < args.length; curArg++) {
                String arg = args[curArg];

                if (arg.equals("--")) {
                    curArg++;
                    break;
                } else if (!arg.startsWith("--")) {
                    break;
                }
            }

            if (curArg == args.length) {
                throw new IllegalArgumentException("Missing classname argument to RuntimeInit!");
            }

            startClass = args[curArg++];
            startArgs = new String[args.length - curArg];
            System.arraycopy(args, curArg, startArgs, 0, startArgs.length);
        }
    }

    /**
     * Helper class which holds a method and arguments and can call them. This is used as part of
     * a trampoline to get rid of the initial process setup stack frames.
     */
    static class MethodAndArgsCaller implements Runnable {
        /** method to call */
        private final Method mMethod;

        /** argument array */
        private final String[] mArgs;

        public MethodAndArgsCaller(Method method, String[] args) {
            mMethod = method;
            mArgs = args;
        }

        public void run() {
            try {
                mMethod.invoke(null, new Object[] { mArgs });
            } catch (IllegalAccessException ex) {
                throw new RuntimeException(ex);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                } else if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw new RuntimeException(ex);
            }
        }
    }
}
