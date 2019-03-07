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
import android.util.KeyValueListParser;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Retrieves settings from {@link DeviceConfig} and {@link android.provider.Settings}.
 * It will try DeviceConfig first and then Settings.
 *
 * @hide
 */
@VisibleForTesting
public final class ConfigParser {
    private static final String TAG = "ConfigParser";

    private final KeyValueListParser mParser;

    public ConfigParser(@Nullable String textClassifierConstants) {
        final KeyValueListParser parser = new KeyValueListParser(',');
        try {
            parser.setString(textClassifierConstants);
        } catch (IllegalArgumentException e) {
            // Failed to parse the settings string, log this and move on with defaults.
            Log.w(TAG, "Bad text_classifier_constants: " + textClassifierConstants);
        }
        mParser = parser;
    }

    /**
     * Reads a boolean flag.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                key,
                mParser.getBoolean(key, defaultValue));
    }

    /**
     * Reads an integer flag.
     */
    public int getInt(String key, int defaultValue) {
        return DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                key,
                mParser.getInt(key, defaultValue));
    }

    /**
     * Reads a float flag.
     */
    public float getFloat(String key, float defaultValue) {
        return DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                key,
                mParser.getFloat(key, defaultValue));
    }

    /**
     * Reads a string flag.
     */
    public String getString(String key, String defaultValue) {
        return DeviceConfig.getString(
                DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                key,
                mParser.getString(key, defaultValue));
    }
}
