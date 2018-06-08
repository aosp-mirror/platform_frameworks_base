/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib.core.instrumentation;

import android.content.Context;
import android.metrics.LogMaker;
import android.util.Log;
import android.util.Pair;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;

/**
 * {@link LogWriter} that writes data to eventlog.
 */
public class EventLogWriter implements LogWriter {

    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    public void visible(Context context, int source, int category) {
        final LogMaker logMaker = new LogMaker(category)
                .setType(MetricsProto.MetricsEvent.TYPE_OPEN)
                .addTaggedData(MetricsProto.MetricsEvent.FIELD_CONTEXT, source);
        MetricsLogger.action(logMaker);
    }

    public void hidden(Context context, int category) {
        MetricsLogger.hidden(context, category);
    }

    public void action(int category, int value, Pair<Integer, Object>... taggedData) {
        if (taggedData == null || taggedData.length == 0) {
            mMetricsLogger.action(category, value);
        } else {
            final LogMaker logMaker = new LogMaker(category)
                    .setType(MetricsProto.MetricsEvent.TYPE_ACTION)
                    .setSubtype(value);
            for (Pair<Integer, Object> pair : taggedData) {
                logMaker.addTaggedData(pair.first, pair.second);
            }
            mMetricsLogger.write(logMaker);
        }
    }

    public void action(int category, boolean value, Pair<Integer, Object>... taggedData) {
        action(category, value ? 1 : 0, taggedData);
    }

    public void action(Context context, int category, Pair<Integer, Object>... taggedData) {
        action(context, category, "", taggedData);
    }

    public void actionWithSource(Context context, int source, int category) {
        final LogMaker logMaker = new LogMaker(category)
                .setType(MetricsProto.MetricsEvent.TYPE_ACTION);
        if (source != MetricsProto.MetricsEvent.VIEW_UNKNOWN) {
            logMaker.addTaggedData(MetricsProto.MetricsEvent.FIELD_CONTEXT, source);
        }
        MetricsLogger.action(logMaker);
    }

    /** @deprecated use {@link #action(int, int, Pair[])} */
    @Deprecated
    public void action(Context context, int category, int value) {
        MetricsLogger.action(context, category, value);
    }

    /** @deprecated use {@link #action(int, boolean, Pair[])} */
    @Deprecated
    public void action(Context context, int category, boolean value) {
        MetricsLogger.action(context, category, value);
    }

    public void action(Context context, int category, String pkg,
            Pair<Integer, Object>... taggedData) {
        if (taggedData == null || taggedData.length == 0) {
            MetricsLogger.action(context, category, pkg);
        } else {
            final LogMaker logMaker = new LogMaker(category)
                    .setType(MetricsProto.MetricsEvent.TYPE_ACTION)
                    .setPackageName(pkg);
            for (Pair<Integer, Object> pair : taggedData) {
                logMaker.addTaggedData(pair.first, pair.second);
            }
            MetricsLogger.action(logMaker);
        }
    }

    public void count(Context context, String name, int value) {
        MetricsLogger.count(context, name, value);
    }

    public void histogram(Context context, String name, int bucket) {
        MetricsLogger.histogram(context, name, bucket);
    }
}
