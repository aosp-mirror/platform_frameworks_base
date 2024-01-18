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

package com.android.server.utils;

import static android.text.TextUtils.formatSimple;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.Trace;
import android.text.TextUtils;
import android.text.format.TimeMigrationUtils;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.Keep;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.RingBuffer;

import java.lang.ref.WeakReference;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Objects;

/**
 * This class managers AnrTimers.  An AnrTimer is a substitute for a delayed Message.  In legacy
 * mode, the timer just sends a delayed message.  In modern mode, the timer is implemented in
 * native code; on expiration, the message is sent without delay.
 *
 * <p>There are four external operations on a timer:
 * <ul>
 *
 * <li>{@link #start} starts a timer.  The timer is started with an object that the message
 * argument.  The timer is also given the pid and uid of the target. A timer that is started must
 * be canceled, accepted, or discarded.
 *
 * <li>{@link #cancel} stops a timer and removes any in-flight expiration messages.
 *
 * <li>{@link #accept} acknowledges that the timer has expired, and that an ANR should be
 * generated.  This clears bookkeeping information for the timer.
 *
 * <li>{@link #discard} acknowledges that the timer has expired but, for other reasons, no ANR
 * will be generated.  This clears bookkeeping information for the timer.
 *
 *</li></p>
 *
 * <p>There is one internal operation on a timer: {@link #expire}.  A timer may have automatic
 * extensions enabled.  If so, the extension is computed and if the extension is non-zero, the timer
 * is restarted with the extension timeout.  If extensions are disabled or if the extension is zero,
 * the client process is notified of the expiration.
 *
 * <p>Instances use native resources but not system resources when the feature is enabled.
 * Instances should be explicitly closed unless they are being closed as part of process
 * exit. (So, instances in system server generally need not be explicitly closed since they are
 * created during process start and will last until process exit.)
 *
 * @hide
 */
public class AnrTimer<V> implements AutoCloseable {

    /**
     * The log tag.
     */
    final static String TAG = "AnrTimer";

    /**
     * The trace track for these events.  There is a single track for all AnrTimer instances.  The
     * tracks give a sense of handler latency: the time between timer expiration and ANR
     * collection.
     */
    private final static String TRACK = "AnrTimer";

    /**
     * Enable debug messages.
     */
    private static boolean DEBUG = false;

    /**
     * The trace tag.
     */
    private static final long TRACE_TAG = Trace.TRACE_TAG_ACTIVITY_MANAGER;

    /**
     * Enable tracing from the time a timer expires until it is accepted or discarded.  This is
     * used to diagnose long latencies in the client.
     */
    private static final boolean ENABLE_TRACING = false;

    /**
     * Return true if the feature is enabled.  By default, the value is take from the Flags class
     * but it can be changed for local testing.
     */
    private static boolean anrTimerServiceEnabled() {
        return Flags.anrTimerService();
    }

    /**
     * This class allows test code to provide instance-specific overrides.
     */
    static class Injector {
        boolean anrTimerServiceEnabled() {
            return AnrTimer.anrTimerServiceEnabled();
        }
    }

    /** The default injector. */
    private static final Injector sDefaultInjector = new Injector();

    /**
     * An error is defined by its issue, the operation that detected the error, the tag of the
     * affected service, a short stack of the bad call, and the stringified arg associated with
     * the error.
     */
    private static final class Error {
        /** The issue is the kind of error that was detected.  This is a free-form string. */
        final String issue;
        /** The operation that detected the error: start, cancel, accept, or discard. */
        final String operation;
        /** The argument (stringified) passed in to the operation. */
        final String arg;
        /** The tag of the associated AnrTimer. */
        final String tag;
        /** A partial stack that localizes the caller of the operation. */
        final StackTraceElement[] stack;
        /** The date, in local time, the error was created. */
        final long timestamp;

        Error(@NonNull String issue, @NonNull String operation, @NonNull String tag,
                @NonNull StackTraceElement[] stack, @NonNull String arg) {
            this.issue = issue;
            this.operation = operation;
            this.tag = tag;
            this.stack = stack;
            this.arg = arg;
            this.timestamp = SystemClock.elapsedRealtime();
        }

