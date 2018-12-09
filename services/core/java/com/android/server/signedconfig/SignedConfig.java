/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.signedconfig;

import com.android.internal.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents signed configuration.
 *
 * <p>This configuration should only be used if the signature has already been verified.
 */
public class SignedConfig {

    private static final String KEY_VERSION = "version";
    private static final String KEY_CONFIG = "config";

    private static final String CONFIG_KEY_MIN_SDK = "minSdk";
    private static final String CONFIG_KEY_MAX_SDK = "maxSdk";
    private static final String CONFIG_KEY_VALUES = "values";
    // TODO it may be better to use regular key/value pairs in a JSON object, rather than an array
    // of objects with the 2 keys below.
    private static final String CONFIG_KEY_KEY = "key";
    private static final String CONFIG_KEY_VALUE = "value";

    /**
     * Represents config values targetting to an SDK range.
     */
    public static class PerSdkConfig {
        public final int minSdk;
        public final int maxSdk;
        public final Map<String, String> values;

        public PerSdkConfig(int minSdk, int maxSdk, Map<String, String> values) {
            this.minSdk = minSdk;
            this.maxSdk = maxSdk;
            this.values = Collections.unmodifiableMap(values);
        }

    }

    public final int version;
    public final List<PerSdkConfig> perSdkConfig;

    public SignedConfig(int version, List<PerSdkConfig> perSdkConfig) {
        this.version = version;
        this.perSdkConfig = Collections.unmodifiableList(perSdkConfig);
    }

    /**
     * Find matching sdk config for a given SDK level.
     *
     * @param sdkVersion SDK version of device.
     * @return Matching config, of {@code null} if there is none.
     */
    public PerSdkConfig getMatchingConfig(int sdkVersion) {
        for (PerSdkConfig config : perSdkConfig) {
            if (config.minSdk <= sdkVersion && sdkVersion <= config.maxSdk) {
                return config;
            }
        }
        // nothing matching
        return null;
    }

    /**
     * Parse configuration from an APK.
     *
     * @param config config as read from the APK metadata.
     * @return Parsed configuration.
     * @throws InvalidConfigException If there's a problem parsing the config.
     */
    public static SignedConfig parse(String config, Set<String> allowedKeys)
            throws InvalidConfigException {
        try {
            JSONObject json = new JSONObject(config);
            int version = json.getInt(KEY_VERSION);

            JSONArray perSdkConfig = json.getJSONArray(KEY_CONFIG);
            List<PerSdkConfig> parsedConfigs = new ArrayList<>();
            for (int i = 0; i < perSdkConfig.length(); ++i) {
                parsedConfigs.add(parsePerSdkConfig(perSdkConfig.getJSONObject(i), allowedKeys));
            }

            return new SignedConfig(version, parsedConfigs);
        } catch (JSONException e) {
            throw new InvalidConfigException("Could not parse JSON", e);
        }

    }

    @VisibleForTesting
    static PerSdkConfig parsePerSdkConfig(JSONObject json, Set<String> allowedKeys)
            throws JSONException, InvalidConfigException {
        int minSdk = json.getInt(CONFIG_KEY_MIN_SDK);
        int maxSdk = json.getInt(CONFIG_KEY_MAX_SDK);
        JSONArray valueArray = json.getJSONArray(CONFIG_KEY_VALUES);
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < valueArray.length(); ++i) {
            JSONObject keyValuePair = valueArray.getJSONObject(i);
            String key = keyValuePair.getString(CONFIG_KEY_KEY);
            String value = keyValuePair.has(CONFIG_KEY_VALUE)
                    ? keyValuePair.getString(CONFIG_KEY_VALUE)
                    : null;
            if (!allowedKeys.contains(key)) {
                throw new InvalidConfigException("Config key " + key + " is not allowed");
            }
            values.put(key, value);
        }
        return new PerSdkConfig(minSdk, maxSdk, values);
    }

}
