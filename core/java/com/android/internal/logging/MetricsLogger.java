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

import android.content.Context;
import android.metrics.LogMaker;
import android.os.Build;
import android.view.View;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

/**
 * Log all the things.
 *
 * @hide
 */
public class MetricsLogger {
    // define metric categories in frameworks/base/proto/src/metrics_constants.proto.
    // mirror changes in native version at system/core/libmetricslogger/metrics_logger.cpp

    private static MetricsLogger sMetricsLogger;

    private static MetricsLogger getLogger() {
        if (sMetricsLogger == null) {
            sMetricsLogger = new MetricsLogger();
        }
        return sMetricsLogger;
    }

    protected void saveLog(Object[] rep) {
        EventLogTags.writeSysuiMultiAction(rep);
    }

    public static final int VIEW_UNKNOWN = MetricsEvent.VIEW_UNKNOWN;
    public static final int LOGTAG = EventLogTags.SYSUI_MULTI_ACTION;

    public void write(LogMaker content) {
        if (content.getType() == MetricsEvent.TYPE_UNKNOWN) {
            content.setType(MetricsEvent.TYPE_ACTION);
        }
        saveLog(content.serialize());
    }

    public void visible(int category) throws IllegalArgumentException {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        EventLogTags.writeSysuiViewVisibility(category, 100);
        saveLog(new LogMaker(category)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .serialize());
    }

    public void hidden(int category) throws IllegalArgumentException {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        EventLogTags.writeSysuiViewVisibility(category, 0);
        saveLog(new LogMaker(category)
                        .setType(MetricsEvent.TYPE_CLOSE)
                        .serialize());
    }

    public void visibility(int category, boolean visibile)
            throws IllegalArgumentException {
        if (visibile) {
            visible(category);
        } else {
            hidden(category);
        }
    }

    public void visibility(int category, int vis)
            throws IllegalArgumentException {
        visibility(category, vis == View.VISIBLE);
    }

    public void action(int category) {
        EventLogTags.writeSysuiAction(category, "");
        saveLog(new LogMaker(category)
                        .setType(MetricsEvent.TYPE_ACTION)
                        .serialize());
    }

    public void action(int category, int value) {
        EventLogTags.writeSysuiAction(category, Integer.toString(value));
        saveLog(new LogMaker(category)
                        .setType(MetricsEvent.TYPE_ACTION)
                        .setSubtype(value)
                        .serialize());
    }

    public void action(int category, boolean value) {
        EventLogTags.writeSysuiAction(category, Boolean.toString(value));
        saveLog(new LogMaker(category)
                        .setType(MetricsEvent.TYPE_ACTION)
                        .setSubtype(value ? 1 : 0)
                        .serialize());
    }

    public void action(int category, String pkg) {
        if (Build.IS_DEBUGGABLE && category == VIEW_UNKNOWN) {
            throw new IllegalArgumentException("Must define metric category");
        }
        EventLogTags.writeSysuiAction(category, pkg);
        saveLog(new LogMaker(category)
                .setType(MetricsEvent.TYPE_ACTION)
                .setPackageName(pkg)
                .serialize());
    }

    /** Add an integer value to the monotonically increasing counter with the given name. */
    public void count(String name, int value) {
        EventLogTags.writeSysuiCount(name, value);
        saveLog(new LogMaker(MetricsEvent.RESERVED_FOR_LOGBUILDER_COUNTER)
                        .setCounterName(name)
                        .setCounterValue(value)
                        .serialize());
    }

    /** Increment the bucket with the integer label on the histogram with the given name. */
    public void histogram(String name, int bucket) {
        // see LogHistogram in system/core/libmetricslogger/metrics_logger.cpp
        EventLogTags.writeSysuiHistogram(name, bucket);
        saveLog(new LogMaker(MetricsEvent.RESERVED_FOR_LOGBUILDER_HISTOGRAM)
                        .setCounterName(name)
                        .setCounterBucket(bucket)
                        .setCounterValue(1)
                        .serialize());
    }

    /** @deprecated use {@link #visible(int)} */
    @Deprecated
    public static void visible(Context context, int category) throws IllegalArgumentException {
        getLogger().visible(category);
    }

    /** @deprecated use {@link #hidden(int)} */
    @Deprecated
    public static void hidden(Context context, int category) throws IllegalArgumentException {
        getLogger().hidden(category);
    }

    /** @deprecated use {@link #visibility(int, boolean)} */
    @Deprecated
    public static void visibility(Context context, int category, boolean visibile)
            throws IllegalArgumentException {
        getLogger().visibility(category, visibile);
    }

    /** @deprecated use {@link #visibility(int, int)} */
    @Deprecated
    public static void visibility(Context context, int category, int vis)
            throws IllegalArgumentException {
        visibility(context, category, vis == View.VISIBLE);
    }

    /** @deprecated use {@link #action(int)} */
    @Deprecated
    public static void action(Context context, int category) {
        getLogger().action(category);
    }

    /** @deprecated use {@link #action(int, int)} */
    @Deprecated
    public static void action(Context context, int category, int value) {
        getLogger().action(category, value);
    }

    /** @deprecated use {@link #action(int, boolean)} */
    @Deprecated
    public static void action(Context context, int category, boolean value) {
        getLogger().action(category, value);
    }

    /** @deprecated use {@link #write(LogMaker)} */
    @Deprecated
    public static void action(LogMaker content) {
        getLogger().write(content);
    }

    /** @deprecated use {@link #action(int, String)} */
    @Deprecated
    public static void action(Context context, int category, String pkg) {
        getLogger().action(category, pkg);
    }

    /**
     * Add an integer value to the monotonically increasing counter with the given name.
     * @deprecated use {@link #count(String, int)}
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
