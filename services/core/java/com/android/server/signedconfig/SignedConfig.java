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
 *
 * This class also parses signed config from JSON. The format expected is:
 * <pre>
 * {
 *   "version": 1
 *   "config": [
 *     {
 *       "min_sdk": 28,
 *       "max_sdk": 29,
 *       "values": {
 *         "key": "value",
 *         "key2": "value2"
 *         ...
 *       }
 *     },
 *     ...
 *   ],
 * }
 * </pre>
 */
public class SignedConfig {

    private static final String KEY_VERSION = "version";
    private static final String KEY_CONFIG = "config";

    private static final String CONFIG_KEY_MIN_SDK = "min_sdk";
    private static final String CONFIG_KEY_MAX_SDK = "max_sdk";
    private static final String CONFIG_KEY_VALUES = "values";

    /**
     * Represents config values targeting an SDK range.
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
     * @param config Config string as read from the APK metadata.
     * @param allowedKeys Set of allowed keys in the config. Any key/value mapping for a key not in
     *                    this set will result in an {@link InvalidConfigException} being thrown.
     * @param keyValueMappers Mappings for values per key. The keys in the top level map should be
     *                        a subset of {@code allowedKeys}. The keys in the inner map indicate
     *                        the set of allowed values for that keys value. This map will be
     *                        applied to the value in the configuration. This is intended to allow
     *                        enum-like values to be encoded as strings in the configuration, and
     *                        mapped back to integers when the configuration is parsed.
     *
     *                        <p>Any config key with a value that does not appear in the
     *                        corresponding map will result in an {@link InvalidConfigException}
     *                        being thrown.
     * @return Parsed configuration.
     * @throws InvalidConfigException If there's a problem parsing the config.
     */
    public static SignedConfig parse(String config, Set<String> allowedKeys,
            Map<String, Map<String, String>> keyValueMappers)
            throws InvalidConfigException {
        try {
            JSONObject json = new JSONObject(config);
            int version = json.getInt(KEY_VERSION);

            JSONArray perSdkConfig = json.getJSONArray(KEY_CONFIG);
            List<PerSdkConfig> parsedConfigs = new ArrayList<>();
            for (int i = 0; i < perSdkConfig.length(); ++i) {
                parsedConfigs.add(parsePerSdkConfig(perSdkConfig.getJSONObject(i), allowedKeys,
                        keyValueMappers));
            }

            return new SignedConfig(version, parsedConfigs);
        } catch (JSONException e) {
            throw new InvalidConfigException("Could not parse JSON", e);
        }

    }

    private static CharSequence quoted(Object s) {
        if (s == null) {
            return "null";
        } else {
            return "\"" + s + "\"";
        }
    }

    @VisibleForTesting
    static PerSdkConfig parsePerSdkConfig(JSONObject json, Set<String> allowedKeys,
            Map<String, Map<String, String>> keyValueMappers)
            throws JSONException, InvalidConfigException {
        int minSdk = json.getInt(CONFIG_KEY_MIN_SDK);
        int maxSdk = json.getInt(CONFIG_KEY_MAX_SDK);
        JSONObject valuesJson = json.getJSONObject(CONFIG_KEY_VALUES);
        Map<String, String> values = new HashMap<>();
        for (String key : valuesJson.keySet()) {
            Object valueObject = valuesJson.get(key);
            String value = valueObject == JSONObject.NULL || valueObject == null
                            ? null
                            : valueObject.toString();
            if (!allowedKeys.contains(key)) {
                throw new InvalidConfigException("Config key " + key + " is not allowed");
            }
            if (keyValueMappers.containsKey(key)) {
                Map<String, String> mapper = keyValueMappers.get(key);
                if (!mapper.containsKey(value)) {
                    throw new InvalidConfigException(
                            "Config key " + key + " contains unsupported value " + quoted(value));
                }
                value = mapper.get(value);
            }
            values.put(key, value);
        }
        return new PerSdkConfig(minSdk, maxSdk, values);
    }

}
