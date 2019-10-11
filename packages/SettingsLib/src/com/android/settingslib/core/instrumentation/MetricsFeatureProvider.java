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

import android.app.Activity;
import android.app.settings.SettingsEnums;
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
    /**
     * The metrics category constant for logging source when a setting fragment is opened.
     */
    public static final String EXTRA_SOURCE_METRICS_CATEGORY = ":settings:source_metrics";

    protected List<LogWriter> mLoggerWriters;

    public MetricsFeatureProvider() {
        mLoggerWriters = new ArrayList<>();
        installLogWriters();
    }

    protected void installLogWriters() {
        mLoggerWriters.add(new EventLogWriter());
    }

    /**
     * Returns the attribution id for specified activity. If no attribution is set, returns {@link
     * SettingsEnums#PAGE_UNKNOWN}.
     *
     * <p/> Attribution is a {@link SettingsEnums} page id that indicates where the specified
     * activity is launched from.
     */
    public int getAttribution(Activity activity) {
        if (activity == null) {
            return SettingsEnums.PAGE_UNKNOWN;
        }
        final Intent intent = activity.getIntent();
        if (intent == null) {
            return SettingsEnums.PAGE_UNKNOWN;
        }
        return intent.getIntExtra(EXTRA_SOURCE_METRICS_CATEGORY,
                SettingsEnums.PAGE_UNKNOWN);
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

    /**
     * Logs a simple action without page id or attribution
     */
    public void action(Context context, int category,  Pair<Integer, Object>... taggedData) {
        for (LogWriter writer : mLoggerWriters) {
            writer.action(context, category, taggedData);
        }
    }

    /**
     * Logs a generic Settings event.
     */
    public void action(Context context, int category, String pkg) {
        for (LogWriter writer : mLoggerWriters) {
            writer.action(context, category, pkg);
        }
    }

    /**
     * Logs a generic Settings event.
     */
    public void action(int attribution, int action, int pageId, String key, int value) {
        for (LogWriter writer : mLoggerWriters) {
            writer.action(attribution, action, pageId, key, value);
        }
    }

    public void action(Context context, int category, int value) {
        for (LogWriter writer : mLoggerWriters) {
            writer.action(context, category, value);
        }
    }

    public void action(Context context, int category, boolean value) {
        for (LogWriter writer : mLoggerWriters) {
            writer.action(context, category, value);
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
            action(sourceMetricsCategory,
                    MetricsEvent.ACTION_SETTINGS_TILE_CLICK,
                    SettingsEnums.PAGE_UNKNOWN,
                    action,
                    0);
            return;
        } else if (TextUtils.equals(cn.getPackageName(), context.getPackageName())) {
            // Going to a Setting internal page, skip click logging in favor of page's own
            // visibility logging.
            return;
        }
        action(sourceMetricsCategory,
                MetricsEvent.ACTION_SETTINGS_TILE_CLICK,
                SettingsEnums.PAGE_UNKNOWN,
                cn.flattenToString(),
                0);
    }

}
