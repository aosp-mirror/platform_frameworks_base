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
import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;

/**
 * {@link LogWriter} that writes data to eventlog.
 */
public class EventLogWriter implements LogWriter {

    @Override
    public void visible(Context context, int source, int category, int latency) {
        final LogMaker logMaker = new LogMaker(category)
                .setType(MetricsProto.MetricsEvent.TYPE_OPEN)
                .addTaggedData(MetricsProto.MetricsEvent.FIELD_CONTEXT, source)
                .addTaggedData(MetricsProto.MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE,
                        latency);
        MetricsLogger.action(logMaker);
    }

    @Override
    public void hidden(Context context, int category, int visibleTime) {
        final LogMaker logMaker = new LogMaker(category)
                .setType(MetricsProto.MetricsEvent.TYPE_CLOSE)
                .addTaggedData(MetricsProto.MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE,
                        visibleTime);
        MetricsLogger.action(logMaker);
    }

    @Override
    public void action(Context context, int category, Pair<Integer, Object>... taggedData) {
        final LogMaker logMaker = new LogMaker(category)
                .setType(MetricsProto.MetricsEvent.TYPE_ACTION);
        if (taggedData != null) {
            for (Pair<Integer, Object> pair : taggedData) {
                logMaker.addTaggedData(pair.first, pair.second);
            }
        }
        MetricsLogger.action(logMaker);
    }

    @Override
    public void action(Context context, int category, int value) {
        MetricsLogger.action(context, category, value);
    }

    @Override
    public void action(Context context, int category, boolean value) {
        MetricsLogger.action(context, category, value);
    }

    @Override
    public void action(Context context, int category, String pkg) {
        final LogMaker logMaker = new LogMaker(category)
                .setType(MetricsProto.MetricsEvent.TYPE_ACTION)
                .setPackageName(pkg);

        MetricsLogger.action(logMaker);
    }

    @Override
    public void action(int attribution, int action, int pageId, String key, int value) {
        final LogMaker logMaker = new LogMaker(action)
                .setType(MetricsProto.MetricsEvent.TYPE_ACTION);
        if (attribution != MetricsProto.MetricsEvent.VIEW_UNKNOWN) {
            logMaker.addTaggedData(MetricsProto.MetricsEvent.FIELD_CONTEXT, pageId);
        }
        if (!TextUtils.isEmpty(key)) {
            logMaker.addTaggedData(MetricsProto.MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_NAME,
                    key);
            logMaker.addTaggedData(
                    MetricsProto.MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE,
                    value);
        }
        MetricsLogger.action(logMaker);
    }
}
