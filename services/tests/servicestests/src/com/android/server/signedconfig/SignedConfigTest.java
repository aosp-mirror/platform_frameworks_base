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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

import android.util.ArrayMap;

import androidx.test.runner.AndroidJUnit4;

import com.google.common.collect.Sets;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;


/**
 * Tests for {@link SignedConfig}
 */
@RunWith(AndroidJUnit4.class)
public class SignedConfigTest {

    private static Set<String> setOf(String... values) {
        return Sets.newHashSet(values);
    }

    private static <K, V> Map<K, V> mapOf(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException();
        }
        final int len = keyValuePairs.length / 2;
        ArrayMap<K, V> m = new ArrayMap<>(len);
        for (int i = 0; i < len;  ++i) {
            m.put((K) keyValuePairs[i * 2], (V) keyValuePairs[(i * 2) + 1]);
        }
        return Collections.unmodifiableMap(m);

    }


    @Test
    public void testParsePerSdkConfigSdkMinMax() throws JSONException, InvalidConfigException {
        JSONObject json = new JSONObject("{\"min_sdk\":2, \"max_sdk\": 3, \"values\": {}}");
        SignedConfig.PerSdkConfig config = SignedConfig.parsePerSdkConfig(json, emptySet(),
                emptyMap());
        assertThat(config.minSdk).isEqualTo(2);
        assertThat(config.maxSdk).isEqualTo(3);
    }

    @Test
    public void testParsePerSdkConfigNoMinSdk() throws JSONException {
        JSONObject json = new JSONObject("{\"max_sdk\": 3, \"values\": {}}");
        try {
            SignedConfig.parsePerSdkConfig(json, emptySet(), emptyMap());
            fail("Expected InvalidConfigException or JSONException");
        } catch (JSONException | InvalidConfigException e) {
            // expected
        }
    }

    @Test
    public void testParsePerSdkConfigNoMaxSdk() throws JSONException {
        JSONObject json = new JSONObject("{\"min_sdk\": 1, \"values\": {}}");
        try {
            SignedConfig.parsePerSdkConfig(json, emptySet(), emptyMap());
            fail("Expected InvalidConfigException or JSONException");
        } catch (JSONException | InvalidConfigException e) {
            // expected
        }
    }

    @Test
    public void testParsePerSdkConfigNoValues() throws JSONException {
        JSONObject json = new JSONObject("{\"min_sdk\": 1, \"max_sdk\": 3}");
        try {
            SignedConfig.parsePerSdkConfig(json, emptySet(), emptyMap());
            fail("Expected InvalidConfigException or JSONException");
        } catch (JSONException | InvalidConfigException e) {
            // expected
        }
    }

    @Test
    public void testParsePerSdkConfigSdkNullMinSdk() throws JSONException {
        JSONObject json = new JSONObject("{\"min_sdk\":null, \"max_sdk\": 3, \"values\": {}}");
        try {
            SignedConfig.parsePerSdkConfig(json, emptySet(), emptyMap());
            fail("Expected InvalidConfigException or JSONException");
        } catch (JSONException | InvalidConfigException e) {
            // expected
        }
    }

    @Test
    public void testParsePerSdkConfigSdkNullMaxSdk() throws JSONException {
        JSONObject json = new JSONObject("{\"min_sdk\":1, \"max_sdk\": null, \"values\": {}}");
        try {
            SignedConfig.parsePerSdkConfig(json, emptySet(), emptyMap());
            fail("Expected InvalidConfigException or JSONException");
        } catch (JSONException | InvalidConfigException e) {
            // expected
        }
    }

    @Test
    public void testParsePerSdkConfigNullValues() throws JSONException {
        JSONObject json = new JSONObject("{\"min_sdk\": 1, \"max_sdk\": 3, \"values\": null}");
        try {
            SignedConfig.parsePerSdkConfig(json, emptySet(), emptyMap());
            fail("Expected InvalidConfigException or JSONException");
        } catch (JSONException | InvalidConfigException e) {
            // expected
        }
    }

    @Test
    public void testParsePerSdkConfigZeroValues()
            throws JSONException, InvalidConfigException {
        JSONObject json = new JSONObject("{\"min_sdk\": 1, \"max_sdk\": 3, \"values\": {}}");
        SignedConfig.PerSdkConfig config = SignedConfig.parsePerSdkConfig(json, setOf("a", "b"),
                emptyMap());
        assertThat(config.values).hasSize(0);
    }

    @Test
    public void testParsePerSdkConfigSingleKey()
            throws JSONException, InvalidConfigException {
        JSONObject json = new JSONObject(
                "{\"min_sdk\": 1, \"max_sdk\": 1, \"values\": {\"a\": \"1\"}}");
        SignedConfig.PerSdkConfig config = SignedConfig.parsePerSdkConfig(json, setOf("a", "b"),
                emptyMap());
        assertThat(config.values).containsExactly("a", "1");
    }

    @Test
    public void testParsePerSdkConfigSingleKeyNullValue()
            throws JSONException, InvalidConfigException {
        JSONObject json = new JSONObject(
                "{\"min_sdk\": 1, \"max_sdk\": 1, \"values\": {\"a\": null}}");
        SignedConfig.PerSdkConfig config = SignedConfig.parsePerSdkConfig(json, setOf("a", "b"),
                emptyMap());
        assertThat(config.values.keySet()).containsExactly("a");
        assertThat(config.values.get("a")).isNull();
    }

    @Test
    public void testParsePerSdkConfigMultiKeys()
            throws JSONException, InvalidConfigException {
        JSONObject json = new JSONObject(
                "{\"min_sdk\": 1, \"max_sdk\": 1, \"values\": {\"a\": \"1\", \"c\": \"2\"}}");
        SignedConfig.PerSdkConfig config = SignedConfig.parsePerSdkConfig(
                json, setOf("a", "b", "c"), emptyMap());
        assertThat(config.values).containsExactly("a", "1", "c", "2");
    }

    @Test
    public void testParsePerSdkConfigSingleKeyNotAllowed() throws JSONException {
        JSONObject json = new JSONObject(
                "{\"min_sdk\": 1, \"max_sdk\": 1, \"values\": {\"a\": \"1\"}}");
        try {
            SignedConfig.parsePerSdkConfig(json, setOf("b"), emptyMap());
            fail("Expected InvalidConfigException or JSONException");
        } catch (JSONException | InvalidConfigException e) {
            // expected
        }
    }

    @Test
    public void testParsePerSdkConfigSingleKeyWithMap()
            throws JSONException, InvalidConfigException {
        JSONObject json = new JSONObject(
                "{\"min_sdk\": 1, \"max_sdk\": 1, \"values\": {\"a\": \"1\"}}");
        SignedConfig.PerSdkConfig config = SignedConfig.parsePerSdkConfig(json, setOf("a"),
                mapOf("a", mapOf("1", "one")));
        assertThat(config.values).containsExactly("a", "one");
    }

    @Test
    public void testParsePerSdkConfigSingleKeyWithMapInvalidValue() throws JSONException {
        JSONObject json = new JSONObject(
                "{\"min_sdk\": 1, \"max_sdk\": 1, \"values\": {\"a\": \"2\"}}");
        try {
            SignedConfig.parsePerSdkConfig(json, setOf("b"), mapOf("a", mapOf("1", "one")));
            fail("Expected InvalidConfigException");
        } catch (InvalidConfigException e) {
            // expected
        }
    }

    @Test
    public void testParsePerSdkConfigMultiKeysWithMap()
            throws JSONException, InvalidConfigException {
        JSONObject json = new JSONObject(
                "{\"min_sdk\": 1, \"max_sdk\": 1, \"values\": {\"a\": \"1\", \"b\": \"1\"}}");
        SignedConfig.PerSdkConfig config = SignedConfig.parsePerSdkConfig(json, setOf("a", "b"),
                mapOf("a", mapOf("1", "one")));
        assertThat(config.values).containsExactly("a", "one", "b", "1");
    }

    @Test
    public void testParsePerSdkConfigSingleKeyWithMapToNull()
            throws JSONException, InvalidConfigException {
        JSONObject json = new JSONObject(
                "{\"min_sdk\": 1, \"max_sdk\": 1, \"values\": {\"a\": \"1\"}}");
        SignedConfig.PerSdkConfig config = SignedConfig.parsePerSdkConfig(json, setOf("a"),
                mapOf("a", mapOf("1", null)));
        assertThat(config.values).containsExactly("a", null);
    }

    @Test
    public void testParsePerSdkConfigSingleKeyWithMapFromNull()
            throws JSONException, InvalidConfigException {
        assertThat(mapOf(null, "allitnil")).containsExactly(null, "allitnil");
        assertThat(mapOf(null, "allitnil").containsKey(null)).isTrue();
        JSONObject json = new JSONObject(
                "{\"min_sdk\": 1, \"max_sdk\": 1, \"values\": {\"a\": null}}");
        SignedConfig.PerSdkConfig config = SignedConfig.parsePerSdkConfig(json, setOf("a"),
                mapOf("a", mapOf(null, "allitnil")));
        assertThat(config.values).containsExactly("a", "allitnil");
    }

    @Test
    public void testParsePerSdkConfigValuesInvalid() throws JSONException  {
        JSONObject json = new JSONObject("{\"min_sdk\": 1, \"max_sdk\": 1,  \"values\": \"foo\"}");
        try {
            SignedConfig.parsePerSdkConfig(json, emptySet(), emptyMap());
            fail("Expected InvalidConfigException or JSONException");
        } catch (JSONException | InvalidConfigException e) {
            // expected
        }
    }

    @Test
    public void testParsePerSdkConfigConfigEntryInvalid() throws JSONException {
        JSONObject json = new JSONObject("{\"min_sdk\": 1, \"max_sdk\": 1,  \"values\": [1, 2]}");
        try {
            SignedConfig.parsePerSdkConfig(json, emptySet(), emptyMap());
            fail("Expected InvalidConfigException or JSONException");
        } catch (JSONException | InvalidConfigException e) {
            // expected
        }
    }

    @Test
    public void testParseVersion() throws InvalidConfigException {
        SignedConfig config = SignedConfig.parse(
                "{\"version\": 1, \"config\": []}", emptySet(), emptyMap());
        assertThat(config.version).isEqualTo(1);
    }

    @Test
    public void testParseVersionInvalid() {
        try {
            SignedConfig.parse("{\"version\": \"notanint\", \"config\": []}", emptySet(),
                    emptyMap());
            fail("Expected InvalidConfigException");
        } catch (InvalidConfigException e) {
            //expected
        }
    }

    @Test
    public void testParseNoVersion() {
        try {
            SignedConfig.parse("{\"config\": []}", emptySet(), emptyMap());
            fail("Expected InvalidConfigException");
        } catch (InvalidConfigException e) {
            //expected
        }
    }

    @Test
    public void testParseNoConfig() {
        try {
            SignedConfig.parse("{\"version\": 1}", emptySet(), emptyMap());
            fail("Expected InvalidConfigException");
        } catch (InvalidConfigException e) {
            //expected
        }
    }

    @Test
    public void testParseConfigNull() {
        try {
            SignedConfig.parse("{\"version\": 1, \"config\": null}", emptySet(), emptyMap());
            fail("Expected InvalidConfigException");
        } catch (InvalidConfigException e) {
            //expected
        }
    }

    @Test
    public void testParseVersionNull() {
        try {
            SignedConfig.parse("{\"version\": null, \"config\": []}", emptySet(), emptyMap());
            fail("Expected InvalidConfigException");
        } catch (InvalidConfigException e) {
            //expected
        }
    }

    @Test
    public void testParseConfigInvalidEntry() {
        try {
            SignedConfig.parse("{\"version\": 1, \"config\": [{}]}", emptySet(), emptyMap());
            fail("Expected InvalidConfigException");
        } catch (InvalidConfigException e) {
            //expected
        }
    }

    @Test
    public void testParseSdkConfigSingle() throws InvalidConfigException {
        SignedConfig config = SignedConfig.parse(
                "{\"version\": 1, \"config\":[{\"min_sdk\": 1, \"max_sdk\": 1, \"values\": {}}]}",
                emptySet(), emptyMap());
        assertThat(config.perSdkConfig).hasSize(1);
    }

    @Test
    public void testParseSdkConfigMultiple() throws InvalidConfigException {
        SignedConfig config = SignedConfig.parse(
                "{\"version\": 1, \"config\":[{\"min_sdk\": 1, \"max_sdk\": 1, \"values\": {}}, "
                        + "{\"min_sdk\": 2, \"max_sdk\": 2, \"values\": {}}]}", emptySet(),
                emptyMap());
        assertThat(config.perSdkConfig).hasSize(2);
    }

    @Test
    public void testGetMatchingConfigFirst() {
        SignedConfig.PerSdkConfig sdk1 = new SignedConfig.PerSdkConfig(
                1, 1, Collections.emptyMap());
        SignedConfig.PerSdkConfig sdk2 = new SignedConfig.PerSdkConfig(
                2, 2, Collections.emptyMap());
        SignedConfig config = new SignedConfig(0, Arrays.asList(sdk1, sdk2));
        assertThat(config.getMatchingConfig(1)).isEqualTo(sdk1);
    }

    @Test
    public void testGetMatchingConfigSecond() {
        SignedConfig.PerSdkConfig sdk1 = new SignedConfig.PerSdkConfig(
                1, 1, Collections.emptyMap());
        SignedConfig.PerSdkConfig sdk2 = new SignedConfig.PerSdkConfig(
                2, 2, Collections.emptyMap());
        SignedConfig config = new SignedConfig(0, Arrays.asList(sdk1, sdk2));
        assertThat(config.getMatchingConfig(2)).isEqualTo(sdk2);
    }

    @Test
    public void testGetMatchingConfigInRange() {
        SignedConfig.PerSdkConfig sdk13 = new SignedConfig.PerSdkConfig(
                1, 3, Collections.emptyMap());
        SignedConfig.PerSdkConfig sdk46 = new SignedConfig.PerSdkConfig(
                4, 6, Collections.emptyMap());
        SignedConfig config = new SignedConfig(0, Arrays.asList(sdk13, sdk46));
        assertThat(config.getMatchingConfig(2)).isEqualTo(sdk13);
    }
    @Test
    public void testGetMatchingConfigNoMatch() {
        SignedConfig.PerSdkConfig sdk1 = new SignedConfig.PerSdkConfig(
                1, 1, Collections.emptyMap());
        SignedConfig.PerSdkConfig sdk2 = new SignedConfig.PerSdkConfig(
                2, 2, Collections.emptyMap());
        SignedConfig config = new SignedConfig(0, Arrays.asList(sdk1, sdk2));
        assertThat(config.getMatchingConfig(3)).isNull();
    }

}
