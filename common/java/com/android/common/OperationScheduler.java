/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.common;

import android.content.SharedPreferences;
import android.net.http.AndroidHttpClient;
import android.text.format.Time;

import java.util.Map;
import java.util.TreeSet;

/**
 * Tracks the success/failure history of a particular network operation in
 * persistent storage and computes retry strategy accordingly.  Handles
 * exponential backoff, periodic rescheduling, event-driven triggering,
 * retry-after moratorium intervals, etc. based on caller-specified parameters.
 *
 * <p>This class does not directly perform or invoke any operations,
 * it only keeps track of the schedule.  Somebody else needs to call
 * {@link #getNextTimeMillis()} as appropriate and do the actual work.
 */
public class OperationScheduler {
    /** Tunable parameter options for {@link #getNextTimeMillis}. */
    public static class Options {
        /** Wait this long after every error before retrying. */
        public long backoffFixedMillis = 0;

        /** Wait this long times the number of consecutive errors so far before retrying. */
        public long backoffIncrementalMillis = 5000;

        /** Maximum duration of moratorium to honor.  Mostly an issue for clock rollbacks. */
        public long maxMoratoriumMillis = 24 * 3600 * 1000;

        /** Minimum duration after success to wait before allowing another trigger. */
        public long minTriggerMillis = 0;

        /** Automatically trigger this long after the last success. */
        public long periodicIntervalMillis = 0;

        @Override
        public String toString() {
            return String.format(
                    "OperationScheduler.Options[backoff=%.1f+%.1f max=%.1f min=%.1f period=%.1f]",
                    backoffFixedMillis / 1000.0, backoffIncrementalMillis / 1000.0,
                    maxMoratoriumMillis / 1000.0, minTriggerMillis / 1000.0,
                    periodicIntervalMillis / 1000.0);
        }
    }

    private static final String PREFIX = "OperationScheduler_";
    private final SharedPreferences mStorage;

    /**
     * Initialize the scheduler state.
     * @param storage to use for recording the state of operations across restarts/reboots
     */
    public OperationScheduler(SharedPreferences storage) {
        mStorage = storage;
    }

    /**
     * Parse scheduler options supplied in this string form:
     *
     * <pre>
     * backoff=(fixed)+(incremental) max=(maxmoratorium) min=(mintrigger) [period=](interval)
     * </pre>
     *
     * All values are times in (possibly fractional) <em>seconds</em> (not milliseconds).
     * Omitted settings are left at whatever existing default value was passed in.
     *
     * <p>
     * The default options: <code>backoff=0+5 max=86400 min=0 period=0</code><br>
     * Fractions are OK: <code>backoff=+2.5 period=10.0</code><br>
     * The "period=" can be omitted: <code>3600</code><br>
     *
     * @param spec describing some or all scheduler options.
     * @param options to update with parsed values.
     * @return the options passed in (for convenience)
     * @throws IllegalArgumentException if the syntax is invalid
     */
    public static Options parseOptions(String spec, Options options)
            throws IllegalArgumentException {
        for (String param : spec.split(" +")) {
            if (param.length() == 0) continue;
            if (param.startsWith("backoff=")) {
                int plus = param.indexOf('+', 8);
                if (plus < 0) {
                    options.backoffFixedMillis = parseSeconds(param.substring(8));
                } else {
                    if (plus > 8) {
                        options.backoffFixedMillis = parseSeconds(param.substring(8, plus));
                    }
                    options.backoffIncrementalMillis = parseSeconds(param.substring(plus + 1));
                }
            } else if (param.startsWith("max=")) {
                options.maxMoratoriumMillis = parseSeconds(param.substring(4));
            } else if (param.startsWith("min=")) {
                options.minTriggerMillis = parseSeconds(param.substring(4));
            } else if (param.startsWith("period=")) {
                options.periodicIntervalMillis = parseSeconds(param.substring(7));
            } else {
                options.periodicIntervalMillis = parseSeconds(param);
            }
        }
        return options;
    }

    private static long parseSeconds(String param) throws NumberFormatException {
        return (long) (Float.parseFloat(param) * 1000);
    }

