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

import androidx.annotation.NonNull;
import androidx.preference.Preference;

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

    /**
     * Logs an event when target page is visible.
     *
     * @param source from this page id to target page
     * @param category the target page id
     * @param latency the latency of target page creation
     */
    public void visible(Context context, int source, int category, int latency) {
        for (LogWriter writer : mLoggerWriters) {
            writer.visible(context, source, category, latency);
        }
    }

    /**
     * Logs an event when target page is hidden.
     *
     * @param category the target page id
     * @param visibleTime the time spending on target page since being visible
     */
    public void hidden(Context context, int category, int visibleTime) {
        for (LogWriter writer : mLoggerWriters) {
            writer.hidden(context, category, visibleTime);
        }
    }

    /**
     * Logs an event when user click item.
     *
     * @param category the target page id
     * @param key the key id that user clicked
     */
    public void clicked(int category, String key) {
        for (LogWriter writer : mLoggerWriters) {
            writer.clicked(category, key);
        }
    }

    /**
     * Logs a value changed event when user changed item value.
     *
     * @param category the target page id
     * @param key the key id that user clicked
     * @param value the value that user changed which converted to integer
     */
    public void changed(int category, String key, int value) {
        for (LogWriter writer : mLoggerWriters) {
            writer.changed(category, key, value);
        }
    }

    /**
     * Logs a simple action without page id or attribution
     *
     * @param category the target page
     * @param taggedData the data for {@link EventLogWriter}
     */
    public void action(Context context, int category, Pair<Integer, Object>... taggedData) {
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
        if (!(object instanceof Instrumentable)) {
            return MetricsEvent.VIEW_UNKNOWN;
        }
        return ((Instrumentable) object).getMetricsCategory();
    }

    /**
     * Logs an event when the preference is clicked.
     *
     * @return true if the preference is loggable, otherwise false
     */
    public boolean logClickedPreference(@NonNull Preference preference, int sourceMetricsCategory) {
        if (preference == null) {
            return false;
        }
        return logSettingsTileClick(preference.getKey(), sourceMetricsCategory)
                || logStartedIntent(preference.getIntent(), sourceMetricsCategory)
                || logSettingsTileClick(preference.getFragment(), sourceMetricsCategory);
    }

    /**
     * Logs an event when the intent is started.
     *
     * @return true if the intent is loggable, otherwise false
     */
    public boolean logStartedIntent(Intent intent, int sourceMetricsCategory) {
        if (intent == null) {
            return false;
        }
        final ComponentName cn = intent.getComponent();
        return logSettingsTileClick(cn != null ? cn.flattenToString() : intent.getAction(),
                sourceMetricsCategory);
    }

    /**
     * Logs an event when the intent is started by Profile select dialog.
     *
     * @return true if the intent is loggable, otherwise false
     */
    public boolean logStartedIntentWithProfile(Intent intent, int sourceMetricsCategory,
            boolean isWorkProfile) {
        if (intent == null) {
            return false;
        }
        final ComponentName cn = intent.getComponent();
        final String key = cn != null ? cn.flattenToString() : intent.getAction();
        return logSettingsTileClickWithProfile(key, sourceMetricsCategory, isWorkProfile);
    }

    /**
     * Logs an event when the setting key is clicked.
     *
     * @return true if the key is loggable, otherwise false
     */
    public boolean logSettingsTileClick(String logKey, int sourceMetricsCategory) {
        if (TextUtils.isEmpty(logKey)) {
            // Not loggable
            return false;
        }
        clicked(sourceMetricsCategory, logKey);
        return true;
    }

    /**
     * Logs an event when the setting key is clicked with a specific profile from Profile select
     * dialog.
     *
     * @return true if the key is loggable, otherwise false
     */
    public boolean logSettingsTileClickWithProfile(String logKey, int sourceMetricsCategory,
            boolean isWorkProfile) {
        if (TextUtils.isEmpty(logKey)) {
            // Not loggable
            return false;
        }
        clicked(sourceMetricsCategory, logKey + (isWorkProfile ? "/work" : "/personal"));
        return true;
    }
}
