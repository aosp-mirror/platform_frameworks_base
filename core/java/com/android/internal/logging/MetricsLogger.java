/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.internal.logging;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.metrics.LogMaker;
import android.os.Build;
import android.view.View;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.FrameworkStatsLog;

/**
 * Writes sysui_multi_event records to the system event log.
 *
 * Prefer the methods write(LogMaker), or count() or histogram(). Replace legacy methods with
 * their current equivalents when the opportunity arises.
 *
 * This class is a lightweight dependency barrier - it is cheap and easy to construct.
 * Logging is also cheap, so it is not normally necessary to move logging off of the UI thread.
 *
 * @hide
 */
public class MetricsLogger {
    // define metric categories in frameworks/base/proto/src/metrics_constants.proto.
    // mirror changes in native version at system/core/libmetricslogger/metrics_logger.cpp

    private static MetricsLogger sMetricsLogger;

    @UnsupportedAppUsage
    public MetricsLogger() {
    }

    private static MetricsLogger getLogger() {
        if (sMetricsLogger == null) {
            sMetricsLogger = new MetricsLogger();
        }
        return sMetricsLogger;
    }

    protected void saveLog(LogMaker log) {
        // TODO(b/116684537): Flag guard logging to event log and statsd socket.
        EventLogTags.writeSysuiMultiAction(log.serialize());
        FrameworkStatsLog.write(FrameworkStatsLog.KEY_VALUE_PAIRS_ATOM,
                /* UID is retrieved from statsd side */ 0, log.getEntries());
    }

    public static final int VIEW_UNKNOWN = MetricsEvent.VIEW_UNKNOWN;
    public static final int LOGTAG = EventLogTags.SYSUI_MULTI_ACTION;

    /** Write an event log record, consisting of content.serialize(). */
    @UnsupportedAppUsage
    public void write(LogMaker content) {
        if (content.getType() == MetricsEvent.TYPE_UNKNOWN) {
            content.setType(MetricsEvent.TYPE_ACTION);
        }
        saveLog(content);
    }

    /** Add an integer value to the monotonically increasing counter with the given name. */
    public void count(String name, int value) {
        saveLog(new LogMaker(MetricsEvent.RESERVED_FOR_LOGBUILDER_COUNTER)
                .setCounterName(name)
                .setCounterValue(value));
    }

    /** Increment the bucket with the integer label on the histogram with the given name. */
    public void histogram(String name, int bucket) {
        // see LogHistogram in system/core/libmetricslogger/metrics_logger.cpp
        saveLog(new LogMaker(MetricsEvent.RESERVED_FOR_LOGBUILDER_HISTOGRAM)
                .setCounterName(name)
                .setCounterBucket(bucket)
                .setCounterValue(1));
    }

    /* Legacy logging methods follow.  These are all simple shorthands and can be replaced
     * with an equivalent write(). */

    /** Logs an OPEN event on the category.
     *  Equivalent to write(new LogMaker(category).setType(MetricsEvent.TYPE_OPEN)) */
    public void visible(int category) throws IllegalArgumentException {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        saveLog(new LogMaker(category).setType(MetricsEvent.TYPE_OPEN));
    }

    /** Logs a CLOSE event on the category.
     *  Equivalent to write(new LogMaker(category).setType(MetricsEvent.TYPE_CLOSE)) */
    public void hidden(int category) throws IllegalArgumentException {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        saveLog(new LogMaker(category).setType(MetricsEvent.TYPE_CLOSE));
    }

    /** Logs an OPEN or CLOSE event on the category, depending on visible.
     *  Equivalent to write(new LogMaker(category)
     *                     .setType(visible ? MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE)) */
    public void visibility(int category, boolean visible)
            throws IllegalArgumentException {
        if (visible) {
            visible(category);
        } else {
            hidden(category);
        }
    }