    /**
     * Compute the time of the next operation.  Does not modify any state
     * (unless the clock rolls backwards, in which case timers are reset).
     *
     * @param options to use for this computation.
     * @return the wall clock time ({@link System#currentTimeMillis()}) when the
     * next operation should be attempted -- immediately, if the return value is
     * before the current time.
     */
    public long getNextTimeMillis(Options options) {
        boolean enabledState = mStorage.getBoolean(PREFIX + "enabledState", true);
        if (!enabledState) return Long.MAX_VALUE;

        boolean permanentError = mStorage.getBoolean(PREFIX + "permanentError", false);
        if (permanentError) return Long.MAX_VALUE;

        // We do quite a bit of limiting to prevent a clock rollback from totally
        // hosing the scheduler.  Times which are supposed to be in the past are
        // clipped to the current time so we don't languish forever.

        int errorCount = mStorage.getInt(PREFIX + "errorCount", 0);
        long now = currentTimeMillis();
        long lastSuccessTimeMillis = getTimeBefore(PREFIX + "lastSuccessTimeMillis", now);
        long lastErrorTimeMillis = getTimeBefore(PREFIX + "lastErrorTimeMillis", now);
        long triggerTimeMillis = mStorage.getLong(PREFIX + "triggerTimeMillis", Long.MAX_VALUE);
        long moratoriumSetMillis = getTimeBefore(PREFIX + "moratoriumSetTimeMillis", now);
        long moratoriumTimeMillis = getTimeBefore(PREFIX + "moratoriumTimeMillis",
                moratoriumSetMillis + options.maxMoratoriumMillis);

        long time = triggerTimeMillis;
        if (options.periodicIntervalMillis > 0) {
            time = Math.min(time, lastSuccessTimeMillis + options.periodicIntervalMillis);
        }

        time = Math.max(time, moratoriumTimeMillis);
        time = Math.max(time, lastSuccessTimeMillis + options.minTriggerMillis);
        if (errorCount > 0) {
            time = Math.max(time, lastErrorTimeMillis + options.backoffFixedMillis +
                    options.backoffIncrementalMillis * errorCount);
        }
        return time;
    }

    /**
     * Return the last time the operation completed.  Does not modify any state.
     *
     * @return the wall clock time when {@link #onSuccess()} was last called.
     */
    public long getLastSuccessTimeMillis() {
        return mStorage.getLong(PREFIX + "lastSuccessTimeMillis", 0);
    }

    /**
     * Return the last time the operation was attempted.  Does not modify any state.
     *
     * @return the wall clock time when {@link #onSuccess()} or {@link
     * #onTransientError()} was last called.
     */
    public long getLastAttemptTimeMillis() {
        return Math.max(
                mStorage.getLong(PREFIX + "lastSuccessTimeMillis", 0),
                mStorage.getLong(PREFIX + "lastErrorTimeMillis", 0));
    }

    /**
     * Fetch a {@link SharedPreferences} property, but force it to be before
     * a certain time, updating the value if necessary.  This is to recover
     * gracefully from clock rollbacks which could otherwise strand our timers.
     *
     * @param name of SharedPreferences key
     * @param max time to allow in result
     * @return current value attached to key (default 0), limited by max
     */
    private long getTimeBefore(String name, long max) {
        long time = mStorage.getLong(name, 0);
        if (time > max) mStorage.edit().putLong(name, (time = max)).commit();
        return time;
    }

    /**
     * Request an operation to be performed at a certain time.  The actual
     * scheduled time may be affected by error backoff logic and defined
     * minimum intervals.  Use {@link Long#MAX_VALUE} to disable triggering.
     *
     * @param millis wall clock time ({@link System#currentTimeMillis()}) to
     * trigger another operation; 0 to trigger immediately
     */
    public void setTriggerTimeMillis(long millis) {
        mStorage.edit().putLong(PREFIX + "triggerTimeMillis", millis).commit();
    }

    /**
     * Forbid any operations until after a certain (absolute) time.
     * Limited by {@link #Options.maxMoratoriumMillis}.
     *
     * @param millis wall clock time ({@link System#currentTimeMillis()})
     * when operations should be allowed again; 0 to remove moratorium
     */
    public void setMoratoriumTimeMillis(long millis) {
        mStorage.edit()
                .putLong(PREFIX + "moratoriumTimeMillis", millis)
                .putLong(PREFIX + "moratoriumSetTimeMillis", currentTimeMillis())
                .commit();
    }

