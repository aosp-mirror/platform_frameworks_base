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

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

public class SharedPreferencesLogger implements SharedPreferences {

    private static final String LOG_TAG = "SharedPreferencesLogger";

    private final String mTag;
    private final Context mContext;
    private final MetricsFeatureProvider mMetricsFeature;
    private final Set<String> mPreferenceKeySet;

    public SharedPreferencesLogger(Context context, String tag,
            MetricsFeatureProvider metricsFeature) {
        mContext = context;
        mTag = tag;
        mMetricsFeature = metricsFeature;
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

    private void logValue(String key, Object value) {
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
        // TODO: Remove count logging to save some resource.
        mMetricsFeature.count(mContext, buildCountName(prefKey, value), 1);

        final Pair<Integer, Object> valueData;
        if (value instanceof Long) {
            final Long longVal = (Long) value;
            final int intVal;
            if (longVal > Integer.MAX_VALUE) {
                intVal = Integer.MAX_VALUE;
            } else if (longVal < Integer.MIN_VALUE) {
                intVal = Integer.MIN_VALUE;
            } else {
                intVal = longVal.intValue();
            }
            valueData = Pair.create(MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE,
                    intVal);
        } else if (value instanceof Integer) {
            valueData = Pair.create(MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE,
                    value);
        } else if (value instanceof Boolean) {
            valueData = Pair.create(MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE,
                    (Boolean) value ? 1 : 0);
        } else if (value instanceof Float) {
            valueData = Pair.create(MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_FLOAT_VALUE,
                    value);
        } else if (value instanceof String) {
            Log.d(LOG_TAG, "Tried to log string preference " + prefKey + " = " + value);
            valueData = null;
        } else {
            Log.w(LOG_TAG, "Tried to log unloggable object" + value);
            valueData = null;
        }
        if (valueData != null) {
            // Pref key exists in set, log it's change in metrics.
            mMetricsFeature.action(mContext, MetricsEvent.ACTION_SETTINGS_PREFERENCE_CHANGE,
                    Pair.create(MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_NAME, prefKey),
                    valueData);
        }
    }

    @VisibleForTesting
    void logPackageName(String key, String value) {
        final String prefKey = mTag + "/" + key;
        mMetricsFeature.action(mContext, MetricsEvent.ACTION_SETTINGS_PREFERENCE_CHANGE, value,
                Pair.create(MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_NAME, prefKey));
    }

    private void safeLogValue(String key, String value) {
        new AsyncPackageCheck().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, key, value);
    }

    public static String buildCountName(String prefKey, Object value) {
        return prefKey + "|" + value;
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
