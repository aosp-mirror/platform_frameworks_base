/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.StringJoiner;


/**
 * Class to centralize logging functionality for tethering.
 *
 * All access to class methods other than dump() must be on the same thread.
 *
 * TODO: this is a copy of SharedLog in the NetworkStack. Remove after Tethering is migrated.
 * @hide
 */
public class SharedLog {
    private static final int DEFAULT_MAX_RECORDS = 500;
    private static final String COMPONENT_DELIMITER = ".";

    private enum Category {
        NONE,
        ERROR,
        MARK,
        WARN,
    };

    private final LocalLog mLocalLog;
    // The tag to use for output to the system log. This is not output to the
    // LocalLog because that would be redundant.
    private final String mTag;
    // The component (or subcomponent) of a system that is sharing this log.
    // This can grow in depth if components call forSubComponent() to obtain
    // their SharedLog instance. The tag is not included in the component for
    // brevity.
    private final String mComponent;

    public SharedLog(String tag) {
        this(DEFAULT_MAX_RECORDS, tag);
    }

    public SharedLog(int maxRecords, String tag) {
        this(new LocalLog(maxRecords), tag, tag);
    }

    private SharedLog(LocalLog localLog, String tag, String component) {
        mLocalLog = localLog;
        mTag = tag;
        mComponent = component;
    }

    public String getTag() {
        return mTag;
    }

    /**
     * Create a SharedLog based on this log with an additional component prefix on each logged line.
     */
    public SharedLog forSubComponent(String component) {
        if (!isRootLogInstance()) {
            component = mComponent + COMPONENT_DELIMITER + component;
        }
        return new SharedLog(mLocalLog, mTag, component);
    }

    /**
     * Dump the contents of this log.
     *
     * <p>This method may be called on any thread.
     */
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mLocalLog.readOnlyLocalLog().dump(fd, writer, args);
    }

    //////
    // Methods that both log an entry and emit it to the system log.
    //////

    /**
     * Log an error due to an exception. This does not include the exception stacktrace.
     *
     * <p>The log entry will be also added to the system log.
     * @see #e(String, Throwable)
     */
    public void e(Exception e) {
        Log.e(mTag, record(Category.ERROR, e.toString()));
    }

    /**
     * Log an error message.
     *
     * <p>The log entry will be also added to the system log.
     */
    public void e(String msg) {
        Log.e(mTag, record(Category.ERROR, msg));
    }

    /**
     * Log an error due to an exception, with the exception stacktrace if provided.
     *
     * <p>The error and exception message appear in the shared log, but the stacktrace is only
     * logged in general log output (logcat). The log entry will be also added to the system log.
     */
    public void e(@NonNull String msg, @Nullable Throwable exception) {
        if (exception == null) {
            e(msg);
            return;
        }
        Log.e(mTag, record(Category.ERROR, msg + ": " + exception.getMessage()), exception);
    }

    /**
     * Log an informational message.
     *
     * <p>The log entry will be also added to the system log.
     */
    public void i(String msg) {
        Log.i(mTag, record(Category.NONE, msg));
    }

    /**
     * Log a warning message.
     *
     * <p>The log entry will be also added to the system log.
     */
    public void w(String msg) {
        Log.w(mTag, record(Category.WARN, msg));
    }

    //////
    // Methods that only log an entry (and do NOT emit to the system log).
    //////

    /**
     * Log a general message to be only included in the in-memory log.
     *
     * <p>The log entry will *not* be added to the system log.
     */
    public void log(String msg) {
        record(Category.NONE, msg);
    }

    /**
     * Log a general, formatted message to be only included in the in-memory log.
     *
     * <p>The log entry will *not* be added to the system log.
     * @see String#format(String, Object...)
     */
    public void logf(String fmt, Object... args) {
        log(String.format(fmt, args));
    }

    /**
     * Log a message with MARK level.
     *
     * <p>The log entry will *not* be added to the system log.
     */
    public void mark(String msg) {
        record(Category.MARK, msg);
    }

    private String record(Category category, String msg) {
        final String entry = logLine(category, msg);
        mLocalLog.log(entry);
        return entry;
    }

    private String logLine(Category category, String msg) {
        final StringJoiner sj = new StringJoiner(" ");
        if (!isRootLogInstance()) sj.add("[" + mComponent + "]");
        if (category != Category.NONE) sj.add(category.toString());
        return sj.add(msg).toString();
    }

    // Check whether this SharedLog instance is nominally the top level in
    // a potential hierarchy of shared logs (the root of a tree),
    // or is a subcomponent within the hierarchy.
    private boolean isRootLogInstance() {
        return TextUtils.isEmpty(mComponent) || mComponent.equals(mTag);
    }
}