        /**
         * Dump a single error to the output stream.
         */
        private void dump(IndentingPrintWriter ipw, int seq) {
            ipw.format("%2d: op:%s tag:%s issue:%s arg:%s\n", seq, operation, tag, issue, arg);

            final long offset = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            final long etime = offset + timestamp;
            ipw.println("    date:" + TimeMigrationUtils.formatMillisWithFixedFormat(etime));
            ipw.increaseIndent();
            for (int i = 0; i < stack.length; i++) {
                ipw.println("    " + stack[i].toString());
            }
            ipw.decreaseIndent();
        }
    }

    /**
     * A list of errors detected during processing.  Errors correspond to "timer not found"
     * conditions.  The stack trace identifies the source of the call.  The list is
     * first-in/first-out, and the size is limited to 20.
     */
    @GuardedBy("sErrors")
    private static final RingBuffer<Error> sErrors = new RingBuffer<>(Error.class, 20);

    /** A lock for the AnrTimer instance. */
    private final Object mLock = new Object();

    /** The map from client argument to the associated timer ID. */
    @GuardedBy("mLock")
    private final ArrayMap<V, Integer> mTimerIdMap = new ArrayMap<>();

    /** Reverse map from timer ID to client argument. */
    @GuardedBy("mLock")
    private final SparseArray<V> mTimerArgMap = new SparseArray<>();

    /** The highwater mark of started, but not closed, timers. */
    @GuardedBy("mLock")
    private int mMaxStarted = 0;

    /** The total number of timers started. */
    @GuardedBy("mLock")
    private int mTotalStarted = 0;

    /** The total number of errors detected. */
    @GuardedBy("mLock")
    private int mTotalErrors = 0;

    /** The total number of timers that have expired. */
    @GuardedBy("mLock")
    private int mTotalExpired = 0;

    /** The handler for messages sent from this instance. */
    private final Handler mHandler;

    /** The message type for messages sent from this interface. */
    private final int mWhat;

    /** A label that identifies the AnrTimer associated with a Timer in log messages. */
    private final String mLabel;

    /** Whether this timer instance supports extending timeouts. */
    private final boolean mExtend;

    /** The injector used to create this instance.  This is only used for testing. */
    private final Injector mInjector;

    /** The top-level switch for the feature enabled or disabled. */
    private final FeatureSwitch mFeature;

    /**
     * Create one AnrTimer instance.  The instance is given a handler and a "what".  Individual
     * timers are started with {@link #start}.  If a timer expires, then a {@link Message} is sent
     * immediately to the handler with {@link Message.what} set to what and {@link Message.obj} set
     * to the timer key.
     *
     * AnrTimer instances have a label, which must be unique.  The label is used for reporting and
     * debug.
     *
     * If an individual timer expires internally, and the "extend" parameter is true, then the
     * AnrTimer may extend the individual timer rather than immediately delivering the timeout to
     * the client.  The extension policy is not part of the instance.
     *
     * @param handler The handler to which the expiration message will be delivered.
     * @param what The "what" parameter for the expiration message.
     * @param label A name for this instance.
     * @param extend A flag to indicate if expired timers can be granted extensions.
     * @param injector An injector to provide overrides for testing.
     */
    @VisibleForTesting
    AnrTimer(@NonNull Handler handler, int what, @NonNull String label, boolean extend,
             @NonNull Injector injector) {
        mHandler = handler;
        mWhat = what;
        mLabel = label;
        mExtend = extend;
        mInjector = injector;
        boolean enabled = mInjector.anrTimerServiceEnabled() && nativeTimersSupported();
        mFeature = createFeatureSwitch(enabled);
    }

    // Return the correct feature.  FeatureEnabled is returned if and only if the feature is
    // flag-enabled and if the native shadow was successfully created.  Otherwise, FeatureDisabled
    // is returned.
    private FeatureSwitch createFeatureSwitch(boolean enabled) {
        if (!enabled) {
            return new FeatureDisabled();
        } else {
            try {
                return new FeatureEnabled();
            } catch (RuntimeException e) {
                // Something went wrong in the native layer.  Log the error and fall back on the
                // feature-disabled logic.
                Log.e(TAG, e.toString());
                return new FeatureDisabled();
            }
        }
    }