    /** Logs an OPEN or CLOSE event on the category, depending on vis.
     *  Equivalent to write(new LogMaker(category)
                           .setType(vis == View.VISIBLE ?
                                    MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE)) */
    public void visibility(int category, int vis)
            throws IllegalArgumentException {
        visibility(category, vis == View.VISIBLE);
    }

    /** Logs an ACTION event on the category.
     * Equivalent to write(new LogMaker(category).setType(MetricsEvent.TYPE_ACTION)) */
    public void action(int category) {
        saveLog(new LogMaker(category).setType(MetricsEvent.TYPE_ACTION));
    }

    /** Logs an ACTION event on the category.
     * Equivalent to write(new LogMaker(category).setType(MetricsEvent.TYPE_ACTION)
                           .setSubtype(value) */
    public void action(int category, int value) {
        saveLog(new LogMaker(category).setType(MetricsEvent.TYPE_ACTION).setSubtype(value));
    }

    /** Logs an ACTION event on the category.
     * Equivalent to write(new LogMaker(category).setType(MetricsEvent.TYPE_ACTION)
                           .setSubtype(value ? 1 : 0) */
    public void action(int category, boolean value) {
        saveLog(new LogMaker(category).setType(MetricsEvent.TYPE_ACTION).setSubtype(value ? 1 : 0));
    }

    /** Logs an ACTION event on the category.
     * Equivalent to write(new LogMaker(category).setType(MetricsEvent.TYPE_ACTION)
                           .setPackageName(value ? 1 : 0) */
    public void action(int category, String pkg) {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        saveLog(new LogMaker(category).setType(MetricsEvent.TYPE_ACTION).setPackageName(pkg));
    }

    /** @deprecated because untestable; use {@link #visible(int)} */
    @Deprecated
    public static void visible(Context context, int category) throws IllegalArgumentException {
        getLogger().visible(category);
    }

    /** @deprecated because untestable; use {@link #hidden(int)} */
    @Deprecated
    public static void hidden(Context context, int category) throws IllegalArgumentException {
        getLogger().hidden(category);
    }

    /** @deprecated because untestable; use {@link #visibility(int, boolean)} */
    @Deprecated
    public static void visibility(Context context, int category, boolean visibile)
            throws IllegalArgumentException {
        getLogger().visibility(category, visibile);
    }

    /** @deprecated because untestable; use {@link #visibility(int, int)} */
    @Deprecated
    public static void visibility(Context context, int category, int vis)
            throws IllegalArgumentException {
        visibility(context, category, vis == View.VISIBLE);
    }

    /** @deprecated because untestable; use {@link #action(int)} */
    @Deprecated
    public static void action(Context context, int category) {
        getLogger().action(category);
    }

    /** @deprecated because untestable; use {@link #action(int, int)} */
    @Deprecated
    public static void action(Context context, int category, int value) {
        getLogger().action(category, value);
    }

    /** @deprecated because untestable; use {@link #action(int, boolean)} */
    @Deprecated
    public static void action(Context context, int category, boolean value) {
        getLogger().action(category, value);
    }

    /** @deprecated because untestable; use {@link #write(LogMaker)} */
    @Deprecated
    public static void action(LogMaker content) {
        getLogger().write(content);
    }

    /** @deprecated because untestable; use {@link #action(int, String)} */
    @Deprecated
    public static void action(Context context, int category, String pkg) {
        getLogger().action(category, pkg);
    }

    /**
     * Add an integer value to the monotonically increasing counter with the given name.
     * @deprecated because untestable; use {@link #count(String, int)}
     */
    @Deprecated
    public static void count(Context context, String name, int value) {
        getLogger().count(name, value);
    }

    /**
     * Increment the bucket with the integer label on the histogram with the given name.
     * @deprecated use {@link #histogram(String, int)}
     */
    @Deprecated
    public static void histogram(Context context, String name, int bucket) {
        getLogger().histogram(name, bucket);
    }
}