    /**
     * Forbid any operations until after a certain time, as specified in
     * the format used by the HTTP "Retry-After" header.
     * Limited by {@link #Options.maxMoratoriumMillis}.
     *
     * @param retryAfter moratorium time in HTTP format
     * @return true if a time was successfully parsed
     */
    public boolean setMoratoriumTimeHttp(String retryAfter) {
        try {
            long ms = Long.valueOf(retryAfter) * 1000;
            setMoratoriumTimeMillis(ms + currentTimeMillis());
            return true;
        } catch (NumberFormatException nfe) {
            try {
                setMoratoriumTimeMillis(AndroidHttpClient.parseDate(retryAfter));
                return true;
            } catch (IllegalArgumentException iae) {
                return false;
            }
        }
    }

    /**
     * Enable or disable all operations.  When disabled, all calls to
     * {@link #getNextTimeMillis()} return {@link Long#MAX_VALUE}.
     * Commonly used when data network availability goes up and down.
     *
     * @param enabled if operations can be performed
     */
    public void setEnabledState(boolean enabled) {
        mStorage.edit().putBoolean(PREFIX + "enabledState", enabled).commit();
    }

    /**
     * Report successful completion of an operation.  Resets all error
     * counters, clears any trigger directives, and records the success.
     */
    public void onSuccess() {
        resetTransientError();
        resetPermanentError();
        mStorage.edit()
                .remove(PREFIX + "errorCount")
                .remove(PREFIX + "lastErrorTimeMillis")
                .remove(PREFIX + "permanentError")
                .remove(PREFIX + "triggerTimeMillis")
                .putLong(PREFIX + "lastSuccessTimeMillis", currentTimeMillis()).commit();
    }

    /**
     * Report a transient error (usually a network failure).  Increments
     * the error count and records the time of the latest error for backoff
     * purposes.
     */
    public void onTransientError() {
        mStorage.edit().putLong(PREFIX + "lastErrorTimeMillis", currentTimeMillis()).commit();
        mStorage.edit().putInt(PREFIX + "errorCount",
                mStorage.getInt(PREFIX + "errorCount", 0) + 1).commit();
    }

    /**
     * Reset all transient error counts, allowing the next operation to proceed
     * immediately without backoff.  Commonly used on network state changes, when
     * partial progress occurs (some data received), and in other circumstances
     * where there is reason to hope things might start working better.
     */
    public void resetTransientError() {
        mStorage.edit().remove(PREFIX + "errorCount").commit();
    }

    /**
     * Report a permanent error that will not go away until further notice.
     * No operation will be scheduled until {@link #resetPermanentError()}
     * is called.  Commonly used for authentication failures (which are reset
     * when the accounts database is updated).
     */
    public void onPermanentError() {
        mStorage.edit().putBoolean(PREFIX + "permanentError", true).commit();
    }

    /**
     * Reset any permanent error status set by {@link #onPermanentError},
     * allowing operations to be scheduled as normal.
     */
    public void resetPermanentError() {
        mStorage.edit().remove(PREFIX + "permanentError").commit();
    }

    /**
     * Return a string description of the scheduler state for debugging.
     */
    public String toString() {
        StringBuilder out = new StringBuilder("[OperationScheduler:");
        for (String key : new TreeSet<String>(mStorage.getAll().keySet())) {  // Sort keys
            if (key.startsWith(PREFIX)) {
                if (key.endsWith("TimeMillis")) {
                    Time time = new Time();
                    time.set(mStorage.getLong(key, 0));
                    out.append(" ").append(key.substring(PREFIX.length(), key.length() - 10));
                    out.append("=").append(time.format("%Y-%m-%d/%H:%M:%S"));
                } else {
                    out.append(" ").append(key.substring(PREFIX.length()));
                    out.append("=").append(mStorage.getAll().get(key).toString());
                }
            }
        }
        return out.append("]").toString();
    }

    /**
     * Gets the current time.  Can be overridden for unit testing.
     *
     * @return {@link System#currentTimeMillis()}
     */
    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