    /**
     * Create one AnrTimer instance.  The instance is given a handler and a "what".  Individual
     * timers are started with {@link #start}.  If a timer expires, then a {@link Message} is sent
     * immediately to the handler with {@link Message.what} set to what and {@link Message.obj} set
     * to the timer key.
     *
     * AnrTimer instances have a label, which must be unique.  The label is used for reporting and
     * debug.
     *
     * If an individual timer expires internally, and the "extend" parameter is true, then the
     * AnrTimer may extend the individual timer rather than immediately delivering the timeout to
     * the client.  The extension policy is not part of the instance.
     *
     * @param handler The handler to which the expiration message will be delivered.
     * @param what The "what" parameter for the expiration message.
     * @param label A name for this instance.
     * @param extend A flag to indicate if expired timers can be granted extensions.
     */
    public AnrTimer(@NonNull Handler handler, int what, @NonNull String label, boolean extend) {
        this(handler, what, label, extend, sDefaultInjector);
    }

    /**
     * Create an AnrTimer instance with the default {@link #Injector} and with extensions disabled.
     * See {@link AnrTimer(Handler, int, String, boolean, Injector} for a functional description.
     *
     * @param handler The handler to which the expiration message will be delivered.
     * @param what The "what" parameter for the expiration message.
     * @param label A name for this instance.
     */
    public AnrTimer(@NonNull Handler handler, int what, @NonNull String label) {
        this(handler, what, label, false);
    }

    /**
     * Return true if the service is enabled on this instance.  Clients should use this method to
     * decide if the feature is enabled, and not read the flags directly.  This method should be
     * deleted if and when the feature is enabled permanently.
     *
     * @return true if the service is flag-enabled.
     */
    public boolean serviceEnabled() {
        return mFeature.enabled();
    }

    /**
     * Start a trace on the timer.  The trace is laid down in the AnrTimerTrack.
     */
    private void traceBegin(int timerId, int pid, int uid, String what) {
        if (ENABLE_TRACING) {
            final String label = formatSimple("%s(%d,%d,%s)", what, pid, uid, mLabel);
            final int cookie = timerId;
            Trace.asyncTraceForTrackBegin(TRACE_TAG, TRACK, label, cookie);
        }
    }

    /**
     * End a trace on the timer.
     */
    private void traceEnd(int timerId) {
        if (ENABLE_TRACING) {
            final int cookie = timerId;
            Trace.asyncTraceForTrackEnd(TRACE_TAG, TRACK, cookie);
        }
    }

    /**
     * The FeatureSwitch class provides a quick switch between feature-enabled behavior and
     * feature-disabled behavior.
     */
    private abstract class FeatureSwitch {
        abstract void start(@NonNull V arg, int pid, int uid, long timeoutMs);

        abstract boolean cancel(@NonNull V arg);

        abstract boolean accept(@NonNull V arg);

        abstract boolean discard(@NonNull V arg);

        abstract boolean enabled();

        abstract void dump(PrintWriter pw, boolean verbose);

        abstract void close();
    }

    /**
     * The FeatureDisabled class bypasses almost all AnrTimer logic.  It is used when the AnrTimer
     * service is disabled via Flags.anrTimerServiceEnabled.
     */
    private class FeatureDisabled extends FeatureSwitch {
        /** Start a timer by sending a message to the client's handler. */
        @Override
        void start(@NonNull V arg, int pid, int uid, long timeoutMs) {
            final Message msg = mHandler.obtainMessage(mWhat, arg);
            mHandler.sendMessageDelayed(msg, timeoutMs);
        }

        /** Cancel a timer by removing the message from the client's handler. */
        @Override
        boolean cancel(@NonNull V arg) {
            mHandler.removeMessages(mWhat, arg);
            return true;
        }

        /** accept() is a no-op when the feature is disabled. */
        @Override
        boolean accept(@NonNull V arg) {
            return true;
        }

        /** discard() is a no-op when the feature is disabled. */
        @Override
        boolean discard(@NonNull V arg) {
            return true;
        }

        /** The feature is not enabled. */
        @Override
        boolean enabled() {
            return false;
        }

        /** dump() is a no-op when the feature is disabled. */
        @Override
        void dump(PrintWriter pw, boolean verbose) {
        }

        /** close() is a no-op when the feature is disabled. */
        @Override
        void close() {
        }
    }

    /**
     * A static list of AnrTimer instances.  The list is traversed by dumpsys.  Only instances
     * using native resources are included.
     */
    @GuardedBy("sAnrTimerList")
    private static final LongSparseArray<WeakReference<AnrTimer>> sAnrTimerList =
        new LongSparseArray<>();

