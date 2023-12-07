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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.text.TextUtils;
import android.text.format.TimeMigrationUtils;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.MathUtils;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.Keep;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.util.RingBuffer;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

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
     * Enable tracing from the time a timer expires until it is accepted or discarded.  This is
     * used to diagnose long latencies in the client.
     */
    private static final boolean ENABLE_TRACING = false;

    /**
     * Return true if the feature is enabled.  By default, the value is take from the Flags class
     * but it can be changed for local testing.
     */
    private static boolean anrTimerServiceEnabled() {
        return Flags.anrTimerServiceEnabled();
    }

    /**
     * The status of an ANR timer.  TIMER_INVALID status is returned when an error is detected.
     */
    private static final int TIMER_INVALID = 0;
    private static final int TIMER_RUNNING = 1;
    private static final int TIMER_EXPIRED = 2;

    @IntDef(prefix = { "TIMER_" }, value = {
                TIMER_INVALID, TIMER_RUNNING, TIMER_EXPIRED
            })
    private @interface TimerStatus {}

    /**
     * A static list of all known AnrTimer instances, used for dumping and testing.
     */
    @GuardedBy("sAnrTimerList")
    private static final ArrayList<WeakReference<AnrTimer>> sAnrTimerList = new ArrayList<>();

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
    }

    /**
     * A list of errors detected during processing.  Errors correspond to "timer not found"
     * conditions.  The stack trace identifies the source of the call.  The list is
     * first-in/first-out, and the size is limited to 20.
     */
    @GuardedBy("sErrors")
    private static final RingBuffer<Error> sErrors = new RingBuffer<>(Error.class, 20);

    /**
     * A record of a single anr timer.  The pid and uid are retained for reference but they do not
     * participate in the equality tests. A {@link Timer} is bound to its parent {@link AnrTimer}
     * through the owner field.  Access to timer fields is guarded by the mLock of the owner.
     */
    private static class Timer {
        /** The AnrTimer that is managing this Timer. */
        final AnrTimer owner;

        /** The argument that uniquely identifies the Timer in the context of its current owner. */
        final Object arg;
        /** The pid of the process being tracked by this Timer. */
        final int pid;
        /** The uid of the process being tracked by this Timer as reported by the kernel. */
        final int uid;
        /** The original timeout. */
        final long timeoutMs;

        /** The status of the Timer.  */
        @GuardedBy("owner.mLock")
        @TimerStatus
        int status;

        /** The absolute time the timer was startd */
        final long startedMs;

        /** Fields used by the native timer service. */

        /** The timer ID: used to exchange information with the native service. */
        int timerId;

        /** Fields used by the legacy timer service. */

        /**
         * The process's cpu delay time when the timer starts . It is meaningful only if
         * extendable is true.  The cpu delay is cumulative, so the incremental delay that occurs
         * during a timer is the delay at the end of the timer minus this value.  Units are in
         * milliseconds.
         */
        @GuardedBy("owner.mLock")
        long initialCpuDelayMs;

        /** True if the timer has been extended. */
        @GuardedBy("owner.mLock")
        boolean extended;

        /**
         * Fetch a new Timer.  This is private.  Clients should get a new timer using the obtain()
         * method.
         */
        private Timer(int pid, int uid, @Nullable Object arg, long timeoutMs,
                @NonNull AnrTimer service) {
            this.arg = arg;
            this.pid = pid;
            this.uid = uid;
            this.timerId = 0;
            this.timeoutMs = timeoutMs;
            this.startedMs = now();
            this.owner = service;
            this.initialCpuDelayMs = 0;
            this.extended = false;
            this.status = TIMER_INVALID;
        }

        /** Get a timer.  This implementation constructs a new timer. */
        static Timer obtain(int pid, int uid, @Nullable Object arg, long timeout,
                @NonNull AnrTimer service) {
            return new Timer(pid, uid, arg, timeout, service);
        }

        /** Release a timer. This implementation simply drops the timer. */
        void release() {
        }

        /** Return the age of the timer. This is used for debugging. */
        long age() {
            return now() - startedMs;
        }

        /**
         * The hash code is generated from the owner and the argument.  By definition, the
         * combination must be unique for the lifetime of an in-use Timer.
         */
        @Override
        public int hashCode() {
            return Objects.hash(owner, arg);
        }

        /**
         * The equality check compares the owner and the argument.  By definition, the combination
         * must be unique for the lifetime of an in-use Timer.
         */
        @Override
        public boolean equals(Object r) {
            if (r instanceof Timer) {
                Timer t = (Timer) r;
                return Objects.equals(owner, t.owner) && Objects.equals(arg, t.arg);
            }
            return false;
        }

        @Override
        public String toString() {
            final int myStatus;
            synchronized (owner.mLock) {
                myStatus = status;
            }
            return "timerId=" + timerId + " pid=" + pid + " uid=" + uid
                    + " " + statusString(myStatus) + " " + owner.mLabel;
        }
    }

    /** A lock for the AnrTimer instance. */
    private final Object mLock = new Object();

    /**
     * The map from client argument to the associated timer.
     */
    @GuardedBy("mLock")
    private final ArrayMap<V, Timer> mTimerMap = new ArrayMap<>();

    /** The highwater mark of started, but not closed, timers. */
    @GuardedBy("mLock")
    private int mMaxStarted = 0;

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
     * The total number of timers that have expired.
     */
    @GuardedBy("mLock")
    private int mTotalExpired = 0;

    /**
     * A TimerService that generates a timeout event <n> milliseconds in the future.  See the
     * class documentation for an explanation of the operations.
     */
    private abstract class TimerService {
        /** Start a timer.  The timeout must be initialized. */
        abstract boolean start(@NonNull Timer timer);

        abstract void cancel(@NonNull Timer timer);

        abstract void accept(@NonNull Timer timer);

        abstract void discard(@NonNull Timer timer);
    }

    /**
     * A class to assist testing.  All methods are null by default but can be overridden as
     * necessary for a test.
     */
    @VisibleForTesting
    static class Injector {
        private final Handler mReferenceHandler;

        Injector(@NonNull Handler handler) {
            mReferenceHandler = handler;
        }

        /**
         * Return a handler for the given Callback, based on the reference handler. The handler
         * might be mocked, in which case it does not have a valid Looper.  In this case, use the
         * main Looper.
         */
        @NonNull
        Handler newHandler(@NonNull Handler.Callback callback) {
            Looper looper = mReferenceHandler.getLooper();
            if (looper == null) looper = Looper.getMainLooper();
            return new Handler(looper, callback);
        }

        /**
         * Return a CpuTracker. The default behavior is to create a new CpuTracker but this changes
         * for unit tests.
         **/
        @NonNull
        CpuTracker newTracker() {
            return new CpuTracker();
        }

        /** Return true if the feature is enabled. */
        boolean isFeatureEnabled() {
            return anrTimerServiceEnabled();
        }
    }

    /**
     * A helper class to measure CPU delays.  Given a process ID, this class will return the
     * cumulative CPU delay for the PID, since process inception.  This class is defined to assist
     * testing.
     */
    @VisibleForTesting
    static class CpuTracker {
        /**
         * The parameter to ProcessCpuTracker indicates that statistics should be collected on a
         * single process and not on the collection of threads associated with that process.
         */
        private final ProcessCpuTracker mCpu = new ProcessCpuTracker(false);

        /** A simple wrapper to fetch the delay.  This method can be overridden for testing. */
        long delay(int pid) {
            return mCpu.getCpuDelayTimeForPid(pid);
        }
    }

    /**
     * The "user-space" implementation of the timer service.  This service uses its own message
     * handler to create timeouts.
     */
    private class HandlerTimerService extends TimerService {
        /** The lock for this handler */
        private final Object mLock = new Object();

        /** The message handler for scheduling future events. */
        private final Handler mHandler;

        /** The interface to fetch process statistics that might extend an ANR timeout. */
        private final CpuTracker mCpu;

        /** Create a HandlerTimerService that directly uses the supplied handler and tracker. */
        @VisibleForTesting
        HandlerTimerService(@NonNull Injector injector) {
            mHandler = injector.newHandler(this::expires);
            mCpu = injector.newTracker();
        }

        /** Post a message with the specified timeout.  The timer is not modified. */
        private void post(@NonNull Timer t, long timeoutMillis) {
            final Message msg = mHandler.obtainMessage();
            msg.obj = t;
            mHandler.sendMessageDelayed(msg, timeoutMillis);
        }

        /**
         * The local expiration handler first attempts to compute a timer extension.  If the timer
         * should be extended, it is rescheduled in the future (granting more time to the
         * associated process).  If the timer should not be extended then the timeout is delivered
         * to the client.
         *
         * A process is extended to account for the time the process was swapped out and was not
         * runnable through no fault of its own.  A timer can only be extended once and only if
         * the AnrTimer permits extensions.  Finally, a timer will never be extended by more than
         * the original timeout, so the total timeout will never be more than twice the originally
         * configured timeout.
         */
        private boolean expires(Message msg) {
            Timer t = (Timer) msg.obj;
            synchronized (mLock) {
                long extension = 0;
                if (mExtend && !t.extended) {
                    extension = mCpu.delay(t.pid) - t.initialCpuDelayMs;
                    if (extension < 0) extension = 0;
                    if (extension > t.timeoutMs) extension = t.timeoutMs;
                    t.extended = true;
                }
                if (extension > 0) {
                    post(t, extension);
                } else {
                    onExpiredLocked(t);
                }
            }
            return true;
        }

        @GuardedBy("mLock")
        @Override
        boolean start(@NonNull Timer t) {
            if (mExtend) {
                t.initialCpuDelayMs = mCpu.delay(t.pid);
            }
            post(t, t.timeoutMs);
            return true;
        }

        @Override
        void cancel(@NonNull Timer t) {
            mHandler.removeMessages(0, t);
        }

        @Override
        void accept(@NonNull Timer t) {
            // Nothing to do.
        }

        @Override
        void discard(@NonNull Timer t) {
            // Nothing to do.
        }

        /** The string identifies this subclass of AnrTimerService as being based on handlers. */
        @Override
        public String toString() {
            return "handler";
        }
    }

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
     * The timer service to use for this AnrTimer.
     */
    private final TimerService mTimerService;

    /**
     * Whether or not canceling a non-existent timer is an error.  Clients often cancel freely
     * preemptively, without knowing if the timer was ever started.  Keeping this variable true
     * means that such behavior is not an error.
     */
    private final boolean mLenientCancel = true;

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
     * This method accepts an {@link #Injector} to tune behavior for testing.  This method should
     * not be called directly by regular clients.
     *
     * @param handler The handler to which the expiration message will be delivered.
     * @param what The "what" parameter for the expiration message.
     * @param label A name for this instance.
     * @param extend A flag to indicate if expired timers can be granted extensions.
     * @param injector An {@link #Injector} to tune behavior for testing.
     */
    @VisibleForTesting
    AnrTimer(@NonNull Handler handler, int what, @NonNull String label, boolean extend,
            @NonNull Injector injector) {
        mHandler = handler;
        mWhat = what;
        mLabel = label;
        mExtend = extend;
        boolean enabled = injector.isFeatureEnabled();
        if (!enabled) {
            mFeature = new FeatureDisabled();
            mTimerService = null;
        } else {
            mFeature = new FeatureEnabled();
            mTimerService = new HandlerTimerService(injector);

            synchronized (sAnrTimerList) {
                sAnrTimerList.add(new WeakReference(this));
            }
        }
        Log.i(TAG, formatSimple("created %s label: \"%s\"", mTimerService, label));
    }

    /**
     * Create an AnrTimer instance with the default {@link #Injector}.  See {@link AnrTimer(Handler,
     * int, String, boolean, Injector} for a functional description.
     *
     * @param handler The handler to which the expiration message will be delivered.
     * @param what The "what" parameter for the expiration message.
     * @param label A name for this instance.
     * @param extend A flag to indicate if expired timers can be granted extensions.
     */
    public AnrTimer(@NonNull Handler handler, int what, @NonNull String label, boolean extend) {
        this(handler, what, label, extend, new Injector(handler));
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
    private void traceBegin(Timer t, String what) {
        if (ENABLE_TRACING) {
            final String label = formatSimple("%s(%d,%d,%s)", what, t.pid, t.uid, mLabel);
            final int cookie = t.hashCode();
            Trace.asyncTraceForTrackBegin(TRACE_TAG, TRACK, label, cookie);
        }
    }

    /**
     * End a trace on the timer.
     */
    private void traceEnd(Timer t) {
        if (ENABLE_TRACING) {
            final int cookie = t.hashCode();
            Trace.asyncTraceForTrackEnd(TRACE_TAG, TRACK, cookie);
        }
    }

    /**
     * Return the string representation for a timer status.
     */
    private static String statusString(int s) {
        switch (s) {
            case TIMER_INVALID: return "invalid";
            case TIMER_RUNNING: return "running";
            case TIMER_EXPIRED: return "expired";
        }
        return formatSimple("unknown: %d", s);
    }

    /**
     * Delete the timer associated with arg from the maps and return it.  Return null if the timer
     * was not found.
     */
    @GuardedBy("mLock")
    private Timer removeLocked(V arg) {
        Timer timer = mTimerMap.remove(arg);
        return timer;
    }

    /**
     * Return the number of timers currently running.
     */
    @VisibleForTesting
    static int sizeOfTimerList() {
        synchronized (sAnrTimerList) {
            int totalTimers = 0;
            for (int i = 0; i < sAnrTimerList.size(); i++) {
                AnrTimer client = sAnrTimerList.get(i).get();
                if (client != null) totalTimers += client.mTimerMap.size();
            }
            return totalTimers;
        }
    }

    /**
     * Clear out all existing timers.  This will lead to unexpected behavior if used carelessly.
     * It is available only for testing.  It returns the number of times that were actually
     * erased.
     */
    @VisibleForTesting
    static int resetTimerListForHermeticTest() {
        synchronized (sAnrTimerList) {
            int mapLen = 0;
            for (int i = 0; i < sAnrTimerList.size(); i++) {
                AnrTimer client = sAnrTimerList.get(i).get();
                if (client != null) {
                    mapLen += client.mTimerMap.size();
                    client.mTimerMap.clear();
                }
            }
            if (mapLen > 0) {
                Log.w(TAG, formatSimple("erasing timer list: clearing %d timers", mapLen));
            }
            return mapLen;
        }
    }

    /**
     * Generate a log message for a timer.
     */
    private void report(@NonNull Timer timer, @NonNull String msg) {
        Log.i(TAG, msg + " " + timer + " " + Objects.toString(timer.arg));
    }

    /**
     * The FeatureSwitch class provides a quick switch between feature-enabled behavior and
     * feature-disabled behavior.
     */
    private abstract class FeatureSwitch {
        abstract boolean start(@NonNull V arg, int pid, int uid, long timeoutMs);

        abstract boolean cancel(@NonNull V arg);

        abstract boolean accept(@NonNull V arg);

        abstract boolean discard(@NonNull V arg);

        abstract boolean enabled();
    }

    /**
     * The FeatureDisabled class bypasses almost all AnrTimer logic.  It is used when the AnrTimer
     * service is disabled via Flags.anrTimerServiceEnabled.
     */
    private class FeatureDisabled extends FeatureSwitch {
        /** Start a timer by sending a message to the client's handler. */
        @Override
        boolean start(@NonNull V arg, int pid, int uid, long timeoutMs) {
            final Message msg = mHandler.obtainMessage(mWhat, arg);
            mHandler.sendMessageDelayed(msg, timeoutMs);
            return true;
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
    }

    /**
     * The FeatureEnabled class enables the AnrTimer logic.  It is used when the AnrTimer service
     * is enabled via Flags.anrTimerServiceEnabled.
     */
    private class FeatureEnabled extends FeatureSwitch {

        /**
         * Start a timer.
         */
        @Override
        boolean start(@NonNull V arg, int pid, int uid, long timeoutMs) {
            final Timer timer = Timer.obtain(pid, uid, arg, timeoutMs, AnrTimer.this);
            synchronized (mLock) {
                Timer old = mTimerMap.get(arg);
                // There is an existing timer.  If the timer was running, then cancel the running
                // timer and restart it.  If the timer was expired record a protocol error and
                // discard the expired timer.
                if (old != null) {
                    if (old.status == TIMER_EXPIRED) {
                      restartedLocked(old.status, arg);
                        discard(arg);
                    } else {
                        cancel(arg);
                    }
                }
                if (mTimerService.start(timer)) {
                    timer.status = TIMER_RUNNING;
                    mTimerMap.put(arg, timer);
                    mTotalStarted++;
                    mMaxStarted = Math.max(mMaxStarted, mTimerMap.size());
                    if (DEBUG) report(timer, "start");
                    return true;
                } else {
                    Log.e(TAG, "AnrTimer.start failed");
                    return false;
                }
            }
        }

        /**
         * Cancel a timer.  Return false if the timer was not found.
         */
        @Override
        boolean cancel(@NonNull V arg) {
            synchronized (mLock) {
                Timer timer = removeLocked(arg);
                if (timer == null) {
                    if (!mLenientCancel) notFoundLocked("cancel", arg);
                    return false;
                }
                mTimerService.cancel(timer);
                // There may be an expiration message in flight.  Cancel it.
                mHandler.removeMessages(mWhat, arg);
                if (DEBUG) report(timer, "cancel");
                timer.release();
                return true;
            }
        }

        /**
         * Accept a timer in the framework-level handler.  The timeout has been accepted and the
         * timeout handler is executing.  Return false if the timer was not found.
         */
        @Override
        boolean accept(@NonNull V arg) {
            synchronized (mLock) {
                Timer timer = removeLocked(arg);
                if (timer == null) {
                    notFoundLocked("accept", arg);
                    return false;
                }
                mTimerService.accept(timer);
                traceEnd(timer);
                if (DEBUG) report(timer, "accept");
                timer.release();
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
                Timer timer = removeLocked(arg);
                if (timer == null) {
                    notFoundLocked("discard", arg);
                    return false;
                }
                mTimerService.discard(timer);
                traceEnd(timer);
                if (DEBUG) report(timer, "discard");
                timer.release();
                return true;
            }
        }

        /** The feature is enabled. */
        @Override
        boolean enabled() {
            return true;
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
     * @return true if the timer was successfully created.
     */
    public boolean start(@NonNull V arg, int pid, int uid, long timeoutMs) {
        return mFeature.start(arg, pid, uid, timeoutMs);
    }

    /**
     * Cancel the running timer associated with arg.  The timer is forgotten.  If the timer has
     * expired, the call is treated as a discard.  No errors are reported if the timer does not
     * exist or if the timer has expired.
     *
     * @return true if the timer was found and was running.
     */
    public boolean cancel(@NonNull V arg) {
        return mFeature.cancel(arg);
    }

    /**
     * Accept the expired timer associated with arg.  This indicates that the caller considers the
     * timer expiration to be a true ANR.  (See {@link #discard} for an alternate response.)  It is
     * an error to accept a running timer, however the running timer will be canceled.
     *
     * @return true if the timer was found and was expired.
     */
    public boolean accept(@NonNull V arg) {
        return mFeature.accept(arg);
    }

    /**
     * Discard the expired timer associated with arg.  This indicates that the caller considers the
     * timer expiration to be a false ANR.  ((See {@link #accept} for an alternate response.)  One
     * reason to discard an expired timer is if the process being timed was also being debugged:
     * such a process could be stopped at a breakpoint and its failure to respond would not be an
     * error.  It is an error to discard a running timer, however the running timer will be
     * canceled.
     *
     * @return true if the timer was found and was expired.
     */
    public boolean discard(@NonNull V arg) {
        return mFeature.discard(arg);
    }

    /**
     * The notifier that a timer has fired.  The timer is not modified.
     */
    @GuardedBy("mLock")
    private void onExpiredLocked(@NonNull Timer timer) {
        if (DEBUG) report(timer, "expire");
        traceBegin(timer, "expired");
        mHandler.sendMessage(Message.obtain(mHandler, mWhat, timer.arg));
        synchronized (mLock) {
            mTotalExpired++;
        }
    }

    /**
     * Dump a single AnrTimer.
     */
    private void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.format("timer: %s\n", mLabel);
            pw.increaseIndent();
            pw.format("started=%d maxStarted=%d running=%d expired=%d error=%d\n",
                    mTotalStarted, mMaxStarted, mTimerMap.size(),
                    mTotalExpired, mTotalErrors);
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
     * The current time in milliseconds.
     */
    private static long now() {
        return SystemClock.uptimeMillis();
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
     * Log an error about a timer that is started when there is an existing timer.
     */
    @GuardedBy("mLock")
    private void restartedLocked(@TimerStatus int status, Object arg) {
        recordErrorLocked("start", status == TIMER_EXPIRED ? "autoDiscard" : "autoCancel", arg);
    }

    /**
     * Dump a single error to the output stream.
     */
    private static void dump(IndentingPrintWriter ipw, int seq, Error err) {
        ipw.format("%2d: op:%s tag:%s issue:%s arg:%s\n", seq, err.operation, err.tag,
                err.issue, err.arg);

        final long offset = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        final long etime = offset + err.timestamp;
        ipw.println("    date:" + TimeMigrationUtils.formatMillisWithFixedFormat(etime));
        ipw.increaseIndent();
        for (int i = 0; i < err.stack.length; i++) {
            ipw.println("    " + err.stack[i].toString());
        }
        ipw.decreaseIndent();
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
            if (errors[i] != null) dump(ipw, i, errors[i]);
        }
        ipw.decreaseIndent();
    }

    /**
     * Dumpsys output.
     */
    public static void dump(@NonNull PrintWriter pw, boolean verbose) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.println("AnrTimer statistics");
        ipw.increaseIndent();
        synchronized (sAnrTimerList) {
            for (int i = 0; i < sAnrTimerList.size(); i++) {
                AnrTimer client = sAnrTimerList.get(i).get();
                if (client != null) client.dump(ipw);
            }
        }
        if (verbose) dumpErrors(ipw);
        ipw.format("AnrTimerEnd\n");
        ipw.decreaseIndent();
    }
}
