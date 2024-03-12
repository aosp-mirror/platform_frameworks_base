/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.utils;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.provider.DeviceConfig;
import android.util.KeyValueListParser;

/**
 * Helper class to mediate the value to use when a constant exists in both a key=value pair Settings
 * constant (that can be parsed by {@link KeyValueListParser})
 * and the {@link DeviceConfig} properties.
 */
public abstract class UserSettingDeviceConfigMediator {
    private static final String TAG = UserSettingDeviceConfigMediator.class.getSimpleName();

    @Nullable
    protected DeviceConfig.Properties mProperties;
    @NonNull
    protected final KeyValueListParser mSettingsParser;

    /**
     * @param keyValueListDelimiter The delimiter passed into the {@link KeyValueListParser}.
     */
    protected UserSettingDeviceConfigMediator(char keyValueListDelimiter) {
        mSettingsParser = new KeyValueListParser(keyValueListDelimiter);
    }

    /**
     * Sets the key=value list string to read from. Setting {@code null} will clear any previously
     * set string.
     */
    public void setSettingsString(@Nullable String settings) {
        mSettingsParser.setString(settings);
    }

    /**
     * Sets the DeviceConfig Properties to read from. Setting {@code null} will clear any previously
     * set properties.
     */
    public void setDeviceConfigProperties(@Nullable DeviceConfig.Properties properties) {
        mProperties = properties;
    }

    /**
     * Get the value for key as a boolean.
     *
     * @param key          The key to lookup.
     * @param defaultValue The value to return if the key was not found, or not properly defined.
     */
    public abstract boolean getBoolean(@NonNull String key, boolean defaultValue);

    /**
     * Get the value for key as a float.
     *
     * @param key          The key to lookup.
     * @param defaultValue The value to return if the key was not found, or not properly defined.
     */
    public abstract float getFloat(@NonNull String key, float defaultValue);

    /**
     * Get the value for key as an int.
     *
     * @param key          The key to lookup.
     * @param defaultValue The value to return if the key was not found, or not properly defined.
     */
    public abstract int getInt(@NonNull String key, int defaultValue);

    /**
     * Get the value for key as a long.
     *
     * @param key          The key to lookup.
     * @param defaultValue The value to return if the key was not found, or not properly defined.
     */
    public abstract long getLong(@NonNull String key, long defaultValue);

    /**
     * Get the value for key as a String.
     *
     * @param key          The key to lookup.
     * @param defaultValue The value to return if the key was not found, or not properly defined.
     */
    public abstract String getString(@NonNull String key, @Nullable String defaultValue);

    /**
     * A mediator in which the existence of a single settings key-value pair will override usage
     * of DeviceConfig properties. That is, if the Settings constant has any values set,
     * then everything in the DeviceConfig namespace will be ignored.
     */
    public static class SettingsOverridesAllMediator extends UserSettingDeviceConfigMediator {
        public SettingsOverridesAllMediator(char keyValueListDelimiter) {
            super(keyValueListDelimiter);
        }

        @Override
        public boolean getBoolean(@NonNull String key, boolean defaultValue) {
            if (mSettingsParser.size() == 0) {
                return mProperties == null
                        ? defaultValue : mProperties.getBoolean(key, defaultValue);
            }
            return mSettingsParser.getBoolean(key, defaultValue);
        }

        @Override
        public float getFloat(@NonNull String key, float defaultValue) {
            if (mSettingsParser.size() == 0) {
                return mProperties == null ? defaultValue : mProperties.getFloat(key, defaultValue);
            }
            return mSettingsParser.getFloat(key, defaultValue);
        }

        @Override
        public int getInt(@NonNull String key, int defaultValue) {
            if (mSettingsParser.size() == 0) {
                return mProperties == null ? defaultValue : mProperties.getInt(key, defaultValue);
            }
            return mSettingsParser.getInt(key, defaultValue);
        }

        @Override
        public long getLong(@NonNull String key, long defaultValue) {
            if (mSettingsParser.size() == 0) {
                return mProperties == null ? defaultValue : mProperties.getLong(key, defaultValue);
            }
            return mSettingsParser.getLong(key, defaultValue);
        }

        @Override
        public String getString(@NonNull String key, @Nullable String defaultValue) {
            if (mSettingsParser.size() == 0) {
                return mProperties == null
                        ? defaultValue : mProperties.getString(key, defaultValue);
            }
            return mSettingsParser.getString(key, defaultValue);
        }
    }

    /**
     * A mediator in which only individual keys in the DeviceConfig namespace will be overridden
     * by the same key in the Settings constant. If the Settings constant does not have a specific
     * key set, then the DeviceConfig value will be used instead.
     */
    public static class SettingsOverridesIndividualMediator
            extends UserSettingDeviceConfigMediator {
        public SettingsOverridesIndividualMediator(char keyValueListDelimiter) {
            super(keyValueListDelimiter);
        }

        @Override
        public boolean getBoolean(@NonNull String key, boolean defaultValue) {
            return mSettingsParser.getBoolean(key,
                    mProperties == null ? defaultValue : mProperties.getBoolean(key, defaultValue));
        }

        @Override
        public float getFloat(@NonNull String key, float defaultValue) {
            return mSettingsParser.getFloat(key,
                    mProperties == null ? defaultValue : mProperties.getFloat(key, defaultValue));
        }

        @Override
        public int getInt(@NonNull String key, int defaultValue) {
            return mSettingsParser.getInt(key,
                    mProperties == null ? defaultValue : mProperties.getInt(key, defaultValue));
        }

        @Override
        public long getLong(@NonNull String key, long defaultValue) {
            return mSettingsParser.getLong(key,
                    mProperties == null ? defaultValue : mProperties.getLong(key, defaultValue));
        }

        @Override
        public String getString(@NonNull String key, @Nullable String defaultValue) {
            return mSettingsParser.getString(key,
                    mProperties == null ? defaultValue : mProperties.getString(key, defaultValue));
        }
    }
}