    /**
     * The FeatureEnabled class enables the AnrTimer logic.  It is used when the AnrTimer service
     * is enabled via Flags.anrTimerServiceEnabled.
     */
    private class FeatureEnabled extends FeatureSwitch {

        /**
         * The native timer that supports this instance. The value is set to non-zero when the
         * native timer is created and it is set back to zero when the native timer is freed.
         */
        private long mNative = 0;

        /** Fetch the native tag (an integer) for the given label. */
        FeatureEnabled() {
            mNative = nativeAnrTimerCreate(mLabel);
            if (mNative == 0) throw new IllegalArgumentException("unable to create native timer");
            synchronized (sAnrTimerList) {
                sAnrTimerList.put(mNative, new WeakReference(AnrTimer.this));
            }
        }

        /**
         * Start a timer.
         */
        @Override
        void start(@NonNull V arg, int pid, int uid, long timeoutMs) {
            synchronized (mLock) {
                if (mTimerIdMap.containsKey(arg)) {
                    // There is an existing timer.  Cancel it.
                    cancel(arg);
                }
                int timerId = nativeAnrTimerStart(mNative, pid, uid, timeoutMs, mExtend);
                if (timerId > 0) {
                    mTimerIdMap.put(arg, timerId);
                    mTimerArgMap.put(timerId, arg);
                    mTotalStarted++;
                    mMaxStarted = Math.max(mMaxStarted, mTimerIdMap.size());
                } else {
                    throw new RuntimeException("unable to start timer");
                }
            }
        }

        /**
         * Cancel a timer.  No error is reported if the timer is not found because some clients
         * cancel timers from common code that runs even if a timer was never started.
         */
        @Override
        boolean cancel(@NonNull V arg) {
            synchronized (mLock) {
                Integer timer = removeLocked(arg);
                if (timer == null) {
                    return false;
                }
                if (!nativeAnrTimerCancel(mNative, timer)) {
                    // There may be an expiration message in flight.  Cancel it.
                    mHandler.removeMessages(mWhat, arg);
                    return false;
                }
                return true;
            }
        }

        /**
         * Accept a timer in the framework-level handler.  The timeout has been accepted and the
         * timeout handler is executing.
         */
        @Override
        boolean accept(@NonNull V arg) {
            synchronized (mLock) {
                Integer timer = removeLocked(arg);
                if (timer == null) {
                    notFoundLocked("accept", arg);
                    return false;
                }
                nativeAnrTimerAccept(mNative, timer);
                traceEnd(timer);
                return true;
            }
        }

        /**
         * Discard a timer in the framework-level handler.  For whatever reason, the timer is no
         * longer interesting.  No statistics are collected.  Return false if the time was not
         * found.
         */
        @Override
        boolean discard(@NonNull V arg) {
            synchronized (mLock) {
                Integer timer = removeLocked(arg);
                if (timer == null) {
                    notFoundLocked("discard", arg);
                    return false;
                }
                nativeAnrTimerDiscard(mNative, timer);
                traceEnd(timer);
                return true;
            }
        }

        /** The feature is enabled. */
        @Override
        boolean enabled() {
            return true;
        }

        /** Dump statistics from the native layer. */
        @Override
        void dump(PrintWriter pw, boolean verbose) {
            synchronized (mLock) {
                if (mNative != 0) {
                    nativeAnrTimerDump(mNative, verbose);
                } else {
                    pw.println("closed");
                }
            }
        }

        /** Free native resources. */
        @Override
        void close() {
            // Remove self from the list of active timers.
            synchronized (sAnrTimerList) {
                sAnrTimerList.remove(mNative);
            }
            synchronized (mLock) {
                if (mNative != 0) nativeAnrTimerClose(mNative);
                mNative = 0;
            }
        }

        /**
         * Delete the entries associated with arg from the maps and return the ID of the timer, if
         * any.
         */
        @GuardedBy("mLock")
        private Integer removeLocked(V arg) {
            Integer r = mTimerIdMap.remove(arg);
            if (r != null) {
                synchronized (mTimerArgMap) {
                    mTimerArgMap.remove(r);
                }
            }
            return r;
        }
    }

