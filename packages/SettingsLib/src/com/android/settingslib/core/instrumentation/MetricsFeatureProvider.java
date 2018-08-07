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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * FeatureProvider for metrics.
 */
public class MetricsFeatureProvider {
    private List<LogWriter> mLoggerWriters;

    public MetricsFeatureProvider() {
        mLoggerWriters = new ArrayList<>();
        installLogWriters();
    }

    protected void installLogWriters() {
        mLoggerWriters.add(new EventLogWriter());
    }

    public void visible(Context context, int source, int category) {
        for (LogWriter writer : mLoggerWriters) {
            writer.visible(context, source, category);
        }
    }

    public void hidden(Context context, int category) {
        for (LogWriter writer : mLoggerWriters) {
            writer.hidden(context, category);
        }
    }

    public void actionWithSource(Context context, int source, int category) {
        for (LogWriter writer : mLoggerWriters) {
            writer.actionWithSource(context, source, category);
        }
    }

    /**
     * Logs a user action. Includes the elapsed time since the containing
     * fragment has been visible.
     */
    public void action(VisibilityLoggerMixin visibilityLogger, int category, int value) {
        for (LogWriter writer : mLoggerWriters) {
            writer.action(category, value,
                    sinceVisibleTaggedData(visibilityLogger.elapsedTimeSinceVisible()));
        }
    }

    /**
     * Logs a user action. Includes the elapsed time since the containing
     * fragment has been visible.
     */
    public void action(VisibilityLoggerMixin visibilityLogger, int category, boolean value) {
        for (LogWriter writer : mLoggerWriters) {
            writer.action(category, value,
                    sinceVisibleTaggedData(visibilityLogger.elapsedTimeSinceVisible()));
        }
    }

    public void action(Context context, int category, Pair<Integer, Object>... taggedData) {
        for (LogWriter writer : mLoggerWriters) {
            writer.action(context, category, taggedData);
        }
    }

    /** @deprecated use {@link #action(VisibilityLoggerMixin, int, int)} */
    @Deprecated
    public void action(Context context, int category, int value) {
        for (LogWriter writer : mLoggerWriters) {
            writer.action(context, category, value);
        }
    }

    /** @deprecated use {@link #action(VisibilityLoggerMixin, int, boolean)} */
    @Deprecated
    public void action(Context context, int category, boolean value) {
        for (LogWriter writer : mLoggerWriters) {
            writer.action(context, category, value);
        }
    }

    public void action(Context context, int category, String pkg,
            Pair<Integer, Object>... taggedData) {
        for (LogWriter writer : mLoggerWriters) {
            writer.action(context, category, pkg, taggedData);
        }
    }

    public void count(Context context, String name, int value) {
        for (LogWriter writer : mLoggerWriters) {
            writer.count(context, name, value);
        }
    }

    public void histogram(Context context, String name, int bucket) {
        for (LogWriter writer : mLoggerWriters) {
            writer.histogram(context, name, bucket);
        }
    }

    public int getMetricsCategory(Object object) {
        if (object == null || !(object instanceof Instrumentable)) {
            return MetricsEvent.VIEW_UNKNOWN;
        }
        return ((Instrumentable) object).getMetricsCategory();
    }

    public void logDashboardStartIntent(Context context, Intent intent,
            int sourceMetricsCategory) {
        if (intent == null) {
            return;
        }
        final ComponentName cn = intent.getComponent();
        if (cn == null) {
            final String action = intent.getAction();
            if (TextUtils.isEmpty(action)) {
                // Not loggable
                return;
            }
            action(context, MetricsEvent.ACTION_SETTINGS_TILE_CLICK, action,
                    Pair.create(MetricsEvent.FIELD_CONTEXT, sourceMetricsCategory));
            return;
        } else if (TextUtils.equals(cn.getPackageName(), context.getPackageName())) {
            // Going to a Setting internal page, skip click logging in favor of page's own
            // visibility logging.
            return;
        }
        action(context, MetricsEvent.ACTION_SETTINGS_TILE_CLICK, cn.flattenToString(),
                Pair.create(MetricsEvent.FIELD_CONTEXT, sourceMetricsCategory));
    }

    private Pair<Integer, Object> sinceVisibleTaggedData(long timestamp) {
        return Pair.create(MetricsEvent.NOTIFICATION_SINCE_VISIBLE_MILLIS, timestamp);
    }
}
