/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.settingslib.core.instrumentation;

import android.app.settings.SettingsEnums;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

public class SharedPreferencesLogger implements SharedPreferences {

    private static final String LOG_TAG = "SharedPreferencesLogger";

    private final String mTag;
    private final int mMetricCategory;
    private final Context mContext;
    private final MetricsFeatureProvider mMetricsFeature;
    private final Set<String> mPreferenceKeySet;

    public SharedPreferencesLogger(Context context, String tag,
            MetricsFeatureProvider metricsFeature) {
        this(context, tag, metricsFeature, SettingsEnums.PAGE_UNKNOWN);
    }

    public SharedPreferencesLogger(Context context, String tag,
            MetricsFeatureProvider metricsFeature, int metricCategory) {
        mContext = context;
        mTag = tag;
        mMetricsFeature = metricsFeature;
        mMetricCategory = metricCategory;
        mPreferenceKeySet = new ConcurrentSkipListSet<>();
    }

    @Override
    public Map<String, ?> getAll() {
        return null;
    }

    @Override
    public String getString(String key, @Nullable String defValue) {
        return defValue;
    }

    @Override
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        return defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        return defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        return defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }

    @Override
    public boolean contains(String key) {
        return false;
    }

    @Override
    public Editor edit() {
        return new EditorLogger();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
    }

    @VisibleForTesting
    protected void logValue(String key, Object value) {
        logValue(key, value, false /* forceLog */);
    }

    private void logValue(String key, Object value, boolean forceLog) {
        final String prefKey = buildPrefKey(mTag, key);
        if (!forceLog && !mPreferenceKeySet.contains(prefKey)) {
            // Pref key doesn't exist in set, this is initial display so we skip metrics but
            // keeps track of this key.
            mPreferenceKeySet.add(prefKey);
            return;
        }

        final int intVal;
        if (value instanceof Long) {
            final Long longVal = (Long) value;
            if (longVal > Integer.MAX_VALUE) {
                intVal = Integer.MAX_VALUE;
            } else if (longVal < Integer.MIN_VALUE) {
                intVal = Integer.MIN_VALUE;
            } else {
                intVal = longVal.intValue();
            }
        } else if (value instanceof Integer) {
            intVal = (int) value;
        } else if (value instanceof Boolean) {
            intVal = (Boolean) value ? 1 : 0;
        } else if (value instanceof Float) {
            final float floatValue = (float) value;
            if (floatValue > Integer.MAX_VALUE) {
                intVal = Integer.MAX_VALUE;
            } else if (floatValue < Integer.MIN_VALUE) {
                intVal = Integer.MIN_VALUE;
            } else {
                intVal = (int) floatValue;
            }
        } else if (value instanceof String) {
            try {
                intVal = Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                Log.w(LOG_TAG, "Tried to log unloggable object=" + value);
                return;
            }
        } else {
            Log.w(LOG_TAG, "Tried to log unloggable object=" + value);
            return;
        }
        // Pref key exists in set, log its change in metrics.
        mMetricsFeature.changed(mMetricCategory, key, intVal);
    }

    @VisibleForTesting
    void logPackageName(String key, String value) {
        mMetricsFeature.action(mMetricCategory,
                SettingsEnums.ACTION_SETTINGS_PREFERENCE_CHANGE,
                SettingsEnums.PAGE_UNKNOWN,
                key + ":" + value,
                0);
    }

    private void safeLogValue(String key, String value) {
        new AsyncPackageCheck().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, key, value);
    }

    public static String buildPrefKey(String tag, String key) {
        return tag + "/" + key;
    }

    private class AsyncPackageCheck extends AsyncTask<String, Void, Void> {
        @Override
        protected Void doInBackground(String... params) {
            String key = params[0];
            String value = params[1];
            PackageManager pm = mContext.getPackageManager();
            try {
                // Check if this might be a component.
                ComponentName name = ComponentName.unflattenFromString(value);
                if (value != null) {
                    value = name.getPackageName();
                }
            } catch (Exception e) {
            }
            try {
                pm.getPackageInfo(value, PackageManager.MATCH_ANY_USER);
                logPackageName(key, value);
            } catch (PackageManager.NameNotFoundException e) {
                // Clearly not a package, and it's unlikely this preference is in prefSet, so
                // lets force log it.
                logValue(key, value, true /* forceLog */);
            }
            return null;
        }
    }

    public class EditorLogger implements Editor {
        @Override
        public Editor putString(String key, @Nullable String value) {
            safeLogValue(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, @Nullable Set<String> values) {
            safeLogValue(key, TextUtils.join(",", values));
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            logValue(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            logValue(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            logValue(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            logValue(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            return this;
        }

        @Override
        public Editor clear() {
            return this;
        }

        @Override
        public boolean commit() {
            return true;
        }

        @Override
        public void apply() {
        }
    }
}