    /**
     * Start a timer associated with arg.  The same object must be used to cancel, accept, or
     * discard a timer later.  If a timer already exists with the same arg, then the existing timer
     * is canceled and a new timer is created.  The timeout is signed but negative delays are
     * nonsensical.  Rather than throw an exception, timeouts less than 0ms are forced to 0ms.  This
     * allows a client to deliver an immediate timeout via the AnrTimer.
     *
     * @param arg The key by which the timer is known.  This is never examined or modified.
     * @param pid The Linux process ID of the target being timed.
     * @param uid The Linux user ID of the target being timed.
     * @param timeoutMs The timer timeout, in milliseconds.
     */
    public void start(@NonNull V arg, int pid, int uid, long timeoutMs) {
        if (timeoutMs < 0) timeoutMs = 0;
        mFeature.start(arg, pid, uid, timeoutMs);
    }

    /**
     * Cancel the running timer associated with arg.  The timer is forgotten.  If the timer has
     * expired, the call is treated as a discard.  The function returns true if a running timer was
     * found, and false if an expired timer was found or if no timer was found.  After this call,
     * the timer does not exist.
     *
     * Note: the return value is always true if the feature is not enabled.
     *
     * @param arg The key by which the timer is known.  This is never examined or modified.
     * @return True if a running timer was canceled.
     */
    public boolean cancel(@NonNull V arg) {
        return mFeature.cancel(arg);
    }

    /**
     * Accept the expired timer associated with arg.  This indicates that the caller considers the
     * timer expiration to be a true ANR.  (See {@link #discard} for an alternate response.)  The
     * function returns true if an expired timer was found and false if a running timer was found or
     * if no timer was found.  After this call, the timer does not exist.  It is an error to accept
     * a running timer, however, the running timer will be canceled.
     *
     * Note: the return value is always true if the feature is not enabled.
     *
     * @param arg The key by which the timer is known.  This is never examined or modified.
     * @return True if an expired timer was accepted.
     */
    public boolean accept(@NonNull V arg) {
        return mFeature.accept(arg);
    }

    /**
     * Discard the expired timer associated with arg.  This indicates that the caller considers the
     * timer expiration to be a false ANR.  ((See {@link #accept} for an alternate response.)  One
     * reason to discard an expired timer is if the process being timed was also being debugged:
     * such a process could be stopped at a breakpoint and its failure to respond would not be an
     * error.  After this call thie timer does not exist. It is an error to discard a running timer,
     * however the running timer will be canceled.
     *
     * Note: the return value is always true if the feature is not enabled.
     *
     * @param arg The key by which the timer is known.  This is never examined or modified.
     * @return True if an expired timer was discarded.
     */
    public boolean discard(@NonNull V arg) {
        return mFeature.discard(arg);
    }

    /**
     * The notifier that a timer has fired.  The timerId and original pid/uid are supplied.  This
     * method is called from native code.  This method takes mLock so that a timer cannot expire
     * in the middle of another operation (like start or cancel).
     */
    @Keep
    private boolean expire(int timerId, int pid, int uid) {
        traceBegin(timerId, pid, uid, "expired");
        V arg = null;
        synchronized (mLock) {
            arg = mTimerArgMap.get(timerId);
            if (arg == null) {
                Log.e(TAG, formatSimple("failed to expire timer %s:%d : arg not found",
                                mLabel, timerId));
                mTotalErrors++;
                return false;
            }
            mTotalExpired++;
        }
        mHandler.sendMessage(Message.obtain(mHandler, mWhat, arg));
        return true;
    }

    /**
     * Close the object and free any native resources.
     */
    public void close() {
        mFeature.close();
    }

