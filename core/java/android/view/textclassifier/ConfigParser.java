/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.view.textclassifier;

import android.annotation.Nullable;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.KeyValueListParser;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Retrieves settings from {@link DeviceConfig} and {@link android.provider.Settings}.
 * It will try DeviceConfig first and then Settings.
 *
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PACKAGE)
public final class ConfigParser {
    private static final String TAG = "ConfigParser";

    static final boolean ENABLE_DEVICE_CONFIG = true;

    private static final String STRING_LIST_DELIMITER = ":";

    private final Supplier<String> mLegacySettingsSupplier;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<String, Object> mCache = new ArrayMap<>();
    @GuardedBy("mLock")
    private @Nullable KeyValueListParser mSettingsParser;  // Call getLegacySettings() instead.

    public ConfigParser(Supplier<String> legacySettingsSupplier) {
        mLegacySettingsSupplier = Preconditions.checkNotNull(legacySettingsSupplier);
    }

    private KeyValueListParser getLegacySettings() {
        synchronized (mLock) {
            if (mSettingsParser == null) {
                final String legacySettings = mLegacySettingsSupplier.get();
                try {
                    mSettingsParser = new KeyValueListParser(',');
                    mSettingsParser.setString(legacySettings);
                } catch (IllegalArgumentException e) {
                    // Failed to parse the settings string, log this and move on with defaults.
                    Log.w(TAG, "Bad text_classifier_constants: " + legacySettings);
                }
            }
            return mSettingsParser;
        }
    }

    /**
     * Reads a boolean setting through the cache.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        synchronized (mLock) {
            final Object cached = mCache.get(key);
            if (cached instanceof Boolean) {
                return (boolean) cached;
            }
            final boolean value;
            if (ENABLE_DEVICE_CONFIG) {
                value = DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                        key,
                        getLegacySettings().getBoolean(key, defaultValue));
            } else {
                value = getLegacySettings().getBoolean(key, defaultValue);
            }
            mCache.put(key, value);
            return value;
        }
    }

    /**
     * Reads an integer setting through the cache.
     */
    public int getInt(String key, int defaultValue) {
        synchronized (mLock) {
            final Object cached = mCache.get(key);
            if (cached instanceof Integer) {
                return (int) cached;
            }
            final int value;
            if (ENABLE_DEVICE_CONFIG) {
                value = DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                        key,
                        getLegacySettings().getInt(key, defaultValue));
            } else {
                value = getLegacySettings().getInt(key, defaultValue);
            }
            mCache.put(key, value);
            return value;
        }
    }

    /**
     * Reads a float setting through the cache.
     */
    public float getFloat(String key, float defaultValue) {
        synchronized (mLock) {
            final Object cached = mCache.get(key);
            if (cached instanceof Float) {
                return (float) cached;
            }
            final float value;
            if (ENABLE_DEVICE_CONFIG) {
                value = DeviceConfig.getFloat(
                        DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                        key,
                        getLegacySettings().getFloat(key, defaultValue));
            } else {
                value = getLegacySettings().getFloat(key, defaultValue);
            }
            mCache.put(key, value);
            return value;
        }
    }

    /**
     * Reads a string setting through the cache.
     */
    public String getString(String key, String defaultValue) {
        synchronized (mLock) {
            final Object cached = mCache.get(key);
            if (cached instanceof String) {
                return (String) cached;
            }
            final String value;
            if (ENABLE_DEVICE_CONFIG) {
                value = DeviceConfig.getString(
                        DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                        key,
                        getLegacySettings().getString(key, defaultValue));
            } else {
                value = getLegacySettings().getString(key, defaultValue);
            }
            mCache.put(key, value);
            return value;
        }
    }

    /**
     * Reads a string list setting through the cache.
     */
    public List<String> getStringList(String key, List<String> defaultValue) {
        synchronized (mLock) {
            final Object cached = mCache.get(key);
            if (cached instanceof List) {
                final List asList = (List) cached;
                if (asList.isEmpty()) {
                    return Collections.emptyList();
                } else if (asList.get(0) instanceof String) {
                    return (List<String>) cached;
                }
            }
            final List<String> value;
            if (ENABLE_DEVICE_CONFIG) {
                value = getDeviceConfigStringList(
                        key,
                        getSettingsStringList(key, defaultValue));
            } else {
                value = getSettingsStringList(key, defaultValue);
            }
            mCache.put(key, value);
            return value;
        }
    }

    /**
     * Reads a float array through the cache. The returned array should be expected to be of the
     * same length as that of the defaultValue.
     */
    public float[] getFloatArray(String key, float[] defaultValue) {
        synchronized (mLock) {
            final Object cached = mCache.get(key);
            if (cached instanceof float[]) {
                return (float[]) cached;
            }
            final float[] value;
            if (ENABLE_DEVICE_CONFIG) {
                value = getDeviceConfigFloatArray(
                        key,
                        getSettingsFloatArray(key, defaultValue));
            } else {
                value = getSettingsFloatArray(key, defaultValue);
            }
            mCache.put(key, value);
            return value;
        }
    }

    private List<String> getSettingsStringList(String key, List<String> defaultValue) {
        return parse(mSettingsParser.getString(key, null), defaultValue);
    }

    private static List<String> getDeviceConfigStringList(String key, List<String> defaultValue) {
        return parse(
                DeviceConfig.getString(DeviceConfig.NAMESPACE_TEXTCLASSIFIER, key, null),
                defaultValue);
    }

    private static float[] getDeviceConfigFloatArray(String key, float[] defaultValue) {
        return parse(
                DeviceConfig.getString(DeviceConfig.NAMESPACE_TEXTCLASSIFIER, key, null),
                defaultValue);
    }

    private float[] getSettingsFloatArray(String key, float[] defaultValue) {
        return parse(mSettingsParser.getString(key, null), defaultValue);
    }

    private static List<String> parse(@Nullable String listStr, List<String> defaultValue) {
        if (listStr != null) {
            return Collections.unmodifiableList(
                    Arrays.asList(listStr.split(STRING_LIST_DELIMITER)));
        }
        return defaultValue;
    }

    private static float[] parse(@Nullable String arrayStr, float[] defaultValue) {
        if (arrayStr != null) {
            final String[] split = arrayStr.split(STRING_LIST_DELIMITER);
            if (split.length != defaultValue.length) {
                return defaultValue;
            }
            final float[] result = new float[split.length];
            for (int i = 0; i < split.length; i++) {
                try {
                    result[i] = Float.parseFloat(split[i]);
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            return result;
        } else {
            return defaultValue;
        }
    }
}
