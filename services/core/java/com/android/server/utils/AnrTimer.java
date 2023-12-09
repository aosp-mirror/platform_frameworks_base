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

import android.annotation.NonNull;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.Trace;
import android.text.format.TimeMigrationUtils;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.RingBuffer;

import java.io.PrintWriter;
import java.util.Arrays;
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
 * @hide
 */
public class AnrTimer<V> {

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
     * Return true if the feature is enabled.  By default, the value is take from the Flags class
     * but it can be changed for local testing.
     */
    private static boolean anrTimerServiceEnabled() {
        return Flags.anrTimerServiceEnabled();
    }

    /**
     * This class allows test code to provide instance-specific overrides.
     */
    static class Injector {
        boolean anrTimerServiceEnabled() {
            return AnrTimer.anrTimerServiceEnabled();
        }
    }

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

    /**
     * The total number of timers started.
     */
    @GuardedBy("mLock")
    private int mTotalStarted = 0;

    /**
     * The total number of errors detected.
     */
    @GuardedBy("mLock")
    private int mTotalErrors = 0;

    /**
     * The handler for messages sent from this instance.
     */
    private final Handler mHandler;

    /**
     * The message type for messages sent from this interface.
     */
    private final int mWhat;

    /**
     * A label that identifies the AnrTimer associated with a Timer in log messages.
     */
    private final String mLabel;

    /**
     * Whether this timer instance supports extending timeouts.
     */
    private final boolean mExtend;

    /**
     * The top-level switch for the feature enabled or disabled.
     */
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
        mFeature = new FeatureDisabled();
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
        this(handler, what, label, extend, new Injector());
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
     * The FeatureSwitch class provides a quick switch between feature-enabled behavior and
     * feature-disabled behavior.
     */
    private abstract class FeatureSwitch {
        abstract void start(@NonNull V arg, int pid, int uid, long timeoutMs);

        abstract void cancel(@NonNull V arg);

        abstract void accept(@NonNull V arg);

        abstract void discard(@NonNull V arg);

        abstract boolean enabled();
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
        void cancel(@NonNull V arg) {
            mHandler.removeMessages(mWhat, arg);
        }

        /** accept() is a no-op when the feature is disabled. */
        @Override
        void accept(@NonNull V arg) {
        }

        /** discard() is a no-op when the feature is disabled. */
        @Override
        void discard(@NonNull V arg) {
        }

        /** The feature is not enabled. */
        @Override
        boolean enabled() {
            return false;
        }
    }

    /**
     * Start a timer associated with arg.  The same object must be used to cancel, accept, or
     * discard a timer later.  If a timer already exists with the same arg, then the existing timer
     * is canceled and a new timer is created.
     *
     * @param arg The key by which the timer is known.  This is never examined or modified.
     * @param pid The Linux process ID of the target being timed.
     * @param uid The Linux user ID of the target being timed.
     * @param timeoutMs The timer timeout, in milliseconds.
     */
    public void start(@NonNull V arg, int pid, int uid, long timeoutMs) {
        mFeature.start(arg, pid, uid, timeoutMs);
    }

    /**
     * Cancel the running timer associated with arg.  The timer is forgotten.  If the timer has
     * expired, the call is treated as a discard.  No errors are reported if the timer does not
     * exist or if the timer has expired.
     */
    public void cancel(@NonNull V arg) {
        mFeature.cancel(arg);
    }

    /**
     * Accept the expired timer associated with arg.  This indicates that the caller considers the
     * timer expiration to be a true ANR.  (See {@link #discard} for an alternate response.)  It is
     * an error to accept a running timer, however the running timer will be canceled.
     */
    public void accept(@NonNull V arg) {
        mFeature.accept(arg);
    }

    /**
     * Discard the expired timer associated with arg.  This indicates that the caller considers the
     * timer expiration to be a false ANR.  ((See {@link #accept} for an alternate response.)  One
     * reason to discard an expired timer is if the process being timed was also being debugged:
     * such a process could be stopped at a breakpoint and its failure to respond would not be an
     * error.  It is an error to discard a running timer, however the running timer will be
     * canceled.
     */
    public void discard(@NonNull V arg) {
        mFeature.discard(arg);
    }

    /**
     * Dump a single AnrTimer.
     */
    private void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.format("timer: %s\n", mLabel);
            pw.increaseIndent();
            pw.format("started=%d errors=%d\n", mTotalStarted, mTotalErrors);
            pw.decreaseIndent();
        }
    }

    /**
     * Enable or disable debugging.
     */
    static void debug(boolean f) {
        DEBUG = f;
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

    /**
     * Log an error about  a timer not found.
     */
    @GuardedBy("mLock")
    private void notFoundLocked(String operation, Object arg) {
        recordErrorLocked(operation, "notFound", arg);
    }

    /**
     * Dumpsys output.
     */
    public static void dump(@NonNull PrintWriter pw, boolean verbose) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.println("AnrTimer statistics");
        ipw.increaseIndent();
        if (verbose) dumpErrors(ipw);
        ipw.format("AnrTimerEnd\n");
        ipw.decreaseIndent();
    }
}