    /**
     * Ensure any native resources are freed when the object is GC'ed.  Best practice is to close
     * the object explicitly, but overriding finalize() avoids accidental leaks.
     */
    @SuppressWarnings("Finalize")
    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }

    /**
     * Dump a single AnrTimer.
     */
    private void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.format("timer: %s\n", mLabel);
            pw.increaseIndent();
            pw.format("started=%d maxStarted=%d running=%d expired=%d errors=%d\n",
                    mTotalStarted, mMaxStarted, mTimerIdMap.size(),
                    mTotalExpired, mTotalErrors);
            pw.decreaseIndent();
            mFeature.dump(pw, false);
        }
    }

    /**
     * Enable or disable debugging.
     */
    static void debug(boolean f) {
        DEBUG = f;
    }

    /**
     * The current time in milliseconds.
     */
    private static long now() {
        return SystemClock.uptimeMillis();
    }

    /**
     * Dump all errors to the output stream.
     */
    private static void dumpErrors(IndentingPrintWriter ipw) {
        Error errors[];
        synchronized (sErrors) {
            if (sErrors.size() == 0) return;
            errors = sErrors.toArray();
        }
        ipw.println("Errors");
        ipw.increaseIndent();
        for (int i = 0; i < errors.length; i++) {
            if (errors[i] != null) errors[i].dump(ipw, i);
        }
        ipw.decreaseIndent();
    }

    /**
     * Log an error.  A limited stack trace leading to the client call that triggered the error is
     * recorded.  The stack trace assumes that this method is not called directly.
     *
     * If DEBUG is true, a log message is generated as well.
     */
    @GuardedBy("mLock")
    private void recordErrorLocked(String operation, String errorMsg, Object arg) {
        StackTraceElement[] s = Thread.currentThread().getStackTrace();
        final String what = Objects.toString(arg);
        // The copy range starts at the caller of the timer operation, and includes three levels.
        // This should be enough to isolate the location of the call.
        StackTraceElement[] location = Arrays.copyOfRange(s, 6, 9);
        synchronized (sErrors) {
            sErrors.append(new Error(errorMsg, operation, mLabel, location, what));
        }
        if (DEBUG) Log.w(TAG, operation + " " + errorMsg + " " + mLabel + " timer " + what);
        mTotalErrors++;
    }

    /** Record an error about a timer not found. */
    @GuardedBy("mLock")
    private void notFoundLocked(String operation, Object arg) {
        recordErrorLocked(operation, "notFound", arg);
    }

    /** Dumpsys output, allowing for overrides. */
    @VisibleForTesting
    static void dump(@NonNull PrintWriter pw, boolean verbose, @NonNull Injector injector) {
        if (!injector.anrTimerServiceEnabled()) return;

        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.println("AnrTimer statistics");
        ipw.increaseIndent();
        synchronized (sAnrTimerList) {
            final int size = sAnrTimerList.size();
            ipw.println("reporting " + size + " timers");
            for (int i = 0; i < size; i++) {
                AnrTimer a = sAnrTimerList.valueAt(i).get();
                if (a != null) a.dump(ipw);
            }
        }
        if (verbose) dumpErrors(ipw);
        ipw.format("AnrTimerEnd\n");
        ipw.decreaseIndent();
    }

    /** Dumpsys output.  There is no output if the feature is not enabled. */
    public static void dump(@NonNull PrintWriter pw, boolean verbose) {
        dump(pw, verbose, sDefaultInjector);
    }

    /**
     * Return true if the native timers are supported.  Native timers are supported if the method
     * nativeAnrTimerSupported() can be executed and it returns true.
     */
    private static boolean nativeTimersSupported() {
        try {
            return nativeAnrTimerSupported();
        } catch (java.lang.UnsatisfiedLinkError e) {
            return false;
        }
    }

    /**
     * Native methods
     */

    /** Return true if the native AnrTimer code is operational. */
    private static native boolean nativeAnrTimerSupported();

    /**
     * Create a new native timer with the given key and name.  The key is not used by the native
     * code but it is returned to the Java layer in the expiration handler.  The name is only for
     * logging.  Unlike the other methods, this is an instance method: the "this" parameter is
     * passed into the native layer.
     */
    private native long nativeAnrTimerCreate(String name);

    /** Release the native resources.  No further operations are premitted. */
    private static native int nativeAnrTimerClose(long service);

    /** Start a timer and return its ID.  Zero is returned on error. */
    private static native int nativeAnrTimerStart(long service, int pid, int uid, long timeoutMs,
            boolean extend);

    /**
     * Cancel a timer by ID.  Return true if the timer was running and canceled.  Return false if
     * the timer was not found or if the timer had already expired.
     */
    private static native boolean nativeAnrTimerCancel(long service, int timerId);

    /** Accept an expired timer by ID.  Return true if the timer was found. */
    private static native boolean nativeAnrTimerAccept(long service, int timerId);

    /** Discard an expired timer by ID.  Return true if the timer was found.  */
    private static native boolean nativeAnrTimerDiscard(long service, int timerId);

    /** Prod the native library to log a few statistics. */
    private static native void nativeAnrTimerDump(long service, boolean verbose);

    // This is not a native method but it is a native interface, in the sense that it is called from
    // the native layer to report timer expiration.  The function must return true if the expiration
    // message is delivered to the upper layers and false if it could not be delivered.
    // private boolean expire(int timerId, int pid, int uid);
}
